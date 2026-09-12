/* t23_errors.c — parse-time error messages and string-overflow guard,
 * exercised through lx_dostring so each failing snippet returns rc=1 with a
 * locatable message. Runtime error cases live in t14_lang.lua (via pcall). */
#include "../lx.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

static int fails;
static void bad(const char* label, const char* src, const char* needle){
  lx_State* g = lx_new();
  char err[512]; memset(err,0,sizeof(err));
  int rc = lx_dostring(g, src, err, sizeof(err));
  int ok = (rc==1) && strstr(err, needle);
  if(!ok){ fprintf(stderr,"FAIL %s (rc=%d err='%s' want '%s')\n", label, rc, err, needle); fails++; }
  lx_close(g);
}
static void good(const char* label, const char* src){
  lx_State* g = lx_new();
  char err[512]; memset(err,0,sizeof(err));
  int rc = lx_dostring(g, src, err, sizeof(err));
  if(rc!=0){ fprintf(stderr,"FAIL %s (rc=%d err='%s') expected success\n", label, rc, err); fails++; }
  lx_close(g);
}

int main(void){
  /* ---- parse errors (lx_error path) ---- */
  bad("unterminated string",  "\"abc",            "unterminated string");
  bad("unterminated long str","[[abc",            "unterminated long string");
  bad("trailing tokens",      "return 1\n2",      "trailing tokens");
  bad("statement syntax",      "print(1) x",       "syntax error");
  bad("unexpected symbol",    "1 + 1",            "unexpected symbol");
  bad("bad for",              "for 1,10 do end",  "bad for");
  bad("method args",          "local o={}\no:m",   "function args expected");
  bad("expected statement",   "local x={}\nx.y z", "syntax error");
  bad("goto unsupported",     "goto x",           "goto");
  bad("label unsupported",    "::x::",            "labels");
  bad("expected ]",           "local t={}\nt[1",   "expected ']'");

  /* ---- long string literals (readStr now grows dynamically — the old
   * 4096-byte guard rejected legitimate data literals) ---- */
  { char* big = malloc(7000); memcpy(big,"local s=\"",9); memset(big+9,'a',4997); memcpy(big+5006,"\"\nprint(#s)",11); big[5017]=0;
    good("long string literal", big);
    free(big); }

  /* ---- sanity: valid snippets still parse ---- */
  good("plain print",   "print('hi')");
  good("long bracket",  "local s=[[a]b]]\nreturn s");

  if(fails){ fprintf(stderr, "t23: %d failure(s)\n", fails); return 1; }
  printf("t23 ok\n");
  return 0;
}
