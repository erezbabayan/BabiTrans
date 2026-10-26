#include "whisper.h"
#include <cstdio>
#include <vector>
#include <chrono>
#include <thread>
#include <mutex>
#include <atomic>
static long long ms(){return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();}
std::vector<float> rd(const char*p){FILE*f=fopen(p,"rb");std::vector<float>a;float x;while(fread(&x,4,1,f))a.push_back(x);fclose(f);return a;}
struct J{long long dl;}; std::mutex STT; long long T0;
void job(whisper_context*c,const char*name,std::vector<float>a,int timeout_s){
  long long w=ms(); printf("[%5lld] %s: ממתין למנוע\n",ms()-T0,name); fflush(stdout);
  std::lock_guard<std::mutex> g(STT); printf("[%5lld] %s: מתמלל (waited %lldms)\n",ms()-T0,name,ms()-w);fflush(stdout);
  auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);p.language="he";p.no_context=true;p.temperature_inc=0;p.n_threads=6;
  J j{timeout_s>0?ms()+timeout_s*1000:0}; p.abort_callback=[](void*u){J*j=(J*)u;return j->dl>0&&ms()>j->dl;};p.abort_callback_user_data=&j;
  auto*st=whisper_init_state(c); long long t=ms(); int r=whisper_full_with_state(c,st,p,a.data(),a.size());
  printf("[%5lld] %s: done r=%d stt=%lldms %s\n",ms()-T0,name,r,ms()-t,r? "(TIMEOUT->lock released)":"");fflush(stdout); whisper_free_state(st);}
int main(){whisper_log_set([](ggml_log_level,const char*,void*){},nullptr);auto cp=whisper_context_default_params();
 auto*c=whisper_init_from_file_with_params_no_state("/workspace/models/ivrit-turbo-q4_0.bin",cp); T0=ms();
 auto L=rd("long.f32"),S=rd("short.f32");
 std::thread a(job,c,"job1-long(timeout 4s, simulates hang)",L,4); std::this_thread::sleep_for(std::chrono::milliseconds(200));
 std::thread b(job,c,"job2-short",S,30); a.join(); b.join();}
