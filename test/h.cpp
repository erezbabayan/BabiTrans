#include "whisper.h"
#include <cstdio>
#include <vector>
#include <chrono>
#include <string>
#include <algorithm>
#include <atomic>
struct J{std::atomic<int> tok{0};long long dl;};
static long long ms(){return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();}
int main(int c,char**v){ // model pcm mode(0=app037,1=app038) threads
  whisper_log_set([](ggml_log_level,const char*,void*){},nullptr);
  FILE*f=fopen(v[2],"rb");std::vector<float>a;float x;while(fread(&x,4,1,f))a.push_back(x);fclose(f);
  int mode=atoi(v[3]),nth=atoi(v[4]);int n=a.size();
  long long t0=ms(); auto cp=whisper_context_default_params();cp.use_gpu=false;
  auto*ctx=whisper_init_from_file_with_params_no_state(v[1],cp); long long tl=ms()-t0;
  auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  p.language="he";p.no_context=true;p.print_progress=false;
  if(n<16000*28){int need=n/320+96;need=((need+63)/64)*64;p.audio_ctx=std::min(1500,std::max(256,need));p.single_segment=true;}
  p.temperature=0;p.temperature_inc=0;p.suppress_blank=true;p.suppress_nst=true;p.n_threads=nth;
  if(mode==1){ p.max_tokens = n<16000*28 ? std::max(32,(int)(n/16000)*6+24) : 0; }
  J j; p.logits_filter_callback=[](whisper_context*,whisper_state*,const whisper_token_data*,int,float*,void*u){((J*)u)->tok++;};p.logits_filter_callback_user_data=&j;
  auto*st=whisper_init_state(ctx); long long t1=ms(); int r=whisper_full_with_state(ctx,st,p,a.data(),n); long long ts=ms()-t1;
  std::string o;for(int i=0;i<whisper_full_n_segments_from_state(st);i++)o+=whisper_full_get_segment_text_from_state(st,i);
  printf("audio=%.1fs load=%lldms stt=%lldms rtf=%.3f tokens=%d r=%d\n%s\n",n/16000.0,tl,ts,ts/1000.0/(n/16000.0),j.tok.load(),r,o.c_str());}
