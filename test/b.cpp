#include "whisper.h"
#include <cstdio>
#include <vector>
#include <chrono>
#include <string>
#include <algorithm>
static long long ms(){return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();}
static void chunk_plan(const float*d,int n,std::vector<std::pair<int,int>>&ch){
  const int MAXS=16000*28; int k=std::max(1,(n+MAXS-1)/MAXS); int prev=0;
  for(int i=1;i<k;i++){ int tgt=(int)((long long)n*i/k), best=tgt; double be=1e30;
    for(int s=std::max(prev+16000*5,tgt-32000); s<=std::min(n-320,tgt+32000); s+=160){ double e=0; for(int q=0;q<320;q++) e+=d[s+q]*d[s+q]; if(e<be){be=e;best=s+160;} }
    if(best-prev>MAXS+16000*2) best=prev+MAXS;
    ch.push_back({prev,best-prev}); prev=best; }
  ch.push_back({prev,n-prev}); }
long long enc_ms=0, eb=0;
int main(int c,char**v){
  whisper_log_set([](ggml_log_level,const char*,void*){},nullptr);
  FILE*f=fopen(v[2],"rb");std::vector<float>a;float x;while(fread(&x,4,1,f))a.push_back(x);fclose(f);
  int mode=atoi(v[3]),nth=atoi(v[4]);int n=a.size();
  auto cp=whisper_context_default_params();cp.use_gpu=false;
  auto*ctx=whisper_init_from_file_with_params_no_state(v[1],cp); auto*st=whisper_init_state(ctx);
  std::vector<std::pair<int,int>> ch; if(mode==1) chunk_plan(a.data(),n,ch); else ch.push_back({0,n});
  long long t1=ms(); std::string o; int toks=0;
  for(auto&cc:ch){ int len=cc.second;
    auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language="he";p.no_context=true;p.print_progress=false;p.n_threads=nth;
    p.temperature=0;p.temperature_inc=0;p.suppress_blank=true;p.suppress_nst=true;
    if(mode==1||len<16000*28){int need=len/320+96;need=((need+63)/64)*64;p.audio_ctx=std::min(1500,std::max(256,need));p.single_segment=true;p.max_tokens=std::max(32,(len/16000)*6+24);}
    p.encoder_begin_callback=[](whisper_context*,whisper_state*,void*){eb=ms();return true;};
    p.logits_filter_callback=[](whisper_context*,whisper_state*,const whisper_token_data*,int,float*,void*u){ if(eb){enc_ms+=ms()-eb;eb=0;} (*(int*)u)++;};p.logits_filter_callback_user_data=&toks;
    whisper_full_with_state(ctx,st,p,a.data()+cc.first,len);
    for(int i=0;i<whisper_full_n_segments_from_state(st);i++)o+=whisper_full_get_segment_text_from_state(st,i);}
  long long ts=ms()-t1;
  printf("mode=%d audio=%.1fs chunks=%zu stt=%lldms enc=%lldms rtf=%.3f tokens=%d\n%s\n",mode,n/16000.0,ch.size(),ts,enc_ms,ts/1000.0/(n/16000.0),toks,o.c_str());}
