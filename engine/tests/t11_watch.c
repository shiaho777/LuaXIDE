#include "../lx.h"
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>

static lx_State* g;
static volatile int done;
static volatile int ok_eval;

static void* controller(void* arg) {
  (void)arg;
  for (int i = 0; i < 800 && !done; i++) {
    usleep(3000);
    if (!lx_debug_is_paused(g)) continue;
    const char* r1 = lx_debug_eval(g, "a + b");
    char* c1 = r1 ? strdup(r1) : NULL;
    const char* r2 = lx_debug_eval(g, "missing");
    char* c2 = r2 ? strdup(r2) : NULL;
    printf("eval1=%s\neval2=%s\n", c1 ? c1 : "null", c2 ? c2 : "null");
    if (c1 && strstr(c1, "\"ok\":true") && strstr(c1, "3")) ok_eval = 1;
    free(c1); free(c2);
    lx_debug_continue(g);
  }
  return NULL;
}

int main(void) {
  g = lx_new();
  lx_debug_enable(g, 1);
  int bp[] = {3};
  lx_debug_set_breakpoints(g, bp, 1);
  pthread_t th;
  pthread_create(&th, NULL, controller, NULL);
  char err[256]; memset(err, 0, sizeof(err));
  int rc = lx_run(g,
    "local a = 1\n"
    "local b = 2\n"
    "local c = a + b\n"
    "return c\n", err, sizeof(err));
  done = 1;
  pthread_join(th, NULL);
  printf("rc=%d ok_eval=%d\n", rc, ok_eval);
  lx_close(g);
  return (rc != 0 || !ok_eval) ? 1 : 0;
}
