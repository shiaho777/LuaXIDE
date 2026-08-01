#include "../lx.h"
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static lx_State* g;
static volatile int done;
static volatile int hits;
static volatile int first_line;
static volatile int second_line;

static void* controller_step_out(void* arg) {
  (void)arg;
  for (int i = 0; i < 800 && !done; i++) {
    usleep(3000);
    if (!lx_debug_is_paused(g)) continue;
    int line = lx_debug_pause_line(g);
    hits++;
    if (hits == 1) {
      first_line = line;
      lx_debug_step_out(g);
    } else {
      second_line = line;
      lx_debug_continue(g);
    }
  }
  return NULL;
}

static int test_step_out(void) {
  hits = 0; first_line = 0; second_line = 0; done = 0;
  g = lx_new();
  lx_set_step_limit(g, 1000000);
  lx_debug_enable(g, 1);
  int bp[] = {2};
  lx_debug_set_breakpoints(g, bp, 1);
  pthread_t th;
  pthread_create(&th, NULL, controller_step_out, NULL);
  const char* src =
    "local function deep()\n"
    "  local x = 1\n"
    "  local y = 2\n"
    "  return x + y\n"
    "end\n"
    "local r = deep()\n"
    "print(r)\n"
    "return r\n";
  char err[256]; memset(err,0,sizeof(err));
  int rc = lx_run(g, src, err, sizeof(err));
  done = 1; pthread_join(th, NULL);
  printf("step_out rc=%d hits=%d first=%d second=%d out=%s\n", rc, hits, first_line, second_line, lx_last_output(g));
  lx_close(g);
  if (rc != 0) return 1;
  if (hits < 2) return 1;
  if (first_line != 2) return 1;
  if (second_line < 6) return 1;
  return 0;
}

static void* controller_cond(void* arg) {
  (void)arg;
  for (int i = 0; i < 800 && !done; i++) {
    usleep(3000);
    if (!lx_debug_is_paused(g)) continue;
    hits++;
    first_line = lx_debug_pause_line(g);
    const char* locals = lx_debug_locals(g);
    printf("cond pause line=%d locals=%s\n", first_line, locals ? locals : "[]");
    lx_debug_continue(g);
  }
  return NULL;
}

static int test_cond(void) {
  hits = 0; first_line = 0; done = 0;
  g = lx_new();
  lx_set_step_limit(g, 1000000);
  lx_debug_enable(g, 1);
  int lines[] = {3};
  const char* conds[] = {"i == 3"};
  lx_debug_set_breakpoints_ex(g, lines, conds, 1);
  pthread_t th;
  pthread_create(&th, NULL, controller_cond, NULL);
  const char* src =
    "local sum = 0\n"
    "for i = 1, 5 do\n"
    "  sum = sum + i\n"
    "end\n"
    "print(sum)\n"
    "return sum\n";
  char err[256]; memset(err,0,sizeof(err));
  int rc = lx_run(g, src, err, sizeof(err));
  done = 1; pthread_join(th, NULL);
  printf("cond rc=%d hits=%d line=%d out=%s\n", rc, hits, first_line, lx_last_output(g));
  lx_close(g);
  if (rc != 0) return 1;
  if (hits != 1) return 1;
  if (first_line != 3) return 1;
  return 0;
}

int main(void) {
  int fail = 0;
  fail |= test_step_out();
  fail |= test_cond();
  return fail ? 1 : 0;
}
