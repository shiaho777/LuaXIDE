/* p2_cancel.c — runaway-execution protection via MICROPY_VM_HOOK_LOOP,
 * mirroring j5 on the JS side: the VM-hook poll raises a pending
 * KeyboardInterrupt that unwinds the loop and surfaces the same messages
 * the other engines produce.
 */
#include "../mpy_x.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s, sub); }

static MpyX* shared;
static void* cancel_soon(void* unused) { (void)unused; usleep(100000); mpyx_cancel(shared); return NULL; }

int main(void){
  char err[512];
  MpyX* x = mpyx_new();
  CHK(x != NULL, "engine created");
  shared = x;

  /* --- 1. step limit stops an infinite loop --- */
  mpyx_set_step_limit(x, 100000);
  int rc = mpyx_run(x, "s = 0\nwhile True:\n    s += 1\n", err, sizeof(err));
  CHK(rc == 1, "step limit errors");
  CHK(has(err, "execution step limit exceeded"), "step limit message");
  fprintf(stderr, "  step-limit err: %s\n", err);

  /* engine still usable afterwards */
  rc = mpyx_run(x, "print('alive')\n", err, sizeof(err));
  CHK(rc == 0, "engine alive after step limit");
  CHK(has(mpyx_last_output(x), "alive"), "output after step limit");

  /* --- 2. no limit by default: a bounded loop completes --- */
  mpyx_set_step_limit(x, 0);
  rc = mpyx_run(x, "t = 0\nfor i in range(100000):\n    t += i\nprint('sum', t)\n", err, sizeof(err));
  CHK(rc == 0, "bounded loop with no limit");
  CHK(has(mpyx_last_output(x), "sum 4999950000"), "bounded loop result");

  /* --- 3. a fresh run clears a stale cancel flag (lx_reset_run semantics) --- */
  mpyx_set_step_limit(x, 50000000L);
  mpyx_cancel(x);
  rc = mpyx_run(x, "print('fresh run')\n", err, sizeof(err));
  CHK(rc == 0, "stale cancel cleared by run");
  CHK(has(mpyx_last_output(x), "fresh run"), "output after stale cancel");

  /* --- 4. cancel while a long loop is running stops it --- */
  {
    pthread_t th;
    pthread_create(&th, NULL, cancel_soon, NULL);
    rc = mpyx_run(x, "s = 0\nwhile True:\n    s += 1\n", err, sizeof(err));
    pthread_join(th, NULL);
    CHK(rc == 1, "mid-run cancel errors");
    CHK(has(err, "cancelled by user"), "mid-run cancel message");
    fprintf(stderr, "  mid-run cancel err: %s\n", err);
  }
  mpyx_clear_cancel(x);
  rc = mpyx_run(x, "print('back')\n", err, sizeof(err));
  CHK(rc == 0, "engine alive after cancel");
  CHK(has(mpyx_last_output(x), "back"), "output after cancel");

  /* --- 5. cancel also stops an invoke-time handler --- */
  rc = mpyx_run(x,
    "count = 0\n"
    "def bump():\n"
    "    global count\n"
    "    while True:\n"
    "        count += 1\n"
    "def view():\n"
    "    return ui.app({'title': 'c'}, ui.button({'text': 'go', 'onClick': bump}))\n",
    err, sizeof(err));
  CHK(rc == 0, "set up infinite handler");
  const char* j = mpyx_last_json(x);
  const char* h = j ? strstr(j, "__handler") : NULL;
  CHK(h != NULL, "handler registered");
  if (h) {
    int id = atoi(strchr(h, ':') + 1);
    mpyx_cancel(x);
    rc = mpyx_invoke(x, id, NULL, err, sizeof(err));
    CHK(rc == 1, "cancelled invoke errors");
    CHK(has(err, "cancelled by user"), "cancel during invoke");
  }

  mpyx_free(x);
  if (fails) { fprintf(stderr, "p2-cancel: %d failure(s)\n", fails); return 1; }
  printf("p2-cancel ok\n");
  return 0;
}
