#include <jni.h>
#include <string>
#include <thread>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <map>
// Per-job cancel/watchdog: whisper polls abort_callback between graph steps, so a stuck or
// too-slow job is stopped (cancel button, or deadline) instead of spinning forever.
struct Job { std::atomic<bool> cancel{false}; long long deadline_ms=0; std::atomic<int> phase{0}, tokens{0}, pct{0}; };
// phase: 0=prep 1=encoding 2=decoding 3=done
static std::mutex g_mu; static std::map<int,Job*> g_jobs; static std::map<int,bool> g_precancel; // cancel arriving before registration
static long long now_ms(){ return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
static bool abort_cb(void*u){ Job*j=(Job*)u; return j->cancel.load() || (j->deadline_ms>0 && now_ms()>j->deadline_ms); }
// Live progress for the UI: (phase<<24)|(min(tokens,0xFFFF)<<8)|pct ; -1 if job not running natively.
extern "C" JNIEXPORT jint JNICALL Java_com_erezbabayan_babitrans_Whisper_progress(JNIEnv*,jclass,jint id){
  std::lock_guard<std::mutex> g(g_mu); auto it=g_jobs.find(id); if(it==g_jobs.end()) return -1; Job*j=it->second;
  return (j->phase.load()<<24)|(std::min(j->tokens.load(),0xFFFF)<<8)|std::min(100,std::max(0,j->pct.load())); }
extern "C" JNIEXPORT void JNICALL Java_com_erezbabayan_babitrans_Whisper_cancel(JNIEnv*,jclass,jint id){
  std::lock_guard<std::mutex> g(g_mu); auto it=g_jobs.find(id); if(it!=g_jobs.end()) it->second->cancel=true; else g_precancel[id]=true; }
#include "whisper.h"
extern "C" JNIEXPORT jlong JNICALL Java_com_erezbabayan_babitrans_Whisper_init(JNIEnv*e,jclass,jstring p){
  const char*s=e->GetStringUTFChars(p,0);
  whisper_context_params cp=whisper_context_default_params(); cp.use_gpu=false;
  whisper_context*c=whisper_init_from_file_with_params_no_state(s,cp);
  e->ReleaseStringUTFChars(p,s); return (jlong)c;}
extern "C" JNIEXPORT void JNICALL Java_com_erezbabayan_babitrans_Whisper_free(JNIEnv*,jclass,jlong c){ if(c) whisper_free((whisper_context*)c);}
extern "C" JNIEXPORT jstring JNICALL Java_com_erezbabayan_babitrans_Whisper_transcribe(JNIEnv*e,jclass,jlong h,jfloatArray a,jstring lang,jint nth,jint jobId,jint timeoutSec){
  whisper_context*c=(whisper_context*)h; if(!c) return e->NewStringUTF("");
  jsize n=e->GetArrayLength(a); jfloat*d=e->GetFloatArrayElements(a,0);
  const char*l=e->GetStringUTFChars(lang,0);
  whisper_full_params p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  p.language=l; p.translate=false; p.print_progress=false; p.print_realtime=false; p.print_timestamps=false;
  p.no_context=true;
  // Short-note fast path: the encoder normally always processes a 30s window (1500 frames).
  // For a 6s note that is ~5x wasted work. audio_ctx = frames actually needed (+margin), rounded to 64.
  // 1 encoder frame = 320 samples @16k (20ms).
  if(n < 16000*28){ int need=(int)(n/320)+96; need=((need+63)/64)*64; p.audio_ctx=std::min(1500,std::max(256,need)); p.single_segment=true;
    // v0.3.8: cap tokens (~6/s of speech + margin). Without a cap a greedy repetition loop (temperature fallback is off)
    // can emit ~220 tokens and stretch a 10s note to minutes on the phone.
    p.max_tokens=std::max(32,(int)(n/16000)*6+24); }
  else p.single_segment=false;
  p.temperature=0.0f; p.temperature_inc=0.0f; // no costly fallback re-decodes
  p.suppress_blank=true; p.suppress_nst=true;
  int hc=(int)std::thread::hardware_concurrency();
  // S23 (8 cores: 1 X3 + 4 A715/A710 + 3 A510). Using all 8 drags on little cores; 6 is a good balance.
  p.n_threads=nth>0?(int)nth:std::max(1,std::min(6,hc>0?hc:4));
  Job job; if(timeoutSec>0) job.deadline_ms=now_ms()+(long long)timeoutSec*1000;
  { std::lock_guard<std::mutex> g(g_mu); g_jobs[jobId]=&job; auto pc=g_precancel.find(jobId); if(pc!=g_precancel.end()){ job.cancel=true; g_precancel.erase(pc);} }
  p.abort_callback=abort_cb; p.abort_callback_user_data=&job;
  p.encoder_begin_callback=[](whisper_context*,whisper_state*,void*u){ ((Job*)u)->phase=1; return !abort_cb(u); };
  p.progress_callback=[](whisper_context*,whisper_state*,int pr,void*u){ ((Job*)u)->pct=pr; }; p.progress_callback_user_data=&job;
  // called once per decoded token -> live "decoding, N tokens" heartbeat (proves we're not stuck)
  p.logits_filter_callback=[](whisper_context*,whisper_state*,const whisper_token_data*,int,float*,void*u){ Job*j=(Job*)u; j->phase=2; j->tokens++; }; p.logits_filter_callback_user_data=&job; p.encoder_begin_callback_user_data=&job;
  std::string out; bool aborted=false;
  // Each job gets its own whisper_state over the shared read-only model weights, so several
  // transcriptions can run concurrently without loading the ~450MB model twice.
  whisper_state*st=whisper_init_state(c);
  if(st && !abort_cb(&job)){ if(whisper_full_with_state(c,st,p,d,n)==0){ int k=whisper_full_n_segments_from_state(st); for(int i=0;i<k;i++){ out+=whisper_full_get_segment_text_from_state(st,i);} }
  }
  job.phase=3; if(st) whisper_free_state(st);
  aborted=abort_cb(&job); bool cancelled=job.cancel.load();
  { std::lock_guard<std::mutex> g(g_mu); g_jobs.erase(jobId); }
  if(aborted){ out = cancelled ? "\x01" "CANCEL" : "\x01" "TIMEOUT"; }
  e->ReleaseStringUTFChars(lang,l); e->ReleaseFloatArrayElements(a,d,JNI_ABORT);
  // trim leading space
  size_t s=out.find_first_not_of(' '); if(s!=std::string::npos) out=out.substr(s);
  jbyteArray b=e->NewByteArray(out.size()); e->SetByteArrayRegion(b,0,out.size(),(const jbyte*)out.data());
  jclass sc=e->FindClass("java/lang/String"); jmethodID m=e->GetMethodID(sc,"<init>","([BLjava/lang/String;)V");
  return (jstring)e->NewObject(sc,m,b,e->NewStringUTF("UTF-8"));}
