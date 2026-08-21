/* t19_stdio.c — stdin queue, blocking io.read()/input(), cancel-during-read,
 * and the io.write/flush/os.* helpers. Uses feeder threads so the blocking
 * cond_wait path is exercised. */
#include "../lx.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <unistd.h>

static lx_State* g;
static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s, sub); }

/* feeder: after a short delay push a line into the stdin queue */
struct feed_arg { const char* line; long delay_ms; };
static void* feeder(void* a){
  struct feed_arg* f = (struct feed_arg*)a;
  usleep((useconds_t)(f->delay_ms * 1000));
  lx_push_stdin(g, f->line);
  return NULL;
}
/* wait_watcher: record whether the engine reports it is blocked on stdin */
struct wait_arg { volatile int saw_waiting; };
static void* wait_watcher(void* a){
  struct wait_arg* w = (struct wait_arg*)a;
  for(int i=0;i<200;i++){ if(lx_waiting_stdin(g)){ w->saw_waiting=1; break; } usleep(1000); }
  return NULL;
}
static void* canceller(void* a){
  (void)a; usleep(30000); lx_cancel(g); return NULL;
}

int main(void){
  g = lx_new();
  char err[512];

  /* 1. pre-pushed line: io.read returns immediately, no wait */
  lx_push_stdin(g, "hello");
  memset(err, 0, sizeof(err));
  int rc = lx_dostring(g, "local n=io.read(); print('got', n)", err, sizeof(err));
  CHK(rc == 0 && has(lx_last_output(g), "got\thello"), "read prepushed");

  /* 2. blocking read with a feeder thread + a wait-watcher that confirms
   *    lx_waiting_stdin becomes 1 while blocked */
  struct feed_arg fa = { "from-thread", 40 };
  struct wait_arg wa = { 0 };
  pthread_t tf, tw;
  pthread_create(&tw, NULL, wait_watcher, &wa);
  pthread_create(&tf, NULL, feeder, &fa);
  memset(err, 0, sizeof(err));
  rc = lx_dostring(g, "print('start'); local n=input('name: '); print('hi', n)", err, sizeof(err));
  pthread_join(tf, NULL); pthread_join(tw, NULL);
  CHK(rc == 0, "read blocking rc");
  CHK(has(lx_last_output(g), "name: "), "input prompt echoed");
  CHK(has(lx_last_output(g), "hi\tfrom-thread"), "read from feeder");
  CHK(wa.saw_waiting, "waiting_stdin observed");

  /* 3. cancel during a blocked read raises 'cancelled by user' */
  pthread_t tc;
  pthread_create(&tc, NULL, canceller, NULL);
  memset(err, 0, sizeof(err));
  rc = lx_dostring(g, "local n=io.read(); print(n)", err, sizeof(err));
  pthread_join(tc, NULL);
  CHK(rc == 1 && has(err, "cancelled by user"), "cancel during read");
  CHK(lx_is_cancelled(g) == 1, "cancel flag set after read cancel");
  lx_clear_cancel(g);

  /* 4. empty pushed line reads as the empty string */
  lx_push_stdin(g, "");
  memset(err, 0, sizeof(err));
  rc = lx_dostring(g, "local n=io.read(); print('len', #n)", err, sizeof(err));
  CHK(rc == 0 && has(lx_last_output(g), "len\t0"), "read empty line");

  /* 5. a long line is truncated to the read buffer size (4095 chars) */
  char* bigline = malloc(6000); memset(bigline, 'a', 5999); bigline[5999]=0;
  lx_push_stdin(g, bigline); free(bigline);
  memset(err, 0, sizeof(err));
  rc = lx_dostring(g, "local n=io.read(); print('rlen', #n)", err, sizeof(err));
  CHK(rc == 0 && has(lx_last_output(g), "rlen\t4095"), "read truncation");

  /* 6. multiple newline-separated lines in one push pop sequentially */
  lx_push_stdin(g, "one\ntwo");
  memset(err, 0, sizeof(err));
  rc = lx_dostring(g, "local a=io.read(); local b=io.read(); print(a, b)", err, sizeof(err));
  CHK(rc == 0 && has(lx_last_output(g), "one\ttwo"), "two lines one push");

  /* 7. io.write / io.flush / os helpers (no stdin) */
  memset(err, 0, sizeof(err));
  rc = lx_dostring(g,
    "io.write('w', 1, '\\n'); io.flush()\n"
    "print('t', type(os.time()))\n"
    "print('c', type(os.clock()))\n"
    "print('e', os.getenv('THIS_MISSING_X9Q'))\n",
    err, sizeof(err));
  CHK(rc == 0, "io/os rc");
  CHK(has(lx_last_output(g), "w1"), "io.write multi");
  const char* out = lx_last_output(g);
  CHK(has(out, "t\tnumber") && has(out, "c\tnumber") && has(out, "e\tnil"), "os.* helpers");

  lx_close(g);
  if(fails){ fprintf(stderr, "t19: %d failure(s)\n", fails); return 1; }
  printf("t19 ok\n");
  return 0;
}
