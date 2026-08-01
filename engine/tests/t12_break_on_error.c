#include "../lx.h"
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static lx_State* g;
static volatile int done;
static volatile int hit;
static volatile int reason;

static void* controller(void* arg) {
  (void)arg;
  for (int i = 0; i < 800 && !done; i++) {
    usleep(3000);
    if (!lx_debug_is_paused(g)) continue;
    hit = 1;
    reason = lx_debug_pause_reason(g);
    printf("paused line=%d reason=%d err=%s locals=%s\n",
           lx_debug_pause_line(g), reason, lx_debug_last_error(g), lx_debug_locals(g));
    lx_debug_continue(g);
  }
  return NULL;
}

int main(void) {
  g = lx_new();
  lx_debug_enable(g, 1);
  lx_debug_set_break_on_error(g, 1);
  pthread_t th;
  pthread_create(&th, NULL, controller, NULL);
  char err[256]; memset(err, 0, sizeof(err));
  int rc = lx_run(g,
    "local a = 1\n"
    "local b = nil\n"
    "local c = a + b\n"
    "return c\n", err, sizeof(err));
  done = 1;
  pthread_join(th, NULL);
  printf("rc=%d hit=%d reason=%d err=%s\n", rc, hit, reason, err);
  lx_close(g);
  if (!hit || reason != 1 || rc == 0) return 1;
  return 0;
}
