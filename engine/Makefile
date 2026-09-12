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
          t18-repl t19-stdio t20-dbg2 t21-modules t22-ui2 t23-errors t24-snake \
          t26-invoke-tree t28-hardening

# scripts whose output must be identical with the bytecode VM on and off
BC_DIFF_SCRIPTS = t1_hello t2_arith t3_control t4_funcs t5_tables \
                  t6_strings t7_meta t14_lang t16_modules t25_bytecode t27_patterns

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
	@echo "=== t25 bytecode ==="    && ./$(LX) tests/t25_bytecode.lua
	@echo "=== t26 invoke tree ===" && ./t26-invoke-tree
	@echo "=== t27 patterns ==="   && ./$(LX) tests/t27_patterns.lua
	@echo "=== t28 hardening ==="  && ./t28-hardening
	@echo "=== fail-grep ==="       && ! ./$(LX) tests/t25_bytecode.lua | grep -q "^FAIL " && ! ./$(LX) tests/t27_patterns.lua | grep -q "^FAIL " || { echo "silent FAIL line in assert-script output"; exit 1; }
	@echo "=== bc engagement ==="   && LUAX_BC_STATS=1 ./$(LX) tests/t25_bytecode.lua >/dev/null 2>.bc_stats; grep -qE "bc: [1-9][0-9]* compiled" .bc_stats || { echo "bytecode VM did not engage:"; cat .bc_stats; rm -f .bc_stats; exit 1; }; rm -f .bc_stats; echo "vm engaged"
	@echo "=== bc differential ===" && for t in $(BC_DIFF_SCRIPTS); do ./$(LX) tests/$$t.lua 2>&1 | sed -E 's/0x[0-9a-f]+/0xADDR/g' >/tmp/.lx_bc_a; LUAX_NO_BC=1 ./$(LX) tests/$$t.lua 2>&1 | sed -E 's/0x[0-9a-f]+/0xADDR/g' >/tmp/.lx_bc_b; diff /tmp/.lx_bc_a /tmp/.lx_bc_b >/dev/null || { echo "BC DIVERGENCE in $$t:"; diff /tmp/.lx_bc_a /tmp/.lx_bc_b | head -10; rm -f /tmp/.lx_bc_a /tmp/.lx_bc_b; exit 1; }; done; rm -f /tmp/.lx_bc_a /tmp/.lx_bc_b; echo "bc-diff ok ($(words $(BC_DIFF_SCRIPTS)) scripts)"
	@echo "=== doc examples ==="     && python3 tests/doc_check.py
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

t26-invoke-tree: lx.c lx.h tests/t26_invoke_tree.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t26_invoke_tree.c $(LDFLAGS)

t28-hardening: lx.c lx.h tests/t28_hardening.c
	$(CC) $(CFLAGS) -o $@ lx.c tests/t28_hardening.c $(LDFLAGS)

clean:
	rm -f $(LX) $(DRIVERS)

bench-bc: $(LX)
	@echo "== tree-walk (LUAX_NO_BC=1) =="
	@LUAX_NO_BC=1 ./$(LX) tests/bench_bc.lua
	@echo "== bytecode VM =="
	@./$(LX) tests/bench_bc.lua

.PHONY: test bench-bc clean all
