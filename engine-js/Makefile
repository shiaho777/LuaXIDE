# LuaXIDE JS engine facade — desktop CLI + tests.
# Upstream quickjs-ng source lives in quickjs/ (vendored, unmodified).
QJSX = qjsx
CC = clang
CFLAGS = -O2 -g -Wall -Wno-unused -Wno-unused-result -D_GNU_SOURCE -Iquickjs
LDFLAGS = -lm -pthread

QJS_CORE = quickjs/quickjs.c quickjs/libregexp.c quickjs/libunicode.c quickjs/cutils.c quickjs/xsum.c
SRC = qjs_x.c qjs_main.c $(QJS_CORE)

all: $(QJSX)

$(QJSX): $(SRC) qjs_x.h
	$(CC) $(CFLAGS) -o $@ $(SRC) $(LDFLAGS)

test: $(QJSX)
	@echo "=== j1 hello ===" && ./$(QJSX) tests/j1_hello.js
	@echo "=== j2 ui tree ===" && ./$(QJSX) tests/j2_ui.js --ui
	@echo "=== j3 handler ===" && ./$(QJSX) tests/j3_handler.js --ui --invoke 0
	@echo "=== j4 error ===" && (./$(QJSX) tests/j4_error.js || true)
	@echo "ALL JS ENGINE TESTS DONE"

clean:
	rm -f $(QJSX)

.PHONY: all test clean
