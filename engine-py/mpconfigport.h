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
