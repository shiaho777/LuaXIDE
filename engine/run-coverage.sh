#!/bin/sh
# run-coverage.sh — build the engine with gcov coverage, run every test, then
# report line coverage of lx.c and lx_main.c. Run from the engine/ directory.
set -e
cd "$(dirname "$0")"

mkdir -p cov
rm -f cov/*.gcno cov/*.gcda *.gcov lx_cov

echo "== building coverage binaries =="
clang -fprofile-arcs -ftest-coverage -O0 -g -c lx.c     -o cov/lx.o
clang -fprofile-arcs -ftest-coverage -O0 -g -c lx_main.c -o cov/lx_main.o
clang -fprofile-arcs -ftest-coverage -o lx_cov cov/lx.o cov/lx_main.o -lm -pthread

echo "== running lua tests =="
for t in t1_hello t2_arith t3_control t4_funcs t5_tables t6_strings t7_meta t14_lang t16_modules; do
    ./lx_cov tests/$t.lua > /dev/null 2>&1 || true
done
./lx_cov --ui tests/t8_ui.lua  > /dev/null 2>&1 || true
./lx_cov --ui tests/t15_ui.lua > /dev/null 2>&1 || true

echo "== running C driver tests =="
for d in t9_debug_driver t10_debug_extra t11_watch t12_break_on_error t13_logpoint \
         t18_repl t19_stdio t20_dbg2 t21_modules t22_ui2 t23_errors; do
    # link the driver against cov/lx.o so its counters merge into cov/lx.gcda
    clang -fprofile-arcs -ftest-coverage -O0 -g -o cov/t-drv cov/lx.o tests/$d.c -lm -pthread
    ./cov/t-drv > /dev/null 2>&1 || true
done

echo "== running CLI invocations (lx_main.c coverage) =="
./lx_cov tests/t1_hello.lua > /dev/null 2>&1 || true                  # file mode
./lx_cov -e "print(42)" > /dev/null 2>&1 || true                       # eval mode
./lx_cov --ui tests/t8_ui.lua > /dev/null 2>&1 || true                 # ui file
./lx_cov --ui -e "return require('ui').app{title='X'}" > /dev/null 2>&1 || true  # ui eval
echo 'print(1)' | ./lx_cov > /dev/null 2>&1 || true                    # stdin mode
echo 'return require("ui").app{title="S"}' | ./lx_cov --ui > /dev/null 2>&1 || true  # ui stdin
./lx_cov --bogus > /dev/null 2>&1 || true                              # usage error
./lx_cov tests/nope_missing.lua > /dev/null 2>&1 || true               # missing file
./lx_cov -e "error('boom')" > /dev/null 2>&1 || true                   # eval error
./lx_cov --ui -e "error('x')" > /dev/null 2>&1 || true                 # ui error path

echo "== coverage report =="
for src in lx lx_main; do
    gcov -o cov $src.c > /dev/null 2>&1
    gcov -o cov $src.c 2>&1 | grep -E "Lines executed"
done
