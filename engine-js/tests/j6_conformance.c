/* j6_conformance.c — the JS side of the cross-engine conformance suite.
 * Mirrors the assertions of engine/tests/t26_invoke_tree.c so the LuaX and
 * QuickJS facades cannot drift apart on the PLATFORM_ABI contract:
 *   - every one of the 16 ui constructors must produce its type in the tree
 *   - a handler returning a ui tree replaces the view
 *   - a handler returning undefined keeps the previous tree
 *   - the event payload reaches the handler as its first argument
 *   - print output is captured; handler errors surface as rc=1 + message
 */
#include "../qjs_x.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s, sub); }

static int handler_id(const char* j){
  const char* h = j ? strstr(j, "__handler") : NULL;
  return h ? atoi(strchr(h, ':') + 1) : -1;
}

/* every constructor the Lua engine exposes; each must serialize with its type */
static const char* ALL_COMPONENTS =
    "return ui.app({ title: 'conformance' },"
    "  ui.column({ spacing: 1 },"
    "    ui.row({ spacing: 1 },"
    "      ui.text({ text: 't' }),"
    "      ui.button({ text: 'b', onClick: function(){} }),"
    "      ui.card({}, ui.text({ text: 'c' })),"
    "      ui.input({ label: 'i', onSubmit: function(){} }),"
    "      ui.image({ src: 'x.png' }),"
    "      ui.spacer({ size: 4 }),"
    "      ui.divider({}),"
    "      ui.scrollview({}, ui.text({ text: 's' }))),"
    "    ui.list({}, ui.listitem({ title: 'li' })),"
    "    ui.stack({ selected: 'a' },"
    "      ui.page({ key: 'a' }, ui.text({ text: 'pa' })),"
    "      ui.page({ key: 'b' }, ui.text({ text: 'pb' }))),"
    "    ui.switch({ label: 'sw', onToggle: function(){} })));";

static const char* PAYLOAD_VIEW =
    "var name = '?';"
    "function view(){"
    "  return ui.app({ title: 'payload' },"
    "    ui.input({ key: 'in', label: 'name', onSubmit: function(text){ name = text; return view(); } }),"
    "    ui.text({ key: 'echo', text: 'hello, ' + name }));"
    "}"
    "return view();";

static const char* TOGGLE_VIEW =
    "var flag = false;"
    "function view(){"
    "  return ui.app({ title: 'toggle' },"
    "    ui.switch({ key: 'sw', checked: flag, onToggle: function(v){ flag = (v === 'true'); return view(); } }),"
    "    ui.text({ key: 'state', text: flag ? 'on' : 'off' }));"
    "}"
    "return view();";

int main(void){
  char err[512];
  QjsX* x = qjsx_new();
  CHK(x != NULL, "engine created");

  /* --- 1. component parity: all 16 types serialize --- */
  {
    static const char* types[] = { "app","column","row","text","button","card","input",
                                   "image","spacer","divider","scrollview","list",
                                   "listitem","stack","page","switch" };
    int rc = qjsx_run(x, ALL_COMPONENTS, err, sizeof(err));
    CHK(rc == 0, "all-components run");
    if (rc) fprintf(stderr, "  err: %s\n", err);
    const char* j = qjsx_last_json(x);
    for (size_t i = 0; i < sizeof(types)/sizeof(types[0]); i++) {
      char pat[32];
      snprintf(pat, sizeof(pat), "\"type\":\"%s\"", types[i]);
      CHK(has(j, pat), types[i]); /* message = the missing component */
    }
    CHK(handler_id(j) >= 0, "handlers registered");
  }

  /* --- 2. payload reaches the handler; returned tree replaces the view --- */
  {
    int rc = qjsx_run(x, PAYLOAD_VIEW, err, sizeof(err));
    CHK(rc == 0, "payload view run");
    const char* j = qjsx_last_json(x);
    CHK(has(j, "hello, ?"), "initial echo");
    int id = handler_id(j);
    CHK(id >= 0, "onSubmit handler");
    rc = qjsx_invoke(x, id, "shiaho", err, sizeof(err));
    CHK(rc == 0, "invoke with payload");
    CHK(has(qjsx_last_json(x), "hello, shiaho"), "payload reaches Lua-side state");
    /* switch-style payload: "false" string */
    j = qjsx_last_json(x);
    id = handler_id(j);
    rc = qjsx_invoke(x, id, "false", err, sizeof(err));
    CHK(rc == 0, "invoke with false payload");
    CHK(has(qjsx_last_json(x), "hello, false"), "false payload as string");
  }

  /* --- 3. undefined return keeps the previous tree --- */
  {
    int rc = qjsx_run(x,
        "var n = 0;"
        "return ui.app({ title: 'keep' },"
        "  ui.button({ text: 'go', onClick: function(){ n += 1; } }));",
        err, sizeof(err));
    CHK(rc == 0, "keep view run");
    const char* j = qjsx_last_json(x);
    int id = handler_id(j);
    CHK(id >= 0, "handler for keep test");
    rc = qjsx_invoke(x, id, NULL, err, sizeof(err));
    CHK(rc == 0, "undefined-return invoke");
    CHK(has(qjsx_last_json(x), "\"title\":\"keep\""), "undefined keeps previous view");
  }

  /* --- 4. switch payload carries the new state --- */
  {
    int rc = qjsx_run(x, TOGGLE_VIEW, err, sizeof(err));
    CHK(rc == 0, "toggle view run");
    const char* j = qjsx_last_json(x);
    CHK(has(j, "\"checked\":false"), "initial unchecked");
    int id = handler_id(j);
    CHK(id >= 0, "onToggle handler");
    rc = qjsx_invoke(x, id, "true", err, sizeof(err));
    CHK(rc == 0, "toggle invoke");
    j = qjsx_last_json(x);
    CHK(has(j, "\"checked\":true"), "switch state re-rendered from payload");
    CHK(has(j, "\"text\":\"on\""), "state text follows payload");
  }

  /* --- 5. print capture + handler error --- */
  {
    int rc = qjsx_run(x, "print('out-works'); 1", err, sizeof(err));
    CHK(rc == 0 && has(qjsx_last_output(x), "out-works"), "print captured");
    rc = qjsx_run(x,
        "return ui.app({}, ui.button({ text: 'x', onClick: function(){ throw new Error('boom'); } }));",
        err, sizeof(err));
    CHK(rc == 0, "set up throwing handler");
    int id = handler_id(qjsx_last_json(x));
    rc = qjsx_invoke(x, id, NULL, err, sizeof(err));
    CHK(rc == 1, "handler error surfaces");
    CHK(has(err, "boom"), "handler error message");
  }

  qjsx_free(x);
  if (fails) { fprintf(stderr, "j6-conformance: %d failure(s)\n", fails); return 1; }
  printf("j6-conformance ok\n");
  return 0;
}
