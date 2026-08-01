#include "../lx.h"
#include <stdio.h>
#include <string.h>

int main(void) {
  lx_State* g = lx_new();
  lx_debug_enable(g, 1);
  int lines[] = {3, 5};
  const char* conds[] = {"", ""};
  const char* logs[] = {"i={i} sum={sum}", "done sum={sum}"};
  int log_only[] = {1, 1};
  lx_debug_set_breakpoints_full(g, lines, conds, logs, log_only, 2);
  char err[256]; memset(err, 0, sizeof(err));
  int rc = lx_run(g,
    "local sum = 0\n"
    "for i = 1, 3 do\n"
    "  sum = sum + i\n"
    "end\n"
    "return sum\n", err, sizeof(err));
  const char* out = lx_last_output(g);
  printf("rc=%d err=%s\nout=%s\n", rc, err, out ? out : "");
  int ok = rc == 0 && out && strstr(out, "[log] L3:") && strstr(out, "i=3")
        && strstr(out, "[log] L5:") && strstr(out, "sum=6");
  lx_close(g);
  return ok ? 0 : 1;
}
