# LuaXIDE Python engine facade — tests over embedded MicroPython.
# micropython_embed/ is the self-contained output of MicroPython's embed port
# (v1.25.0, generated with our mpconfigport.h; regenerate with the upstream
# ports/embed/embed.mk if you bump the vendored version).
CC = clang
CFLAGS = -O2 -g -w -I. -Imicropython_embed -Imicropython_embed/port -Imicropython_embed/genhdr
LDFLAGS = -lm

# the embed port's own HAL (mphalport.c) prints to stdout; mpy_x.c overrides
# both stdout entry points to capture output, so mphalport.c is excluded.
EMBED_SRC = $(filter-out micropython_embed/port/mphalport.c, \
            $(wildcard micropython_embed/py/*.c) \
            $(wildcard micropython_embed/shared/runtime/*.c) \
            $(wildcard micropython_embed/port/*.c))

DRIVERS = p1-smoke

all: $(DRIVERS)

p1-smoke: tests/p1_smoke.c mpy_x.c mpy_x.h $(EMBED_SRC)
	$(CC) $(CFLAGS) -o $@ tests/p1_smoke.c mpy_x.c $(EMBED_SRC) $(LDFLAGS)

test: $(DRIVERS)
	@echo "=== p1 smoke (run -> tree -> invoke -> re-render) ===" && ./p1-smoke
	@echo "ALL PY ENGINE TESTS DONE"

clean:
	rm -f $(DRIVERS)

.PHONY: all test clean
