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
