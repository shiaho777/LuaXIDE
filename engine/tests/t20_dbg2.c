/* t20_dbg2.c — extra debugger coverage:
 *  - lx_debug_eval of every value kind + error/empty/too-long exprs
 *  - lx_debug_eval when NOT paused
 *  - logpoints with {expr}, failing {expr}, unbalanced/empty braces
 *  - conditional breakpoint (cond true) + log + pause
 *  - step_out at top level (nstack<=1)
 *  - lx_debug_stop, lx_debug_clear_breakpoints,
 *    lx_debug_set_break_on_error(0) / (1), lx_debug_last_error
 *
 * The controller thread is parameterised by a target state plus a small
 * action script, so each lx_State gets its own (no shared globals). */
#include "../lx.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <pthread.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s, sub); }

struct ctl { lx_State* g; int* seq; };
/* action 1 = eval-suite then continue ; 2 = step_out ; 3 = stop ; 9 = continue-only */
static void* controller(void* a){
  struct ctl* c = (struct ctl*)a;
  for(int i=0;i<2000;i++){
    usleep(2000);
    if(!lx_debug_is_paused(c->g)) continue;
    (*c->seq)++;
    int n = *c->seq;
    if(n==1){
      const char* e;
      e=lx_debug_eval(c->g,"s");     CHK(has(e,"\"hi"),"eval string");
      e=lx_debug_eval(c->g,"flag");  CHK(has(e,"false"),"eval bool false");
      e=lx_debug_eval(c->g,"x");     CHK(has(e,"\"value\":1"),"eval number");
      e=lx_debug_eval(c->g,"t");     CHK(has(e,"\"a\":1"),"eval table");
      e=lx_debug_eval(c->g,"nested");CHK(has(e,"\"b\""),"eval nested depth");
      e=lx_debug_eval(c->g,"big");   CHK(has(e,"\"k32\""),"eval big table");
      e=lx_debug_eval(c->g,"print"); CHK(has(e,"\"[function]\""),"eval function");
      e=lx_debug_eval(c->g,"1+");    CHK(has(e,"\"ok\":false"),"eval parse err");
      char buf[300]; buf[0]='('; memset(buf+1,'1',295); buf[296]=')'; buf[297]=0;
      e=lx_debug_eval(c->g,buf);     CHK(has(e,"\"ok\":false"),"eval too long");
      e=lx_debug_eval(c->g,"");      CHK(has(e,"\"ok\":false")||has(e,"empty"),"eval empty");
      lx_debug_continue(c->g);
    } else if(n==2){ lx_debug_step_out(c->g); }
    else if(n==3){ lx_debug_stop(c->g); }
    else { lx_debug_continue(c->g); }
  }
  return NULL;
}

int main(void){
  char err[512]; int rc;

  /* ---------- run 1: bps at 8/9/10, eval suite + step_out + stop ---------- */
  lx_State* g = lx_new();
  lx_set_step_limit(g, 10000000);
  lx_debug_enable(g, 1);
  const char* src =
    "local s = \"hi\\n\"\n"               /* 1 */
    "local flag = false\n"                /* 2 */
    "local t = {a=1, b=\"x\", [1]=10}\n"  /* 3 */
    "local nested = {a={b={c=1}}}\n"      /* 4 */
    "local big = {}\n"                    /* 5 */
    "for i=1,40 do big['k'..i]=i end\n"   /* 6 */
    "local x = 1\n"                       /* 7 */
    "x = x + 1\n"                         /* 8 bp: cond x>=1 + log */
    "x = x + 1\n"                         /* 9 bp: step_out (top level) */
    "print('done', x)\n";                 /* 10 bp: stop */
  int lines[]={8,9,10};
  const char* conds[]={"x>=1","",""};
  const char* logs[]={"t={t} big={big} s={s} flag={flag}","",""};
  int log_only[]={0,0,0};
  lx_debug_set_breakpoints_full(g, lines, conds, logs, log_only, 3);
  int seq=0; struct ctl c1={g,&seq};
  pthread_t th; pthread_create(&th,NULL,controller,&c1);
  memset(err,0,sizeof(err));
  rc=lx_run(g, src, err, sizeof(err));
  pthread_join(th,NULL);
  CHK(rc==1 && has(err,"debug stopped by user"), "stop by user");
  CHK(has(lx_last_output(g),"[log] L8:"), "logpoint fired");
  CHK(has(lx_last_output(g),"\"a\":1"), "logpoint table value");
  lx_close(g);

  /* ---------- run 2: logpoint brace handling (log_only, no pauses) ---------- */
  lx_State* g2 = lx_new();
  lx_debug_enable(g2, 1);
  int l2[]={1}; const char* cc2[]={""}; const char* lo2[]={"a{b c{}d {1+}e"}; int lon2[]={1};
  lx_debug_set_breakpoints_full(g2, l2, cc2, lo2, lon2, 1);
  memset(err,0,sizeof(err));
  rc=lx_run(g2, "local x=1\nreturn x\n", err, sizeof(err));
  CHK(rc==0, "logonly rc");
  { const char* o=lx_last_output(g2);
    /* { ... } always denotes an expression to evaluate; 'b c{' (up to the
       first '}') and '1+' both fail to parse and render as '?' */
    CHK(has(o,"a?d"), "log braced-expr eval");
    CHK(has(o,"?e"), "log failed expr -> ?"); }

  /* a '{' with no following '}' (or too far away) is emitted literally */
  lx_debug_clear_breakpoints(g2);
  { int l3[]={1}; const char* c3[]={""}; const char* lo3[]={"end{open"}; int lon3[]={1};
    lx_debug_set_breakpoints_full(g2, l3, c3, lo3, lon3, 1); }
  memset(err,0,sizeof(err));
  rc=lx_run(g2, "local x=1\nreturn x\n", err, sizeof(err));
  CHK(rc==0 && has(lx_last_output(g2),"end{open"), "log literal lone brace");

  /* ---------- eval while NOT paused ---------- */
  CHK(has(lx_debug_eval(g2,"x"),"not paused"), "eval not paused");

  /* ---------- clear_breakpoints: run does not pause ---------- */
  lx_debug_clear_breakpoints(g2);
  int seq2=0; struct ctl ctl2={g2,&seq2};
  pthread_t th2; pthread_create(&th2,NULL,controller,&ctl2);
  memset(err,0,sizeof(err));
  rc=lx_run(g2, "local y=5\nreturn y\n", err, sizeof(err));
  pthread_join(th2,NULL);
  CHK(rc==0 && seq2==0, "clear bp no pause");

  /* ---------- break_on_error off: error does not pause ---------- */
  lx_State* g3 = lx_new();
  lx_debug_enable(g3, 1);
  lx_debug_set_break_on_error(g3, 0);
  memset(err,0,sizeof(err));
  rc=lx_run(g3, "local a=1\nreturn a+nil\n", err, sizeof(err));
  CHK(rc==1, "boe off errors");
  CHK(has(err,"arithmetic on a nil value"), "boe off msg");
  CHK(lx_debug_last_error(g3)[0]!=0, "last_error accessor");
  lx_close(g3);

  /* ---------- break_on_error on: error pauses, controller continues ---------- */
  lx_State* g4 = lx_new();
  lx_set_step_limit(g4, 10000000);
  lx_debug_enable(g4, 1);
  lx_debug_set_break_on_error(g4, 1);
  int seq4=99; /* >=4 -> continue-only */ struct ctl c4={g4,&seq4};
  pthread_t th4; pthread_create(&th4,NULL,controller,&c4);
  memset(err,0,sizeof(err));
  rc=lx_run(g4, "local a=1\nreturn a+nil\n", err, sizeof(err));
  pthread_join(th4,NULL);
  CHK(rc==1, "boe on errors after continue");
  lx_close(g4);

  if(fails){ fprintf(stderr, "t20: %d failure(s)\n", fails); return 1; }
  printf("t20 ok\n");
  return 0;
}
