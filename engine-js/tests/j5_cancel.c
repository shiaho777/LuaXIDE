/* j5_cancel.c — runaway-execution protection, mirroring t26/lx semantics:
 * the QuickJS interrupt handler must stop a dead loop at the step limit and
 * honour qjsx_cancel, with the same error strings the Lua engine produces.
 */
#include "../qjs_x.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static QjsX* shared_engine;
static void* cancel_soon(void* unused) { (void)unused; usleep(100000); qjsx_cancel(shared_engine); return NULL; }
static int has(const char* s, const char* sub){ return s && strstr(s, sub); }

int main(void){
  char err[512];
  QjsX* x = qjsx_new();
  CHK(x != NULL, "engine created");
  shared_engine = x;

  /* --- 1. step limit stops an infinite loop --- */
  qjsx_set_step_limit(x, 2000);
  int rc = qjsx_run(x, "var s = 0; while (true) { s += 1; }", err, sizeof(err));
  CHK(rc == 1, "step limit errors");
  CHK(has(err, "execution step limit exceeded"), "step limit message");
  fprintf(stderr, "  step-limit err: %s\n", err);

  /* engine is still usable afterwards */
  rc = qjsx_run(x, "print('alive'); 1", err, sizeof(err));
  CHK(rc == 0, "engine alive after step limit");
  CHK(has(qjsx_last_output(x), "alive"), "output after step limit");

  /* --- 2. unlimited by default; a bounded loop completes --- */
  qjsx_set_step_limit(x, 0);
  rc = qjsx_run(x, "var t = 0; for (var i = 0; i < 100000; i++) { t += i; } print('sum ' + t);", err, sizeof(err));
  CHK(rc == 0, "long bounded loop with no limit");
  CHK(has(qjsx_last_output(x), "sum 4999950000"), "bounded loop result");

  /* --- 3. a fresh run clears a stale cancel flag (lx_reset_run semantics) --- */
  qjsx_set_step_limit(x, 50000000L);
  qjsx_cancel(x);
  rc = qjsx_run(x, "print('fresh run'); 1", err, sizeof(err));
  CHK(rc == 0, "stale cancel cleared by run");
  CHK(has(qjsx_last_output(x), "fresh run"), "output after stale cancel");

  /* --- 4. cancel while a long loop is running stops it --- */
  {
    pthread_t th;
    pthread_create(&th, NULL, (void* (*)(void*))cancel_soon, NULL);
    rc = qjsx_run(x, "var s = 0; while (true) { s += 1; }", err, sizeof(err));
    pthread_join(th, NULL);
    CHK(rc == 1, "mid-run cancel errors");
    CHK(has(err, "cancelled by user"), "mid-run cancel message");
    fprintf(stderr, "  mid-run cancel err: %s\n", err);
  }
  qjsx_clear_cancel(x);
  rc = qjsx_run(x, "print('back'); 1", err, sizeof(err));
  CHK(rc == 0, "engine alive after cancel");
  CHK(has(qjsx_last_output(x), "back"), "output after cancel");

  /* --- 5. cancel also stops an invoke-time handler --- */
  rc = qjsx_run(x,
    "function page(){ return ui.app({ title: 'c' },"
    "  ui.button({ text: 'go', onClick: function(){ while (true) {} } })); }"
    "return page();",
    err, sizeof(err));
  CHK(rc == 0, "set up handler");
  const char* j = qjsx_last_json(x);
  const char* h = j ? strstr(j, "__handler") : NULL;
  CHK(h != NULL, "handler registered");
  if (h) {
    int id = atoi(strchr(h, ':') + 1);
    qjsx_cancel(x);
    rc = qjsx_invoke(x, id, NULL, err, sizeof(err));
    CHK(rc == 1, "cancelled invoke errors");
    CHK(has(err, "cancelled by user"), "cancel during invoke");
  }

  qjsx_free(x);
  if (fails) { fprintf(stderr, "j5-cancel: %d failure(s)\n", fails); return 1; }
  printf("j5-cancel ok\n");
  return 0;
}
