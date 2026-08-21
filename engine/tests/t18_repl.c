/* t18_repl.c — REPL, lx_run return capture, lx_invoke, lx_dofile, rootfs/modroot,
 * and the cancel-flag family. */
#include "../lx.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)

static int has(const char* s, const char* sub){ return s && strstr(s, sub); }

int main(void){
  lx_State* g = lx_new();
  char err[512];

  /* ---- REPL: plain print, =sugar, globals persist across lines ---- */
  memset(err, 0, sizeof(err));
  int rc = lx_repl(g, "print(1+2)", err, sizeof(err));
  CHK(rc == 0, "repl print rc");
  CHK(has(lx_last_output(g), "3"), "repl print out");

  rc = lx_repl(g, "=1+2", err, sizeof(err));
  CHK(rc == 0 && has(lx_last_output(g), "3"), "repl = sugar");

  rc = lx_repl(g, "x = 7", err, sizeof(err));
  CHK(rc == 0, "repl assign");
  rc = lx_repl(g, "=x", err, sizeof(err));
  CHK(rc == 0 && has(lx_last_output(g), "7"), "repl global persists");

  /* leading whitespace is trimmed before the = check */
  rc = lx_repl(g, "   =5", err, sizeof(err));
  CHK(rc == 0 && has(lx_last_output(g), "5"), "repl trim");

  /* a return with values and no print -> auto-print of the values (tab separated) */
  rc = lx_repl(g, "return 1, 2, 3", err, sizeof(err));
  CHK(rc == 0 && has(lx_last_output(g), "1\t2\t3"), "repl multi-return print");

  /* empty input line is a no-op */
  rc = lx_repl(g, "", err, sizeof(err));
  CHK(rc == 0, "repl empty");

  /* parse/runtime errors surface as rc=1 with a message */
  rc = lx_repl(g, "1+", err, sizeof(err));
  CHK(rc == 1 && err[0], "repl parse error");
  rc = lx_repl(g, "=1/0", err, sizeof(err));
  CHK(rc == 0, "repl inf ok");   /* 1/0 = inf, not an error here */

  /* nil-state guard */
  memset(err, 0, sizeof(err));
  rc = lx_repl(NULL, "print(1)", err, sizeof(err));
  CHK(rc == 1 && has(err, "nil state"), "repl nil state");

  /* line-too-long guard (only the =-sugar rewrite path enforces it) */
  char big[10000]; big[0]='='; memset(big+1, '1', sizeof(big)-3); big[sizeof(big)-2]='\n'; big[sizeof(big)-1]=0;
  rc = lx_repl(g, big, err, sizeof(err));
  CHK(rc == 1 && has(err, "line too long"), "repl too long");

  /* ---- lx_clear_output ---- */
  lx_repl(g, "print(99)", err, sizeof(err));
  CHK(has(lx_last_output(g), "99"), "clear before");
  lx_clear_output(g);
  CHK(lx_last_output(g)[0] == 0, "clear after");

  /* ---- lx_run: non-UI return serializes to "null" ---- */
  memset(err, 0, sizeof(err));
  rc = lx_run(g, "local a=1 local b=2 return a+b", err, sizeof(err));
  CHK(rc == 0, "run sum rc");
  CHK(strcmp(lx_last_json(g), "null") == 0, "run non-ui json null");

  /* ---- lx_dofile: existing + missing ---- */
  memset(err, 0, sizeof(err));
  rc = lx_dofile(g, "tests/t1_hello.lua", err, sizeof(err));
  CHK(rc == 0, "dofile existing");
  memset(err, 0, sizeof(err));
  rc = lx_dofile(g, "tests/does_not_exist_9q.lua", err, sizeof(err));
  CHK(rc == 1 && has(err, "cannot open"), "dofile missing");

  /* ---- rootfs / modroot getters (path set + NULL clears) ---- */
  lx_set_rootfs(g, "/tmp/rootfs-x");
  CHK(strcmp(lx_rootfs(g), "/tmp/rootfs-x") == 0, "rootfs get");
  lx_set_rootfs(g, NULL);
  CHK(lx_rootfs(g)[0] == 0, "rootfs null clears");
  lx_set_modroot(g, "/tmp/modroot-x");
  CHK(strcmp(lx_modroot(g), "/tmp/modroot-x") == 0, "modroot get");
  lx_set_modroot(g, NULL);
  CHK(lx_modroot(g)[0] == 0, "modroot null clears");

  /* ---- cancel-flag family ---- */
  CHK(lx_is_cancelled(g) == 0, "cancel initially clear");
  lx_cancel(g);
  CHK(lx_is_cancelled(g) == 1, "cancel set");
  lx_clear_cancel(g);
  CHK(lx_is_cancelled(g) == 0, "cancel cleared");
  CHK(lx_is_cancelled(NULL) == 0, "cancel nil safe");

  lx_close(g);
  if(fails){ fprintf(stderr, "t18: %d failure(s)\n", fails); return 1; }
  printf("t18 ok\n");
  return 0;
}
