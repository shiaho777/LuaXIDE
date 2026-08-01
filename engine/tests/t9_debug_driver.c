#include "../lx.h"
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static lx_State* g;
static volatile int done;
static volatile int hits;

static void* controller(void* arg) {
  (void)arg;
  for (int i = 0; i < 500 && !done; i++) {
    usleep(5000);
    if (!lx_debug_is_paused(g)) continue;
    int line = lx_debug_pause_line(g);
    const char* locals = lx_debug_locals(g);
    const char* stack = lx_debug_stack(g);
    printf("paused line=%d locals=%s stack=%s\n", line, locals ? locals : "[]", stack ? stack : "[]");
    hits++;
    if (hits == 1) lx_debug_step(g);
    else lx_debug_continue(g);
  }
  printf("controller hits=%d\n", hits);
  return NULL;
}

int main(void) {
  g = lx_new();
  lx_set_step_limit(g, 1000000);
  lx_debug_enable(g, 1);
  int bp[] = {2};
  lx_debug_set_breakpoints(g, bp, 1);

  pthread_t th;
  pthread_create(&th, NULL, controller, NULL);

  const char* src =
    "local function add(x, y)\n"
    "  local s = x + y\n"
    "  return s\n"
    "end\n"
    "local a = 1\n"
    "local b = 2\n"
    "local c = add(a, b)\n"
    "print(c)\n"
    "return c\n";
  char err[512];
  memset(err, 0, sizeof(err));
  int rc = lx_run(g, src, err, sizeof(err));
  done = 1;
  pthread_join(th, NULL);
  printf("rc=%d err=%s out=%s\n", rc, err, lx_last_output(g));
  lx_close(g);
  if (rc != 0) return 1;
  if (hits < 1) { fprintf(stderr, "no breakpoints hit\n"); return 1; }
  return 0;
}
