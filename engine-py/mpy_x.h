/* mpy_x.h — LuaXIDE Python engine facade over embedded MicroPython.
 *
 * Contract mirrors the other engines per docs/PLATFORM_ABI.md:
 *  - mpyx_run(src) executes; print is captured; a ui tree (dict with a
 *    string "type" — normally the global ui.* helpers) is serialized to the
 *    same JSON shape {type, props, children}
 *  - mpyx_invoke(id, arg) calls a registered handler with the event payload;
 *    a returned ui tree replaces the view
 *  - cooperative cancel + step limit via MicroPython's scheduler hooks
 */
#ifndef MPY_X_H
#define MPY_X_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MpyX MpyX;

MpyX* mpyx_new(void);
void  mpyx_free(MpyX* x);

int  mpyx_run(MpyX* x, const char* src, char* err, size_t errlen);
int  mpyx_invoke(MpyX* x, int handler_id, const char* arg, char* err, size_t errlen);

/* lx_set_modroot counterpart: dir appended to sys.path before each run so
 * `import helper` resolves sibling files next to the entry point. */
void        mpyx_set_modroot(MpyX* x, const char* path);
const char* mpyx_modroot(MpyX* x);

const char* mpyx_last_json(MpyX* x);
const char* mpyx_last_output(MpyX* x);
void        mpyx_clear_output(MpyX* x);

void mpyx_cancel(MpyX* x);
void mpyx_clear_cancel(MpyX* x);
void mpyx_set_step_limit(MpyX* x, long steps);

#ifdef __cplusplus
}
#endif

#endif /* MPY_X_H */
