#include <jni.h>
#include <string>
#include <thread>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <map>
#include <vector>
// Per-job cancel/watchdog: whisper polls abort_callback between graph steps, so a stuck or
// too-slow job is stopped (cancel button, or deadline) instead of spinning forever.
struct Job { std::atomic<bool> cancel{false}; long long deadline_ms=0; std::atomic<int> phase{0}, tokens{0}, pct{0}, chunk{0}, nchunks{1}; std::atomic<long long> enc_start_ms{0}; long long enc_limit_ms=0; std::atomic<bool> stalled{false}; };
// phase: 0=prep 1=encoding 2=decoding 3=done
static std::mutex g_mu; static std::map<int,Job*> g_jobs; static std::map<int,bool> g_precancel; // cancel arriving before registration
static long long now_ms(){ return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
static bool abort_cb(void*u){ Job*j=(Job*)u; long long t=now_ms(), es=j->enc_start_ms.load();
  if(es>0 && j->enc_limit_ms>0 && t-es>j->enc_limit_ms) j->stalled=true;
  return j->cancel.load() || j->stalled.load() || (j->deadline_ms>0 && t>j->deadline_ms); }
// Split PCM into near-equal chunks of <=28s, each boundary moved (+-2s) to the quietest 20ms frame.
static void chunk_plan(const float*d,int n,std::vector<std::pair<int,int>>&ch){
  const int MAXS=16000*28; int k=std::max(1,(n+MAXS-1)/MAXS); int prev=0;
  for(int i=1;i<k;i++){ int tgt=(int)((long long)n*i/k), best=tgt; double be=1e30;
    for(int s=std::max(prev+16000*5,tgt-32000); s<=std::min(n-320,tgt+32000); s+=160){ double e=0; for(int q=0;q<320;q++) e+=d[s+q]*d[s+q]; if(e<be){be=e;best=s+160;} }
    if(best-prev>MAXS+16000*2) best=prev+MAXS; // never exceed ~30s (1500 frames)
    ch.push_back({prev,best-prev}); prev=best; }
  ch.push_back({prev,n-prev}); }
// Live progress for the UI: (phase<<24)|(min(tokens,0xFFFF)<<8)|pct ; -1 if job not running natively.
extern "C" JNIEXPORT jint JNICALL Java_com_erezbabayan_babitrans_Whisper_progress(JNIEnv*,jclass,jint id){
  std::lock_guard<std::mutex> g(g_mu); auto it=g_jobs.find(id); if(it==g_jobs.end()) return -1; Job*j=it->second;
  return (j->phase.load()<<28)|(std::min(j->chunk.load(),15)<<24)|(std::min(j->nchunks.load(),15)<<20)|(std::min(j->tokens.load(),0xFFF)<<8)|std::min(100,std::max(0,j->pct.load())); }
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
  int hc=(int)std::thread::hardware_concurrency();
  // S23 (8 cores: 1 X3 + 4 A715/A710 + 3 A510). Using all 8 drags on little cores; 6 is a good balance.
  int threads=nth>0?(int)nth:std::max(1,std::min(6,hc>0?hc:4));
  Job job; if(timeoutSec>0) job.deadline_ms=now_ms()+(long long)timeoutSec*1000;
  { std::lock_guard<std::mutex> g(g_mu); g_jobs[jobId]=&job; auto pc=g_precancel.find(jobId); if(pc!=g_precancel.end()){ job.cancel=true; g_precancel.erase(pc);} }
  // v0.3.9 root-cause fix: whisper's encoder ALWAYS pads every 30s seek window to 1500 frames unless audio_ctx is set.
  // A 35s note (> old 28s cutoff) paid for TWO full 30s encodes (=60s of encoder work) -> stuck on "מעבד שמע" on the phone.
  // Now every note is split into near-equal chunks of <=28s (cut at the quietest 20ms frame near each boundary) and each
  // chunk is encoded with audio_ctx sized to its real length. 35s -> 2x17.5s, 90s -> 4x22.5s. No padding is ever encoded.
  std::vector<std::pair<int,int>> ch; chunk_plan(d,n,ch); job.nchunks=(int)ch.size();
  std::string out;
  whisper_state*st=whisper_init_state(c);
  for(size_t ci=0; st && ci<ch.size() && !abort_cb(&job); ci++){
    int off=ch[ci].first, len=ch[ci].second; job.chunk=(int)ci;
    whisper_full_params p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language=l; p.translate=false; p.print_progress=false; p.print_realtime=false; p.print_timestamps=false;
    p.no_context=true; p.n_threads=threads;
    int need=(int)(len/320)+96; need=((need+63)/64)*64; p.audio_ctx=std::min(1500,std::max(256,need)); p.single_segment=true;
    // cap tokens (~6/s + margin): greedy repetition loops can't stretch a note to minutes
    p.max_tokens=std::max(32,(int)(len/16000)*6+24);
    p.temperature=0.0f; p.temperature_inc=0.0f; p.suppress_blank=true; p.suppress_nst=true;
    // encoder stall guard: one chunk's encode must not exceed max(25s, 2.5x its audio length) on any sane phone
    job.enc_limit_ms=std::max(25000LL,(long long)(len/16)*5/2);
    p.abort_callback=abort_cb; p.abort_callback_user_data=&job;
    p.encoder_begin_callback=[](whisper_context*,whisper_state*,void*u){ Job*j=(Job*)u; j->phase=1; j->enc_start_ms=now_ms(); return !abort_cb(u); }; p.encoder_begin_callback_user_data=&job;
    p.progress_callback=nullptr;
    p.logits_filter_callback=[](whisper_context*,whisper_state*,const whisper_token_data*,int,float*,void*u){ Job*j=(Job*)u; j->phase=2; j->enc_start_ms=0; j->tokens++; }; p.logits_filter_callback_user_data=&job;
    if(whisper_full_with_state(c,st,p,d+off,len)!=0) break;
    int k=whisper_full_n_segments_from_state(st); for(int i=0;i<k;i++){ out+=whisper_full_get_segment_text_from_state(st,i);} out+=' ';
    job.pct=(int)((ci+1)*100/ch.size()); job.enc_start_ms=0;
  }
  job.phase=3; if(st) whisper_free_state(st);
  bool aborted=abort_cb(&job); bool cancelled=job.cancel.load(); bool stalled=job.stalled.load();
  { std::lock_guard<std::mutex> g(g_mu); g_jobs.erase(jobId); }
  if(aborted){ out = cancelled ? "\x01" "CANCEL" : stalled ? "\x01" "STALL" : "\x01" "TIMEOUT"; }
  e->ReleaseStringUTFChars(lang,l); e->ReleaseFloatArrayElements(a,d,JNI_ABORT);
  // trim leading space
  size_t s=out.find_first_not_of(' '); if(s!=std::string::npos) out=out.substr(s);
  jbyteArray b=e->NewByteArray(out.size()); e->SetByteArrayRegion(b,0,out.size(),(const jbyte*)out.data());
  jclass sc=e->FindClass("java/lang/String"); jmethodID m=e->GetMethodID(sc,"<init>","([BLjava/lang/String;)V");
  return (jstring)e->NewObject(sc,m,b,e->NewStringUTF("UTF-8"));}
