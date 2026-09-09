/* t22_ui2.c — UI serialization edge cases + lx_invoke + app_view as a function. */
#include "../lx.h"
#include <stdio.h>
#include <string.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s,sub); }

int main(void){
  char err[512]; int rc;
  const char* j;

  /* non-UI return serializes to "null" */
  lx_State* g = lx_new();
  memset(err,0,sizeof(err));
  rc = lx_run(g, "local a=1\nreturn a+1\n", err, sizeof(err));
  CHK(rc==0, "non-ui run");
  CHK(strcmp(lx_last_json(g),"null")==0, "non-ui json null");
  lx_close(g);

  /* app_view is a function: lx_build_tree calls it and serializes its result */
  g = lx_new();
  memset(err,0,sizeof(err));
  rc = lx_run(g, "return function() return require('ui').app{title='F'} end\n", err, sizeof(err));
  j = lx_last_json(g);
  CHK(rc==0 && has(j,"\"type\":\"app\""), "app_view function");
  CHK(has(j,"\"title\":\"F\""), "app_view function title");
  lx_close(g);

  /* JSON string escapes in a UI tree (\n \t \r \" \\ and a control char) */
  g = lx_new();
  memset(err,0,sizeof(err));
  rc = lx_run(g,
    "local ui=require('ui')\n"
    "return ui.app{ title=\"a\\nb\\tc\\rd\\\"e\\\\f\\x01g\" }\n",
    err, sizeof(err));
  j = lx_last_json(g);
  CHK(rc==0, "escape run");
  CHK(has(j,"\\n")&&has(j,"\\t")&&has(j,"\\r")&&has(j,"\\\"")&&has(j,"\\\\")&&has(j,"\\u0001"), "json escapes");
  lx_close(g);

  /* false/nil/number props + plain-array prop + ui-node prop */
  g = lx_new();
  memset(err,0,sizeof(err));
  rc = lx_run(g,
    "local ui=require('ui')\n"
    "return ui.app{\n"
    "  flag = false,\n"
    "  miss = nil,\n"
    "  n = 7,\n"
    "  items = {1, 'two', false},\n"
    "  ui.text{text='child'},\n"
    "}\n",
    err, sizeof(err));
  j = lx_last_json(g);
  CHK(rc==0, "props run");
  CHK(has(j,"\"flag\":false"), "false prop");
  CHK(has(j,"\"miss\":null"), "nil prop");
  CHK(has(j,"\"items\":[1,\"two\",false]"), "plain array prop");
  lx_close(g);

  /* lx_invoke: a registered handler rebuilds the tree */
  g = lx_new();
  memset(err,0,sizeof(err));
  rc = lx_run(g,
    "local ui=require('ui')\n"
    "local count=0\n"
    "return ui.app{ title='A', ui.button{ text='b', onClick=function() count=count+1 end } }\n",
    err, sizeof(err));
  CHK(rc==0, "invoke setup");
  j = lx_last_json(g);
  CHK(has(j,"\"__handler\":0"), "handler registered");
  /* invalid handler id */
  memset(err,0,sizeof(err));
  rc = lx_invoke(g, 99, NULL, err, sizeof(err));
  CHK(rc==1 && has(err,"invalid handler id"), "invoke invalid id");
  /* valid handler: increments count and rebuilds tree */
  memset(err,0,sizeof(err));
  rc = lx_invoke(g, 0, NULL, err, sizeof(err));
  CHK(rc==0, "invoke valid rc");
  CHK(has(lx_last_json(g),"\"__handler\":0"), "invoke rebuilds tree");
  lx_close(g);

  /* lx_invoke with a handler that errors reports the error */
  g = lx_new();
  memset(err,0,sizeof(err));
  rc = lx_run(g,
    "local ui=require('ui')\n"
    "return ui.app{ ui.button{ text='x', onClick=function() error('boom') end } }\n",
    err, sizeof(err));
  CHK(rc==0, "invoke-err setup");
  memset(err,0,sizeof(err));
  rc = lx_invoke(g, 0, NULL, err, sizeof(err));
  CHK(rc==1 && has(err,"boom"), "invoke handler error");
  lx_close(g);

  if(fails){ fprintf(stderr, "t22: %d failure(s)\n", fails); return 1; }
  printf("t22 ok\n");
  return 0;
}
