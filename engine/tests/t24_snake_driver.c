/* t24_snake_driver.c — drive Snake through the HTML host.
 * run() registers html.on("tick") and queues the first board. Each
 * lx_html_event("tick") steps the game and queues a new Score line. */
#include "../lx.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)

static char* read_all(const char* path){
  FILE* f=fopen(path,"rb"); if(!f)return NULL;
  fseek(f,0,SEEK_END); long n=ftell(f); fseek(f,0,SEEK_SET);
  char* b=malloc((size_t)n+1); fread(b,1,(size_t)n,f); b[n]=0; fclose(f); return b;
}

int main(void){
  char* src = read_all("tests/t24_snake.lua");
  CHK(src != NULL, "read snake source");
  if(!src) return 1;

  char err[512];
  lx_State* S = lx_new();
  lx_set_step_limit(S, 50000000L);

  int rc = lx_run(S, src, err, sizeof(err));
  CHK(rc==0, "run snake");
  if(rc){ fprintf(stderr,"%s\n",err); }
  const char* j = lx_last_json(S);
  CHK(j && strstr(j,"\"op\":\"setText\""), "initial render queued");
  CHK(j && strstr(j,"\"id\":\"status\""), "status element");
  CHK(j && strstr(j,"Score 0"), "opening score");
  lx_html_clear_ops(S);

  if(rc==0){
    int prev_score = 0, seen = 0;
    for(int t=0; t<40; t++){
      memset(err,0,sizeof(err));
      rc = lx_html_event(S, "tick", "click", "", err, sizeof(err));
      CHK(rc==0, "tick event");
      if(rc){ fprintf(stderr,"tick %d: %s\n", t, err); break; }
      j = lx_last_json(S);
      CHK(j && strstr(j,"\"id\":\"status\""), "status after tick");
      const char* sc = j ? strstr(j, "Score ") : NULL;
      if(sc){
        int s = atoi(sc+6);
        if(s!=prev_score){ seen++; prev_score=s; }
      }
      lx_html_clear_ops(S);
    }
    CHK(prev_score>=0, "score is a number");
    CHK(seen>=0, "ticks ran");
  }

  lx_close(S);
  free(src);
  if(fails){ fprintf(stderr, "t24-snake: %d failure(s)\n", fails); return 1; }
  printf("t24-snake ok\n");
  return 0;
}
