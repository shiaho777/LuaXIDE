# LuaXIDE Python engine facade — tests over embedded MicroPython.
# micropython_embed/ is the self-contained output of MicroPython's embed port
# (v1.25.0, generated with our mpconfigport.h; regenerate with the upstream
# ports/embed/embed.mk if you bump the vendored version).
CC = clang
CFLAGS = -O2 -g -w -pthread -I. -Imicropython_embed -Imicropython_embed/port -Imicropython_embed/genhdr
LDFLAGS = -lm -pthread

# the embed port's own HAL (mphalport.c) prints to stdout; mpy_x.c overrides
# both stdout entry points to capture output, so mphalport.c is excluded.
# extmod/modre.c #includes lib/re1.5/*.c itself — list only the module root.
EMBED_SRC = $(filter-out micropython_embed/port/mphalport.c, \
            $(wildcard micropython_embed/py/*.c) \
            $(wildcard micropython_embed/shared/runtime/*.c) \
            $(wildcard micropython_embed/port/*.c)) \
            extmod/modre.c extmod/modjson.c

GENHDR = micropython_embed/genhdr/qstrdefs.generated.h \
         micropython_embed/genhdr/moduledefs.h mpconfigport.h

DRIVERS = p1-smoke p2-cancel p3-import p4-multi p5-modules

all: $(DRIVERS)

p1-smoke: tests/p1_smoke.c mpy_x.c mpy_x.h $(EMBED_SRC) $(GENHDR)
	$(CC) $(CFLAGS) -o $@ tests/p1_smoke.c mpy_x.c $(EMBED_SRC) $(LDFLAGS)

p2-cancel: tests/p2_cancel.c mpy_x.c mpy_x.h $(EMBED_SRC) $(GENHDR)
	$(CC) $(CFLAGS) -o $@ tests/p2_cancel.c mpy_x.c $(EMBED_SRC) $(LDFLAGS)

p3-import: tests/p3_import.c mpy_x.c mpy_x.h $(EMBED_SRC) $(GENHDR)
	$(CC) $(CFLAGS) -o $@ tests/p3_import.c mpy_x.c $(EMBED_SRC) $(LDFLAGS)

p4-multi: tests/p4_multi.c mpy_x.c mpy_x.h $(EMBED_SRC) $(GENHDR)
	$(CC) $(CFLAGS) -o $@ tests/p4_multi.c mpy_x.c $(EMBED_SRC) $(LDFLAGS)

p5-modules: tests/p5_modules.c mpy_x.c mpy_x.h $(EMBED_SRC) $(GENHDR)
	$(CC) $(CFLAGS) -o $@ tests/p5_modules.c mpy_x.c $(EMBED_SRC) $(LDFLAGS)

test: $(DRIVERS)
	@echo "=== p1 smoke (run -> tree -> invoke -> re-render) ===" && ./p1-smoke
	@echo "=== p2 cancel (VM-hook step limit + mid-run cancel) ===" && ./p2-cancel
	@echo "=== p3 import (modroot sys.path, sibling + package modules) ===" && ./p3-import
	@echo "=== p4 multi (independent engines, interleaved run/invoke) ===" && ./p4-multi
	@echo "=== p5 modules (json + re stdlib) ===" && ./p5-modules
	@echo "ALL PY ENGINE TESTS DONE"

clean:
	rm -f $(DRIVERS)

.PHONY: all test clean
