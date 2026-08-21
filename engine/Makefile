LX = lx
CC = clang
CFLAGS = -O2 -g -Wall -Wno-unused -Wno-unused-result
LDFLAGS = -lm -pthread

SRC = lx.c lx_main.c

all: $(LX)

$(LX): $(SRC) lx.h
	$(CC) $(CFLAGS) -o $@ $(SRC) $(LDFLAGS)

# every C driver test binary is built from lx.c + its driver
DRIVERS = t9-debug t10-debug t11-watch t12-err t13-log \
          t18-repl t19-stdio t20-dbg2 t21-modules t22-ui2 t23-errors t24-snake

test: $(LX) $(DRIVERS)
	@echo "=== t1 hello ==="        && ./$(LX) tests/t1_hello.lua
	@echo "=== t2 arith ==="        && ./$(LX) tests/t2_arith.lua
	@echo "=== t3 control ==="      && ./$(LX) tests/t3_control.lua
	@echo "=== t4 funcs ==="        && ./$(LX) tests/t4_funcs.lua
	@echo "=== t5 tables ==="       && ./$(LX) tests/t5_tables.lua
	@echo "=== t6 strings ==="      && ./$(LX) tests/t6_strings.lua
	@echo "=== t7 meta ==="         && ./$(LX) tests/t7_meta.lua
	@echo "=== t8 ui ==="           && ./$(LX) --ui tests/t8_ui.lua
	@echo "=== t9 debug ==="        && ./t9-debug
	@echo "=== t10 debug extra ===" && ./t10-debug
	@echo "=== t11 watch ==="       && ./t11-watch
	@echo "=== t12 break on error" == && ./t12-err
	@echo "=== t13 logpoint ==="    && ./t13-log
	@echo "=== t14 lang ==="        && ./$(LX) tests/t14_lang.lua
	@echo "=== t15 ui ==="          && ./$(LX) --ui tests/t15_ui.lua > /dev/null
	@echo "=== t16 modules ==="     && ./$(LX) tests/t16_modules.lua
	@echo "=== t18 repl ==="        && ./t18-repl
	@echo "=== t19 stdio ==="       && ./t19-stdio
	@echo "=== t20 dbg2 ==="        && ./t20-dbg2
	@echo "=== t21 modules drv ===" && ./t21-modules
	@echo "=== t22 ui2 ==="         && ./t22-ui2
	@echo "=== t23 errors ==="      && ./t23-errors
	@echo "=== t24 snake =="      && ./t24-snake
	@echo "=== cli ==="             && ./$(LX) -e "print('cli-ok')" | grep -q cli-ok
	@echo "=== cli ui ==="          && ./$(LX) --ui -e "return require('ui').app{title='C'}" | grep -q '"type":"app"'
	@echo "=== cli stdin ==="       && echo 'print(123)' | ./$(LX) | grep -q 123
	@echo "=== cli ui stdin ==="    && echo 'return require("ui").app{title="S"}' | ./$(LX) --ui | grep -q '"title":"S"'
	@echo "ALL TESTS PASSED"

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

t18-repl: lx.c lx.h tests/t18_repl.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t18_repl.c $(LDFLAGS)

t19-stdio: lx.c lx.h tests/t19_stdio.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t19_stdio.c $(LDFLAGS)

t20-dbg2: lx.c lx.h tests/t20_dbg2.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t20_dbg2.c $(LDFLAGS)

t21-modules: lx.c lx.h tests/t21_modules.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t21_modules.c $(LDFLAGS)

t22-ui2: lx.c lx.h tests/t22_ui2.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t22_ui2.c $(LDFLAGS)

t23-errors: lx.c lx.h tests/t23_errors.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t23_errors.c $(LDFLAGS)

t24-snake: lx.c lx.h tests/t24_snake_driver.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t24_snake_driver.c $(LDFLAGS)

clean:
	rm -f $(LX) $(DRIVERS)
