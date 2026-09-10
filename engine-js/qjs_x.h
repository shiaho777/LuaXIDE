/* qjs_x.h — LuaXIDE JavaScript engine facade over QuickJS.
 *
 * Contract mirrors the Lua engine (lx.h) where it matters to the host:
 *  - print(...) output is captured into an output buffer (terminal mode)
 *  - a script returning a ui tree (object with a string "type") is serialized
 *    to the same JSON shape the Lua engine emits: {type, props, children},
 *    function props becoming {"__handler": id}
 *  - qjsx_invoke(id, arg) calls a registered handler, passing arg (when
 *    non-NULL) as its first argument — the event payload; if the handler
 *    returns a ui tree the last-json is replaced (re-render), like lx_invoke.
 */
#ifndef QJS_X_H
#define QJS_X_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct QjsX QjsX;

QjsX* qjsx_new(void);
void qjsx_free(QjsX* x);

/* Evaluate src (wrapped so top-level `return` is allowed).
 * Returns 0 on success; on error returns 1 and fills err (when non-NULL). */
int qjsx_run(QjsX* x, const char* src, char* err, size_t errlen);

/* Invoke a handler previously serialized as {"__handler": id}. Same return
 * convention as qjsx_run. */
int qjsx_invoke(QjsX* x, int handler_id, const char* arg, char* err, size_t errlen);

/* Last ui-tree JSON ("" when the script returned no tree). */
const char* qjsx_last_json(QjsX* x);

/* Captured print output since last clear. */
const char* qjsx_last_output(QjsX* x);
void qjsx_clear_output(QjsX* x);

/* Runaway-execution protection, mirroring lx_cancel / lx_set_step_limit:
 * the QuickJS interrupt handler polls these on every back-edge/eval slice.
 * qjsx_cancel stops the running script with "cancelled by user";
 * the step limit (0 = unlimited) reports the same "execution step limit
 * exceeded (possible infinite loop)" message as the Lua engine. Both are
 * reset by the engine at the start of every run/invoke. */
void qjsx_cancel(QjsX* x);
void qjsx_clear_cancel(QjsX* x);
void qjsx_set_step_limit(QjsX* x, long steps);

#ifdef __cplusplus
}
#endif

#endif /* QJS_X_H */
