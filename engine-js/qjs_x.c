/* qjs_x.c — LuaXIDE JavaScript engine facade over QuickJS (see qjs_x.h). */
#include "qjs_x.h"
#include "quickjs.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* JS prelude: builds the ui.* component constructors. A ui node is a plain
 * object {type, props, children}; function-valued props are registered as
 * handlers and serialized as {"__handler": id} so the host can invoke them
 * back through __lx_invoke — the same contract as the Lua engine. */
static const char* PRELUDE =
    "(function(){"
    "  var __handlers = {};"
    "  var __nextId = 0;"
    "  globalThis.__lx_invoke = function(id){"
    "    var f = __handlers[id];"
    "    if (typeof f !== 'function') throw new Error('no handler ' + id);"
    "    var a = globalThis.__lx_event_arg;"
    "    globalThis.__lx_event_arg = undefined;"
    "    return (a === undefined) ? f() : f(a);"
    "  };"
    "  function props(p){"
    "    var out = {};"
    "    if (!p) return out;"
    "    for (var k in p){"
    "      var v = p[k];"
    "      if (typeof v === 'function'){ var id = __nextId++; __handlers[id] = v; out[k] = {__handler: id}; }"
    "      else out[k] = v;"
    "    }"
    "    return out;"
    "  }"
    "  function child(c){"
    "    if (c == null || c === false || c === true) return null;"
    "    if (typeof c === 'object') return c;"
    "    return {type: 'text', props: {text: String(c)}, children: []};"
    "  }"
    "  function node(type, p){"
    "    var kids = [];"
    "    for (var i = 2; i < arguments.length; i++){"
    "      var a = arguments[i];"
    "      if (Array.isArray(a)) { for (var j = 0; j < a.length; j++) { var c = child(a[j]); if (c) kids.push(c); } }"
    "      else { var c2 = child(a); if (c2) kids.push(c2); }"
    "    }"
    "    return {type: type, props: props(p), children: kids};"
    "  }"
    "  var ui = {};"
    "  ['app','column','row','text','button','card','input','image','spacer',"
    "   'divider','scrollview','list','listitem','stack','page','switch']"
    "    .forEach(function(t){ ui[t] = function(p){ var a = [t, p]; for (var i = 1; i < arguments.length; i++) a.push(arguments[i]); return node.apply(null, a); }; });"
    "  ui.node = node;"
    "  globalThis.ui = ui;"
    "})();";

struct StrBuf {
    char* p;
    size_t n, cap;
};

static void sb_init(struct StrBuf* b) { b->p = NULL; b->n = 0; b->cap = 0; }
static void sb_free(struct StrBuf* b) { free(b->p); sb_init(b); }
static void sb_clear(struct StrBuf* b) { b->n = 0; if (b->p) b->p[0] = 0; }
static void sb_put(struct StrBuf* b, const char* s, size_t len) {
    if (b->n + len + 1 > b->cap) {
        size_t ncap = (b->cap ? b->cap * 2 : 1024);
        while (ncap < b->n + len + 1) ncap *= 2;
        char* np = realloc(b->p, ncap);
        if (!np) return;
        b->p = np; b->cap = ncap;
    }
    memcpy(b->p + b->n, s, len);
    b->n += len;
    b->p[b->n] = 0;
}
static void sb_puts(struct StrBuf* b, const char* s) { sb_put(b, s, strlen(s)); }

struct QjsX {
    JSRuntime* rt;
    JSContext* ctx;
    struct StrBuf out;   /* print capture */
    struct StrBuf json;  /* last ui tree */
    volatile int cancel_flag; /* set by qjsx_cancel, polled by the interrupt handler */
    int interrupt_kind;  /* 0 none, 1 cancelled, 2 step limit (why the last interrupt fired) */
    long steps;          /* interrupt polls since the last run/invoke began */
    long step_limit;     /* 0 = unlimited */
};

static JSValue js_print(JSContext* ctx, JSValueConst this_val, int argc, JSValueConst* argv) {
    QjsX* x = (QjsX*)JS_GetContextOpaque(ctx);
    for (int i = 0; i < argc; i++) {
        if (i) sb_puts(&x->out, " ");
        if (JS_IsObject(argv[i])) {
            JSValue s = JS_JSONStringify(ctx, argv[i], JS_UNDEFINED, JS_UNDEFINED);
            const char* cs = JS_ToCString(ctx, s);
            if (cs) { sb_puts(&x->out, cs); JS_FreeCString(ctx, cs); }
            JS_FreeValue(ctx, s);
        } else {
            const char* cs = JS_ToCString(ctx, argv[i]);
            if (cs) { sb_puts(&x->out, cs); JS_FreeCString(ctx, cs); }
        }
    }
    sb_puts(&x->out, "\n");
    return JS_UNDEFINED;
}

/* Serialize a ui-tree object into x->json via JSON.stringify. */
static void capture_tree(QjsX* x, JSValueConst v) {
    if (!JS_IsObject(v)) return;
    JSValue t = JS_GetPropertyStr(x->ctx, v, "type");
    int is_ui = JS_IsString(t);
    JS_FreeValue(x->ctx, t);
    if (!is_ui) return;
    JSValue s = JS_JSONStringify(x->ctx, v, JS_UNDEFINED, JS_UNDEFINED);
    const char* cs = JS_ToCString(x->ctx, s);
    if (cs) {
        sb_clear(&x->json);
        sb_puts(&x->json, cs);
        JS_FreeCString(x->ctx, cs);
    }
    JS_FreeValue(x->ctx, s);
}

/* Extract a line number from a QuickJS stack string like
 * "at fn (eval.js:12:3)\n at <anonymous> (eval.js:20:1)". Returns 0 if none. */
static int stack_line(const char* stack, const char** colon_out) {
    if (!stack) return 0;
    const char* p = strstr(stack, ":");
    while (p) {
        /* find pattern :<digits>: or :<digits>) */
        const char* q = p + 1;
        int n = 0, digits = 0;
        while (q[0] >= '0' && q[0] <= '9') { n = n * 10 + (q[0] - '0'); q++; digits++; }
        if (digits > 0 && (*q == ':' || *q == ')')) { *colon_out = p; return n; }
        p = strstr(p + 1, ":");
    }
    return 0;
}

static int qjs_interrupt_cb(JSRuntime* rt, void* opaque) {
    QjsX* x = (QjsX*)opaque;
    (void)rt;
    if (x->cancel_flag) { x->interrupt_kind = 1; return 1; }
    if (x->step_limit > 0 && ++x->steps > x->step_limit) { x->interrupt_kind = 2; return 1; }
    return 0;
}

static void run_guard_reset(QjsX* x) {
    x->steps = 0;
    x->interrupt_kind = 0;
    /* mirrors lx_reset_run: a fresh run clears a stale cancel flag, but an
     * invoke keeps it — cancelling mid-run then clicking a button must not
     * fire that handler (lx_invoke resets steps only). */
}

static int report_exception(QjsX* x, char* err, size_t errlen) {
    if (x->interrupt_kind) {
        if (err && errlen)
            snprintf(err, errlen, "%s",
                     x->interrupt_kind == 1 ? "cancelled by user"
                                          : "execution step limit exceeded (possible infinite loop)");
        JS_FreeValue(x->ctx, JS_GetException(x->ctx)); /* drain the interrupt exception */
        return 1;
    }
    JSValue e = JS_GetException(x->ctx);
    const char* msg = JS_ToCString(x->ctx, e);
    JSValue st = JS_GetPropertyStr(x->ctx, e, "stack");
    const char* stack = JS_IsString(st) ? JS_ToCString(x->ctx, st) : NULL;
    const char* unused = NULL;
    int line = stack_line(stack, &unused);
    if (err && errlen) {
        if (line > 0) snprintf(err, errlen, "line %d: %s", line, msg ? msg : "js exception");
        else snprintf(err, errlen, "%s", msg ? msg : "js exception");
    }
    if (stack) JS_FreeCString(x->ctx, stack);
    JS_FreeValue(x->ctx, st);
    if (msg) JS_FreeCString(x->ctx, msg);
    JS_FreeValue(x->ctx, e);
    return 1;
}

QjsX* qjsx_new(void) {
    QjsX* x = calloc(1, sizeof(QjsX));
    if (!x) return NULL;
    sb_init(&x->out);
    sb_init(&x->json);
    x->rt = JS_NewRuntime();
    if (!x->rt) { free(x); return NULL; }
    JS_SetMemoryLimit(x->rt, 64 * 1024 * 1024);
    JS_SetMaxStackSize(x->rt, 4 * 1024 * 1024);
    JS_SetInterruptHandler(x->rt, qjs_interrupt_cb, x);
    x->ctx = JS_NewContext(x->rt);
    if (!x->ctx) { JS_FreeRuntime(x->rt); free(x); return NULL; }
    JS_SetContextOpaque(x->ctx, x);
    JSValue g = JS_GetGlobalObject(x->ctx);
    JS_SetPropertyStr(x->ctx, g, "print",
                      JS_NewCFunction(x->ctx, js_print, "print", 1));
    JS_FreeValue(x->ctx, g);
    JSValue r = JS_Eval(x->ctx, PRELUDE, strlen(PRELUDE), "<prelude>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(r)) {
        char buf[256];
        report_exception(x, buf, sizeof(buf));
        fprintf(stderr, "qjsx prelude failed: %s\n", buf);
    }
    JS_FreeValue(x->ctx, r);
    return x;
}

void qjsx_free(QjsX* x) {
    if (!x) return;
    if (x->ctx) JS_FreeContext(x->ctx);
    if (x->rt) JS_FreeRuntime(x->rt);
    sb_free(&x->out);
    sb_free(&x->json);
    free(x);
}

/* Wrap user source so `return` works at top level and the completion value is
 * the script's return value. */
static JSValue eval_wrapped(QjsX* x, const char* src) {
    static const char* HEAD = "(function(){\n";
    static const char* TAIL = "\n})()";
    struct StrBuf buf;
    sb_init(&buf);
    sb_puts(&buf, HEAD);
    sb_puts(&buf, src ? src : "");
    sb_puts(&buf, TAIL);
    JSValue v = JS_Eval(x->ctx, buf.p ? buf.p : "", buf.n, "<script>", JS_EVAL_TYPE_GLOBAL);
    sb_free(&buf);
    return v;
}

int qjsx_run(QjsX* x, const char* src, char* err, size_t errlen) {
    if (!x || !x->ctx) { if (err && errlen) snprintf(err, errlen, "engine not initialized"); return 1; }
    run_guard_reset(x);
    x->cancel_flag = 0; /* lx_reset_run semantics */
    sb_clear(&x->json);
    JSValue v = eval_wrapped(x, src);
    if (JS_IsException(v)) { JS_FreeValue(x->ctx, v); return report_exception(x, err, errlen); }
    capture_tree(x, v);
    JS_FreeValue(x->ctx, v);
    return 0;
}

int qjsx_invoke(QjsX* x, int handler_id, const char* arg, char* err, size_t errlen) {
    if (!x || !x->ctx) { if (err && errlen) snprintf(err, errlen, "engine not initialized"); return 1; }
    run_guard_reset(x);
    /* no proactive clear: capture_tree replaces the json only when the handler
     * returns a ui tree, so an undefined return keeps the previous view —
     * the same re-render contract as lx_invoke (caught by j6 conformance) */
    JSValue g = JS_GetGlobalObject(x->ctx);
    JS_SetPropertyStr(x->ctx, g, "__lx_event_arg",
                      arg ? JS_NewString(x->ctx, arg) : JS_UNDEFINED);
    JS_FreeValue(x->ctx, g);
    char call[64];
    snprintf(call, sizeof(call), "globalThis.__lx_invoke(%d)", handler_id);
    JSValue v = JS_Eval(x->ctx, call, strlen(call), "<invoke>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(v)) { JS_FreeValue(x->ctx, v); return report_exception(x, err, errlen); }
    capture_tree(x, v);
    JS_FreeValue(x->ctx, v);
    return 0;
}

const char* qjsx_last_json(QjsX* x) { return (x && x->json.p) ? x->json.p : ""; }
const char* qjsx_last_output(QjsX* x) { return (x && x->out.p) ? x->out.p : ""; }
void qjsx_clear_output(QjsX* x) { if (x) sb_clear(&x->out); }
void qjsx_cancel(QjsX* x) { if (x) x->cancel_flag = 1; }
void qjsx_clear_cancel(QjsX* x) { if (x) x->cancel_flag = 0; }
void qjsx_set_step_limit(QjsX* x, long steps) { if (x) x->step_limit = steps; }
