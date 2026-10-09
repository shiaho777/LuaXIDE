/* t22_ui2.c — HTML op buffer: escapes, bad calls, empty batch, clear. */
#include "../lx.h"
#include <stdio.h>
#include <string.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s,sub); }

int main(void){
  char err[512]; int rc;
  const char* j;

  lx_State* g = lx_new();
  memset(err,0,sizeof(err));
  rc = lx_run(g, "local a=1\nreturn a+1\n", err, sizeof(err));
  CHK(rc==0, "non-html run");
  CHK(strcmp(lx_last_json(g),"[]")==0, "no ops is empty array");
  lx_close(g);

  g = lx_new();
  memset(err,0,sizeof(err));
  rc = lx_run(g,
    "local html=require('html')\n"
    "html.setText('t', \"a\\nb\\tc\\rd\\\"e\\\\f\\x01g\")\n",
    err, sizeof(err));
  j = lx_last_json(g);
  CHK(rc==0, "escape run");
  CHK(has(j,"\\n")&&has(j,"\\t")&&has(j,"\\r")&&has(j,"\\\"")&&has(j,"\\\\")&&has(j,"\\u0001"), "json escapes");
  CHK(has(j,"\"op\":\"setText\"") && has(j,"\"id\":\"t\""), "setText shape");
  lx_html_clear_ops(g);
  CHK(strcmp(lx_last_json(g),"[]")==0, "clear drops the batch");
  lx_close(g);

  g = lx_new();
  rc = lx_run(g, "require('html').setText('a','1')\nrequire('html').setHtml('a','<i>2</i>')\n", err, sizeof(err));
  j = lx_last_json(g);
  CHK(rc==0 && has(j,"\"op\":\"setText\"") && has(j,"\"op\":\"setHtml\""), "two ops one batch");
  CHK(j[0]=='[' && j[strlen(j)-1]==']', "batch is a JSON array");
  lx_close(g);

  g = lx_new();
  rc = lx_run(g, "require('html').setText()\n", err, sizeof(err));
  CHK(rc==1 && has(err,"bad argument"), "setText arity");
  CHK(strcmp(lx_last_json(g),"[]")==0, "failed run drops ops");
  lx_close(g);

  g = lx_new();
  rc = lx_invoke(g, 0, NULL, err, sizeof(err));
  CHK(rc==1 && has(err,"lx_html_event"), "numeric invoke is retired");
  lx_close(g);

  if(fails){ fprintf(stderr,"t22: %d failure(s)\n", fails); return 1; }
  printf("t22-ui2 ok\n");
  return 0;
}
