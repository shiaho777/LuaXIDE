LX = lx
CC = clang
CFLAGS = -O2 -g -Wall -Wno-unused -Wno-unused-result
LDFLAGS = -lm -pthread

SRC = lx.c lx_main.c

all: $(LX)

$(LX): $(SRC) lx.h
	$(CC) $(CFLAGS) -o $@ $(SRC) $(LDFLAGS)

test: $(LX) t9-debug t10-debug t11-watch t12-err t13-log
	@echo "=== t1 hello ===" && ./$(LX) tests/t1_hello.lua
	@echo "=== t2 arith ===" && ./$(LX) tests/t2_arith.lua
	@echo "=== t3 control ===" && ./$(LX) tests/t3_control.lua
	@echo "=== t4 funcs ===" && ./$(LX) tests/t4_funcs.lua
	@echo "=== t5 tables ===" && ./$(LX) tests/t5_tables.lua
	@echo "=== t6 strings ===" && ./$(LX) tests/t6_strings.lua
	@echo "=== t7 meta ===" && ./$(LX) tests/t7_meta.lua
	@echo "=== t8 ui ===" && ./$(LX) --ui tests/t8_ui.lua
	@echo "=== t9 debug ===" && ./t9-debug
	@echo "=== t10 debug extra ===" && ./t10-debug
	@echo "=== t11 watch ===" && ./t11-watch
	@echo "=== t12 break on error ===" && ./t12-err
	@echo "=== t13 logpoint ===" && ./t13-log

t9-debug: lx.c lx.h tests/t9_debug_driver.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t9_debug_driver.c $(LDFLAGS)

t10-debug: lx.c lx.h tests/t10_debug_extra.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t10_debug_extra.c $(LDFLAGS)

t11-watch: lx.c lx.h tests/t11_watch.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t11_watch.c $(LDFLAGS)

t12-err: lx.c lx.h tests/t12_break_on_error.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t12_break_on_error.c $(LDFLAGS)

t13-log: lx.c lx.h tests/t13_logpoint.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t13_logpoint.c $(LDFLAGS)

clean:
	rm -f $(LX) t9-debug t10-debug t11-watch t12-err t13-log
