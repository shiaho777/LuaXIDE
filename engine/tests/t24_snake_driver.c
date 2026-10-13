/* t24-snake.c — drive the pure-Lua Snake game the way the App will:
 * run() the chunk, find the onTick handler id in the JSON tree, then invoke
 * it repeatedly (App-side timer loop) and watch the tree rebuild each tick.
 */
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

/* find "onTick":{"__handler":N} in the JSON tree */
static int tick_handler_id(const char* j){
  const char* p = j ? strstr(j, "\"onTick\"") : NULL;
  if(!p) return -1;
  const char* h = strstr(p, "__handler");
  if(!h) return -1;
  return atoi(h + strlen("__handler") + 2); /* skip : "  -> actually +2 past __handler" */
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
  CHK(j && strstr(j,"\"type\":\"app\""), "tree is app");
  CHK(j && strstr(j,"\"interval\":260"), "interval prop");
  int id = tick_handler_id(j);
  CHK(id>=0, "onTick handler found");

  if(rc==0 && id>=0){
    /* 40 ticks: no crash, tree rebuilds, score stays a number */
    int prev_score = -1, seen = 0;
    for(int t=0; t<40; t++){
      memset(err,0,sizeof(err));
      rc = lx_invoke(S, id, NULL, err, sizeof(err));
      CHK(rc==0, "tick invoke");
      if(rc){ fprintf(stderr,"tick %d: %s\n", t, err); break; }
      j = lx_last_json(S);
      CHK(j && strstr(j,"\"type\":\"app\""), "tree after tick");
      id = tick_handler_id(j); /* ids are re-assigned every rebuild */
      CHK(id>=0, "onTick handler still present");
      if(id<0) break;
      /* grab Score from the first text child */
      const char* sc = j ? strstr(j, "\"Score ") : NULL;
      if(sc){ int s = atoi(sc+7); if(s!=prev_score){ seen++; prev_score=s; } }
    }
    CHK(seen>=1, "score observable");
    CHK(prev_score>=0, "score is a number");
  }

  lx_close(S);
  free(src);
  if(fails){ fprintf(stderr, "t24-snake: %d failure(s)\n", fails); return 1; }
  printf("t24-snake ok\n");
  return 0;
}
