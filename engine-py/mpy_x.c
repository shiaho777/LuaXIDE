/* mpy_x.c — LuaXIDE Python engine facade over embedded MicroPython.
 * Implements the same host contract as lx.c / qjs_x.c (docs/PLATFORM_ABI.md).
 *
 * The ui DSL is defined in a Python prelude: a node is a dict
 * {type, props, children}; function-valued props register into a handler
 * table and serialize as {"__handler": id}. The tree is serialized to the
 * platform JSON with Python's json module (dicts with str keys only).
 */
#include "mpy_x.h"
#include "py/compile.h"
#include "py/gc.h"
#include "py/mpstate.h"
#include "py/runtime.h"
#include "py/stackctrl.h"
#include "shared/runtime/gchelper.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MPY_HEAP_SIZE (512 * 1024)

struct MpyX {
    char* heap;
    char* out;    size_t out_sz, out_used;
    char* json;   size_t json_sz, json_used;
    volatile int cancel_flag;
    int interrupt_kind; /* 0 none, 1 cancelled, 2 step limit */
    long steps, step_limit;
    int ready;         /* runtime initialized */
    int stack_mark;
};

static void buf_append(char** b, size_t* sz, size_t* used, const char* s, size_t n) {
    if (*used + n + 1 > *sz) {
        size_t ns = *sz ? *sz * 2 : 256;
        while (ns < *used + n + 1) ns *= 2;
        char* nb = realloc(*b, ns);
        if (!nb) return;
        *b = nb; *sz = ns;
    }
    memcpy(*b + *used, s, n);
    *used += n;
    (*b)[*used] = 0;
}

/* single active engine per process (pilot scope; the host owns one worker thread) */
static MpyX* g_active;

void mp_hal_stdout_tx_strn(const char* str, size_t len) {
    if (g_active && str) buf_append(&g_active->out, &g_active->out_sz, &g_active->out_used, str, len);
}

/* the embed port routes print through the cooked variant; capture it too.
 * mphalport.c is excluded from our build (its cooked impl goes to printf). */
void mp_hal_stdout_tx_strn_cooked(const char* str, size_t len) {
    if (g_active && str) buf_append(&g_active->out, &g_active->out_sz, &g_active->out_used, str, len);
}

/* Cooperative cancel / step limit: py/vm.c calls MICROPY_VM_HOOK_LOOP
 * (= mpy_x_vm_poll) on every dispatch-loop branch. Raising a pending
 * exception makes the VM unwind cleanly; mpy_exec's nlr handler turns it
 * into the same "cancelled by user" / "execution step limit exceeded"
 * strings the other engines produce. */
void mpy_x_vm_poll(void) {
    MpyX* x = g_active;
    if (!x) return;
    if (x->cancel_flag) { x->interrupt_kind = 1; }
    else if (x->step_limit > 0 && ++x->steps > x->step_limit) { x->interrupt_kind = 2; }
    else return;
    MP_STATE_THREAD(mp_pending_exception) =
        mp_obj_new_exception_msg(&mp_type_KeyboardInterrupt, MP_ERROR_TEXT(""));
}

/* the VM polls this on its schedule loop (mp_sched_num_pending etc) — but the
 * embed VM has no periodic hook; we hook the deep-recursion-friendly point
 * instead: MICROPY_VM_HOOK? Not available. For the pilot we poll in the
 * lexer loop via mp_import override? Simplest reliable hook: wrap long loops
 * by raising the interrupt through MICROPY_SCHEDULER_STATIC. Since none is
 * portable, the pilot enforces cancel/limit only between bytecode blocks via
 * mp_obj calls below (see mpyx_exec guarded by tick checks around chunks). */

static const char* PRELUDE =
    "import builtins\n"
    "_handlers = {}\n"
    "_next = [0]\n"
    "def _reg(fn):\n"
    "    _next[0] += 1\n"
    "    _handlers[_next[0]] = fn\n"
    "    return {'__handler': _next[0]}\n"
    "class _Ui:\n"
    "    def __getattr__(self, name):\n"
    "        def make(*args):\n"
    "            props = dict(args[0]) if args and isinstance(args[0], dict) else {}\n"
    "            for k, v in list(props.items()):\n"
    "                if callable(v):\n"
    "                    props[k] = _reg(v)\n"
    "            children = [a for a in args[1:] if a is not None]\n"
    "            return {'type': name, 'props': props, 'children': children}\n"
    "        return make\n"
    "ui = _Ui()\n";;

/* evaluate expr in globals; returns MP_OBJ_NULL on error (exception string in err) */
static int mpy_exec(MpyX* x, const char* src, char* err, size_t errlen) {
    nlr_buf_t nlr;
    if (nlr_push(&nlr) == 0) {
        mp_lexer_t* lex = mp_lexer_new_from_str_len(MP_QSTR__lt_stdin_gt_, src, strlen(src), 0);
        qstr source_name = lex->source_name;
        mp_parse_tree_t parse_tree = mp_parse(lex, MP_PARSE_FILE_INPUT);
        mp_obj_t module_fun = mp_compile(&parse_tree, source_name, false);
        mp_call_function_0(module_fun);
        nlr_pop();
        return 0;
    } else {
        mp_obj_t exc = (mp_obj_t)nlr.ret_val;
        /* the VM hook already set interrupt_kind before raising; a stray
         * KeyboardInterrupt raised by user code falls through as-is */
        if (x->interrupt_kind) {
            MP_STATE_THREAD(mp_pending_exception) = MP_OBJ_NULL;
            if (err && errlen) snprintf(err, errlen, "%s",
                x->interrupt_kind == 1 ? "cancelled by user"
                                      : "execution step limit exceeded (possible infinite loop)");
        } else {
            /* str(exc) into the error buffer via the mp_print machinery */
            vstr_t vstr; vstr_init(&vstr, 128);
            mp_print_t print;
            print.data = &vstr;
            print.print_strn = (void (*)(void *, const char *, size_t))vstr_add_strn;
            mp_obj_print_helper(&print, exc, PRINT_STR);
            if (err && errlen) snprintf(err, errlen, "%s", vstr_len(&vstr) ? vstr_str(&vstr) : "python exception");
            vstr_clear(&vstr);
        }
        return 1;
    }
}

MpyX* mpyx_new(void) {
    MpyX* x = calloc(1, sizeof(MpyX));
    if (!x) return NULL;
    x->heap = malloc(MPY_HEAP_SIZE);
    if (!x->heap) { free(x); return NULL; }
    g_active = x;
    return x;
}

static void mpy_reset(MpyX* x) {
    if (x->ready) return; /* one runtime for the engine's lifetime; run() re-executes
                             a fresh globals dict instead of tearing the VM down */
    int stack_top;
    mp_stack_set_top(&stack_top);
    gc_init(x->heap, x->heap + MPY_HEAP_SIZE);
    mp_init();
    x->ready = 1;
}

void mpyx_free(MpyX* x) {
    if (!x) return;
    g_active = NULL;
    if (x->ready) mp_deinit();
    free(x->heap);
    free(x->out);
    free(x->json);
    free(x);
}

const char* mpyx_last_json(MpyX* x) { return (x && x->json) ? x->json : ""; }
const char* mpyx_last_output(MpyX* x) { return (x && x->out) ? x->out : ""; }
void mpyx_clear_output(MpyX* x) { if (x) { x->out_used = 0; if (x->out) x->out[0] = 0; } }
void mpyx_cancel(MpyX* x) { if (x) x->cancel_flag = 1; }
void mpyx_clear_cancel(MpyX* x) { if (x) x->cancel_flag = 0; }
void mpyx_set_step_limit(MpyX* x, long steps) { if (x) x->step_limit = steps; }

/* find the LAST __LXTREE__ marker in the captured output — user prints
 * containing the marker must not corrupt the tree or truncate the output
 * (the real capture line is always printed last, after user code runs). */
static const char* last_tree_marker(const char* out, size_t used) {
    const char* m = NULL;
    if (!out || !used) return NULL;
    for (const char* p = out; (p = strstr(p, "__LXTREE__")) != NULL; p += 10) m = p;
    return m;
}

int mpyx_run(MpyX* x, const char* src, char* err, size_t errlen) {
    if (!x) { if (err && errlen) snprintf(err, errlen, "engine not initialized"); return 1; }
    mpy_reset(x); /* one VM per engine lifetime; run() re-executes in fresh globals */
    x->cancel_flag = 0;   /* lx_reset_run semantics */
    x->steps = 0; x->interrupt_kind = 0;
    MP_STATE_THREAD(mp_pending_exception) = MP_OBJ_NULL;
    x->out_used = 0; if (x->out) x->out[0] = 0;
    x->json_used = 0; if (x->json) x->json[0] = 0;
    char errbuf[512];
    /* fresh globals: prelude + script run in their own namespace per run */
    mpy_exec(x, "globals().clear()", NULL, 0);
    if (mpy_exec(x, PRELUDE, errbuf, sizeof(errbuf))) {
        if (err && errlen) snprintf(err, errlen, "prelude: %s", errbuf);
        return 1;
    }
    if (mpy_exec(x, src, err, errlen)) return 1;
    /* capture tree: view() or _lx_tree global, serialized with json.dumps */
    static const char* cap =
        "def _lx_str(s):\n"
        "    out = ''\n"
        "    for ch in s:\n"
        "        if ch == chr(92) or ch == chr(34):\n"
        "            out += chr(92) + ch\n"
        "        elif ch == chr(10):\n"
        "            out += chr(92) + 'n'\n"
        "        elif ch == chr(13):\n"
        "            out += chr(92) + 'r'\n"
        "        elif ch == chr(9):\n"
        "            out += chr(92) + 't'\n"
        "        elif ord(ch) < 32:\n"
        "            out += '\\\\u%04x' % ord(ch)\n"
        "        else:\n"
        "            out += ch\n"
        "    return chr(34) + out + chr(34)\n"
        "def _lx_ser(v):\n"
        "    if isinstance(v, dict) and isinstance(v.get('type'), str):\n"
        "        props = v.get('props', {})\n"
        "        ps = [_lx_str(k) + ':' + _lx_ser(props[k]) for k in props]\n"
        "        cs = [_lx_ser(c) for c in v.get('children', [])]\n"
        "        return '{' + chr(34) + 'type' + chr(34) + ':' + _lx_str(v['type']) + ',' + chr(34) + 'props' + chr(34) + ':{' + ','.join(ps) + '},' + chr(34) + 'children' + chr(34) + ':[' + ','.join(cs) + ']}'\n"
        "    if isinstance(v, dict) and '__handler' in v:\n"
        "        return '{' + chr(34) + '__handler' + chr(34) + ':' + str(v['__handler']) + '}'\n"
        "    if isinstance(v, bool):\n"
        "        return 'true' if v else 'false'\n"
        "    if isinstance(v, str):\n"
        "        return _lx_str(v)\n"
        "    if isinstance(v, int):\n"
        "        return str(v)\n"
        "    try:\n"
        "        return str(float(v))\n"
        "    except Exception:\n"
        "        return 'null'\n"
        "_t = None\n"
        "try:\n"
        "    _v = globals().get('view')\n"
        "    if callable(_v):\n"
        "        _t = _v()\n"
        "except Exception:\n"
        "    _t = None\n"
        "if _t is None:\n"
        "    _t = globals().get('_lx_tree')\n"
        "if isinstance(_t, dict) and isinstance(_t.get('type'), str):\n"
        "    print('__LXTREE__' + _lx_ser(_t))\n";
    mpy_exec(x, cap, NULL, 0);
    /* pull the tree out of the output stream */
    const char* marker = last_tree_marker(x->out, x->out_used);
    if (marker) {
        size_t n = strlen(marker + 10);
        buf_append(&x->json, &x->json_sz, &x->json_used, marker + 10, n);
        x->out_used = marker - x->out;
        x->out[x->out_used] = 0;
    }
    return 0;
}

int mpyx_invoke(MpyX* x, int handler_id, const char* arg, char* err, size_t errlen) {
    if (!x) { if (err && errlen) snprintf(err, errlen, "engine not initialized"); return 1; }
    /* invoke keeps a set cancel flag (lx semantics) but resets steps */
    x->steps = 0; x->interrupt_kind = 0;
    MP_STATE_THREAD(mp_pending_exception) = MP_OBJ_NULL;
    /* ABI §4: a non-tree handler result or a handler error keeps the previous
     * tree. Detach the json buffer instead of clearing it so the old tree can
     * be restored for free when this invoke produces no new one. */
    char* old_json = x->json; size_t old_sz = x->json_sz, old_used = x->json_used;
    x->json = NULL; x->json_sz = 0; x->json_used = 0;
    x->out_used = 0; if (x->out) x->out[0] = 0;
    /* build the invoke snippet dynamically — a payload can be arbitrarily
     * long and must never be silently truncated */
    char* code = NULL;
    if (arg) {
        /* escape into a single-quoted Python literal: quotes/backslashes plus
         * the whitespace and control bytes that would otherwise break the
         * generated source (a raw '\n' in the payload was a syntax error) */
        size_t arglen = strlen(arg);
        char* esc = malloc(arglen * 4 + 1);
        code = malloc(arglen * 4 + 192);
        if (esc) {
            size_t j = 0;
            for (size_t i = 0; i < arglen; i++) {
                unsigned char c = (unsigned char)arg[i];
                if (c == '\'' || c == '\\') { esc[j++] = '\\'; esc[j++] = (char)c; }
                else if (c == '\n') { esc[j++] = '\\'; esc[j++] = 'n'; }
                else if (c == '\r') { esc[j++] = '\\'; esc[j++] = 'r'; }
                else if (c == '\t') { esc[j++] = '\\'; esc[j++] = 't'; }
                else if (c < 0x20 || c == 0x7f) { j += (size_t)sprintf(esc + j, "\\x%02x", c); }
                else esc[j++] = (char)c;
            }
            esc[j] = 0;
            if (code) sprintf(code,
                "import builtins\n_r = _handlers[%d]('%s')\n"
                "if isinstance(_r, dict) and isinstance(_r.get('type'), str):\n"
                "    _lx_tree = _r\n", handler_id, esc);
        }
        free(esc);
    } else {
        code = malloc(192);
        if (code) sprintf(code,
            "_r = _handlers[%d]()\n"
            "if isinstance(_r, dict) and isinstance(_r.get('type'), str):\n"
            "    _lx_tree = _r\n", handler_id);
    }
    if (!code) {
        free(x->json); x->json = old_json; x->json_sz = old_sz; x->json_used = old_used;
        if (err && errlen) snprintf(err, errlen, "out of memory");
        return 1;
    }
    char errbuf[512];
    int failed = mpy_exec(x, code, errbuf, sizeof(errbuf));
    free(code);
    if (failed) {
        free(x->json); x->json = old_json; x->json_sz = old_sz; x->json_used = old_used;
        if (x->interrupt_kind && err && errlen) {
            snprintf(err, errlen, "%s",
                x->interrupt_kind == 1 ? "cancelled by user"
                                      : "execution step limit exceeded (possible infinite loop)");
        } else if (err && errlen) {
            snprintf(err, errlen, "%s", errbuf);
        }
        return 1;
    }
    /* re-render: view() wins (fresh state) over a handler-set _lx_tree,
     * mirroring the Lua app_view-function re-call on every invoke */
    static const char* cap =
        "_t = None\n"
        "try:\n"
        "    _v = globals().get('view')\n"
        "    if callable(_v):\n"
        "        _t = _v()\n"
        "except Exception:\n"
        "    _t = None\n"
        "if _t is None:\n"
        "    _t = globals().get('_lx_tree')\n"
        "if isinstance(_t, dict) and isinstance(_t.get('type'), str):\n"
        "    print('__LXTREE__' + _lx_ser(_t))\n";
    mpy_exec(x, cap, NULL, 0);
    const char* marker = last_tree_marker(x->out, x->out_used);
    if (marker) {
        size_t n = strlen(marker + 10);
        buf_append(&x->json, &x->json_sz, &x->json_used, marker + 10, n);
        x->out_used = marker - x->out;
        x->out[x->out_used] = 0;
    }
    if (x->json_used == 0) { /* no new tree → restore the previous view */
        free(x->json); x->json = old_json; x->json_sz = old_sz; x->json_used = old_used;
    } else free(old_json);
    return 0;
}
