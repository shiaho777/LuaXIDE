/* t21_modules.c — require() with an explicit modroot (lx_set_modroot),
 * covering resolve_module_path / load_file_text / package_loaded branches
 * that the cwd-relative Lua test cannot reach. Creates a temp fixture tree. */
#include "../lx.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>
#include <pthread.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)

static const char* ROOT;

/* background thread that creates+unlinks a module file in a tight loop, so a
 * concurrent require() can observe the file vanish between resolve (file_exists)
 * and load -> triggering the "cannot read module" guard. */
static volatile int unlinker_stop;
static const char* unlinker_path;
static void* unlinker_thread(void* a){
  (void)a;
  while(!unlinker_stop){
    FILE* f=fopen(unlinker_path,"wb");
    if(f){ fputs("return 1\n",f); fclose(f); }
    unlink(unlinker_path);
  }
  return NULL;
}

static void wfile(const char* rel, const char* body){
  char p[1024]; snprintf(p,sizeof(p),"%s/%s",ROOT,rel);
  char d[1024]; snprintf(d,sizeof(d),"%s",p); char* slash=strchr(d,'/');
  /* create intermediate directories */
  char tmp[1024]; snprintf(tmp,sizeof(tmp),"%s",ROOT);
  (void)slash;
  /* naive: create each path component */
  char path[1024]; snprintf(path,sizeof(path),"%s",ROOT); mkdir(path,0755);
  const char* seg = rel; char cur[1024];
  while(seg && strchr(seg,'/')){
    size_t n = (size_t)(strchr(seg,'/')-seg);
    snprintf(cur,sizeof(cur),"%s/%.*s",path,(int)n,seg);
    mkdir(cur,0755); snprintf(path,sizeof(path),"%s",cur); seg += n+1;
  }
  FILE*f=fopen(p,"wb"); if(!f){ fprintf(stderr,"cannot write %s\n",p); return; }
  fputs(body,f); fclose(f);
}
static int has(const char* s, const char* sub){ return s && strstr(s,sub); }

int main(void){
  /* temp fixture root */
  char tmpl[]="/tmp/luax_modtest_XXXXXX"; ROOT=mkdtemp(tmpl);
  if(!ROOT){ fprintf(stderr,"mkdtemp failed\n"); return 1; }

  wfile("lib/util.lua",     "local M={} function M.add(a,b) return a+b end return M\n");
  wfile("lib/init.lua",     "return {via='init'}\n");
  wfile("plain.lua",        "local z=10\n");
  wfile("boom.lua",         "error('kaboom')\n");
  wfile("chain/a.lua",      "local b=require('chain.b')\nreturn {val=b.n+1}\n");
  wfile("chain/b.lua",      "return {n=41}\n");

  lx_State* g = lx_new();
  lx_set_modroot(g, ROOT);
  char err[512]; int rc;

  /* normal load + module value */
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "local u=require('lib.util'); print(u.add(2,3))", err, sizeof(err));
  CHK(rc==0 && has(lx_last_output(g),"5"), "require util");

  /* init.lua fallback path */
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "print(require('lib').via)", err, sizeof(err));
  CHK(rc==0 && has(lx_last_output(g),"init"), "require init.lua");

  /* cached: second require of util returns the same table */
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "print(require('lib.util')==require('lib.util'))", err, sizeof(err));
  CHK(rc==0 && has(lx_last_output(g),"true"), "require cached");

  /* no explicit return -> package.loaded entry is true */
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "print(require('plain'))", err, sizeof(err));
  CHK(rc==0 && has(lx_last_output(g),"true"), "require plain");

  /* nested require inside a module (modroot-relative) */
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "print(require('chain.a').val)", err, sizeof(err));
  CHK(rc==0 && has(lx_last_output(g),"42"), "require chain");

  /* error inside module propagates with 'error loading module' */
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "require('boom')", err, sizeof(err));
  CHK(rc==1 && has(err,"error loading module"), "module error");
  CHK(has(err,"kaboom"), "module error reason");

  /* missing module: all candidates exhausted */
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "require('nope')", err, sizeof(err));
  CHK(rc==1 && has(err,"not found"), "missing module");

  /* relative fallback (rel not under modroot) only when modroot is empty */
  lx_set_modroot(g, "");
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "require('nope_xyz')", err, sizeof(err));
  CHK(rc==1 && has(err,"not found"), "empty-modroot fallback exhausted");

  /* bad argument type */
  lx_set_modroot(g, ROOT);
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "require(42)", err, sizeof(err));
  CHK(rc==1, "require bad arg");

  /* empty name -> j==0 in resolve -> not found */
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "require('')", err, sizeof(err));
  CHK(rc==1 && has(err,"not found"), "empty name");

  /* name with backslash separator converts to path separator */
  memset(err,0,sizeof(err));
  rc = lx_dostring(g, "print(require('lib\\\\util').add(1,1))", err, sizeof(err));
  CHK(rc==0 && has(lx_last_output(g),"2"), "backslash name");

  lx_close(g);

  /* TOCTOU guard: a background thread churns the module file (create/unlink)
   * while require() resolves+loads it. When the unlink lands between
   * file_exists and load_file_text, require reports "cannot read module". */
  {
    static char racepath[1100]; snprintf(racepath,sizeof(racepath),"%s/racy.lua",ROOT);
    unlinker_path = racepath; unlinker_stop = 0;
    lx_State* gt = lx_new(); lx_set_modroot(gt, ROOT);
    pthread_t ut; pthread_create(&ut,NULL,unlinker_thread,NULL);
    int got_cannot_read = 0;
    for(int round=0; round<8000 && !got_cannot_read; round++){
      char err2[512]; memset(err2,0,sizeof(err2));
      lx_dostring(gt, "package.loaded['racy']=nil", err2, sizeof(err2));
      memset(err2,0,sizeof(err2));
      int rrc = lx_dostring(gt, "require('racy')", err2, sizeof(err2));
      if(rrc==1 && has(err2,"cannot read module")) got_cannot_read=1;
    }
    unlinker_stop=1; pthread_join(ut,NULL); unlink(racepath);
    lx_close(gt);
    CHK(got_cannot_read, "load_file_text TOCTOU guard");
  }

  /* clean up fixture tree */
  char cmd[1100]; snprintf(cmd,sizeof(cmd),"rm -rf '%s'",ROOT); int sysrc=system(cmd); (void)sysrc;

  if(fails){ fprintf(stderr, "t21: %d failure(s)\n", fails); return 1; }
  printf("t21 ok\n");
  return 0;
}
