/* t28_hardening.c — regression coverage for the crash-class fixes: every one
 * of these inputs used to reach UB or a C stack overflow; each must now
 * surface as a catchable engine error (or a sane result) instead. */
#include "../lx.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s,sub); }

/* run src, expect rc=1 with err containing `needle` */
static void bad(lx_State* S, const char* label, const char* src, const char* needle){
  char err[512]; memset(err,0,sizeof(err));
  int rc = lx_dostring(S, src, err, sizeof(err));
  if(!(rc==1 && has(err,needle))){ fprintf(stderr,"FAIL %s (rc=%d err='%s' want '%s')\n",label,rc,err,needle); fails++; }
}
/* run src, expect success */
static void good(lx_State* S, const char* label, const char* src){
  char err[512]; memset(err,0,sizeof(err));
  int rc = lx_dostring(S, src, err, sizeof(err));
  if(rc!=0){ fprintf(stderr,"FAIL %s (rc=%d err='%s') expected success\n",label,rc,err); fails++; }
}

int main(void){
  char err[512];

  /* --- unbounded C recursion → "stack overflow", catchable by pcall --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    good(S, "pcall catches recursion",
      "local function f() return f() end\n"
      "local ok, e = pcall(f)\n"
      "assert(ok == false and e:match('stack overflow'))\n"
      "print('recovered')");
    bad(S, "uncaught recursion", "local function f() return f() end f()", "stack overflow");
    /* recursion routed through pcall is *caught* by the pcall — the script
     * unwinds cleanly and keeps running (no crash, no runaway) */
    good(S, "pcall recursion unwinds",
      "local n = 0\n"
      "local function f() n = n + 1 pcall(f) end\n"
      "f()\n"
      "assert(n == 80)  -- 2 frames per level (f + pcall) against the 160 cap\n"
      "print('unwound', n)");
    lx_close(S); }

  /* --- __newindex self-loop → bounded error, not C-stack death --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    bad(S, "newindex loop",
      "local t = setmetatable({}, {})\n"
      "getmetatable(t).__newindex = t\n"
      "t.x = 1", "'__newindex' chain too long");
    lx_close(S); }

  /* --- cyclic ui table → bounded serializer error --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    int rc = lx_run(S,
      "local ui = require('ui')\n"
      "local c = ui.column{}\n"
      "c[1] = c\n"
      "return ui.app{ c }", err, sizeof(err));
    CHK(rc==1 && has(err,"ui tree too deep"), "cyclic ui tree");
    lx_close(S); }

  /* --- unfinished capture → error, not a 4-billion-byte alloc --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    bad(S, "unfinished capture", "print(('abc'):match('(a'))", "unfinished capture");
    lx_close(S); }

  /* --- select() bounds: n>argc gives zero results; |n|>argc errors --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    good(S, "select past end", "assert(select('#', select(9,'a','b')) == 0)");
    bad(S, "select negative oob", "print(select(-9,'a','b'))", "index out of range");
    good(S, "select edge", "assert(select(-2,'a','b','c') == 'b')");
    lx_close(S); }

  /* --- parser name-token reads (hot-reload half-typed code must not crash) --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    bad(S, "trailing dot",      "local a = {}\nprint(a.",     "expected field name");
    bad(S, "local non-name",    "local 5",                     "expected local name");
    bad(S, "func dotted eof",   "function f.",                 "expected function name");
    bad(S, "func dotted num",   "function f.5() end",          "expected"); /* `.5` lexes as the number 0.5 */
    bad(S, "method noname",     "local o={}\no:5()",           "expected method name");
    bad(S, "for var noname",    "for a,5 in pairs({}) do end", "expected loop variable");
    bad(S, "param noname",      "local f = function(5) end",   "expected parameter name");
    lx_close(S); }

  /* --- arity cap: >64 call args errors identically on both exec paths --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    char src[1024]; char* p = src;
    p += sprintf(p, "local function f(...) return select('#',...) end print(f(");
    for(int i=0;i<80;i++) p += sprintf(p, "%s%d", i?",":"", i);
    p += sprintf(p, "))");
    bad(S, "too many args", src, "too many arguments");
    lx_close(S); }

  /* --- format width no longer reads past the scratch buffer --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    good(S, "wide format", "assert(#string.format('%9999d', 1) == 9999)");
    lx_close(S); }

  /* --- require cannot escape the module root --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    good(S, "require traversal rejected",
      "local ok = pcall(require, 'x...y')\n"
      "assert(ok == false)");
    lx_close(S); }

  /* --- long string literals now grow dynamically (old 4096 cap is gone) --- */
  { lx_State* S = lx_new(); lx_set_step_limit(S, 50000000L);
    char* src = malloc(7000);
    memcpy(src, "assert(#'", 9); memset(src+9, 'x', 5000);
    memcpy(src+5009, "' == 5000)", 10); src[5019] = 0;
    good(S, "5KB literal", src);
    free(src);
    lx_close(S); }

  if(fails){ fprintf(stderr, "t28-hardening: %d failure(s)\n", fails); return 1; }
  printf("t28-hardening ok\n");
  return 0;
}
