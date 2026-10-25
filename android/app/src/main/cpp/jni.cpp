#include <jni.h>
#include <string>
#include <thread>
#include <algorithm>
#include "whisper.h"
extern "C" JNIEXPORT jlong JNICALL Java_com_erezbabayan_babitrans_Whisper_init(JNIEnv*e,jclass,jstring p){
  const char*s=e->GetStringUTFChars(p,0);
  whisper_context_params cp=whisper_context_default_params(); cp.use_gpu=false;
  whisper_context*c=whisper_init_from_file_with_params(s,cp);
  e->ReleaseStringUTFChars(p,s); return (jlong)c;}
extern "C" JNIEXPORT void JNICALL Java_com_erezbabayan_babitrans_Whisper_free(JNIEnv*,jclass,jlong c){ if(c) whisper_free((whisper_context*)c);}
extern "C" JNIEXPORT jstring JNICALL Java_com_erezbabayan_babitrans_Whisper_transcribe(JNIEnv*e,jclass,jlong h,jfloatArray a,jstring lang){
  whisper_context*c=(whisper_context*)h; if(!c) return e->NewStringUTF("");
  jsize n=e->GetArrayLength(a); jfloat*d=e->GetFloatArrayElements(a,0);
  const char*l=e->GetStringUTFChars(lang,0);
  whisper_full_params p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  p.language=l; p.translate=false; p.print_progress=false; p.print_realtime=false; p.print_timestamps=false;
  p.no_context=true;
  // Short-note fast path: the encoder normally always processes a 30s window (1500 frames).
  // For a 6s note that is ~5x wasted work. audio_ctx = frames actually needed (+margin), rounded to 64.
  // 1 encoder frame = 320 samples @16k (20ms).
  if(n < 16000*28){ int need=(int)(n/320)+96; need=((need+63)/64)*64; p.audio_ctx=std::min(1500,std::max(256,need)); p.single_segment=true; }
  else p.single_segment=false;
  p.temperature=0.0f; p.temperature_inc=0.0f; // no costly fallback re-decodes
  p.suppress_blank=true; p.suppress_nst=true;
  int hc=(int)std::thread::hardware_concurrency();
  // S23 (8 cores: 1 X3 + 4 A715/A710 + 3 A510). Using all 8 drags on little cores; 6 is a good balance.
  p.n_threads=std::max(1,std::min(6,hc>0?hc:4));
  std::string out;
  if(whisper_full(c,p,d,n)==0){ int k=whisper_full_n_segments(c); for(int i=0;i<k;i++){ out+=whisper_full_get_segment_text(c,i);} }
  e->ReleaseStringUTFChars(lang,l); e->ReleaseFloatArrayElements(a,d,JNI_ABORT);
  // trim leading space
  size_t s=out.find_first_not_of(' '); if(s!=std::string::npos) out=out.substr(s);
  jbyteArray b=e->NewByteArray(out.size()); e->SetByteArrayRegion(b,0,out.size(),(const jbyte*)out.data());
  jclass sc=e->FindClass("java/lang/String"); jmethodID m=e->GetMethodID(sc,"<init>","([BLjava/lang/String;)V");
  return (jstring)e->NewObject(sc,m,b,e->NewStringUTF("UTF-8"));}
