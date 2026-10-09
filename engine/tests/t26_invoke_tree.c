/* t26_invoke_tree.c — LuaX HTML host contract.
 * JS/Python still assert the UI-tree contract in j6. This driver asserts the
 * Lua side: html.on + lx_html_event, payload, error rollback, replacement. */
#include "../lx.h"
#include <stdio.h>
#include <string.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s, sub)!=NULL; }

static int run(lx_State* S, const char* src, char* err, int errlen){
  memset(err, 0, (size_t)errlen);
  return lx_run(S, src, err, errlen);
}

int main(void){
  char err[512];
  lx_State* S = lx_new();
  lx_set_step_limit(S, 50000000L);

  /* click updates text; payload is the event's string */
  int rc = run(S,
    "local html = require('html')\n"
    "local n = 0\n"
    "html.on('plus', 'click', function(payload)\n"
    "  n = n + 1\n"
    "  html.setText('count', payload .. ':' .. n)\n"
    "end)\n"
    "html.setText('count', 'taps: 0')\n",
    err, sizeof(err));
  CHK(rc==0, "run counter");
  if(rc) fprintf(stderr, "%s\n", err);
  CHK(has(lx_last_json(S), "\"text\":\"taps: 0\""), "initial text");
  lx_html_clear_ops(S);

  rc = lx_html_event(S, "plus", "click", "go", err, sizeof(err));
  CHK(rc==0, "click");
  CHK(has(lx_last_json(S), "\"text\":\"go:1\""), "payload and state");
  lx_html_clear_ops(S);

  rc = lx_html_event(S, "plus", "click", "", err, sizeof(err));
  CHK(rc==0 && has(lx_last_json(S), "\"text\":\":2\""), "empty payload is a string");
  lx_html_clear_ops(S);

  /* unknown id succeeds and queues nothing */
  rc = lx_html_event(S, "missing", "click", "", err, sizeof(err));
  CHK(rc==0 && strcmp(lx_last_json(S), "[]")==0, "unknown id is a no-op");

  /* a handler error rewinds ops from that call and keeps the registration */
  rc = run(S,
    "local html = require('html')\n"
    "html.on('boom', 'click', function()\n"
    "  html.setText('count', 'nope')\n"
    "  error('boom')\n"
    "end)\n"
    "html.setText('count', 'safe')\n",
    err, sizeof(err));
  CHK(rc==0 && has(lx_last_json(S), "\"text\":\"safe\""), "baseline before error");
  lx_html_clear_ops(S);
  rc = lx_html_event(S, "boom", "click", "", err, sizeof(err));
  CHK(rc==1 && has(err, "boom"), "handler error");
  CHK(strcmp(lx_last_json(S), "[]")==0, "error rewinds the batch");
  rc = lx_html_event(S, "boom", "click", "", err, sizeof(err));
  CHK(rc==1, "handler still registered after error");

  /* re-registering the same id+event replaces the function */
  rc = run(S,
    "local html = require('html')\n"
    "html.on('plus', 'click', function() html.setText('count', 'first') end)\n"
    "html.on('plus', 'click', function() html.setText('count', 'second') end)\n",
    err, sizeof(err));
  CHK(rc==0, "reregister run");
  lx_html_clear_ops(S);
  rc = lx_html_event(S, "plus", "click", "", err, sizeof(err));
  CHK(rc==0 && has(lx_last_json(S), "\"text\":\"second\""), "later on() wins");
  CHK(!has(lx_last_json(S), "first"), "replaced handler does not run");

  /* input payload round-trip */
  rc = run(S,
    "local html = require('html')\n"
    "html.on('name', 'input', function(text) html.setText('echo', text) end)\n",
    err, sizeof(err));
  CHK(rc==0, "input run");
  rc = lx_html_event(S, "name", "input", "shiaho", err, sizeof(err));
  CHK(rc==0 && has(lx_last_json(S), "\"text\":\"shiaho\""), "input payload");

  /* distinct events on one id */
  rc = run(S,
    "local html = require('html')\n"
    "html.on('go', 'click', function() html.setText('out', 'clicked') end)\n"
    "html.on('go', 'input', function() html.setText('out', 'typed') end)\n",
    err, sizeof(err));
  lx_html_clear_ops(S);
  rc = lx_html_event(S, "go", "input", "x", err, sizeof(err));
  CHK(rc==0 && has(lx_last_json(S), "typed") && !has(lx_last_json(S), "clicked"), "event names are distinct");

  lx_close(S);
  if(fails){ fprintf(stderr, "t26: %d failure(s)\n", fails); return 1; }
  printf("t26-html ok\n");
  return 0;
}
