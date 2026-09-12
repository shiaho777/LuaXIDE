/* t26_invoke_tree.c — re-render contract of lx_invoke:
 *  1. a handler returning a ui tree replaces app_view (JSON changes);
 *  2. a handler returning nil falls back to re-serializing the previous
 *     app_view (calling it first when it is a view function);
 *  3. a view-function script re-renders on every invoke (state mutation);
 *  4. arg (event payload) is passed to the handler as its first argument.
 */
#include "../lx.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s,sub); }

int main(void){
  char err[512];
  lx_State* S;

  /* --- 1+2: handler return value adoption & nil fallback --- */
  S = lx_new();
  lx_set_step_limit(S, 50000000L);
  {
    const char* src =
      "local ui = require(\"ui\")\n"
      "local count = 0\n"
      "local function screen()\n"
      "  return ui.app { title = count == 0 and \"first\" or \"second\", key = \"root\",\n"
      "    ui.button { key = \"b\", text = \"go\",\n"
      "      onClick = function()\n"
      "        count = count + 1\n"
      "        if count == 1 then return screen() end\n"
      "      end } }\n"
      "end\n"
      "return screen()";
    int rc = lx_run(S, src, err, sizeof(err));
    CHK(rc==0, "run static-tree script");
    if(rc){ fprintf(stderr,"%s\n",err); }
    const char* j = lx_last_json(S);
    CHK(has(j,"\"title\":\"first\""), "initial tree");
    int id = -1;
    { const char* h = j?strstr(j,"__handler"):NULL; if(h){ id = atoi(strchr(h,':')+1); } }
    CHK(id>=0, "handler registered");
    rc = lx_invoke(S, id, NULL, err, sizeof(err));
    CHK(rc==0, "invoke #1 (returns tree)");
    if(rc){ fprintf(stderr,"invoke#1: %s\n",err); }
    j = lx_last_json(S);
    CHK(has(j,"\"title\":\"second\""), "handler return replaces app_view");
    /* handler now returns nil: previous (adopted) tree re-serialized.
     * handler ids are re-assigned on every rebuild, so re-read the id. */
    { const char* h = j?strstr(j,"__handler"):NULL; if(h){ id = atoi(strchr(h,':')+1); } }
    rc = lx_invoke(S, id, NULL, err, sizeof(err));
    CHK(rc==0, "invoke #2 (returns nil)");
    if(rc){ fprintf(stderr,"invoke#2: %s\n",err); }
    j = lx_last_json(S);
    CHK(has(j,"\"title\":\"second\""), "nil keeps adopted view");
  }
  lx_close(S);

  /* --- 3: view-function script re-renders on every invoke --- */
  S = lx_new();
  lx_set_step_limit(S, 50000000L);
  {
    const char* src =
      "local ui = require(\"ui\")\n"
      "local count = 0\n"
      "local function view()\n"
      "  return ui.app { title = \"counter\",\n"
      "    ui.text { key = \"v\", text = \"n=\" .. count },\n"
      "    ui.button { key = \"inc\", text = \"+1\", onClick = function() count = count + 1 end } }\n"
      "end\n"
      "return view";
    int rc = lx_run(S, src, err, sizeof(err));
    CHK(rc==0, "run view-function script");
    const char* j = lx_last_json(S);
    CHK(has(j,"\"text\":\"n=0\""), "initial view");
    int id = -1;
    { const char* h = j?strstr(j,"__handler"):NULL; if(h){ id = atoi(strchr(h,':')+1); } }
    for(int t=1;t<=3;t++){
      rc = lx_invoke(S, id, NULL, err, sizeof(err));
      CHK(rc==0, "tick invoke");
      char want[32]; snprintf(want,sizeof(want),"\"text\":\"n=%d\"",t);
      CHK(has(lx_last_json(S),want), "view re-renders with new state");
      j = lx_last_json(S);
      { const char* h = j?strstr(j,"__handler"):NULL; if(h){ id = atoi(strchr(h,':')+1); } }
      CHK(id>=0, "handler id survives rebuild");
    }
  }
  lx_close(S);

  /* --- 4: event payload reaches the handler --- */
  S = lx_new();
  lx_set_step_limit(S, 50000000L);
  {
    const char* src =
      "local ui = require(\"ui\")\n"
      "local name = \"?\"\n"
      "local function view()\n"
      "  return ui.app { title = \"payload\",\n"
      "    ui.input { key = \"in\", label = \"name\",\n"
      "      onSubmit = function(text) name = text; return view() end },\n"
      "    ui.text { key = \"echo\", text = name } }\n"
      "end\n"
      "return view";
    int rc = lx_run(S, src, err, sizeof(err));
    CHK(rc==0, "run payload script");
    const char* j = lx_last_json(S);
    CHK(has(j,"\"text\":\"?\""), "initial view");
    int id = -1;
    { const char* h = j?strstr(j,"__handler"):NULL; if(h){ id = atoi(strchr(h,':')+1); } }
    CHK(id>=0, "onSubmit handler registered");
    rc = lx_invoke(S, id, "shiaho", err, sizeof(err));
    CHK(rc==0, "invoke with payload");
    CHK(has(lx_last_json(S),"\"text\":\"shiaho\""), "payload reaches Lua state");
    /* switch-style payload: "true"/"false" strings */
    j = lx_last_json(S);
    { const char* h = j?strstr(j,"__handler"):NULL; if(h){ id = atoi(strchr(h,':')+1); } }
    rc = lx_invoke(S, id, "false", err, sizeof(err));
    CHK(rc==0, "invoke with false payload");
    CHK(has(lx_last_json(S),"\"text\":\"false\""), "false payload as string");
  }
  lx_close(S);

  /* --- 5: handler error keeps the previous tree; output resets per invoke --- */
  S = lx_new();
  lx_set_step_limit(S, 50000000L);
  {
    const char* src =
      "local ui = require(\"ui\")\n"
      "print(\"run-print\")\n"
      "local function view()\n"
      "  return ui.app { title = \"errk\",\n"
      "    ui.button { key = \"e\", text = \"x\",\n"
      "      onClick = function() print(\"evt-print\") error(\"boom\") end } }\n"
      "end\n"
      "return view";
    int rc = lx_run(S, src, err, sizeof(err));
    CHK(rc==0, "run errk script");
    CHK(has(lx_last_output(S),"run-print"), "run output captured");
    const char* j = lx_last_json(S);
    CHK(has(j,"\"title\":\"errk\""), "errk initial tree");
    int id = -1;
    { const char* h = j?strstr(j,"__handler"):NULL; if(h){ id = atoi(strchr(h,':')+1); } }
    CHK(id>=0, "errk handler registered");
    rc = lx_invoke(S, id, NULL, err, sizeof(err));
    CHK(rc==1, "handler error surfaces");
    CHK(has(err,"boom"), "handler error message");
    CHK(has(lx_last_json(S),"\"title\":\"errk\""), "tree kept after handler error");
    CHK(has(lx_last_output(S),"evt-print"), "invoke print captured");
    CHK(!has(lx_last_output(S),"run-print"), "output resets per invoke");
  }
  lx_close(S);

  /* --- 6: a view function throwing during re-serialize keeps the tree ---
   * (ABI §4.4: any invoke error preserves the current view; the handler
   * itself succeeded here — the failure is inside the view re-call) */
  S = lx_new();
  lx_set_step_limit(S, 50000000L);
  {
    const char* src =
      "local ui = require(\"ui\")\n"
      "local bad = false\n"
      "local function view()\n"
      "  if bad then error(\"view kaput\") end\n"
      "  return ui.app { title = \"ve\",\n"
      "    ui.button { key = \"b\", text = \"x\",\n"
      "      onClick = function() bad = true end } }\n"
      "end\n"
      "return view";
    int rc = lx_run(S, src, err, sizeof(err));
    CHK(rc==0, "run ve script");
    const char* j = lx_last_json(S);
    CHK(has(j,"\"title\":\"ve\""), "ve initial tree");
    int id = -1;
    { const char* h = j?strstr(j,"__handler"):NULL; if(h){ id = atoi(strchr(h,':')+1); } }
    CHK(id>=0, "ve handler registered");
    rc = lx_invoke(S, id, NULL, err, sizeof(err));
    CHK(rc==1, "view-fn error surfaces");
    CHK(has(err,"view kaput"), "view-fn error message");
    CHK(has(lx_last_json(S),"\"title\":\"ve\""), "tree kept after view-fn error");
    /* the preserved tree's handlers must still resolve (ids are positional) */
    rc = lx_invoke(S, id, NULL, err, sizeof(err));
    CHK(rc==1, "preserved handler still invokable");
  }
  lx_close(S);

  if(fails){ fprintf(stderr, "t26-invoke-tree: %d failure(s)\n", fails); return 1; }
  printf("t26-invoke-tree ok\n");
  return 0;
}
