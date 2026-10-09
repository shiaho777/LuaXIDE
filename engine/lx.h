#ifndef LX_H
#define LX_H

struct State;
typedef struct State lx_State;

lx_State* lx_new(void);
void      lx_close(lx_State*);

int  lx_dostring(lx_State*, const char* src, char* errbuf, int errbuflen);
int  lx_dofile(lx_State*, const char* path, char* errbuf, int errbuflen);

void lx_set_step_limit(lx_State*, long steps);

int  lx_run(lx_State*, const char* src, char* errbuf, int errbuflen);
/* Retired for LuaX HTML. Always errors: dispatch events with lx_html_event. */
int  lx_invoke(lx_State*, int handler_id, const char* arg, char* errbuf, int errbuflen);

/* HTML host. lx_last_json is a JSON array of DOM ops ("[]" when empty).
 * lx_html_event runs the handler registered with html.on(id, event, fn) and
 * passes payload as its first argument ("" when payload is NULL). A handler
 * error rewinds ops queued during that call. An id with no handler succeeds
 * and queues nothing. lx_html_clear_ops drops the pending batch. */
int  lx_html_event(lx_State*, const char* id, const char* event, const char* payload, char* errbuf, int errbuflen);
void lx_html_clear_ops(lx_State*);

const char* lx_last_json(lx_State*);
const char* lx_last_output(lx_State*);
void        lx_clear_output(lx_State*);
int         lx_repl(lx_State*, const char* src, char* errbuf, int errbuflen);
void        lx_cancel(lx_State*);
void        lx_clear_cancel(lx_State*);
int         lx_is_cancelled(lx_State*);
void        lx_push_stdin(lx_State*, const char* line);
int         lx_waiting_stdin(lx_State*);
void        lx_set_rootfs(lx_State*, const char* path);
const char* lx_rootfs(lx_State*);
void        lx_set_modroot(lx_State*, const char* path);
const char* lx_modroot(lx_State*);

void lx_debug_enable(lx_State*, int enabled);
void lx_debug_set_break_on_error(lx_State*, int enabled);
void lx_debug_set_breakpoints(lx_State*, const int* lines, int n);
void lx_debug_set_breakpoints_ex(lx_State*, const int* lines, const char* const* conds, int n);
void lx_debug_set_breakpoints_full(lx_State*, const int* lines, const char* const* conds, const char* const* logs, const int* log_only, int n);
void lx_debug_clear_breakpoints(lx_State*);
void lx_debug_continue(lx_State*);
void lx_debug_step(lx_State*);
void lx_debug_step_out(lx_State*);
void lx_debug_stop(lx_State*);
int  lx_debug_is_paused(lx_State*);
int  lx_debug_pause_line(lx_State*);
int  lx_debug_pause_reason(lx_State*);
const char* lx_debug_last_error(lx_State*);
const char* lx_debug_locals(lx_State*);
const char* lx_debug_stack(lx_State*);
const char* lx_debug_eval(lx_State*, const char* expr);

/* bytecode VM (Phase 1b): disassemble every compilable function in src to
 * stdout (returns function count, -1 on parse error); execution counters */
int  lx_bc_disassemble(lx_State*, const char* src, char* errbuf, int errbuflen);
void lx_bc_stats(lx_State*, long* calls, long* fallbacks);

#endif
