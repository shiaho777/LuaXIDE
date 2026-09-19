/* LuaXIDE platform facade — embedded MicroPython configuration. */

// Include common MicroPython embed configuration.
#include <port/mpconfigport_common.h>

// Use the minimal starting configuration (disables all optional features).
#define MICROPY_CONFIG_ROM_LEVEL                (MICROPY_CONFIG_ROM_LEVEL_MINIMUM)

// MicroPython configuration.
#define MICROPY_ENABLE_COMPILER                 (1)
#define MICROPY_ENABLE_GC                       (1)
#define MICROPY_PY_GC                           (1)
#define MICROPY_PY_BUILTINS_STR_SPLIT           (1)
#define MICROPY_PY_BUILTINS_STR_STRIP           (1)
#define MICROPY_PY_BUILTINS_STR_COUNT           (1)
#define MICROPY_PY_BUILTINS_BYTEARRAY           (1)
#define MICROPY_PY_JSON                        (1)
#define MICROPY_PY_BUILTINS_SLICE              (1)
#define MICROPY_PY_BUILTINS_SLICE_INDICES      (1)
#define MICROPY_PY_SYS                         (1)
#define MICROPY_MODULE_BUILTIN_INIT            (1)
#define MICROPY_PY_SYS_PLATFORM                "luax"

// Multi-file import: stat_top_level iterates sys.path (the facade appends
// the module root set via mpyx_set_modroot); mp_reader_new_file uses the
// POSIX fd reader below. The port supplies mp_import_stat in mpy_x.c.
#define MICROPY_ENABLE_EXTERNAL_IMPORT         (1)
#define MICROPY_READER_POSIX                   (1)

// io module: needed by json.load/loads (StringIO stream); also exposes
// io.StringIO/BytesIO to scripts. open() itself is a stub — there is no
// VFS/FileIO in the embed tree (mp_builtin_open raises OSError in mpy_x.c).
#define MICROPY_PY_IO                          (1)

// re module (extmod/modre.c + lib/re1.5). re_sub/re_split use
// mp_local_alloc, which requires the pystack; mpy_x.c initialises it.
#define MICROPY_PY_RE                          (1)
#define MICROPY_PY_RE_MATCH_GROUPS             (1)
#define MICROPY_PY_RE_SUB                      (1)
#define MICROPY_ENABLE_PYSTACK                 (1)

// Cooperative cancel + step limit: py/vm.c calls MICROPY_VM_HOOK_LOOP on
// every dispatch-loop branch. The hook (defined in mpy_x.c, compiled after
// this config) injects a pending exception via the runtime's own pending
// machinery — the VM then unwinds cleanly and mpy_exec's nlr catches it.
void mpy_x_vm_poll(void);
#define MICROPY_VM_HOOK_LOOP  mpy_x_vm_poll();

// float numbers: the MINIMUM ROM level disables them by default; scripts use
// them for sizes/positions, so enable explicitly.
#define MICROPY_PY_BUILTINS_FLOAT              (1)
#define MICROPY_FLOAT_IMPL                    (MICROPY_FLOAT_IMPL_FLOAT)
