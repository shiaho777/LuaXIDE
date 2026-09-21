/* lx.c — LuaX engine, Phase 1a: tree-walking interpreter (corrected).
 * Subset: locals/assign/multi-assign, arithmetic/comparison/logical/bitwise/concat/len,
 * if/elseif/else, while, repeat, numeric for, generic for (pairs/ipairs/next),
 * functions/closures/upvalues via env capture, varargs, multi-return, tables,
 * strings, metatables (__index/__newindex/__tostring/__len), pcall.
 * No GC. Bytecode VM = Phase 1b.
 */
#include "lx.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <ctype.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <pthread.h>
#include <time.h>

enum { T_NIL, T_BOOL, T_NUM, T_STR, T_TAB, T_FN, T_CFN };
typedef struct Str Str; typedef struct Table Table; typedef struct Value Value;
typedef struct Node Node; typedef struct Env Env; typedef struct Func Func;
typedef struct Closure Closure; typedef struct CFn CFn; typedef struct State State; typedef struct Proto Proto;

struct Str    { size_t len; char* p; };
struct Value  { int tag; union { bool b; double num; Str* s; Table* t; Closure* f; CFn* c; } u; };
struct Table  { int cap, n; struct TEntry { Value k, v; int used; } *e; Table* meta; };
struct Env    { Table* vars; Env* parent; };
struct Func   { int nparam; char** params; bool vararg; Node* body; Env* env; int line; Proto* bc; signed char bc_tried; };
struct Closure{ Func* f; };
struct CFn    { Value (*fn)(State*, int, Value*); const char* name; };

#define VNIL     vNil()
#define VBOOL(b) vBool(b)
#define VNUM(n)  vNum(n)
#define VSTR(s)  vStr(s)
#define VTAB(t)  vTab(t)
#define VFN(f)   vFn(f)
#define VCFN(c)  vCfn(c)
static inline Value vNil(void){Value v; v.tag=T_NIL; v.u.num=0; return v;}
static inline Value vBool(bool b){Value v; v.tag=T_BOOL; v.u.b=b; return v;}
static inline Value vNum(double n){Value v; v.tag=T_NUM; v.u.num=n; return v;}
static inline Value vStr(Str* s){Value v; v.tag=T_STR; v.u.s=s; return v;}
static inline Value vTab(Table* t){Value v; v.tag=T_TAB; v.u.t=t; return v;}
static inline Value vFn(Closure* f){Value v; v.tag=T_FN; v.u.f=f; return v;}
static inline Value vCfn(CFn* c){Value v; v.tag=T_CFN; v.u.c=c; return v;}

/* AST node (defined early so forward decls can use it) */
enum { K_CHUNK,K_LOCAL,K_ASSIGN,K_CALLSTAT,K_DO,K_IF,K_WHILE,K_REPEAT,K_NFOR,K_GFOR,K_RET,K_BREAK,
  K_FUNCDECL,K_LABEL,K_GOTO,K_NIL,K_TRUE,K_FALSE,K_NUM,K_STR,K_VARARG,K_NAME,K_INDEX,K_CALL,K_METHODCALL,
  K_BINOP,K_UNOP,K_TABLE,K_FUNC,K_FIELD,K_PAREN };
struct Node{int kind,line;double num;Str*str;char*name;int op;Node*a,*b,*c,*body;Node**list;int nlist;Node**list2;int nlist2;char**names;int nnames;bool vararg,isLocal,isKv;char*method;};
struct Flow{int kind;int nret;Value rets[64];};

#define LX_MAX_HANDLERS 1024
struct State {
  jmp_buf err; char errmsg[512];
  Env* globals;
  int nret; Value retbuf[256];
  char* blk; size_t blksz, blkused;
  struct { char* p; size_t sz; }* pages; int npages;
  /* captured print output (survives across arena; owned by malloc) */
  char* out; size_t outsz, outused;
  /* last serialized UI tree JSON */
  char* json; size_t jsonsz, jsonused;
  /* declarative UI: the value the chunk returned (a view function or a node) */
  Value app_view; bool has_view;
  /* event handler registry, rebuilt on each serialization */
  Value handlers[LX_MAX_HANDLERS]; int nhandlers;
  /* runaway-execution guard */
  long steps; long step_limit;
  /* C-recursion guard: every callValue frame consumes real C stack, so
   * unbounded script recursion must become a catchable error, not a SIGSEGV. */
  int call_depth;
  /* math.random state (splitmix64, per-State; lazily seeded from time) */
  unsigned long long rng; int rng_seeded;
  /* bytecode VM (Phase 1b): value stack shared by compiled frames + stats */
  Value* vstack; int vstack_sz; int vtop;
  long bc_calls; long bc_fallbacks;
  /* line tracking: parseLine follows the lexer token-by-token so every AST
   * node picks up its source line automatically at creation time; curLine is
   * refreshed from the executing statement's node so runtime errors can
   * report "line N: ...". */
  int parseLine;
  int curLine;
  int debug_enabled;
  int break_on_error;
  struct { int line; char cond[96]; char logmsg[128]; int log_only; } breakpoints[256];
  int nbp;
  int step_mode;
  int step_out_depth;
  int dbg_paused;
  int dbg_cmd;
  int pause_line;
  int pause_reason;
  Env* cur_env;
  char* dbg_locals;
  char* dbg_stack;
  char* dbg_eval_buf;
  int dbg_eval_depth;
  struct { char name[48]; int line; int def_line; } call_stack[64];
  int nstack;
  pthread_mutex_t dbg_mu;
  pthread_cond_t dbg_cv;
  int dbg_inited;
  volatile int cancel_flag;
  pthread_mutex_t io_mu;
  pthread_cond_t io_cv;
  int io_inited;
  char* stdin_q;
  size_t stdin_qsz, stdin_qused;
  int stdin_waiting;
  char rootfs[512];
  char modroot[1024];
};

/* dynamic byte-buffer append (malloc-backed, not arena) */
static void buf_append(char** buf, size_t* sz, size_t* used, const char* s, size_t n){
  if(*used+n+1>*sz){ size_t ns=*sz?*sz:256; while(*used+n+1>ns)ns*=2; *buf=realloc(*buf,ns); *sz=ns; }
  memcpy(*buf+*used,s,n); *used+=n; (*buf)[*used]=0;
}

static void* xalloc(State* S, size_t n){ n=(n+15)&~((size_t)15);
  if(!S->blk||S->blkused+n>S->blksz){ size_t sz=n>8192?n:8192; S->blk=malloc(sz);S->blksz=sz;S->blkused=0;
    S->pages=realloc(S->pages,(S->npages+1)*sizeof(*S->pages)); S->pages[S->npages].p=S->blk; S->pages[S->npages].sz=sz; S->npages++; }
  void* p=S->blk+S->blkused; S->blkused+=n; return p; }
static void* xcalloc(State* S,size_t n){ void*p=xalloc(S,n); memset(p,0,n); return p; }
static char* xstrndup(State* S,const char* s,size_t n){ char*p=xalloc(S,n+1); memcpy(p,s,n); p[n]=0; return p; }
#if defined(__clang__)||defined(__GNUC__)
#define LX_NORETURN __attribute__((noreturn))
#else
#define LX_NORETURN
#endif
static void lx_error(State* S,const char* fmt,...) LX_NORETURN;
static void lx_error(State* S,const char* fmt,...){ va_list ap; va_start(ap,fmt);
  vsnprintf(S->errmsg,sizeof(S->errmsg),fmt,ap); va_end(ap); longjmp(S->err,1); }
/* Runtime error: auto-prefixes "line N: " from S->curLine (tracked as statements
 * execute), so failures like "attempt to call a nil value" are locatable in the
 * editor. Parser errors use lx_error directly since they already know their line. */
static void dbg_pause_now(State*S,Env*env,int line,int reason);
static void lx_rt_error(State* S,const char* fmt,...) LX_NORETURN;
static void lx_rt_error(State* S,const char* fmt,...){
  char msg[400]; va_list ap; va_start(ap,fmt); vsnprintf(msg,sizeof(msg),fmt,ap); va_end(ap);
  if(S->curLine>0) snprintf(S->errmsg,sizeof(S->errmsg),"line %d: %s",S->curLine,msg); else snprintf(S->errmsg,sizeof(S->errmsg),"%s",msg);
  if(S->debug_enabled && S->break_on_error && !S->dbg_eval_depth && S->cur_env
      && !(msg[0] && strstr(msg,"debug stopped by user"))){
    int line=S->curLine>0?S->curLine:1;
    dbg_pause_now(S,S->cur_env,line,1);
  }
  longjmp(S->err,1);
}

static Str* newStr(State* S,const char* s,size_t n){ Str*st=xalloc(S,sizeof(Str)); st->len=n; st->p=xstrndup(S,s,n); return st; }
static bool strEq(Str*a,Str*b){ return a==b||(a->len==b->len&&memcmp(a->p,b->p,a->len)==0); }
static Table* newTable(State* S){ Table*t=xcalloc(S,sizeof(Table)); t->cap=8; t->e=xcalloc(S,8*sizeof(*t->e)); return t; }
static unsigned hashVal(Value v){ switch(v.tag){
  case T_NIL:return 0; case T_BOOL:return v.u.b?1:2;
  case T_NUM:{uint64_t u;memcpy(&u,&v.u.num,8);return (unsigned)(u^(u>>32));}
  case T_STR:{unsigned h=2166136261u;for(size_t i=0;i<v.u.s->len;i++){h^=(unsigned char)v.u.s->p[i];h*=16777619u;}return h;}
  default:return (unsigned)(uintptr_t)v.u.t; } }
static bool valEq(Value a,Value b){ if(a.tag!=b.tag)return false;
  switch(a.tag){case T_NIL:return true;case T_BOOL:return a.u.b==b.u.b;case T_NUM:return a.u.num==b.u.num;
  case T_STR:return strEq(a.u.s,b.u.s);default:return a.u.t==b.u.t;} }
static Value* tfind(Table*t,Value k){ unsigned h=hashVal(k)&(t->cap-1);
  for(int i=0;i<t->cap;i++){int j=(h+i)&(t->cap-1); if(!t->e[j].used)return NULL; if(valEq(t->e[j].k,k))return &t->e[j].v;} return NULL; }
static void tresize(State*S,Table*t){ int oc=t->cap;t->cap*=2;struct TEntry*ne=xcalloc(S,t->cap*sizeof(*t->e));struct TEntry*oe=t->e;t->e=ne;t->n=0;
  for(int i=0;i<oc;i++)if(oe[i].used){Value k=oe[i].k,v=oe[i].v;unsigned h=hashVal(k)&(t->cap-1);while(t->e[h].used)h=(h+1)&(t->cap-1);t->e[h].k=k;t->e[h].v=v;t->e[h].used=1;t->n++;} /* oe is arena memory, not freed */ }
static void tset(State*S,Table*t,Value k,Value v){ if(k.tag==T_NIL)lx_rt_error(S,"table index is nil");
  Value*f=tfind(t,k); if(f){*f=v;return;} if(t->n*2>=t->cap)tresize(S,t); unsigned h=hashVal(k)&(t->cap-1);
  while(t->e[h].used)h=(h+1)&(t->cap-1); t->e[h].k=k;t->e[h].v=v;t->e[h].used=1;t->n++; }
static Value tget(Table*t,Value k){ Value*f=tfind(t,k); return f?*f:VNIL; }
static int tlen(Table*t){ int n=0; while(tget(t,VNUM(n+1)).tag!=T_NIL)n++; return n; }

/* ---------- lexer ---------- */
enum { T_EOF=256,T_NAME,TK_NUM,TK_STR,T_AND,T_BREAK,T_DO,T_ELSE,T_ELSEIF,T_END,T_FALSE,T_FOR,
  T_FUNCTION,T_GOTO,T_IF,T_IN,T_LOCAL,TK_NIL,T_NOT,T_OR,T_REPEAT,T_RETURN,T_THEN,T_TRUE,T_UNTIL,T_WHILE,
  T_EQ,T_NE,T_LE,T_GE,T_CONCAT,T_DOTS,T_DBCOLON,T_SHL,T_SHR,T_IDIV };
typedef struct{int kind;double num;Str*str;char*name;int line;}Tok;
typedef struct{const char*s;size_t n;int line;State*S;Tok cur,nxt;}Lex;
static const struct{const char*kw;int tok;}KW[]={{"and",T_AND},{"break",T_BREAK},{"do",T_DO},{"else",T_ELSE},{"elseif",T_ELSEIF},
  {"end",T_END},{"false",T_FALSE},{"for",T_FOR},{"function",T_FUNCTION},{"goto",T_GOTO},{"if",T_IF},{"in",T_IN},
  {"local",T_LOCAL},{"nil",TK_NIL},{"not",T_NOT},{"or",T_OR},{"repeat",T_REPEAT},{"return",T_RETURN},{"then",T_THEN},
  {"true",T_TRUE},{"until",T_UNTIL},{"while",T_WHILE},{NULL,0}};

static int readHex(Lex*L){int v=0;while(L->n&&isxdigit((unsigned char)L->s[0])){v=v*16+(isdigit((unsigned char)L->s[0])?L->s[0]-'0':tolower(L->s[0])-'a'+10);L->s++;L->n--;}return v;}
static int readDec(Lex*L){int v=0;while(L->n&&isdigit((unsigned char)L->s[0])){v=v*10+(L->s[0]-'0');L->s++;L->n--;}return v;}
static Str* readStr(Lex*L,char q){ char*buf=NULL;size_t m=0,cap=0;
  while(L->n&&L->s[0]!=q){ char c=*L->s++;L->n--; if(c=='\n')L->line++;
    if(c=='\\'&&L->n){char e=*L->s++;L->n--;
      switch(e){case'n':c='\n';break;case't':c='\t';break;case'r':c='\r';break;case'\\':c='\\';break;
        case'"':c='"';break;case'\'':c='\'';break;case'a':c='\a';break;case'b':c='\b';break;
        case'f':c='\f';break;case'v':c='\v';break;case'x':c=(char)readHex(L);break;
        case'z':while(L->n&&isspace((unsigned char)L->s[0])){if(L->s[0]=='\n')L->line++;L->s++;L->n--;}continue;
        default:if(isdigit((unsigned char)e)){L->s--;L->n++;c=(char)readDec(L);}else c=e;}}
    if(m+1>cap){cap=cap?cap*2:64;buf=realloc(buf,cap);} buf[m++]=c; }
  if(!L->n){ if(buf)free(buf); lx_error(L->S,"line %d: unterminated string",L->line); } L->s++;L->n--;
  Str*s=newStr(L->S,buf?buf:"",m); if(buf)free(buf); return s; }
/* does the input at L->s begin a long-bracket close "]" ("="*sep) "]" ?
 * Lua long-bracket closers are ] followed by exactly sep '=' then ]; the old
 * code counted consecutive ']' chars which wrongly accepted e.g. ]]==] as a
 * level-0 closer and ]]] as a level-2 closer. */
static int longCloseAt(Lex*L,int sep){ if(L->n<1||L->s[0]!=']')return 0; size_t i=1; for(int e=0;e<sep;e++){ if(i>=L->n||L->s[i]!='=')return 0; i++; } if(i>=L->n||L->s[i]!=']')return 0; return 1; }
static void skipLong(Lex*L,int sep){ while(L->n){ if(L->s[0]==']'&&longCloseAt(L,sep)){ L->s+=sep+2; L->n-=sep+2; return; } if(L->s[0]=='\n')L->line++;L->s++;L->n--; } }
static Str* longStr(Lex*L,int sep){ if(L->n&&L->s[0]=='\r'){L->s++;L->n--;} if(L->n&&L->s[0]=='\n'){L->s++;L->n--;L->line++;}
  char*buf=NULL;size_t m=0,cap=0;
  while(L->n){ if(L->s[0]==']'&&longCloseAt(L,sep)){ L->s+=sep+2; L->n-=sep+2; Str*s=newStr(L->S,buf?buf:"",m); if(buf)free(buf); return s; } if(L->s[0]=='\n')L->line++; if(m+1>cap){cap=cap?cap*2:64;buf=realloc(buf,cap);} buf[m++]=*L->s++;L->n--; } lx_error(L->S,"line %d: unterminated long string",L->line); return NULL; }
static void lexOne(Lex*L){ State*S=L->S; L->cur=L->nxt; S->parseLine=L->cur.line;
  while(L->n){ char c=*L->s;
    if(c==' '||c=='\t'||c=='\r'){L->s++;L->n--;continue;} if(c=='\n'){L->line++;L->s++;L->n--;continue;}
    if(c=='-'&&L->n>1&&L->s[1]=='-'){ L->s+=2;L->n-=2;
      if(L->n&&L->s[0]=='['){int sep=0;const char*p=L->s+1;size_t nn=L->n-1;while(nn&&p[0]=='='){sep++;p++;nn--;}if(nn&&p[0]=='['){L->s=p+1;L->n=nn-1;skipLong(L,sep);continue;}}
      while(L->n&&L->s[0]!='\n'){L->s++;L->n--;} continue; }
    if(c=='['&&L->n>1&&(L->s[1]=='['||L->s[1]=='=')){int sep=0;const char*p=L->s+1;size_t nn=L->n-1;while(nn&&p[0]=='='){sep++;p++;nn--;}if(nn&&p[0]=='['){L->s=p+1;L->n=nn-1;L->nxt.kind=TK_STR;L->nxt.str=longStr(L,sep);L->nxt.line=L->line;return;}}
    if(c=='"'||c=='\''){L->nxt.line=L->line;L->s++;L->n--;L->nxt.kind=TK_STR;L->nxt.str=readStr(L,c);return;}
    if(isdigit((unsigned char)c)||(c=='.'&&L->n>1&&isdigit((unsigned char)L->s[1]))){char*e;double d=strtod(L->s,&e);L->nxt.line=L->line;L->nxt.kind=TK_NUM;L->nxt.num=d;L->n-=e-L->s;L->s=e;return;}
    if(isalpha((unsigned char)c)||c=='_'){const char*st=L->s;size_t sn=0;while(L->n&&(isalnum((unsigned char)L->s[0])||L->s[0]=='_')){L->s++;L->n--;sn++;}L->nxt.line=L->line;int kw=0;for(int i=0;KW[i].kw;i++)if(strlen(KW[i].kw)==sn&&memcmp(KW[i].kw,st,sn)==0){kw=KW[i].tok;break;} if(kw)L->nxt.kind=kw;else{L->nxt.kind=T_NAME;L->nxt.name=xstrndup(S,st,sn);}return;}
    L->nxt.line=L->line; char n1=L->n>1?L->s[1]:0;
    if(c=='.'&&L->n>=3&&L->s[1]=='.'&&L->s[2]=='.'){L->s+=3;L->n-=3;L->nxt.kind=T_DOTS;return;}
    #define TWO(a,b,t) if(c==a&&n1==b){L->s+=2;L->n-=2;L->nxt.kind=t;return;}
    TWO('=','=',T_EQ)TWO('~','=',T_NE)TWO('<','=',T_LE)TWO('>','=',T_GE)TWO('.','.',T_CONCAT)TWO(':',':',T_DBCOLON)TWO('<','<',T_SHL)TWO('>','>',T_SHR)TWO('/','/',T_IDIV)
    #undef TWO
    if(c=='.'&&n1=='.'){L->s+=2;L->n-=2;L->nxt.kind=T_CONCAT;return;}
    L->s++;L->n--;L->nxt.kind=(int)c;return; }
  L->nxt.kind=T_EOF;L->nxt.line=L->line; }
static void lexInit(Lex*L,State*S,const char*src,size_t n){ L->s=src;L->n=n;L->line=1;L->S=S; L->cur.kind=T_EOF; lexOne(L); lexOne(L); }

/* ---------- parser ---------- */
static Node* node(State*S,int k){Node*n=xcalloc(S,sizeof(Node));n->kind=k;n->line=S->parseLine;return n;}
static void pl(State*S,Node*a,Node*e){if(a->nlist==0){a->list=xalloc(S,sizeof(Node*));a->nlist=1;a->list[0]=e;return;}if((a->nlist&(a->nlist-1))==0){int nc=a->nlist*2;Node**nl=xalloc(S,nc*sizeof(Node*));memcpy(nl,a->list,a->nlist*sizeof(Node*));a->list=nl;}a->list[a->nlist++]=e;}
static void pn(State*S,Node*a,char*nm){if(a->nnames==0){a->names=xalloc(S,sizeof(char*));a->nnames=1;a->names[0]=nm;return;}if((a->nnames&(a->nnames-1))==0){int nc=a->nnames*2;char**nn=xalloc(S,nc*sizeof(char*));memcpy(nn,a->names,a->nnames*sizeof(char*));a->names=nn;}a->names[a->nnames++]=nm;}
typedef struct{Lex L;State*S;}P;
static void pnext(P*p){lexOne(&p->L);}
static void expect(P*p,int k,const char*m){if(p->L.cur.kind!=k)lx_error(p->S,"line %d: expected %s",p->L.cur.line,m);pnext(p);}
static int accept(P*p,int k){if(p->L.cur.kind==k){pnext(p);return 1;}return 0;}
/* Read a name token or raise a parse error. Reading cur.name without this
 * check yields an uninitialized pointer on any non-name token (e.g. the
 * `a.` a user is mid-typing during hot-reload) → strlen on garbage → crash. */
static char* expectName(P*p,const char*what){if(p->L.cur.kind!=T_NAME)lx_error(p->S,"line %d: expected %s",p->L.cur.line,what);char*nm=p->L.cur.name;pnext(p);return nm;}
static Node* expr(P*);static Node* block(P*);static Node* tablecons(P*);static Node* funcbody(P*);
static Node* suffix(P*p,Node*base){ State*S=p->S;
  while(1){ int k=p->L.cur.kind;
    if(k=='.'){pnext(p);Node*idx=node(S,K_INDEX);idx->a=base;char*nm=expectName(p,"field name");idx->b=node(S,K_STR);idx->b->str=newStr(S,nm,strlen(nm));base=idx;}
    else if(k=='['){pnext(p);Node*idx=node(S,K_INDEX);idx->a=base;idx->b=expr(p);expect(p,']',"']'");base=idx;}
    else if(k==':'){pnext(p);char*m=expectName(p,"method name");Node*mc=node(S,K_METHODCALL);mc->a=base;mc->method=m;Node*args=node(S,0);
      if(p->L.cur.kind=='('){pnext(p);if(p->L.cur.kind!=')'){pl(S,args,expr(p));while(accept(p,','))pl(S,args,expr(p));}expect(p,')',"')'");}
      else if(p->L.cur.kind=='{'){pl(S,args,tablecons(p));}
      else if(p->L.cur.kind==TK_STR){Node*s=node(S,K_STR);s->str=p->L.cur.str;pnext(p);pl(S,args,s);}
      else lx_error(S,"line %d: function args expected",p->L.cur.line);
      mc->list=args->list;mc->nlist=args->nlist;base=mc;}
    else if(k=='('){pnext(p);Node*call=node(S,K_CALL);call->a=base;if(p->L.cur.kind!=')'){pl(S,call,expr(p));while(accept(p,','))pl(S,call,expr(p));}expect(p,')',"')'");base=call;}
    else if(k=='{'){Node*call=node(S,K_CALL);call->a=base;pl(S,call,tablecons(p));base=call;}
    else if(k==TK_STR){Node*call=node(S,K_CALL);call->a=base;Node*s=node(S,K_STR);s->str=p->L.cur.str;pnext(p);pl(S,call,s);base=call;}
    else break; }
  return base; }
static Node* prefixexp(P*p){ State*S=p->S;Node*base=NULL;
  if(p->L.cur.kind=='('){pnext(p);base=expr(p);expect(p,')',"')'"); /* a parenthesised expression always adjusts to a single value (Lua semantics); wrapping it in K_PAREN collapses any multi-value result so (f()) yields one value. Subsequent suffixes ( . [ ( ) apply to that single value. */ Node*pn_=node(S,K_PAREN);pn_->a=base;base=suffix(p,pn_); }
  else if(p->L.cur.kind==T_NAME){base=node(S,K_NAME);base->name=p->L.cur.name;pnext(p);base=suffix(p,base);}
  else lx_error(S,"line %d: unexpected symbol",p->L.cur.line); return base; }
static Node* simpleexp(P*p){ State*S=p->S;Tok*t=&p->L.cur;
  switch(t->kind){ case TK_NIL:case T_TRUE:case T_FALSE:case TK_NUM:case TK_STR:case T_DOTS:{
    Node*n=node(S, t->kind==TK_NIL?K_NIL:t->kind==T_TRUE?K_TRUE:t->kind==T_FALSE?K_FALSE:t->kind==TK_NUM?K_NUM:t->kind==TK_STR?K_STR:K_VARARG);
    if(t->kind==TK_NUM)n->num=t->num; if(t->kind==TK_STR)n->str=t->str; pnext(p); return n; }
    case T_FUNCTION:{pnext(p);return funcbody(p);} case '{':return tablecons(p); default:return prefixexp(p);} }
static int binop_ok(int op){ switch(op){case T_OR:case T_AND:case'<':case'>':case T_LE:case T_GE:case T_EQ:case T_NE:
  case'|':case'&':case'~':case T_SHL:case T_SHR:case T_CONCAT:case'+':case'-':case'*':case'/':case T_IDIV:case'%':case'^':return 1;} return 0; }
static int binprio(int op){ switch(op){case T_OR:return 1;case T_AND:return 2;case'<':case'>':case T_LE:case T_GE:case T_EQ:case T_NE:return 3;
  case'|':return 4;case'~':return 5;case'&':return 5;case T_SHL:case T_SHR:return 6;case T_CONCAT:return 7;case'+':case'-':return 8;case'*':case'/':case T_IDIV:case'%':return 9;case'^':return 11;}return 0; }
static Node* subexpr(P*p,int limit){ State*S=p->S;Node*e;int uop=0;
  if(p->L.cur.kind==T_NOT||p->L.cur.kind=='-'||p->L.cur.kind=='#'||p->L.cur.kind=='~'){uop=p->L.cur.kind;pnext(p);}
  if(uop){Node*un=node(S,K_UNOP);un->op=uop;un->a=subexpr(p,10);e=un;}else e=simpleexp(p);
  int op=p->L.cur.kind;
  while(binop_ok(op)){int lp=binprio(op);if(lp<=limit)break;int rp=(op==T_CONCAT||op=='^')?lp-1:lp;pnext(p);Node*bin=node(S,K_BINOP);bin->op=op;bin->a=e;bin->b=subexpr(p,rp);e=bin;op=p->L.cur.kind;}
  return e; }
static Node* expr(P*p){return subexpr(p,0);}
static Node* funcbody(P*p){ State*S=p->S;expect(p,'(',"'('");Node*f=node(S,K_FUNC);
  while(p->L.cur.kind!=')'&&p->L.cur.kind!=T_EOF){if(p->L.cur.kind==T_DOTS){pnext(p);f->vararg=1;break;}pn(S,f,expectName(p,"parameter name"));if(!accept(p,','))break;}
  if(p->L.cur.kind==T_DOTS){pnext(p);f->vararg=1;} expect(p,')',"')'");f->body=block(p);expect(p,T_END,"'end'");return f; }
static Node* tablecons(P*p){ State*S=p->S;expect(p,'{',"'{'");Node*t=node(S,K_TABLE);
  while(p->L.cur.kind!='}'&&p->L.cur.kind!=T_EOF){ Node*f=node(S,K_FIELD);
    if(p->L.cur.kind=='['){pnext(p);Node*k=expr(p);expect(p,']',"']'");expect(p,'=',"'='");Node*v=expr(p);f->isKv=1;f->a=k;f->b=v;}
    else if(p->L.cur.kind==T_NAME&&p->L.nxt.kind=='='){Str*k=newStr(S,p->L.cur.name,strlen(p->L.cur.name));pnext(p);pnext(p);Node*v=expr(p);f->isKv=1;f->a=node(S,K_STR);f->a->str=k;f->b=v;}
    else{Node*v=expr(p);f->isKv=0;f->a=v;}
    pl(S,t,f); if(!accept(p,',')&&!accept(p,';'))break; }
  expect(p,'}',"'}'");return t; }
static Node* stat(P*p){ State*S=p->S;int line=p->L.cur.line;
  Node* out=NULL;
  switch(p->L.cur.kind){
  case';':pnext(p);return NULL;
  case T_IF:{pnext(p);Node*n=node(S,K_IF);n->a=expr(p);expect(p,T_THEN,"'then'");n->body=block(p);Node*elifs=node(S,0);
    while(p->L.cur.kind==T_ELSEIF){pnext(p);Node*e=node(S,K_IF);e->a=expr(p);expect(p,T_THEN,"'then'");e->body=block(p);pl(S,elifs,e);} n->list=elifs->list;n->nlist=elifs->nlist;
    Node*els=NULL;if(accept(p,T_ELSE))els=block(p);n->b=els;expect(p,T_END,"'end'");out=n;break;}
  case T_WHILE:{pnext(p);Node*n=node(S,K_WHILE);n->a=expr(p);expect(p,T_DO,"'do'");n->body=block(p);expect(p,T_END,"'end'");out=n;break;}
  case T_DO:{pnext(p);Node*n=node(S,K_DO);n->body=block(p);expect(p,T_END,"'end'");out=n;break;}
  case T_REPEAT:{pnext(p);Node*n=node(S,K_REPEAT);n->body=block(p);expect(p,T_UNTIL,"'until'");n->a=expr(p);out=n;break;}
  case T_FOR:{pnext(p);
    if(p->L.cur.kind==T_NAME){char*first=p->L.cur.name;pnext(p);
      if(p->L.cur.kind=='='){pnext(p);Node*e1=expr(p);expect(p,',',"','");Node*e2=expr(p);Node*e3=NULL;if(accept(p,','))e3=expr(p);expect(p,T_DO,"'do'");Node*body=block(p);expect(p,T_END,"'end'");Node*n=node(S,K_NFOR);n->name=first;n->a=e1;n->b=e2;n->c=e3;n->body=body;out=n;break;}
      else{Node*names=node(S,0);pn(S,names,first);while(accept(p,',')){pn(S,names,expectName(p,"loop variable"));}expect(p,T_IN,"'in'");Node*ex=node(S,0);pl(S,ex,expr(p));while(accept(p,','))pl(S,ex,expr(p));expect(p,T_DO,"'do'");Node*body=block(p);expect(p,T_END,"'end'");Node*n=node(S,K_GFOR);n->names=names->names;n->nnames=names->nnames;n->list=ex->list;n->nlist=ex->nlist;n->body=body;out=n;break;}}
    lx_error(S,"line %d: bad for",line);}
  case T_FUNCTION:{pnext(p);Node*target=node(S,K_NAME);target->name=expectName(p,"function name");bool isMethod=false;
    while(p->L.cur.kind=='.'||p->L.cur.kind==':'){ if(p->L.cur.kind==':')isMethod=true; pnext(p);char*nm=expectName(p,"function name");Node*idx=node(S,K_INDEX);idx->a=target;idx->b=node(S,K_STR);idx->b->str=newStr(S,nm,strlen(nm));target=idx; }
    Node*fd=node(S,K_FUNCDECL);fd->a=target;fd->body=funcbody(p);
    if(isMethod){Node*f=fd->body;char**np=xalloc(S,(f->nnames+1)*sizeof(char*));np[0]="self";if(f->nnames)memcpy(np+1,f->names,f->nnames*sizeof(char*));f->names=np;f->nnames++;}
    out=fd;break;}
  case T_LOCAL:{pnext(p);
    if(accept(p,T_FUNCTION)){Node*fd=node(S,K_FUNCDECL);fd->isLocal=1;Node*target=node(S,K_NAME);target->name=expectName(p,"function name");fd->a=target;fd->body=funcbody(p);out=fd;break;}
    Node*n=node(S,K_LOCAL);pn(S,n,expectName(p,"local name"));while(accept(p,',')){pn(S,n,expectName(p,"local name"));}Node*vals=node(S,0);if(accept(p,'=')){pl(S,vals,expr(p));while(accept(p,','))pl(S,vals,expr(p));}n->list=vals->list;n->nlist=vals->nlist;out=n;break;}
  case T_RETURN:{pnext(p);Node*n=node(S,K_RET);if(p->L.cur.kind!=';'&&p->L.cur.kind!=T_END&&p->L.cur.kind!=T_ELSE&&p->L.cur.kind!=T_ELSEIF&&p->L.cur.kind!=T_UNTIL&&p->L.cur.kind!=T_EOF){Node*e=node(S,0);pl(S,e,expr(p));while(accept(p,','))pl(S,e,expr(p));n->list=e->list;n->nlist=e->nlist;}accept(p,';');out=n;break;}
  case T_BREAK:pnext(p);out=node(S,K_BREAK);break;
  /* LuaX is a Lua 5.1 subset: goto/labels are rejected at parse time rather
   * than silently accepted and ignored (which would be a silent-correctness bug). */
  case T_GOTO:pnext(p);lx_error(S,"line %d: 'goto' is not supported (LuaX is a Lua 5.1 subset)",p->L.cur.line);
  case T_DBCOLON:pnext(p);lx_error(S,"line %d: labels are not supported (LuaX is a Lua 5.1 subset)",p->L.cur.line);
  default:break; }
  if(!out){
    Node*e=prefixexp(p);
    if(p->L.cur.kind=='='){pnext(p);Node*ts=node(S,0);pl(S,ts,e);Node*vs=node(S,0);pl(S,vs,expr(p));while(accept(p,',')){Node*t=prefixexp(p);pl(S,ts,t);pl(S,vs,expr(p));}Node*n=node(S,K_ASSIGN);n->list=ts->list;n->nlist=ts->nlist;n->list2=vs->list;n->nlist2=vs->nlist;out=n;}
    else if(p->L.cur.kind==','){Node*ts=node(S,0);pl(S,ts,e);while(accept(p,',')){Node*t=prefixexp(p);pl(S,ts,t);}expect(p,'=',"'='");Node*vs=node(S,0);pl(S,vs,expr(p));while(accept(p,','))pl(S,vs,expr(p));Node*n=node(S,K_ASSIGN);n->list=ts->list;n->nlist=ts->nlist;n->list2=vs->list;n->nlist2=vs->nlist;out=n;}
    else {
      if(e->kind!=K_CALL&&e->kind!=K_METHODCALL)lx_error(S,"line %d: syntax error (expected statement)",line);
      Node*n=node(S,K_CALLSTAT);n->a=e;out=n;
    }
  }
  if(out) out->line=line;
  return out;
}

static Node* block(P*p){ State*S=p->S;Node*blk=node(S,K_CHUNK);
  while(p->L.cur.kind!=T_END&&p->L.cur.kind!=T_ELSE&&p->L.cur.kind!=T_ELSEIF&&p->L.cur.kind!=T_UNTIL&&p->L.cur.kind!=T_EOF){Node*st=stat(p);if(st){pl(S,blk,st);if(st->kind==K_RET||st->kind==K_BREAK)break;}}
  return blk; }
static Node* parse(State*S,const char*src,size_t n){P p;p.S=S;lexInit(&p.L,S,src,n);Node*c=block(&p);if(p.L.cur.kind!=T_EOF)lx_error(S,"line %d: trailing tokens",p.L.cur.line);return c;}

/* ---------- interpreter ---------- */
#define F_NORMAL ((struct Flow){0,0,{0}})
static Env* newEnv(State*S,Env*parent){Env*e=xalloc(S,sizeof(Env));e->vars=newTable(S);e->parent=parent;return e;}
static const char* lx_typename(Value v){switch(v.tag){case T_NIL:return "nil";case T_BOOL:return "boolean";case T_NUM:return "number";case T_STR:return "string";case T_TAB:return "table";default:return "function";}}
static double toNum(State*S,Value v){if(v.tag==T_NUM)return v.u.num;if(v.tag==T_STR){char*e;double d=strtod(v.u.s->p,&e);if(e==v.u.s->p)lx_rt_error(S,"cannot convert string '%s' to number",v.u.s->p);return d;}lx_rt_error(S,"attempt to perform arithmetic on a %s value",lx_typename(v));return 0;}
/* double->int with defined behavior everywhere: NaN->0, out-of-range saturates
   (plain (int) casts of huge doubles are UB and differ between arm64 and x86-64) */
static int num2int(State*S,Value v){double d=toNum(S,v);if(d!=d)return 0;if(d>=2147483648.0)return 2147483647;if(d<=-2147483649.0)return -2147483647-1;return (int)d;}
static bool toBool(Value v){return !(v.tag==T_NIL||(v.tag==T_BOOL&&!v.u.b)); }
static void envDeclareFn(State*S,Env*e,const char*name,Value v){ Str*st=newStr(S,name,strlen(name)); tset(S,e->vars,VSTR(st),v); }
static void envAssignFn(State*S,Env*e,const char*name,Value v){ Str*st=newStr(S,name,strlen(name)); for(Env*p=e;p;p=p->parent){Value*f=tfind(p->vars,VSTR(st));if(f){*f=v;return;}} tset(S,S->globals->vars,VSTR(st),v); }
static Value envGetFn(State*S,Env*e,const char*name){ Str*st=newStr(S,name,strlen(name)); for(Env*p=e;p;p=p->parent){Value*f=tfind(p->vars,VSTR(st));if(f)return *f;} Value*f=tfind(S->globals->vars,VSTR(st)); return f?*f:VNIL; }
/* Str*-keyed variants for the VM env ops — same semantics minus the per-access alloc */
static void envDeclS(State*S,Env*e,Str*st,Value v){ tset(S,e->vars,VSTR(st),v); }
static void envAssignS(State*S,Env*e,Str*st,Value v){ for(Env*p=e;p;p=p->parent){Value*f=tfind(p->vars,VSTR(st));if(f){*f=v;return;}} tset(S,S->globals->vars,VSTR(st),v); }
static Value envGetS(State*S,Env*e,Str*st){ for(Env*p=e;p;p=p->parent){Value*f=tfind(p->vars,VSTR(st));if(f)return *f;} Value*f=tfind(S->globals->vars,VSTR(st)); return f?*f:VNIL; }

static Value eval(State*S,Env*env,Node*e);
static struct Flow exec(State*S,Env*env,Node*st);
static struct Flow execChunk(State*S,Env*env,Node*chunk);
static void dbg_pause_now(State*S,Env*env,int line,int reason);
static Value callValue(State*S,Value f,int argc,Value*argv);
static Str* toStrx(State*S,Value v);
static Proto* bc_build(State*S,Func*fn);
static Value vm_call(State*S,Proto*p,Closure*cl,int argc,Value*argv);
static bool bc_globals_still_global(State*S,Func*fn,Proto*p);

static Str* toStrx(State*S,Value v){
  if(v.tag==T_STR)return v.u.s;
  if(v.tag==T_TAB&&v.u.t->meta){Value m=tget(v.u.t->meta,VSTR(newStr(S,"__tostring",10)));if(m.tag!=T_NIL){Value a=callValue(S,m,1,&v);if(a.tag==T_STR)return a.u.s;}}
  char buf[64];
  switch(v.tag){case T_NIL:return newStr(S,"nil",3);case T_BOOL:return newStr(S,v.u.b?"true":"false",v.u.b?4:5);
    case T_NUM:{int n=snprintf(buf,sizeof(buf),"%.14g",v.u.num);return newStr(S,buf,n);}
    case T_TAB:snprintf(buf,sizeof(buf),"table: %p",(void*)v.u.t);break;
    case T_FN:snprintf(buf,sizeof(buf),"function: %p",(void*)v.u.f);break;
    case T_CFN:snprintf(buf,sizeof(buf),"function: %p",(void*)v.u.c);break;}
  return newStr(S,buf,strlen(buf));
}
static Value indexVal(State*S,Value t,Value k){
  /* Follow the __index chain. A cap guards against a self-referential
   * metatable (a real loop, e.g. setmetatable(t,{__index=t})) which would
   * otherwise hang the interpreter; 1024 is far beyond any sane prototype
   * chain. Lua itself raises "loop in gettable" on cycles. */
  for(int depth=0;depth<1024;depth++){
    if(t.tag==T_TAB){Value v=tget(t.u.t,k);if(v.tag!=T_NIL)return v;if(t.u.t->meta){Value mt=tget(t.u.t->meta,VSTR(newStr(S,"__index",7)));if(mt.tag==T_TAB){t=mt;continue;}if(mt.tag!=T_NIL){Value args[2]={t,k};return callValue(S,mt,2,args);}}return VNIL;}
    if(t.tag==T_STR){ /* method sugar: ("x"):upper() — strings index the string lib */
      Value st=tget(S->globals->vars,VSTR(newStr(S,"string",6)));
      if(st.tag==T_TAB){Value v=tget(st.u.t,k);if(v.tag!=T_NIL)return v;}
      return VNIL;
    }
    lx_rt_error(S,"attempt to index a %s value",lx_typename(t));
  }
  lx_rt_error(S,"loop in gettable (metatable __index chain too deep)");
  return VNIL; /* unreachable: lx_rt_error does not return */
}
static void newIndex(State*S,Value t,Value k,Value v){
  /* Iterative __index-style chain walk: a self-referential __newindex
   * (setmetatable(t,{__newindex=t})) must not recurse the C stack forever;
   * the same 1024-deep cap as indexVal guards it. */
  for(int depth=0;depth<1024;depth++){
    if(t.tag==T_TAB){ if(tget(t.u.t,k).tag!=T_NIL){tset(S,t.u.t,k,v);return;} if(t.u.t->meta){Value mt=tget(t.u.t->meta,VSTR(newStr(S,"__newindex",10)));if(mt.tag==T_TAB){t=mt;continue;}if(mt.tag!=T_NIL){Value args[3]={t,k,v};callValue(S,mt,3,args);return;}} tset(S,t.u.t,k,v);return; }
    lx_rt_error(S,"attempt to index a %s value",lx_typename(t));
  }
  lx_rt_error(S,"'__newindex' chain too long; possible loop");
}
static void evalInto(State*S,Env*env,Node*e,Value*out,int*nout){
  if(e->kind==K_CALL||e->kind==K_METHODCALL||e->kind==K_VARARG){ Value v=eval(S,env,e); (void)v; int n=S->nret; if(n>64)n=64; for(int i=0;i<n;i++)out[i]=S->retbuf[i]; *nout=n; return; }
  out[0]=eval(S,env,e);*nout=1;
}
/* capacity-aware variant of evalInto: expressions beyond the destination's
 * room are still evaluated (side effects) but their values are dropped —
 * this is what bounds K_LOCAL/K_ASSIGN/K_RET lists and call args. When
 * `over` is given, dropped values set *over=1 so callers can error
 * (buildArgs → "too many arguments", matching the VM's arity check). */
static void mvInto(State*S,Env*env,Node*e,Value*out,int cap,int*pn,int*over){
  if(*pn>=cap){ eval(S,env,e); if(over)*over=1; return; }
  if(e->kind==K_CALL||e->kind==K_METHODCALL||e->kind==K_VARARG){
    Value tmp[64]; int m; evalInto(S,env,e,tmp,&m);
    for(int i=0;i<m;i++){ if(*pn<cap) out[(*pn)++]=tmp[i]; else if(over)*over=1; }
    return;
  }
  out[(*pn)++]=eval(S,env,e);
}
#define LX_MAX_ARGS 64
static int buildArgs(State*S,Env*env,Node**args,int n,Value*argv,int cap){
  int na=0, over=0;
  for(int i=0;i<n;i++){
    if(i==n-1) mvInto(S,env,args[i],argv,cap,&na,&over);
    else { Value v=eval(S,env,args[i]); if(na<cap) argv[na++]=v; else over=1; }
  }
  if(over) lx_rt_error(S,"too many arguments");
  return na;
}
/* Fill out[cap] from an expression list where only the LAST expression may
 * expand multi-values (Lua semantics); values past cap are dropped but the
 * expressions are still evaluated for their side effects. */
static void mvList(State*S,Env*env,Node**exprs,int n,Value*out,int cap,int*pn){
  for(int i=0;i<n;i++){
    if(i==n-1){ Value tmp[64]; int m; evalInto(S,env,exprs[i],tmp,&m);
      for(int j=0;j<m&&*pn<cap;j++) out[(*pn)++]=tmp[j];
    } else if(*pn<cap) out[(*pn)++]=eval(S,env,exprs[i]);
    else eval(S,env,exprs[i]);
  }
}
static void dbg_push_frame(State*S,const char*name,int call_line,int def_line){
  if(!S||S->nstack>=64) return;
  int i=S->nstack++;
  snprintf(S->call_stack[i].name,sizeof(S->call_stack[i].name),"%s",name&&name[0]?name:"fn");
  S->call_stack[i].line=call_line>0?call_line:S->curLine;
  S->call_stack[i].def_line=def_line>0?def_line:0;
}
static void dbg_pop_frame(State*S){ if(S&&S->nstack>0) S->nstack--; }
static void dbg_touch_top(State*S,int line){ if(S&&S->nstack>0&&line>0) S->call_stack[S->nstack-1].line=line; }
/* Max nested callValue frames. Each frame carries eval()'s arg buffer plus
 * exec() frames (~5KB), so 160 stays safely under a 1MB thread stack while
 * covering any sane script recursion. Errors unwinding through a setjmp
 * boundary restore the saved depth there. */
#define LX_MAX_CALL_DEPTH 160
static Value callValue(State*S,Value f,int argc,Value*argv){
  if(S->call_depth>=LX_MAX_CALL_DEPTH) lx_rt_error(S,"stack overflow");
  if(f.tag==T_CFN){
    const char*nm=f.u.c&&f.u.c->name?f.u.c->name:"[C]";
    if(S->nstack>0) dbg_touch_top(S,S->curLine);
    dbg_push_frame(S,nm,S->curLine,0);
    S->call_depth++;
    Value r=f.u.c->fn(S,argc,argv);
    S->call_depth--;
    dbg_pop_frame(S);
    return r;
  }
  if(f.tag==T_FN){
    S->call_depth++;
    Func*fn=f.u.f->f;
    /* Bytecode VM (Phase 1b v0): compile closure-free function bodies on first
     * call; anything else (debug sessions, varargs, nested defs, upvalues)
     * transparently uses the tree-walking path below. */
    if(!S->debug_enabled && !getenv("LUAX_NO_BC")){
      if(!fn->bc && !fn->bc_tried){ fn->bc=bc_build(S,fn); fn->bc_tried=1; }
      if(fn->bc){
        if(!bc_globals_still_global(S,fn,fn->bc)){ S->bc_fallbacks++; }
        else { S->bc_calls++; Value r=vm_call(S,fn->bc,f.u.f,argc,argv); S->call_depth--; return r; }
      } else S->bc_fallbacks++;
    }
    Env*e=newEnv(S,fn->env);
    char nm[48];
    if(fn->line>0) snprintf(nm,sizeof(nm),"fn@%d",fn->line); else snprintf(nm,sizeof(nm),"fn");
    if(S->nstack>0) dbg_touch_top(S,S->curLine);
    dbg_push_frame(S,nm,S->curLine,fn->line);
    S->cur_env=e;
    for(int i=0;i<fn->nparam;i++) envDeclareFn(S,e,fn->params[i], i<argc?argv[i]:VNIL);
    if(fn->vararg){Table*va=newTable(S);for(int i=fn->nparam;i<argc;i++)tset(S,va,VNUM(i-fn->nparam+1),argv[i]); envDeclareFn(S,e,"...",VTAB(va));}
    struct Flow fl=F_NORMAL; Node*blk=fn->body;
    for(int i=0;i<blk->nlist;i++){ fl=exec(S,e,blk->list[i]); if(fl.kind==1)break; if(fl.kind==2)lx_rt_error(S,"'break' outside a loop"); }
    S->nret=fl.nret; for(int i=0;i<fl.nret&&i<64;i++)S->retbuf[i]=fl.rets[i];
    S->call_depth--;
    dbg_pop_frame(S);
    return fl.nret?fl.rets[0]:VNIL;
  }
  lx_rt_error(S,"attempt to call a %s value",lx_typename(f)); return VNIL;
}
static Value eval(State*S,Env*env,Node*e){
  if(e->line>0) S->curLine=e->line;
  switch(e->kind){
  case K_NIL:return VNIL; case K_TRUE:return VBOOL(1); case K_FALSE:return VBOOL(0);
  case K_NUM:return VNUM(e->num); case K_STR:return VSTR(e->str);
  case K_VARARG:{ Value v=envGetFn(S,env,"..."); if(v.tag==T_TAB){ int n=tlen(v.u.t); S->nret=n; for(int i=0;i<n&&i<64;i++)S->retbuf[i]=tget(v.u.t,VNUM(i+1)); return n?S->retbuf[0]:VNIL;} S->nret=0; return VNIL; }
  case K_NAME:return envGetFn(S,env,e->name);
  case K_PAREN:{ Value v=eval(S,env,e->a); S->nret=1; S->retbuf[0]=v; return v; }
  case K_INDEX:{ Value t=eval(S,env,e->a); Value k=eval(S,env,e->b); return indexVal(S,t,k); }
  case K_CALL:{ Value f=eval(S,env,e->a); Value argv[LX_MAX_ARGS]; int na=buildArgs(S,env,e->list,e->nlist,argv,LX_MAX_ARGS); return callValue(S,f,na,argv); }
  case K_METHODCALL:{ Value o=eval(S,env,e->a); Value f=indexVal(S,o,VSTR(newStr(S,e->method,strlen(e->method)))); Value argv[LX_MAX_ARGS]; argv[0]=o; int na=1+buildArgs(S,env,e->list,e->nlist,argv+1,LX_MAX_ARGS-1); return callValue(S,f,na,argv); }
  case K_FUNC:{ Func*fn=xalloc(S,sizeof(Func)); fn->nparam=e->nnames; fn->params=e->names; fn->vararg=e->vararg; fn->body=e->body; fn->env=env; fn->line=e->line>0?e->line:(e->body&&e->body->line>0?e->body->line:S->curLine); fn->bc=NULL; fn->bc_tried=0; Closure*cl=xalloc(S,sizeof(Closure)); cl->f=fn; return VFN(cl); }
  case K_TABLE:{ Table*t=newTable(S); int idx=1; for(int i=0;i<e->nlist;i++){ Node*f=e->list[i]; if(f->isKv){ tset(S,t,eval(S,env,f->a),eval(S,env,f->b)); } else { int m; Value tmp[64]; evalInto(S,env,f->a,tmp,&m); for(int j=0;j<m;j++)tset(S,t,VNUM(idx++),tmp[j]); if(m==0)idx++; } } return VTAB(t); }
  case K_UNOP:{ Value a=eval(S,env,e->a); switch(e->op){ case'-':return VNUM(-toNum(S,a)); case'#':{ if(a.tag==T_STR)return VNUM((double)a.u.s->len); if(a.tag==T_TAB){ if(a.u.t->meta){Value m=tget(a.u.t->meta,VSTR(newStr(S,"__len",5)));if(m.tag!=T_NIL)return callValue(S,m,1,&a);} return VNUM((double)tlen(a.u.t));} lx_rt_error(S,"attempt to get length of a %s value",lx_typename(a)); } case T_NOT:return VBOOL(!toBool(a)); case'~':return VNUM((double)(~(int64_t)toNum(S,a))); } return VNIL; }
  case K_BINOP:{ int op=e->op;
    if(op==T_AND){ Value a=eval(S,env,e->a); if(!toBool(a))return a; return eval(S,env,e->b); }
    if(op==T_OR){ Value a=eval(S,env,e->a); if(toBool(a))return a; return eval(S,env,e->b); }
    Value a=eval(S,env,e->a), b=eval(S,env,e->b);
    if(op==T_CONCAT){ Str*sa=toStrx(S,a),*sb=toStrx(S,b); char*buf=xalloc(S,sa->len+sb->len+1); memcpy(buf,sa->p,sa->len); memcpy(buf+sa->len,sb->p,sb->len); return VSTR(newStr(S,buf,sa->len+sb->len)); }
    if(op==T_EQ||op==T_NE){ bool eq=valEq(a,b); if(op==T_EQ)return VBOOL(eq); return VBOOL(!eq); }
    if(op=='<'||op=='>'||op==T_LE||op==T_GE){ double x=toNum(S,a),y=toNum(S,b); switch(op){case'<':return VBOOL(x<y);case'>':return VBOOL(x>y);case T_LE:return VBOOL(x<=y);case T_GE:return VBOOL(x>=y);} }
    if(op=='^'){return VNUM(pow(toNum(S,a),toNum(S,b)));}
    double x=toNum(S,a),y=toNum(S,b); switch(op){case'+':return VNUM(x+y);case'-':return VNUM(x-y);case'*':return VNUM(x*y);case'/':return VNUM(x/y);case'%':return VNUM(x-floor(x/y)*y);case T_IDIV:return VNUM(floor(x/y));
      case'|':return VNUM((double)((int64_t)x|(int64_t)y));case'&':return VNUM((double)((int64_t)x&(int64_t)y));case'~':return VNUM((double)((int64_t)x^(int64_t)y));case T_SHL:return VNUM((double)((int64_t)x<<((int64_t)y&63)));case T_SHR:return VNUM((double)((int64_t)x>>((int64_t)y&63)));} return VNIL; }
  }
  /* Defensive: the switch above is exhaustive over every AST kind that can
   * reach eval (all expression kinds; statement kinds are handled by exec and
   * never passed here). This line is therefore unreachable by construction —
   * it exists only so the compiler sees a value-producing path after a switch
   * and so a future invariant break surfaces as a clear error instead of UB. */
  lx_rt_error(S,"internal error: bad expression node");
  return VNIL; /* unreachable: lx_rt_error does not return */
}
static void assignTarget(State*S,Env*env,Node*t,Value v){
  if(t->kind==K_NAME) envAssignFn(S,env,t->name,v);
  else if(t->kind==K_INDEX){ Value o=eval(S,env,t->a); Value k=eval(S,env,t->b); newIndex(S,o,k,v); }
  else lx_rt_error(S,"cannot assign to this expression");
}
#define STEP() do{ if(S->cancel_flag) lx_rt_error(S,"cancelled by user"); if(S->step_limit>0 && ++S->steps>S->step_limit) lx_rt_error(S,"execution step limit exceeded (possible infinite loop)"); }while(0)

static int bp_index(State*S,int line){
  for(int i=0;i<S->nbp;i++) if(S->breakpoints[i].line==line) return i;
  return -1;
}
static int bp_has(State*S,int line){ return bp_index(S,line)>=0; }
static void dbg_logpoint(State*S,Env*env,int line,const char*msg);
static int dbg_eval_value(State*S,Env*env,const char*expr,Value*out,char*err,int errlen){
  if(out) *out=VNIL;
  if(!expr||!expr[0]){ if(err&&errlen)snprintf(err,errlen,"empty"); return 0; }
  char src[200];
  int n=snprintf(src,sizeof(src),"return (%s)",expr);
  if(n<=0||n>=(int)sizeof(src)){ if(err&&errlen)snprintf(err,errlen,"expr too long"); return 0; }
  jmp_buf outer; memcpy(&outer,&S->err,sizeof(outer));
  int depth=S->nstack;
  int vdepth=S->vtop;
  int cdepth=S->call_depth;
  int saved_steps=S->steps;
  long saved_limit=S->step_limit;
  S->dbg_eval_depth++;
  S->step_limit = S->step_limit>0 ? S->step_limit : 100000;
  int ok=0;
  if(setjmp(S->err)==0){
    Node*chunk=parse(S,src,strlen(src));
    struct Flow fl=execChunk(S,env,chunk);
    Value v=VNIL;
    if(fl.nret>0) v=fl.rets[0];
    if(out) *out=v;
    ok=1;
    if(err&&errlen) err[0]=0;
  }else{
    if(err&&errlen) snprintf(err,errlen,"%s",S->errmsg);
    ok=0;
  }
  memcpy(&S->err,&outer,sizeof(outer));
  S->nstack=depth;
  S->vtop=vdepth;
  S->call_depth=cdepth;
  S->steps=saved_steps;
  S->step_limit=saved_limit;
  S->dbg_eval_depth--;
  return ok;
}
static int dbg_eval_cond(State*S,Env*env,const char*cond){
  if(!cond||!cond[0]) return 1;
  Value v; char err[128];
  if(!dbg_eval_value(S,env,cond,&v,err,sizeof(err))) return 0;
  return toBool(v)?1:0;
}
static void dbg_json_escape(char**buf,size_t*sz,size_t*used,const char*s,size_t n){
  for(size_t i=0;i<n;i++){
    unsigned char c=(unsigned char)s[i];
    if(c=='"'||c=='\\'){ buf_append(buf,sz,used,"\\",1); char x=(char)c; buf_append(buf,sz,used,&x,1); }
    else if(c=='\n') buf_append(buf,sz,used,"\\n",2);
    else if(c=='\r') buf_append(buf,sz,used,"\\r",2);
    else if(c=='\t') buf_append(buf,sz,used,"\\t",2);
    else if(c<0x20){ char e[8]; int m=snprintf(e,sizeof(e),"\\u%04x",c); buf_append(buf,sz,used,e,m); }
    else { char x=(char)c; buf_append(buf,sz,used,&x,1); }
  }
}
static void dbg_append_value(State*S,char**buf,size_t*sz,size_t*used,Value v,int depth){
  switch(v.tag){
    case T_NIL: buf_append(buf,sz,used,"null",4); break;
    case T_BOOL: buf_append(buf,sz,used,v.u.b?"true":"false",v.u.b?4:5); break;
    case T_NUM:{ char b[64]; int n=snprintf(b,sizeof(b),"%.14g",v.u.num); buf_append(buf,sz,used,b,n); } break;
    case T_STR:{
      buf_append(buf,sz,used,"\"",1);
      dbg_json_escape(buf,sz,used,v.u.s->p,v.u.s->len);
      buf_append(buf,sz,used,"\"",1);
    } break;
    case T_TAB:{
      if(depth<=0){ buf_append(buf,sz,used,"\"[table]\"",8); break; }
      buf_append(buf,sz,used,"{",1);
      int first=1; int count=0;
      for(int i=0;i<v.u.t->cap && count<32;i++){
        if(!v.u.t->e[i].used) continue;
        Value k=v.u.t->e[i].k; if(k.tag!=T_STR) continue;
        if(!first) buf_append(buf,sz,used,",",1); first=0; count++;
        buf_append(buf,sz,used,"\"",1);
        dbg_json_escape(buf,sz,used,k.u.s->p,k.u.s->len);
        buf_append(buf,sz,used,"\":",2);
        dbg_append_value(S,buf,sz,used,v.u.t->e[i].v,depth-1);
      }
      buf_append(buf,sz,used,"}",1);
    } break;
    case T_FN: case T_CFN: buf_append(buf,sz,used,"\"[function]\"",12); break;
  }
}
static void dbg_logpoint(State*S,Env*env,int line,const char*msg){
  if(!msg||!msg[0]) return;
  char*linebuf=NULL; size_t lsz=0, lused=0;
  char head[48];
  int hn=snprintf(head,sizeof(head),"[log] L%d: ",line>0?line:0);
  if(hn>0) buf_append(&linebuf,&lsz,&lused,head,(size_t)hn);
  const char*p=msg;
  while(*p){
    if(*p=='{'){
      const char*q=strchr(p+1,'}');
      if(q && q-p-1>0 && q-p-1<120){
        char expr[128]; size_t el=(size_t)(q-p-1);
        memcpy(expr,p+1,el); expr[el]=0;
        Value v; char err[96];
        if(dbg_eval_value(S,env,expr,&v,err,sizeof(err))){
          char*tmp=NULL; size_t tsz=0, tused=0;
          dbg_append_value(S,&tmp,&tsz,&tused,v,1);
          if(tmp){ buf_append(&linebuf,&lsz,&lused,tmp,tused); free(tmp); }
        }else{
          buf_append(&linebuf,&lsz,&lused,"?",1);
        }
        p=q+1; continue;
      }
    }
    buf_append(&linebuf,&lsz,&lused,p,1);
    p++;
  }
  buf_append(&linebuf,&lsz,&lused,"\n",1);
  if(linebuf){
    buf_append(&S->out,&S->outsz,&S->outused,linebuf,lused);
    fwrite(linebuf,1,lused,stdout);
    free(linebuf);
  }
}

static void dbg_capture_stack(State*S){
  free(S->dbg_stack); S->dbg_stack=NULL;
  char*buf=NULL; size_t sz=0, used=0;
  buf_append(&buf,&sz,&used,"[",1);
  int first=1;
  for(int i=S->nstack-1;i>=0;i--){
    if(!first) buf_append(&buf,&sz,&used,",",1); first=0;
    buf_append(&buf,&sz,&used,"{\"name\":\"",9);
    dbg_json_escape(&buf,&sz,&used,S->call_stack[i].name,strlen(S->call_stack[i].name));
    char b[64];
    int n=snprintf(b,sizeof(b),"\",\"line\":%d,\"def\":%d}",S->call_stack[i].line,S->call_stack[i].def_line);
    buf_append(&buf,&sz,&used,b,(size_t)n);
  }
  buf_append(&buf,&sz,&used,"]",1);
  S->dbg_stack=buf;
}

static void dbg_capture_locals(State*S,Env*env){
  free(S->dbg_locals); S->dbg_locals=NULL;
  char*buf=NULL; size_t sz=0, used=0;
  buf_append(&buf,&sz,&used,"[",1);
  int first=1; int count=0;
  for(Env*e=env;e && count<64;e=e->parent){
    if(e==S->globals) break;
    Table*t=e->vars; if(!t) continue;
    for(int i=0;i<t->cap && count<64;i++){
      if(!t->e[i].used) continue;
      Value k=t->e[i].k; if(k.tag!=T_STR) continue;
      if(!first) buf_append(&buf,&sz,&used,",",1); first=0; count++;
      buf_append(&buf,&sz,&used,"{\"name\":\"",9);
      dbg_json_escape(&buf,&sz,&used,k.u.s->p,k.u.s->len);
      buf_append(&buf,&sz,&used,"\",\"value\":",10);
      dbg_append_value(S,&buf,&sz,&used,t->e[i].v,2);
      buf_append(&buf,&sz,&used,"}",1);
    }
  }
  buf_append(&buf,&sz,&used,"]",1);
  S->dbg_locals=buf;
}
static void dbg_pause_now(State*S,Env*env,int line,int reason){
  if(!S||!env) return;
  if(line<=0) line=1;
  S->curLine=line;
  S->cur_env=env;
  S->pause_line=line;
  S->pause_reason=reason;
  dbg_touch_top(S,line);
  dbg_capture_locals(S,env);
  dbg_capture_stack(S);
  pthread_mutex_lock(&S->dbg_mu);
  S->dbg_paused=1;
  S->dbg_cmd=0;
  while(S->dbg_paused && S->dbg_cmd==0){
    pthread_cond_wait(&S->dbg_cv,&S->dbg_mu);
  }
  int cmd=S->dbg_cmd;
  S->dbg_cmd=0;
  S->dbg_paused=0;
  S->pause_reason=0;
  if(cmd==2){ S->step_mode=1; }
  else if(cmd==4){ S->step_mode=2; }
  else if(cmd==1){ S->step_mode=0; S->step_out_depth=0; }
  pthread_mutex_unlock(&S->dbg_mu);
  if(cmd==3 && reason!=1) lx_rt_error(S,"debug stopped by user");
}
static void lx_line_hook(State*S,Env*env,int line){
  if(!S->debug_enabled || line<=0 || S->dbg_eval_depth) return;
  pthread_mutex_lock(&S->dbg_mu);
  int mode=S->step_mode;
  int out_depth=S->step_out_depth;
  int bpi=bp_index(S,line);
  char cond[96]; cond[0]=0;
  char logmsg[128]; logmsg[0]=0;
  int log_only=0;
  if(bpi>=0){
    snprintf(cond,sizeof(cond),"%s",S->breakpoints[bpi].cond);
    snprintf(logmsg,sizeof(logmsg),"%s",S->breakpoints[bpi].logmsg);
    log_only=S->breakpoints[bpi].log_only;
  }
  pthread_mutex_unlock(&S->dbg_mu);
  int hit=0;
  if(mode==1) hit=1;
  else if(mode==2 && S->nstack<=out_depth) hit=1;
  else if(bpi>=0) hit=dbg_eval_cond(S,env,cond);
  if(!hit) return;
  if(logmsg[0]) dbg_logpoint(S,env,line,logmsg);
  if(log_only && mode==0) return;
  dbg_pause_now(S,env,line,0);
}

static struct Flow exec(State*S,Env*env,Node*st){
  S->cur_env=env;
  if(st->line>0){ S->curLine=st->line; lx_line_hook(S,env,st->line); }
  STEP();
  switch(st->kind){
  case K_LOCAL:{ Value vals[64]; int nval=0;
      if(st->nlist) mvList(S,env,st->list,st->nlist,vals,64,&nval);
      for(int i=0;i<st->nnames;i++) envDeclareFn(S,env,st->names[i], i<nval?vals[i]:VNIL);
      break; }
  case K_ASSIGN:{ Value vs[64]; int nv=0; if(st->nlist2) mvList(S,env,st->list2,st->nlist2,vs,64,&nv); for(int i=0;i<st->nlist;i++) assignTarget(S,env,st->list[i], i<nv?vs[i]:VNIL); break; }
  case K_CALLSTAT:{ int dummy; Value tmp[1]; evalInto(S,env,st->a,tmp,&dummy); break; }
  case K_DO:{ Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; for(int i=0;i<st->body->nlist;i++){fl=exec(S,ne,st->body->list[i]);if(fl.kind)return fl;} return F_NORMAL; }
  case K_IF:{ if(toBool(eval(S,env,st->a))){ Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; for(int i=0;i<st->body->nlist;i++){fl=exec(S,ne,st->body->list[i]);if(fl.kind)return fl;} return F_NORMAL; }
      for(int i=0;i<st->nlist;i++){ Node*eli=st->list[i]; if(toBool(eval(S,env,eli->a))){ Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; for(int j=0;j<eli->body->nlist;j++){fl=exec(S,ne,eli->body->list[j]);if(fl.kind)return fl;} return F_NORMAL; } }
      if(st->b){ Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; for(int i=0;i<st->b->nlist;i++){fl=exec(S,ne,st->b->list[i]);if(fl.kind)return fl;} } break; }
  case K_WHILE:{ while(toBool(eval(S,env,st->a))){ STEP(); Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; bool brk=false; for(int i=0;i<st->body->nlist;i++){fl=exec(S,ne,st->body->list[i]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; } break; }
  case K_REPEAT:{ while(1){ STEP(); Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; bool brk=false; for(int i=0;i<st->body->nlist;i++){fl=exec(S,ne,st->body->list[i]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; /* the until condition can see locals declared in the loop body (Lua semantics) */ if(toBool(eval(S,ne,st->a)))break; } break; }
  case K_NFOR:{ double s=toNum(S,eval(S,env,st->a)), en=toNum(S,eval(S,env,st->b)), step=st->c?toNum(S,eval(S,env,st->c)):1;
      if(step>0){ for(double i=s;i<=en;i+=step){ STEP(); Env*ne=newEnv(S,env); envDeclareFn(S,ne,st->name,VNUM(i)); struct Flow fl=F_NORMAL; bool brk=false; for(int j=0;j<st->body->nlist;j++){fl=exec(S,ne,st->body->list[j]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; } }
      else { for(double i=s;i>=en;i+=step){ STEP(); Env*ne=newEnv(S,env); envDeclareFn(S,ne,st->name,VNUM(i)); struct Flow fl=F_NORMAL; bool brk=false; for(int j=0;j<st->body->nlist;j++){fl=exec(S,ne,st->body->list[j]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; } } break; }
  case K_GFOR:{ Value fs[8]; int nf=0; if(st->nlist) mvList(S,env,st->list,st->nlist,fs,8,&nf); if(nf<1)break;
      for(int i=nf;i<3;i++)fs[i]=VNIL; /* pad iterator triple — keeps parity with the VM's EXPAND pad */
      Value it=fs[0],state=fs[1],ctrl=fs[2];
      while(1){ STEP(); Value args[2]={state,ctrl}; Value r=callValue(S,it,2,args); int n=S->nret; if(n==0||(n>=1&&r.tag==T_NIL))break; ctrl=r;
        Value vals[8]; vals[0]=r; for(int i=1;i<n&&i<8;i++)vals[i]=S->retbuf[i]; int nv=n>0?n:1;
        Env*ne=newEnv(S,env); for(int i=0;i<st->nnames;i++)envDeclareFn(S,ne,st->names[i], i<nv?vals[i]:VNIL);
        struct Flow fl=F_NORMAL; bool brk=false; for(int j=0;j<st->body->nlist;j++){fl=exec(S,ne,st->body->list[j]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; }
      break; }
  case K_RET:{ struct Flow fl; fl.kind=1; fl.nret=0; if(st->nlist) mvList(S,env,st->list,st->nlist,fl.rets,64,&fl.nret); return fl; }
  case K_BREAK:{ struct Flow fl; fl.kind=2; fl.nret=0; return fl; }
  case K_FUNCDECL:{ Node*t=st->a; Value fn=eval(S,env,st->body); if(st->isLocal&&t->kind==K_NAME) envDeclareFn(S,env,t->name,fn); else assignTarget(S,env,t,fn); break; }
  }
  return F_NORMAL;
}
static struct Flow execChunk(State*S,Env*env,Node*chunk){
  /* Phase 1b: the main chunk compiles through the same path as function
   * bodies — wrapped in a synthetic 0-param Func so top-level statements
   * (loops, table building, module bodies, view defs) run on the VM too.
   * Falls back to the tree-walk below whenever the body is not compilable,
   * debugging is on, or LUAX_NO_BC is set — identical gates to callValue. */
  if(env && !S->debug_enabled && !getenv("LUAX_NO_BC")){
    Func df; memset(&df,0,sizeof(df));
    df.body=chunk; df.env=env; df.line=chunk&&chunk->line>0?chunk->line:1;
    Proto*p=bc_build(S,&df);
    if(p&&bc_globals_still_global(S,&df,p)){
      Closure cl; cl.f=&df;
      S->bc_calls++;
      vm_call(S,p,&cl,0,NULL);
      struct Flow fl=F_NORMAL;
      fl.nret=S->nret; for(int i=0;i<fl.nret&&i<64;i++)fl.rets[i]=S->retbuf[i];
      fl.kind=fl.nret>0?1:0;
      return fl;
    }
    S->bc_fallbacks++;
  }
  struct Flow fl=F_NORMAL; for(int i=0;i<chunk->nlist;i++){fl=exec(S,env,chunk->list[i]);if(fl.kind)break;} return fl;
}

/* ---------- bytecode VM (Phase 1b v0) ----------
 * Compiles closure-free function bodies to register bytecode on first call and
 * runs them on a stack VM. Anything the compiler cannot represent — nested
 * function definitions (upvalue capture), varargs, free variables — keeps using
 * the tree-walking interpreter above, so semantics can only gain speed, never
 * diverge on those constructs.
 * Parity rules mirrored from exec()/eval():
 *   - every positional table-constructor field expands multi-values (engine
 *     behavior; Lua proper only expands the last)
 *   - numeric for with step<=0 takes the descending branch (step 0 loops are
 *     stopped by the step limit exactly like the tree-walker)
 *   - comparisons go through toNum (no string ordering)
 *   - AND/OR yield the deciding operand value, not a normalized bool
 *   - names resolving outside locals/globals (dynamic-scope upvalues) bail;
 *     "global" names are re-validated per call in case an enclosing tree-walk
 *     scope shadowed them after first compilation */
enum {
  BC_LOADK,BC_LOADNIL,BC_LOADBOOL,BC_MOVE,
  BC_GETGLOBAL,BC_SETGLOBAL,BC_GETTABLE,BC_SETTABLE,BC_GETFIELD,BC_SETFIELD,
  BC_ADD,BC_SUB,BC_MUL,BC_DIV,BC_MOD,BC_POW,BC_IDIV,BC_BAND,BC_BOR,BC_BXOR,BC_SHL,BC_SHR,
  BC_UNM,BC_BNOT,BC_NOT,BC_LEN,BC_CONCAT,BC_EQ,BC_LT,BC_LE,
  BC_TEST,BC_TESTN,BC_JMP,BC_CALL,BC_RETURN,BC_EXPAND,BC_NEWTABLE,BC_TSETMULT,BC_INC,
  BC_FORPREP,BC_FORLOOP,BC_GFORPREP,BC_GFORLOOP,
  BC_ENVOPEN,BC_ENVCLOSE,BC_DECL,BC_GETENV,BC_SETENV,BC_CLOSURE
};
typedef struct { unsigned char op,a,b,c; } BIns;   /* 4-byte insn; immediates live in Proto.imm[pc] (u16: k-idx or i16 jump delta) */
struct Proto { BIns* code; int ncode; unsigned short* imm; Value* k; int nk; int* lines; int* gk; int ngk; int nparam; int maxstack; int defline; int uses_env; Node** subs; int nsubs; };

#define BC_MAXREG 200
typedef struct {
  State*S; Func*fn;
  BIns* code; int ncode,capcode;
  int* lines;
  int* imm;
  Value* k; int nk,capk;
  int gk[64]; int ngk;
  int reg,maxreg;
  struct { char*name; int reg; } loc[256]; int nloc;
  int scope[40]; int scopereg[40]; int depth;
  struct { int brk[32]; int nbrk; int envmark; } loops[16]; int nloops;
  int curline;
  int failed;
  int capmode;                      /* function contains nested funcs: locals live in scope envs */
  Node* subs[64]; int nsubs;
} Bc;

static const char* bc_opname(int op){
  static const char* N[]={"LOADK","LOADNIL","LOADBOOL","MOVE","GETGLOBAL","SETGLOBAL","GETTABLE","SETTABLE","GETFIELD","SETFIELD",
    "ADD","SUB","MUL","DIV","MOD","POW","IDIV","BAND","BOR","BXOR","SHL","SHR","UNM","BNOT","NOT","LEN","CONCAT","EQ","LT","LE",
    "TEST","TESTN","JMP","CALL","RETURN","EXPAND","NEWTABLE","TSETMULT","INC","FORPREP","FORLOOP","GFORPREP","GFORLOOP",
    "ENVOPEN","ENVCLOSE","DECL","GETENV","SETENV","CLOSURE"};
  return op>=0&&(size_t)op<sizeof(N)/sizeof(*N)?N[op]:"?";
}
static int bc_emit(Bc*C,int op,int a,int b,int c,int imm){
  if(C->ncode>=C->capcode){ C->capcode=C->capcode?C->capcode*2:64; C->code=realloc(C->code,(size_t)C->capcode*sizeof(BIns)); C->lines=realloc(C->lines,(size_t)C->capcode*sizeof(int)); C->imm=realloc(C->imm,(size_t)C->capcode*sizeof(int)); }
  C->code[C->ncode]=(BIns){(unsigned char)op,(unsigned char)a,(unsigned char)b,(unsigned char)c};
  C->imm[C->ncode]=imm;
  C->lines[C->ncode]=C->curline;
  if(C->reg>C->maxreg)C->maxreg=C->reg;
  return C->ncode++;
}
static int bc_reg(Bc*C){ if(C->failed)return 0; if(C->reg>=BC_MAXREG){C->failed=1;return 0;} return C->reg++; }
/* watermark only ever rises within a block; scopes release via bc_endscope */
static void bc_raise(Bc*C,int r){ if(!C->failed && r>C->reg)C->reg=r; }
static int bc_k(Bc*C,Value v){ if(C->nk>=0xFFFF){C->failed=1;return 0;} if(C->nk>=C->capk){C->capk=C->capk?C->capk*2:16;C->k=realloc(C->k,(size_t)C->capk*sizeof(Value));} C->k[C->nk]=v; return C->nk++; }
static int bc_kstr(Bc*C,const char*s){ Str*st=newStr(C->S,s,strlen(s)); return bc_k(C,VSTR(st)); }
static int bc_jmp(Bc*C){ return bc_emit(C,BC_JMP,0,0,0,0); }
/* imm is a u16 slot: jump deltas must fit i16, else soft-fail to tree-walk */
static void bc_patch(Bc*C,int j,int target){ int d=target-(j+1); if(d>32767||d<-32768){C->failed=1;return;} C->imm[j]=d; }
/* capmode mirrors the tree-walker's newEnv points: every non-root scope opens a
 * runtime env at entry and closes it at scope end; the function's own frame env
 * is created by vm_call so the root scope emits neither */
static void bc_scope(Bc*C){ if(C->depth>=40){C->failed=1;return;} if(C->capmode&&C->depth>0)bc_emit(C,BC_ENVOPEN,0,0,0,0); C->scope[C->depth]=C->nloc; C->scopereg[C->depth]=C->reg; C->depth++; }
static void bc_endscope(Bc*C){ if(C->capmode&&C->depth>1)bc_emit(C,BC_ENVCLOSE,0,0,0,0); C->depth--; if(C->depth<40){ C->nloc=C->scope[C->depth]; C->reg=C->scopereg[C->depth]; } }
static void bc_pushloop(Bc*C){ if(C->nloops>=16){C->failed=1;return;} C->loops[C->nloops].nbrk=0; C->loops[C->nloops].envmark=C->depth; C->nloops++; }
static void bc_poploop(Bc*C,int exitat){ C->nloops--; for(int i=0;i<C->loops[C->nloops].nbrk;i++) bc_patch(C,C->loops[C->nloops].brk[i],exitat); }

static int bc_local(Bc*C,const char*name){ if(C->capmode)return -1; for(int i=C->nloc-1;i>=0;i--) if(!strcmp(C->loc[i].name,name)) return C->loc[i].reg; return -1; }
static int bc_sub(Bc*C,Node*e){ if(C->nsubs>=64){C->failed=1;return 0;} C->subs[C->nsubs]=e; return C->nsubs++; }
static int bc_is_upvalue(Bc*C,const char*name){
  Str tmp; tmp.len=strlen(name); tmp.p=(char*)name;
  Value k; k.tag=T_STR; k.u.s=&tmp;
  for(Env*e=C->fn->env;e;e=e->parent){ if(e==C->S->globals)break; if(tfind(e->vars,k))return 1; }
  return 0;
}
/* does the body contain nested function definitions? If so the function runs
 * in capmode: locals become env-resident so BC_CLOSURE can capture them */
static int bc_has_nested(Node*n){
  if(!n)return 0;
  switch(n->kind){ case K_FUNC:case K_FUNCDECL:return 1; default:break; }
  if(bc_has_nested(n->a)||bc_has_nested(n->b)||bc_has_nested(n->c)||bc_has_nested(n->body))return 1;
  for(int i=0;i<n->nlist;i++)if(bc_has_nested(n->list[i]))return 1;
  for(int i=0;i<n->nlist2;i++)if(bc_has_nested(n->list2[i]))return 1;
  return 0;
}
static int bc_ismulti(Node*e){ return e&&e->kind==K_CALL; } /* METHODCALL yields one value; VARARG pre-bailed */

static void bc_expr(Bc*C,Node*e,int dst);
/* mode: 1 = multret (c=0, results counted by mrc), 0 = single result (c=2),
 * 2 = discard (c=1, like Lua's CALL with 0 results) */
static void bc_call_compile(Bc*C,Node*e,int base,int mode){
  if(C->failed)return;
  int cres = mode==1?0 : mode==2?1 : 2;
  if(e->line>0)C->curline=e->line;
  if(e->kind==K_METHODCALL){
    /* Layout: f at base (so the CALL result lands at base, where consumers
     * read it), obj/self at base+1, args from base+2. The old layout put f
     * at base+1 and the result followed it, so a consumer reading the call
     * result at base got the OBJECT instead of the call's return value —
     * reachable whenever a method call's result is consumed inside a
     * compiled function body. */
    bc_raise(C,base+2);
    bc_expr(C,e->a,base+1);                                /* obj = self at base+1 */
    int mk=bc_kstr(C,e->method);
    bc_emit(C,BC_GETFIELD,base,base+1,0,mk);               /* f at base */
    bc_raise(C,base+3);
    Node**A=e->list;int n=e->nlist;
    int lastmulti=n>0&&bc_ismulti(A[n-1]);
    for(int i=0;i<n-(lastmulti?1:0);i++){ bc_expr(C,A[i],base+2+i); bc_raise(C,base+3+i); }
    if(lastmulti){ bc_call_compile(C,A[n-1],base+2+n-1,1); bc_raise(C,C->reg+64); }
    bc_emit(C,BC_CALL,base,(n-(lastmulti?1:0))+2,cres,lastmulti);
    return;
  }
  /* K_CALL: f at base, args from base+1, last arg may expand via mrc.
   * imm marks "last arg expands": na = (b-1) fixed + (imm? mrc : 0) —
   * encoding the fixed count keeps f(x, g()) from silently dropping x. */
  Node**A=e->list;int n=e->nlist;
  bc_raise(C,base+1);
  bc_expr(C,e->a,base);          /* callee (its temps land above the arg slots) */
  bc_raise(C,base+1);
  int lastmulti=n>0&&bc_ismulti(A[n-1]);
  for(int i=0;i<n-(lastmulti?1:0);i++){ bc_expr(C,A[i],base+1+i); bc_raise(C,base+2+i); }
  if(lastmulti){ bc_call_compile(C,A[n-1],base+1+n-1,1); bc_raise(C,C->reg+64); }
  bc_emit(C,BC_CALL,base,(n-(lastmulti?1:0))+1,cres,lastmulti);
}
static void bc_expr_multi(Bc*C,Node*e,int dst){
  if(e->kind==K_CALL||e->kind==K_METHODCALL) bc_call_compile(C,e,dst,1);
  else bc_expr(C,e,dst);
}
static void bc_expr(Bc*C,Node*e,int dst){
  if(C->failed)return;
  /* dst's slot must be owned before any temp is allocated: callers can hand us
   * dst==C->reg (e.g. a table ctor as the first call arg), and without this a
   * bc_reg() temp would alias dst and clobber the in-flight value */
  if(dst>=C->reg)C->reg=dst+1;
  if(e->line>0)C->curline=e->line;
  switch(e->kind){
  case K_NIL: bc_emit(C,BC_LOADNIL,dst,1,0,0); break;
  case K_TRUE: case K_FALSE: bc_emit(C,BC_LOADBOOL,dst,e->kind==K_TRUE?1:0,0,0); break;
  case K_NUM: bc_emit(C,BC_LOADK,dst,0,0,bc_k(C,VNUM(e->num))); break;
  case K_STR: bc_emit(C,BC_LOADK,dst,0,0,bc_k(C,VSTR(e->str))); break;
  case K_NAME:{
    int lr=bc_local(C,e->name);
    if(lr>=0) bc_emit(C,BC_MOVE,dst,lr,0,0);
    else if(C->capmode||bc_is_upvalue(C,e->name)) bc_emit(C,BC_GETENV,dst,0,0,bc_kstr(C,e->name));
    else{
      int k=bc_kstr(C,e->name);
      if(C->ngk<64)C->gk[C->ngk++]=k;
      bc_emit(C,BC_GETGLOBAL,dst,0,0,k); }
    break;}
  case K_PAREN: bc_expr(C,e->a,dst); break;
  case K_INDEX:{
    if(e->b->kind==K_STR){ bc_expr(C,e->a,dst); int k=bc_k(C,VSTR(e->b->str)); bc_emit(C,BC_GETFIELD,dst,dst,0,k); }
    else{ int t=bc_reg(C),kk=bc_reg(C); bc_expr(C,e->a,t); bc_expr(C,e->b,kk); bc_emit(C,BC_GETTABLE,dst,t,kk,0); }
    break;}
  case K_CALL: case K_METHODCALL: bc_call_compile(C,e,dst,0); break;
  case K_FUNC: bc_emit(C,BC_CLOSURE,dst,0,0,bc_sub(C,e)); break;
  case K_TABLE:{
    bc_emit(C,BC_NEWTABLE,dst,0,0,0);
    int ridx=bc_reg(C); bc_emit(C,BC_LOADK,ridx,0,0,bc_k(C,VNUM(1)));
    for(int i=0;i<e->nlist;i++){ Node*f=e->list[i];
      if(f->isKv){ int rk=bc_reg(C),rv=bc_reg(C); bc_expr(C,f->a,rk); bc_expr(C,f->b,rv); bc_emit(C,BC_SETTABLE,dst,rk,rv,0); }
      else if(bc_ismulti(f->a)){ int rb=bc_reg(C); bc_expr_multi(C,f->a,rb); bc_emit(C,BC_TSETMULT,dst,ridx,rb,0); }
      else{ int rv=bc_reg(C); bc_expr(C,f->a,rv); bc_emit(C,BC_SETTABLE,dst,ridx,rv,0); bc_emit(C,BC_INC,ridx,1,0,0); } }
    break;}
  case K_BINOP:{ int op=e->op;
    if(op==T_AND||op==T_OR){
      bc_expr(C,e->a,dst);
      int j=bc_emit(C,op==T_AND?BC_TESTN:BC_TEST,dst,0,0,0);
      bc_expr(C,e->b,dst);
      bc_patch(C,j,C->ncode);
      break; }
    if(e->a->kind==K_NUM&&e->b->kind==K_NUM){          /* fold literal ops — same formulas as the VM cases */
      double x=e->a->num,y=e->b->num; Value v=VNIL; int ok=1;
      if(op=='+')v=VNUM(x+y); else if(op=='-')v=VNUM(x-y); else if(op=='*')v=VNUM(x*y);
      else if(op=='/')v=VNUM(x/y); else if(op=='%')v=VNUM(x-floor(x/y)*y);
      else if(op=='^')v=VNUM(pow(x,y)); else if(op==T_IDIV)v=VNUM(floor(x/y));
      else if(op=='&')v=VNUM((double)((int64_t)x&(int64_t)y));
      else if(op=='|')v=VNUM((double)((int64_t)x|(int64_t)y));
      else if(op=='~')v=VNUM((double)((int64_t)x^(int64_t)y));
      else if(op==T_SHL)v=VNUM((double)((int64_t)x<<((int64_t)y&63)));
      else if(op==T_SHR)v=VNUM((double)((int64_t)x>>((int64_t)y&63)));
      else if(op==T_EQ)v=VBOOL(x==y); else if(op==T_NE)v=VBOOL(x!=y);
      else if(op=='<')v=VBOOL(x<y); else if(op==T_LE)v=VBOOL(x<=y);
      else if(op=='>')v=VBOOL(x>y); else if(op==T_GE)v=VBOOL(x>=y);
      else ok=0;
      if(ok){ bc_emit(C,BC_LOADK,dst,0,0,bc_k(C,v)); break; } }
    if(e->a->kind==K_STR&&e->b->kind==K_STR&&op==T_CONCAT){
      Str*sa=e->a->str,*sb=e->b->str;
      char*buf=xalloc(C->S,sa->len+sb->len+1);
      memcpy(buf,sa->p,sa->len); memcpy(buf+sa->len,sb->p,sb->len);
      bc_emit(C,BC_LOADK,dst,0,0,bc_k(C,VSTR(newStr(C->S,buf,sa->len+sb->len)))); break; }
    int ra=bc_reg(C),rb=bc_reg(C);
    bc_expr(C,e->a,ra); bc_expr(C,e->b,rb);
    int o = op=='+'?BC_ADD: op=='-'?BC_SUB: op=='*'?BC_MUL: op=='/'?BC_DIV: op=='%'?BC_MOD: op=='^'?BC_POW: op==T_IDIV?BC_IDIV:
            op=='|'?BC_BOR: op=='&'?BC_BAND: op=='~'?BC_BXOR: op==T_SHL?BC_SHL: op==T_SHR?BC_SHR:
            op==T_CONCAT?BC_CONCAT: op==T_EQ?BC_EQ: op==T_NE?BC_EQ: op=='<'?BC_LT: op==T_LE?BC_LE:
            op=='>'?BC_LT: op==T_GE?BC_LE:-1;
    if(o<0){C->failed=1;break;}
    if(op=='>'||op==T_GE){int t=ra;ra=rb;rb=t;}
    bc_emit(C,o,dst,ra,rb,0);
    if(op==T_NE) bc_emit(C,BC_NOT,dst,dst,0,0);
    break;}
  case K_UNOP:{
    if(e->a->kind==K_NUM&&e->op=='-'){ bc_emit(C,BC_LOADK,dst,0,0,bc_k(C,VNUM(-e->a->num))); break; }
    if(e->a->kind==K_NUM&&e->op=='~'){ bc_emit(C,BC_LOADK,dst,0,0,bc_k(C,VNUM((double)(~(int64_t)e->a->num)))); break; }
    if(e->a->kind==K_STR&&e->op=='#'){ bc_emit(C,BC_LOADK,dst,0,0,bc_k(C,VNUM((double)e->a->str->len))); break; }
    if(e->op==T_NOT){ Value lv=VNIL; int lit=1;
      switch(e->a->kind){ case K_NIL:lv=VNIL;break; case K_TRUE:lv=VBOOL(1);break; case K_FALSE:lv=VBOOL(0);break;
        case K_NUM:lv=VNUM(e->a->num);break; case K_STR:lv=VSTR(e->a->str);break; default:lit=0; }
      if(lit){ bc_emit(C,BC_LOADK,dst,0,0,bc_k(C,VBOOL(!toBool(lv)))); break; } }
    int ra=bc_reg(C); bc_expr(C,e->a,ra);
    int o=e->op=='-'?BC_UNM: e->op==T_NOT?BC_NOT: e->op=='~'?BC_BNOT: e->op=='#'?BC_LEN:-1;
    if(o<0){C->failed=1;break;}
    bc_emit(C,o,dst,ra,0,0); break;}
  default: C->failed=1;
  }
}

static void bc_block(Bc*C,Node*blk);
static void bc_stat(Bc*C,Node*st){
  if(C->failed)return;
  if(st->line>0)C->curline=st->line;
  switch(st->kind){
  case K_LOCAL:{
    int nn=st->nnames,nv=st->nlist;
    if(nn==0)break;
    int base=C->reg;
    for(int i=0;i<nn;i++) bc_reg(C);
    Node**V=st->list;
    int lastmulti=nv>0&&bc_ismulti(V[nv-1]);
    for(int i=0;i<nv-(lastmulti?1:0);i++) bc_expr(C,V[i],base+i);
    if(lastmulti){ bc_expr_multi(C,V[nv-1],base+nv-1); bc_emit(C,BC_EXPAND,base+nv-1,nn-(nv-1),0,0); }
    else if(nv<nn) bc_emit(C,BC_LOADNIL,base+nv,nn-nv,0,0);
    for(int i=0;i<nn;i++){ if(C->nloc<256){ C->loc[C->nloc].name=st->names[i]; C->loc[C->nloc].reg=base+i; C->nloc++; } }
    if(C->capmode) for(int i=0;i<nn;i++) bc_emit(C,BC_DECL,base+i,0,0,bc_kstr(C,st->names[i]));
    break;}
  case K_ASSIGN:{
    Node**V=st->list2;int nv=st->nlist2;
    if(nv==0)break;
    int nt=st->nlist;
    int base=C->reg;
    for(int i=0;i<nv;i++) bc_reg(C);
    int lastmulti=nv>0&&bc_ismulti(V[nv-1]);
    for(int i=0;i<nv-(lastmulti?1:0);i++) bc_expr(C,V[i],base+i);
    if(lastmulti){ bc_expr_multi(C,V[nv-1],base+nv-1); bc_emit(C,BC_EXPAND,base+nv-1,nt-(nv-1),0,0); }
    else if(nv<nt) bc_emit(C,BC_LOADNIL,base+nv,nt-nv,0,0);
    for(int i=0;i<nt;i++){ Node*t=st->list[i];
      if(t->kind==K_NAME){
        int lr=bc_local(C,t->name);
        if(lr>=0) bc_emit(C,BC_MOVE,lr,base+i,0,0);
        else if(C->capmode||bc_is_upvalue(C,t->name)) bc_emit(C,BC_SETENV,base+i,0,0,bc_kstr(C,t->name));
        else{
          int k=bc_kstr(C,t->name);
          if(C->ngk<64)C->gk[C->ngk++]=k;
          bc_emit(C,BC_SETGLOBAL,base+i,0,0,k); } }
      else if(t->kind==K_INDEX){
        if(t->b->kind==K_STR){ int ro=bc_reg(C); bc_expr(C,t->a,ro); int k=bc_k(C,VSTR(t->b->str)); bc_emit(C,BC_SETFIELD,ro,base+i,0,k); }
        else{ int ro=bc_reg(C),rk=bc_reg(C); bc_expr(C,t->a,ro); bc_expr(C,t->b,rk); bc_emit(C,BC_SETTABLE,ro,rk,base+i,0); } }
      else{ C->failed=1;break; } }
    break;}
  case K_CALLSTAT:{ int b=bc_reg(C); bc_call_compile(C,st->a,b,2); break; }
  case K_FUNCDECL:{
    int base=bc_reg(C);
    bc_emit(C,BC_CLOSURE,base,0,0,bc_sub(C,st->body));
    Node*t=st->a;
    if(st->isLocal&&t->kind==K_NAME) bc_emit(C,BC_DECL,base,0,0,bc_kstr(C,t->name));
    else if(t->kind==K_NAME) bc_emit(C,BC_SETENV,base,0,0,bc_kstr(C,t->name));
    else if(t->kind==K_INDEX){
      if(t->b->kind==K_STR){ int ro=bc_reg(C); bc_expr(C,t->a,ro); int k=bc_k(C,VSTR(t->b->str)); bc_emit(C,BC_SETFIELD,ro,base,0,k); }
      else{ int ro=bc_reg(C),rk=bc_reg(C); bc_expr(C,t->a,ro); bc_expr(C,t->b,rk); bc_emit(C,BC_SETTABLE,ro,rk,base,0); } }
    else{ C->failed=1;break; }
    break;}
  case K_DO: bc_scope(C); bc_block(C,st->body); bc_endscope(C); break;
  case K_IF:{
    int rd=bc_reg(C),jover[33],njover=0;
    bc_expr(C,st->a,rd);
    int jelse=bc_emit(C,BC_TESTN,rd,0,0,0);
    bc_scope(C); bc_block(C,st->body); bc_endscope(C);
    if(st->nlist||st->b){ if(njover<33)jover[njover++]=bc_jmp(C); }
    bc_patch(C,jelse,C->ncode);
    for(int i=0;i<st->nlist;i++){ Node*eli=st->list[i];
      bc_expr(C,eli->a,rd);
      int jn=bc_emit(C,BC_TESTN,rd,0,0,0);
      bc_scope(C); bc_block(C,eli->body); bc_endscope(C);
      if(i<st->nlist-1||st->b){ if(njover<33)jover[njover++]=bc_jmp(C); }
      bc_patch(C,jn,C->ncode); }
    if(st->b){ bc_scope(C); bc_block(C,st->b); bc_endscope(C); }
    for(int i=0;i<njover;i++) bc_patch(C,jover[i],C->ncode);
    break;}
  case K_WHILE:{
    bc_pushloop(C);
    int start=C->ncode;
    int rd=bc_reg(C);
    bc_expr(C,st->a,rd);
    int jexit=bc_emit(C,BC_TESTN,rd,0,0,0);
    bc_scope(C); bc_block(C,st->body); bc_endscope(C);
    int jb=bc_jmp(C); bc_patch(C,jb,start);
    bc_patch(C,jexit,C->ncode);
    bc_poploop(C,C->ncode);
    break;}
  case K_REPEAT:{
    bc_pushloop(C);
    int start=C->ncode;
    bc_scope(C);                       /* until-condition sees body locals */
    bc_block(C,st->body);
    int rc=bc_reg(C);
    bc_expr(C,st->a,rc);
    bc_endscope(C);                     /* capmode: ENVCLOSE after until-eval, before the back-edge */
    int jb=bc_emit(C,BC_TESTN,rc,0,0,0);  /* repeat..until: TESTN loops back while condition is FALSE */
    bc_patch(C,jb,start);
    bc_poploop(C,C->ncode);
    break;}
  case K_NFOR:{
    int a=C->reg;
    for(int i=0;i<4;i++) bc_reg(C);
    bc_expr(C,st->a,a); bc_expr(C,st->b,a+1);
    if(st->c) bc_expr(C,st->c,a+2); else bc_emit(C,BC_LOADK,a+2,0,0,bc_k(C,VNUM(1)));
    bc_pushloop(C);
    int fp=bc_emit(C,BC_FORPREP,a,0,0,0);
    bc_scope(C);
    if(C->capmode) bc_emit(C,BC_DECL,a,0,0,bc_kstr(C,st->name));
    if(C->nloc<256){ C->loc[C->nloc].name=st->name; C->loc[C->nloc].reg=a; C->nloc++; }
    bc_block(C,st->body);
    bc_endscope(C);
    int fl=bc_emit(C,BC_FORLOOP,a,0,0,0);
    bc_patch(C,fp,fl+1);
    bc_patch(C,fl,fp+1);
    bc_poploop(C,fl+1);
    break;}
  case K_GFOR:{
    Node**E=st->list;int ne=st->nlist;
    if(ne<1){C->failed=1;break;}
    int a=C->reg;
    for(int i=0;i<3+st->nnames;i++) bc_reg(C);
    int lastmulti=bc_ismulti(E[ne-1]);
    for(int i=0;i<ne-(lastmulti?1:0);i++) bc_expr(C,E[i],a+i);
    if(lastmulti){ bc_expr_multi(C,E[ne-1],a+ne-1); int want=3-(ne-1); if(want>0) bc_emit(C,BC_EXPAND,a+ne-1,want,0,0); }
    else if(ne<3) bc_emit(C,BC_LOADNIL,a+ne,3-ne,0,0);
    bc_pushloop(C);
    int gp=bc_emit(C,BC_GFORPREP,a,0,0,0);
    bc_scope(C);
    if(C->capmode) for(int i=0;i<st->nnames;i++) bc_emit(C,BC_DECL,a+3+i,0,0,bc_kstr(C,st->names[i]));
    for(int i=0;i<st->nnames;i++){ if(C->nloc<256){ C->loc[C->nloc].name=st->names[i]; C->loc[C->nloc].reg=a+3+i; C->nloc++; } }
    bc_block(C,st->body);
    bc_endscope(C);
    int gl=bc_emit(C,BC_GFORLOOP,a,0,st->nnames,0);
    bc_patch(C,gp,gl);
    bc_patch(C,gl,gp+1);
    bc_poploop(C,gl+1);
    break;}
  case K_RET:{
    Node**V=st->list;int n=st->nlist;
    if(n==0){ bc_emit(C,BC_RETURN,0,1,0,0); break; }
    int base=C->reg;
    for(int i=0;i<n;i++) bc_reg(C);
    int lastmulti=bc_ismulti(V[n-1]);
    for(int i=0;i<n-(lastmulti?1:0);i++) bc_expr(C,V[i],base+i);
    if(lastmulti) bc_expr_multi(C,V[n-1],base+n-1);
    bc_emit(C,BC_RETURN,base,(n-(lastmulti?1:0))+1,0,lastmulti);
    break;}
  case K_BREAK:{ if(!C->nloops){C->failed=1;break;}
    if(C->capmode){ int open=C->depth-C->loops[C->nloops-1].envmark; for(int i=0;i<open;i++) bc_emit(C,BC_ENVCLOSE,0,0,0,0); }
    if(C->loops[C->nloops-1].nbrk<32)C->loops[C->nloops-1].brk[C->loops[C->nloops-1].nbrk++]=bc_jmp(C); break; }
  default: C->failed=1;
  }
}
static void bc_block(Bc*C,Node*blk){ if(blk)for(int i=0;i<blk->nlist;i++) bc_stat(C,blk->list[i]); }

static Proto* bc_build(State*S,Func*fn){
  if(!fn||!fn->body||fn->vararg) return NULL;
  Bc C; memset(&C,0,sizeof(C)); C.S=S; C.fn=fn;
  C.capmode=bc_has_nested(fn->body);
  if(fn->body->line>0)C.curline=fn->body->line;
  Proto*p=xalloc(S,sizeof(Proto)); memset(p,0,sizeof(*p));
  bc_scope(&C);
  /* params own registers [0,nparam): seed them as the outermost locals */
  for(int i=0;i<fn->nparam && C.nloc<256;i++){ C.loc[C.nloc].name=fn->params[i]; C.loc[C.nloc].reg=i; C.nloc++; }
  C.reg=fn->nparam;
  bc_block(&C,fn->body);
  bc_endscope(&C);
  /* always emit the epilogue: a trailing BC_RETURN may sit inside a conditional
   * block (e.g. `if c then return x end`), so "last op is RETURN" does NOT prove
   * the fallthrough path is unreachable */
  if(!C.failed) bc_emit(&C,BC_RETURN,0,1,0,0);
  if(!C.failed && C.reg>BC_MAXREG) C.failed=1;
  if(C.failed || !C.ncode){ free(C.code);free(C.lines);free(C.imm);free(C.k); return NULL; }
  p->code=xalloc(S,(size_t)C.ncode*sizeof(BIns)); memcpy(p->code,C.code,(size_t)C.ncode*sizeof(BIns));
  p->imm=xalloc(S,(size_t)C.ncode*sizeof(short)); for(int i=0;i<C.ncode;i++)p->imm[i]=(unsigned short)(short)C.imm[i];
  p->lines=xalloc(S,(size_t)C.ncode*sizeof(int)); memcpy(p->lines,C.lines,(size_t)C.ncode*sizeof(int));
  p->ncode=C.ncode;
  p->k=xalloc(S,(size_t)(C.nk?C.nk:1)*sizeof(Value)); if(C.nk)memcpy(p->k,C.k,(size_t)C.nk*sizeof(Value));
  p->nk=C.nk;
  p->gk=xalloc(S,(size_t)(C.ngk?C.ngk:1)*sizeof(int)); if(C.ngk)memcpy(p->gk,C.gk,(size_t)C.ngk*sizeof(int));
  p->ngk=C.ngk;
  p->nparam=fn->nparam;
  p->maxstack=C.maxreg+1;
  p->defline=fn->line;
  p->uses_env=C.capmode;
  if(C.nsubs){ p->subs=xalloc(S,(size_t)C.nsubs*sizeof(Node*)); memcpy(p->subs,C.subs,(size_t)C.nsubs*sizeof(Node*)); p->nsubs=C.nsubs; }
  free(C.code); free(C.lines); free(C.imm); free(C.k);
  return p;
}
/* per-call guard: a name compiled as global must not have been captured by a
 * local declared later in an enclosing tree-walk scope (dynamic scoping) */
static bool bc_globals_still_global(State*S,Func*fn,Proto*p){
  for(int i=0;i<p->ngk;i++){
    Value k=p->k[p->gk[i]];
    for(Env*e=fn->env;e;e=e->parent){ if(e==S->globals)break; if(tfind(e->vars,k))return false; }
  }
  return true;
}

#define BVR(i) (S->vstack[base+(i)])
static Value vm_call(State*S,Proto*p,Closure*cl,int argc,Value*argv){
  int base=S->vtop;
  int need=base+p->maxstack+2;
  if(S->vstack_sz<need){ int ns=S->vstack_sz?S->vstack_sz*2:256; while(ns<need)ns*=2; S->vstack=realloc(S->vstack,(size_t)ns*sizeof(Value)); S->vstack_sz=ns; }
  S->vtop=base+p->maxstack;
  for(int i=0;i<p->maxstack;i++) BVR(i)= i<p->nparam ? (i<argc?argv[i]:VNIL) : VNIL;
  char nm[48];
  if(p->defline>0) snprintf(nm,sizeof(nm),"fn@%d",p->defline); else snprintf(nm,sizeof(nm),"fn");
  if(S->nstack>0) dbg_touch_top(S,S->curLine);
  dbg_push_frame(S,nm,S->curLine,p->defline);
  Env*cur=cl&&cl->f&&cl->f->env?cl->f->env:S->globals;
  if(p->uses_env){
    cur=newEnv(S,cur);
    if(cl&&cl->f) for(int i=0;i<p->nparam;i++) envDeclareFn(S,cur,cl->f->params[i], i<argc?argv[i]:VNIL);
  }
  S->cur_env=cur;
  int pc=0,mrc=0;
  Value ret=VNIL;
  BIns in={0,0,0,0};
#if defined(__GNUC__)||defined(__clang__)
  /* computed-goto dispatch: one indirect branch per insn; the inner op-switches
   * below fold away since in.op is a constant at each label. Non-GNU compilers
   * keep the switch fallback via the BC_* macros. */
#define BC_THREADED 1
  static const void*const disp[]={
    &&L_BC_LOADK,&&L_BC_LOADNIL,&&L_BC_LOADBOOL,&&L_BC_MOVE,
    &&L_BC_GETGLOBAL,&&L_BC_SETGLOBAL,&&L_BC_GETTABLE,&&L_BC_SETTABLE,&&L_BC_GETFIELD,&&L_BC_SETFIELD,
    &&L_BC_ADD,&&L_BC_SUB,&&L_BC_MUL,&&L_BC_DIV,&&L_BC_MOD,&&L_BC_POW,&&L_BC_IDIV,&&L_BC_BAND,&&L_BC_BOR,&&L_BC_BXOR,&&L_BC_SHL,&&L_BC_SHR,
    &&L_BC_UNM,&&L_BC_BNOT,&&L_BC_NOT,&&L_BC_LEN,&&L_BC_CONCAT,&&L_BC_EQ,&&L_BC_LT,&&L_BC_LE,
    &&L_BC_TEST,&&L_BC_TESTN,&&L_BC_JMP,&&L_BC_CALL,&&L_BC_RETURN,&&L_BC_EXPAND,&&L_BC_NEWTABLE,&&L_BC_TSETMULT,&&L_BC_INC,
    &&L_BC_FORPREP,&&L_BC_FORLOOP,&&L_BC_GFORPREP,&&L_BC_GFORLOOP,
    &&L_BC_ENVOPEN,&&L_BC_ENVCLOSE,&&L_BC_DECL,&&L_BC_GETENV,&&L_BC_SETENV,&&L_BC_CLOSURE};
#define BC_OP(n) L_##n
#define BC_AGAIN() do{ \
    if(S->cancel_flag) lx_rt_error(S,"cancelled by user"); \
    if(S->step_limit>0 && ++S->steps>S->step_limit) lx_rt_error(S,"execution step limit exceeded (possible infinite loop)"); \
    { int pl=p->lines[pc]; if(pl>0) S->curLine=pl; } \
    in=p->code[pc]; goto *disp[in.op]; }while(0)
#define BC_NEXT() do{ pc++; BC_AGAIN(); }while(0)
  BC_AGAIN();
#else
#define BC_OP(n) case n
#define BC_AGAIN() continue
#define BC_NEXT() break
  while(1){
    in=p->code[pc];
    if(S->cancel_flag) lx_rt_error(S,"cancelled by user");
    if(S->step_limit>0 && ++S->steps>S->step_limit) lx_rt_error(S,"execution step limit exceeded (possible infinite loop)");
    { int pl=p->lines[pc]; if(pl>0) S->curLine=pl; }
    switch(in.op){
#endif
    BC_OP(BC_LOADK): BVR(in.a)=p->k[p->imm[pc]]; BC_NEXT();
    BC_OP(BC_LOADNIL): for(int i=0;i<in.b;i++) BVR(in.a+i)=VNIL; BC_NEXT();
    BC_OP(BC_LOADBOOL): BVR(in.a)=VBOOL(in.b?true:false); BC_NEXT();
    BC_OP(BC_MOVE): BVR(in.a)=BVR(in.b); BC_NEXT();
    BC_OP(BC_GETGLOBAL): BVR(in.a)=tget(S->globals->vars,p->k[p->imm[pc]]); BC_NEXT();
    BC_OP(BC_SETGLOBAL): tset(S,S->globals->vars,p->k[p->imm[pc]],BVR(in.a)); BC_NEXT();
    BC_OP(BC_GETTABLE): BVR(in.a)=indexVal(S,BVR(in.b),BVR(in.c)); BC_NEXT();
    BC_OP(BC_SETTABLE): newIndex(S,BVR(in.a),BVR(in.b),BVR(in.c)); BC_NEXT();
    BC_OP(BC_GETFIELD): BVR(in.a)=indexVal(S,BVR(in.b),p->k[p->imm[pc]]); BC_NEXT();
    BC_OP(BC_SETFIELD): newIndex(S,BVR(in.a),p->k[p->imm[pc]],BVR(in.b)); BC_NEXT();
    BC_OP(BC_ADD): BC_OP(BC_SUB): BC_OP(BC_MUL): BC_OP(BC_DIV): BC_OP(BC_MOD): BC_OP(BC_POW): BC_OP(BC_IDIV):{
      double x=toNum(S,BVR(in.b)),y=toNum(S,BVR(in.c)),v=0;
      switch(in.op){case BC_ADD:v=x+y;break;case BC_SUB:v=x-y;break;case BC_MUL:v=x*y;break;case BC_DIV:v=x/y;break;
        case BC_MOD:v=x-floor(x/y)*y;break;case BC_POW:v=pow(x,y);break;case BC_IDIV:v=floor(x/y);break;}
      BVR(in.a)=VNUM(v); BC_NEXT();}
    BC_OP(BC_BAND): BC_OP(BC_BOR): BC_OP(BC_BXOR): BC_OP(BC_SHL): BC_OP(BC_SHR):{
      int64_t x=(int64_t)toNum(S,BVR(in.b)),y=(int64_t)toNum(S,BVR(in.c)),v=0;
      switch(in.op){case BC_BAND:v=x&y;break;case BC_BOR:v=x|y;break;case BC_BXOR:v=x^y;break;
        case BC_SHL:v=x<<(y&63);break;case BC_SHR:v=x>>(y&63);break;}
      BVR(in.a)=VNUM((double)v); BC_NEXT();}
    BC_OP(BC_UNM): BVR(in.a)=VNUM(-toNum(S,BVR(in.b))); BC_NEXT();
    BC_OP(BC_BNOT): BVR(in.a)=VNUM((double)(~(int64_t)toNum(S,BVR(in.b)))); BC_NEXT();
    BC_OP(BC_NOT):{ Value v=BVR(in.b); BVR(in.a)=VBOOL(!toBool(v)); BC_NEXT();}
    BC_OP(BC_LEN):{ Value a=BVR(in.b); Value r;
      if(a.tag==T_STR)r=VNUM((double)a.u.s->len);
      else if(a.tag==T_TAB){
        if(a.u.t->meta){Value m=tget(a.u.t->meta,VSTR(newStr(S,"__len",5)));r=m.tag!=T_NIL?callValue(S,m,1,&a):VNUM((double)tlen(a.u.t));}
        else r=VNUM((double)tlen(a.u.t)); }
      else lx_rt_error(S,"attempt to get length of a %s value",lx_typename(a));
      BVR(in.a)=r; BC_NEXT();}
    BC_OP(BC_CONCAT):{
      Str*sa=toStrx(S,BVR(in.b)),*sb=toStrx(S,BVR(in.c));
      char*buf=xalloc(S,sa->len+sb->len+1);
      memcpy(buf,sa->p,sa->len); memcpy(buf+sa->len,sb->p,sb->len);
      BVR(in.a)=VSTR(newStr(S,buf,sa->len+sb->len)); BC_NEXT();}
    BC_OP(BC_EQ): BVR(in.a)=VBOOL(valEq(BVR(in.b),BVR(in.c))); BC_NEXT();
    BC_OP(BC_LT): BVR(in.a)=VBOOL(toNum(S,BVR(in.b))<toNum(S,BVR(in.c))); BC_NEXT();
    BC_OP(BC_LE): BVR(in.a)=VBOOL(toNum(S,BVR(in.b))<=toNum(S,BVR(in.c))); BC_NEXT();
    /* fused test-and-branch: imm holds the i16 jump delta directly */
    BC_OP(BC_TEST): if(toBool(BVR(in.a))){pc+=(short)p->imm[pc]+1;BC_AGAIN();} BC_NEXT();
    BC_OP(BC_TESTN): if(!toBool(BVR(in.a))){pc+=(short)p->imm[pc]+1;BC_AGAIN();} BC_NEXT();
    BC_OP(BC_JMP): pc+=(short)p->imm[pc]+1; BC_AGAIN();
    BC_OP(BC_CALL):{
      Value f=BVR(in.a);
      int na=in.b-1+(p->imm[pc]?mrc:0);
      if(na>LX_MAX_ARGS)lx_rt_error(S,"too many arguments");
      Value tmp[LX_MAX_ARGS];
      for(int i=0;i<na;i++) tmp[i]=BVR(in.a+1+i);
      callValue(S,f,na,tmp);
      int n=S->nret;
      if(in.c){ int want=in.c-1; for(int i=0;i<want;i++) BVR(in.a+i)= i<n?S->retbuf[i]:VNIL; mrc=want; }
      else { for(int i=0;i<n;i++) BVR(in.a+i)=S->retbuf[i]; mrc=n; }
      BC_NEXT();}
    BC_OP(BC_EXPAND):{ int want=in.b; if(mrc<want) for(int i=mrc;i<want;i++) BVR(in.a+i)=VNIL; mrc=want; BC_NEXT();}
    BC_OP(BC_NEWTABLE): BVR(in.a)=VTAB(newTable(S)); BC_NEXT();
    BC_OP(BC_TSETMULT):{ Value tv=BVR(in.a);
      if(tv.tag!=T_TAB)lx_rt_error(S,"attempt to index a %s value",lx_typename(tv));
      Table*t=tv.u.t;
      double idx=BVR(in.b).u.num;
      for(int j=0;j<mrc;j++) tset(S,t,VNUM(idx+j),BVR(in.c+j));
      BVR(in.b)=VNUM(idx+mrc);
      BC_NEXT();}
    BC_OP(BC_INC): BVR(in.a)=VNUM(BVR(in.a).u.num+in.b); BC_NEXT();
    BC_OP(BC_FORPREP):{
      double step=BVR(in.a+2).u.num;
      BVR(in.a+3)=BVR(in.a);
      bool inrange = step>0 ? BVR(in.a).u.num<=BVR(in.a+1).u.num : BVR(in.a).u.num>=BVR(in.a+1).u.num;
      if(inrange){ BVR(in.a)=BVR(in.a+3); pc++; BC_AGAIN(); }
      pc+=(short)p->imm[pc]+1; BC_AGAIN(); }
    BC_OP(BC_FORLOOP):{
      double i=BVR(in.a+3).u.num+BVR(in.a+2).u.num, lim=BVR(in.a+1).u.num, st=BVR(in.a+2).u.num;
      BVR(in.a+3)=VNUM(i);
      bool cont = st>0 ? i<=lim : i>=lim;
      if(cont){ BVR(in.a)=VNUM(i); pc+=(short)p->imm[pc]+1; BC_AGAIN(); }
      BC_NEXT();}
    BC_OP(BC_GFORPREP): pc+=(short)p->imm[pc]+1; BC_AGAIN();
    BC_OP(BC_GFORLOOP):{
      Value args[2]={BVR(in.a+1),BVR(in.a+2)};
      callValue(S,BVR(in.a),2,args);
      int n=S->nret;
      if(n==0||S->retbuf[0].tag==T_NIL) BC_NEXT();          /* exit loop */
      int nn=in.c;
      BVR(in.a+2)=S->retbuf[0];
      int nv=n>nn?nn:n;
      for(int i=0;i<nv;i++) BVR(in.a+3+i)=S->retbuf[i];
      for(int i=nv;i<nn;i++) BVR(in.a+3+i)=VNIL;
      pc+=(short)p->imm[pc]+1; BC_AGAIN(); }
    BC_OP(BC_ENVOPEN): cur=newEnv(S,cur); S->cur_env=cur; BC_NEXT();
    BC_OP(BC_ENVCLOSE): cur=cur->parent?cur->parent:cur; S->cur_env=cur; BC_NEXT();
    BC_OP(BC_DECL): envDeclS(S,cur,p->k[p->imm[pc]].u.s,BVR(in.a)); BC_NEXT();
    BC_OP(BC_GETENV): BVR(in.a)=envGetS(S,cur,p->k[p->imm[pc]].u.s); BC_NEXT();
    BC_OP(BC_SETENV): envAssignS(S,cur,p->k[p->imm[pc]].u.s,BVR(in.a)); BC_NEXT();
    BC_OP(BC_CLOSURE):{
      Node*e=p->subs[p->imm[pc]];
      Func*fn=xalloc(S,sizeof(Func));
      fn->nparam=e->nnames; fn->params=e->names; fn->vararg=e->vararg;
      fn->body=e->body; fn->env=cur;
      fn->line=e->line>0?e->line:(e->body&&e->body->line>0?e->body->line:S->curLine);
      fn->bc=NULL; fn->bc_tried=0;
      Closure*c2=xalloc(S,sizeof(Closure)); c2->f=fn;
      BVR(in.a)=VFN(c2); BC_NEXT();}
    BC_OP(BC_RETURN):{
      int n2=in.b-1+(p->imm[pc]?mrc:0);
      if(n2>64)n2=64;
      for(int i=0;i<n2;i++) S->retbuf[i]=BVR(in.a+i);
      S->nret=n2;
      ret=n2?S->retbuf[0]:VNIL;
      S->vtop=base;
      dbg_pop_frame(S);
      return ret;}
#ifndef BC_THREADED
    default: lx_rt_error(S,"internal error: bad opcode %d",(int)in.op);
    }
    pc++;
  }
#endif
}
#undef BVR
#undef BC_OP
#undef BC_AGAIN
#undef BC_NEXT
#ifdef BC_THREADED
#undef BC_THREADED
#endif

/* ---- disassembler (CLI --bc-dump / tests) ---- */
static void bc_collect_funcs(Node*n,Node**out,int*no,int max){
  if(!n||*no>=max)return;
  if(n->kind==K_FUNC) out[(*no)++]=n;
  bc_collect_funcs(n->a,out,no,max); bc_collect_funcs(n->b,out,no,max);
  bc_collect_funcs(n->c,out,no,max); bc_collect_funcs(n->body,out,no,max);
  for(int i=0;i<n->nlist;i++) bc_collect_funcs(n->list[i],out,no,max);
  for(int i=0;i<n->nlist2;i++) bc_collect_funcs(n->list2[i],out,no,max);
}
int lx_bc_disassemble(lx_State*S,const char*src,char*errbuf,int errlen){
  if(setjmp(S->err)){ S->call_depth=0; if(errbuf)snprintf(errbuf,errlen,"%s",S->errmsg); return -1; }
  Node*chunk=parse(S,src,strlen(src));
  Node*funcs[128]; int nf=0; bc_collect_funcs(chunk,funcs,&nf,128);
  for(int i=0;i<nf;i++){
    printf("function #%d (line %d)\n",i,funcs[i]->line);
    Func df; memset(&df,0,sizeof(df));
    df.nparam=funcs[i]->nnames; df.params=funcs[i]->names; df.vararg=funcs[i]->vararg;
    df.body=funcs[i]->body; df.env=S->globals; df.line=funcs[i]->line;
    Proto*p=bc_build(S,&df);
    if(!p){ printf("  <not compilable by bytecode v0>\n"); continue; }
    printf("  params=%d maxstack=%d consts=%d insns=%d\n",p->nparam,p->maxstack,p->nk,p->ncode);
    for(int j=0;j<p->nk;j++){ char b[80]; Value v=p->k[j];
      if(v.tag==T_NUM)snprintf(b,sizeof(b),"%.14g",v.u.num);
      else if(v.tag==T_STR)snprintf(b,sizeof(b),"\"%.*s\"",(int)v.u.s->len,v.u.s->p);
      else snprintf(b,sizeof(b),"?");
      printf("    K%d = %s\n",j,b); }
    for(int j=0;j<p->ncode;j++){ int o=p->code[j].op;
      int sgn=(o==BC_JMP||o==BC_TEST||o==BC_TESTN||o==BC_FORPREP||o==BC_FORLOOP||o==BC_GFORPREP||o==BC_GFORLOOP);
      printf("  %4d  %-9s a=%-3d b=%-3d c=%-3d imm=%-6d ; line %d\n",j,bc_opname(o),p->code[j].a,p->code[j].b,p->code[j].c,sgn?(short)p->imm[j]:(int)p->imm[j],p->lines[j]); }
  }
  return nf;
}
void lx_bc_stats(lx_State*S,long*calls,long*fallbacks){
  if(calls)*calls=S?S->bc_calls:0;
  if(fallbacks)*fallbacks=S?S->bc_fallbacks:0;
}

/* ---------- stdlib ---------- */
static CFn* mkCFn(State*S,const char*name,Value(*fn)(State*,int,Value*)){CFn*c=xalloc(S,sizeof(CFn));c->fn=fn;c->name=name;return c;}
/* type guards: stdlib functions used to dereference argv[].u.* unconditionally,
 * so string/table functions segfaulted on bad argument types (e.g. string.len(5)
 * or table.insert(5,1)). These match Lua 5.1's luaL_checktype behavior instead. */
static Str*   argStr(State*S,Value v,const char*fn){ if(v.tag!=T_STR)lx_rt_error(S,"bad argument #1 to '%s' (string expected, got %s)",fn,lx_typename(v)); return v.u.s; }
static Table* argTab(State*S,Value v,const char*fn){ if(v.tag!=T_TAB)lx_rt_error(S,"bad argument #1 to '%s' (table expected, got %s)",fn,lx_typename(v)); return v.u.t; }
static Value st_next(State*S,int argc,Value*argv);
static Value st_ipiter(State*S,int argc,Value*argv);

static Value st_print(State*S,int argc,Value*argv){ for(int i=0;i<argc;i++){ Str*st=toStrx(S,argv[i]); if(i){fputs("\t",stdout); buf_append(&S->out,&S->outsz,&S->outused,"\t",1);} fwrite(st->p,1,st->len,stdout); buf_append(&S->out,&S->outsz,&S->outused,st->p,st->len);} fputc('\n',stdout); buf_append(&S->out,&S->outsz,&S->outused,"\n",1); S->nret=0; return VNIL; }
static Value st_type(State*S,int argc,Value*argv){ const char*n=lx_typename(argv[0]); S->nret=1; S->retbuf[0]=VSTR(newStr(S,n,strlen(n))); return S->retbuf[0]; }
static Value st_tostring(State*S,int argc,Value*argv){ S->nret=1; S->retbuf[0]=VSTR(toStrx(S,argv[0])); return S->retbuf[0]; }
static Value st_tonumber(State*S,int argc,Value*argv){ Value v=argv[0]; if(v.tag==T_NUM){S->nret=1;S->retbuf[0]=v;return v;} if(v.tag==T_STR){char*e;double d=strtod(v.u.s->p,&e);if(e!=v.u.s->p){S->nret=1;S->retbuf[0]=VNUM(d);return S->retbuf[0];}} S->nret=1; S->retbuf[0]=VNIL; return VNIL; }
static Value st_next(State*S,int argc,Value*argv){ Table*t=argTab(S,argv[0],"next"); Value k=argc>1?argv[1]:VNIL; int from=0;
  if(k.tag!=T_NIL){ unsigned h=hashVal(k)&(t->cap-1); int found=-1; for(int i=0;i<t->cap;i++){int j=(h+i)&(t->cap-1);if(!t->e[j].used)break;if(valEq(t->e[j].k,k)){found=j;break;}} if(found<0)lx_rt_error(S,"invalid key to 'next'"); from=found+1; }
  for(int i=from;i<t->cap;i++){ if(t->e[i].used){ S->nret=2; S->retbuf[0]=t->e[i].k; S->retbuf[1]=t->e[i].v; return S->retbuf[0]; } } S->nret=1; S->retbuf[0]=VNIL; return VNIL; }
static Value st_pairs(State*S,int argc,Value*argv){ S->nret=3; S->retbuf[0]=VCFN(mkCFn(S,"next",st_next)); S->retbuf[1]=argv[0]; S->retbuf[2]=VNIL; return S->retbuf[0]; }
static Value st_ipiter(State*S,int argc,Value*argv){ Table*t=argTab(S,argv[0],"ipairs"); int i=(int)argv[1].u.num+1; Value v=tget(t,VNUM(i)); if(v.tag==T_NIL){S->nret=1;S->retbuf[0]=VNIL;return VNIL;} S->nret=2; S->retbuf[0]=VNUM(i); S->retbuf[1]=v; return S->retbuf[0]; }
static Value st_ipairs(State*S,int argc,Value*argv){ S->nret=3; S->retbuf[0]=VCFN(mkCFn(S,"iter",st_ipiter)); S->retbuf[1]=argv[0]; S->retbuf[2]=VNUM(0); return S->retbuf[0]; }
static Value st_setmt(State*S,int argc,Value*argv){ if(argv[0].tag!=T_TAB)lx_rt_error(S,"bad argument #1 to 'setmetatable'"); if(argv[1].tag!=T_TAB&&argv[1].tag!=T_NIL)lx_rt_error(S,"bad argument #2 to 'setmetatable'"); argv[0].u.t->meta=argv[1].tag==T_TAB?argv[1].u.t:NULL; S->nret=1; S->retbuf[0]=argv[0]; return argv[0]; }
static Value st_getmt(State*S,int argc,Value*argv){ Table*m=argv[0].tag==T_TAB?argv[0].u.t->meta:NULL; S->nret=1; S->retbuf[0]=m?VTAB(m):VNIL; return S->retbuf[0]; }
static Value st_rawget(State*S,int argc,Value*argv){ S->nret=1; S->retbuf[0]=tget(argTab(S,argv[0],"rawget"),argv[1]); return S->retbuf[0]; }
static Value st_rawset(State*S,int argc,Value*argv){ tset(S,argTab(S,argv[0],"rawset"),argv[1],argv[2]); S->nret=1; S->retbuf[0]=argv[0]; return S->retbuf[0]; }
static Value st_raweq(State*S,int argc,Value*argv){ S->nret=1; S->retbuf[0]=VBOOL(valEq(argv[0],argv[1])); return S->retbuf[0]; }
static Value st_assert(State*S,int argc,Value*argv){ if(!toBool(argv[0]))lx_rt_error(S,argc>1&&argv[1].tag==T_STR?argv[1].u.s->p:"assertion failed!"); for(int i=0;i<argc&&i<64;i++)S->retbuf[i]=argv[i]; S->nret=argc; return argc?argv[0]:VNIL; }
static Value st_error(State*S,int argc,Value*argv){ Str*st=toStrx(S,argv[0]); lx_rt_error(S,"%.*s",(int)st->len,st->p); return VNIL; }
static Value st_pcall(State*S,int argc,Value*argv){ Value f=argv[0]; jmp_buf outer; memcpy(&outer,&S->err,sizeof(outer));
  int depth=S->nstack; int vdepth=S->vtop; int cdepth=S->call_depth;
  if(setjmp(S->err)==0){ Value r=callValue(S,f,argc-1,argv+1); int n=S->nret; Value tmp[64]; tmp[0]=r; for(int i=1;i<n&&i<64;i++)tmp[i]=S->retbuf[i];
    memcpy(&S->err,&outer,sizeof(outer)); S->nstack=depth; S->vtop=vdepth; S->call_depth=cdepth; S->retbuf[0]=VBOOL(1); for(int i=0;i<n&&i<63;i++)S->retbuf[1+i]=tmp[i]; S->nret=n+1; return S->retbuf[0]; }
  memcpy(&S->err,&outer,sizeof(outer)); S->nstack=depth; S->vtop=vdepth; S->call_depth=cdepth; S->retbuf[0]=VBOOL(0); S->retbuf[1]=VSTR(newStr(S,S->errmsg,strlen(S->errmsg))); S->nret=2; return S->retbuf[0]; }
static Value st_select(State*S,int argc,Value*argv){ if(argv[0].tag==T_STR&&argv[0].u.s->len==1&&argv[0].u.s->p[0]=='#'){S->nret=1;S->retbuf[0]=VNUM(argc-1);return S->retbuf[0];} int n=num2int(S,argv[0]); if(n<0)n=argc+n; else if(n==0)lx_rt_error(S,"bad argument #1 to 'select'"); if(n<1)lx_rt_error(S,"bad argument #1 to 'select' (index out of range)"); if(n>argc){S->nret=0;return VNIL;} S->nret=argc-n; for(int i=0;i+n<argc;i++)S->retbuf[i]=argv[n+i]; return S->nret?S->retbuf[0]:VNIL; }
static Value st_unpack(State*S,int argc,Value*argv){ Table*t=argTab(S,argv[0],"table.unpack"); int i=argc>1?num2int(S,argv[1]):1; int j=argc>2?num2int(S,argv[2]):tlen(t); if(i<=j && (long)j-(long)i+1>64)lx_rt_error(S,"too many results to unpack"); S->nret=0; for(;i<=j;i++)S->retbuf[S->nret++]=tget(t,VNUM(i)); return S->nret?S->retbuf[0]:VNIL; }
static Value st_slen(State*S,int argc,Value*argv){S->nret=1;S->retbuf[0]=VNUM((double)argStr(S,argv[0],"string.len")->len);return S->retbuf[0];}
static Value st_supper(State*S,int argc,Value*argv){Str*s=argStr(S,argv[0],"string.upper");char*b=xalloc(S,s->len);for(size_t i=0;i<s->len;i++)b[i]=toupper((unsigned char)s->p[i]);S->nret=1;S->retbuf[0]=VSTR(newStr(S,b,s->len));return S->retbuf[0];}
static Value st_slower(State*S,int argc,Value*argv){Str*s=argStr(S,argv[0],"string.lower");char*b=xalloc(S,s->len);for(size_t i=0;i<s->len;i++)b[i]=tolower((unsigned char)s->p[i]);S->nret=1;S->retbuf[0]=VSTR(newStr(S,b,s->len));return S->retbuf[0];}
static Value st_ssub(State*S,int argc,Value*argv){Str*s=argStr(S,argv[0],"string.sub");int len=(int)s->len;int i=num2int(S,argv[1]);if(i<0)i+=len+1;if(i<1)i=1;int j=argc>2?num2int(S,argv[2]):-1;if(j<0)j+=len+1;if(j>len)j=len;S->nret=1;S->retbuf[0]=(i<=j)?VSTR(newStr(S,s->p+i-1,j-i+1)):VSTR(newStr(S,"",0));return S->retbuf[0];}
static Value st_srep(State*S,int argc,Value*argv){Str*s=argStr(S,argv[0],"string.rep");int n=num2int(S,argv[1]);if(n<0)n=0;if(s->len>0&&(size_t)n>0xFFFFFFFu/s->len)lx_rt_error(S,"resulting string too large");char*b=xalloc(S,(size_t)n*s->len+1);for(int k=0;k<n;k++)memcpy(b+k*s->len,s->p,s->len);S->nret=1;S->retbuf[0]=VSTR(newStr(S,b,(size_t)n*s->len));return S->retbuf[0];}
static Value st_tinsert(State*S,int argc,Value*argv){Table*t=argTab(S,argv[0],"table.insert");if(argc==2){int n=tlen(t);tset(S,t,VNUM(n+1),argv[1]);}else{int p=num2int(S,argv[1]);int n=tlen(t);for(int i=n;i>=p;i--)tset(S,t,VNUM(i+1),tget(t,VNUM(i)));tset(S,t,VNUM(p),argv[2]);}S->nret=0;return VNIL;}
static Value st_tremove(State*S,int argc,Value*argv){Table*t=argTab(S,argv[0],"table.remove");int n=tlen(t);int p=argc>1?num2int(S,argv[1]):n;if(p<1)p=1;if(p>n){S->nret=1;S->retbuf[0]=VNIL;return VNIL;}Value v=tget(t,VNUM(p));for(int i=p;i<n;i++)tset(S,t,VNUM(i),tget(t,VNUM(i+1)));tset(S,t,VNUM(n),VNIL);S->nret=1;S->retbuf[0]=v;return v;}
static Value st_tconcat(State*S,int argc,Value*argv){Table*t=argTab(S,argv[0],"table.concat");Str*sep=argc>1&&argv[1].tag==T_STR?argv[1].u.s:NULL;int i=argc>2?num2int(S,argv[2]):1;int j=argc>3?num2int(S,argv[3]):tlen(t);char*buf=NULL;size_t m=0,cap=0;for(;i<=j;i++){Value v=tget(t,VNUM(i));if(v.tag!=T_STR&&v.tag!=T_NUM)lx_rt_error(S,"invalid value (table.concat)");Str*s=toStrx(S,v);if(m+s->len>cap){cap=cap?cap*2:64;while(m+s->len>cap)cap*=2;buf=realloc(buf,cap);}memcpy(buf+m,s->p,s->len);m+=s->len;if(i<j&&sep){if(m+sep->len>cap){cap=cap?cap*2:64;while(m+sep->len>cap)cap*=2;buf=realloc(buf,cap);}memcpy(buf+m,sep->p,sep->len);m+=sep->len;}}S->nret=1;S->retbuf[0]=m?VSTR(newStr(S,buf,m)):VSTR(newStr(S,"",0));if(buf)free(buf);return S->retbuf[0];}

/* ---------- pattern matching (Lua 5.1 semantics, original implementation) ----------
 * Byte-oriented, like Lua 5.1: classes and '.' match single bytes (a UTF-8
 * character is multiple bytes). The subject is length-delimited (may contain
 * NULs); the pattern is treated as NUL-terminated, so patterns cannot contain
 * embedded NULs. Captures: len<0 marks a sentinel (-1 position capture "()",
 * -2 unfinished) while a successful capture has init/len into the subject. */
#define LX_MAXCAPS 32
typedef struct { const char* init; ptrdiff_t len; } PCap;
typedef struct { State* S; const char* src; const char* send; int level; PCap cap[LX_MAXCAPS]; int depth; } PMS;
static void pms_init(PMS*ms,State*S,Str*s){ ms->S=S; ms->src=s->p; ms->send=s->p+s->len; ms->level=0; ms->depth=0; }
static const char* p_classend(PMS*ms,const char*p){
  if(*p=='%'){ if(!p[1])lx_rt_error(ms->S,"malformed pattern (ends with '%%')"); return p+2; }
  if(*p=='['){
    p++; if(*p=='^')p++;
    do{ if(!*p)lx_rt_error(ms->S,"malformed pattern (missing ']')");
        if(*p=='%'){ if(!p[1])lx_rt_error(ms->S,"malformed pattern (ends with '%%')"); p+=2; } else p++;
    }while(*p!=']');
    return p+1;
  }
  if(!*p)lx_rt_error(ms->S,"malformed pattern (empty)");
  return p+1;
}
static int p_class(int c,int cl){
  int res;
  switch(tolower(cl)){
    case 'a':res=isalpha(c);break; case 'c':res=iscntrl(c);break; case 'd':res=isdigit(c);break;
    case 'g':res=isgraph(c);break; case 'l':res=islower(c);break; case 'p':res=ispunct(c);break;
    case 's':res=isspace(c);break; case 'u':res=isupper(c);break; case 'w':res=isalnum(c);break;
    case 'x':res=isxdigit(c);break; case 'z':res=(c==0);break;
    default:return (cl==c);
  }
  if(isupper(cl))res=!res;
  return res;
}
static int p_bracket(int c,const char*p,const char*ec){ /* p at '[', ec past ']' */
  int sig=1;
  if(*(p+1)=='^'){sig=0;p++;}
  while(++p<ec){
    if(*p=='%'){ p++; if(p_class(c,(unsigned char)*p))return sig; }
    else if(*(p+1)=='-'&&p+2<ec){ p+=2; if((unsigned char)*(p-2)<=c&&c<=(unsigned char)*p)return sig; }
    else if((unsigned char)*p==c)return sig;
  }
  return !sig;
}
static int p_single(PMS*ms,const char*s,const char*p,const char*ep){
  if(s>=ms->send)return 0;
  int c=(unsigned char)*s;
  switch(*p){
    case '.': return 1;
    case '%': return p_class(c,(unsigned char)p[1]);
    case '[': return p_bracket(c,p,ep);
    default: return (unsigned char)*p==c;
  }
}
static const char* p_match(PMS*ms,const char*s,const char*p);
static const char* p_max(PMS*ms,const char*s,const char*p,const char*ep);
static const char* p_min(PMS*ms,const char*s,const char*p,const char*ep);
static const char* p_dflt(PMS*ms,const char*s,const char*p){
  const char*ep=p_classend(ms,p);
  if(!p_single(ms,s,p,ep)){
    if(*ep=='*'||*ep=='?'||*ep=='-') return p_match(ms,s,ep+1);
    return NULL;
  }
  switch(*ep){
    case '?':{ const char*r=p_match(ms,s+1,ep+1); if(r)return r; return p_match(ms,s,ep+1); }
    case '+': return p_max(ms,s+1,p,ep);
    case '*': return p_max(ms,s,p,ep);
    case '-': return p_min(ms,s,p,ep);
    default: return p_match(ms,s+1,ep);
  }
}
static const char* p_startcap(PMS*ms,const char*s,const char*p,int pos){
  if(ms->level>=LX_MAXCAPS)lx_rt_error(ms->S,"too many captures");
  ms->cap[ms->level].init=s; ms->cap[ms->level].len=pos?-1:-2; ms->level++;
  const char*res=p_match(ms,s,p);
  if(!res)ms->level--;
  return res;
}
static const char* p_endcap(PMS*ms,const char*s,const char*p){
  int idx=-1;
  for(int i=ms->level-1;i>=0;i--) if(ms->cap[i].len<0){ idx=i; break; }
  if(idx<0||ms->cap[idx].len==-1)lx_rt_error(ms->S,"invalid pattern capture");
  ms->cap[idx].len=s-ms->cap[idx].init;
  const char*res=p_match(ms,s,p);
  if(!res)ms->cap[idx].len=-2;
  return res;
}
static const char* p_balance(PMS*ms,const char*s,const char*p){
  if(p[0]==0||p[1]==0)lx_rt_error(ms->S,"malformed pattern (missing arguments to '%%b')");
  if(s>=ms->send||*s!=*p)return NULL;
  int b=*p,e=p[1],cont=1;
  for(const char*q=s+1;q<ms->send;q++){
    if(*q==e){ if(--cont==0)return p_match(ms,q+1,p+2); }
    else if(*q==b)cont++;
  }
  return NULL;
}
static const char* p_frontier(PMS*ms,const char*s,const char*p){
  if(*p!='[')lx_rt_error(ms->S,"missing '[' after '%%f' in pattern");
  const char*ep=p_classend(ms,p);
  char prev=(s==ms->src)?'\0':*(s-1);
  int cur=(s<ms->send)&&p_bracket((unsigned char)*s,p,ep);
  int prv=p_bracket((unsigned char)prev,p,ep);
  if(!prv&&cur)return p_match(ms,s,ep);
  return NULL;
}
static const char* p_match(PMS*ms,const char*s,const char*p){
  if(ms->depth++>1000)lx_rt_error(ms->S,"pattern too complex");
  const char*res=NULL;
  switch(*p){
    case 0: res=s; break;
    case '(': res=(p[1]==')')?p_startcap(ms,s,p+2,1):p_startcap(ms,s,p+1,0); break;
    case ')': res=p_endcap(ms,s,p+1); break;
    case '$': res=(p[1]==0)?((s==ms->send)?s:NULL):p_dflt(ms,s,p); break;
    case '%':
      if(p[1]=='b'){res=p_balance(ms,s,p+2);break;}
      if(p[1]=='f'){res=p_frontier(ms,s,p+2);break;}
      res=p_dflt(ms,s,p); break;
    default: res=p_dflt(ms,s,p); break;
  }
  ms->depth--;
  return res;
}
static const char* p_max(PMS*ms,const char*s,const char*p,const char*ep){
  const char*start=s;
  while(p_single(ms,s,p,ep))s++;
  while(s>=start){
    const char*res=p_match(ms,s,ep+1);
    if(res)return res;
    s--;
  }
  return NULL;
}
static const char* p_min(PMS*ms,const char*s,const char*p,const char*ep){
  for(;;){
    const char*res=p_match(ms,s,ep+1);
    if(res)return res;
    if(!p_single(ms,s,p,ep))return NULL;
    s++;
  }
}
static Value p_capval(PMS*ms,int i){
  if(ms->cap[i].len==-1)return VNUM((double)(ms->cap[i].init-ms->src+1));
  if(ms->cap[i].len<-1) lx_rt_error(ms->S,"unfinished capture");
  return VSTR(newStr(ms->S,ms->cap[i].init,ms->cap[i].len));
}
/* string.find / match / gsub share init normalization: 1-based, negative
 * counts from the end; returns the start pointer, or NULL when the position
 * is past the end of the subject (no match is possible there). */
static const char* str_from(State*S,Str*src,int argc,Value*argv,int argi){
  int init=argc>argi?num2int(S,argv[argi]):1;
  if(init<0)init=(int)src->len+init+1;
  if(init<1)init=1;
  if((size_t)(init-1)>src->len)return NULL;
  return src->p+(init-1);
}
static Value st_sfind(State*S,int argc,Value*argv){
  Str*src=argStr(S,argv[0],"string.find"); Str*pat=argStr(S,argv[1],"string.find");
  const char*from=str_from(S,src,argc,argv,2);
  if(from){
    if(argc>3&&toBool(argv[3])){ /* plain-text search */
      const char*end=src->p+src->len;
      for(const char*q=from;q+pat->len<=end;q++){
        if(pat->len==0||memcmp(q,pat->p,pat->len)==0){
          S->nret=2; S->retbuf[0]=VNUM((double)(q-src->p+1)); S->retbuf[1]=VNUM((double)(q-src->p+pat->len));
          return S->retbuf[0];
        }
      }
    } else {
      PMS ms; pms_init(&ms,S,src);
      const char*pp=pat->p; int anchor=(*pp=='^'); if(anchor)pp++;
      for(const char*q=from;q<=ms.send;q++){
        ms.level=0;
        const char*e=p_match(&ms,q,pp);
        if(e){
          S->nret=2+ms.level; S->retbuf[0]=VNUM((double)(q-src->p+1)); S->retbuf[1]=VNUM((double)(e-src->p));
          for(int i=0;i<ms.level;i++)S->retbuf[2+i]=p_capval(&ms,i);
          return S->retbuf[0];
        }
        if(anchor)break;
      }
    }
  }
  S->nret=1; S->retbuf[0]=VNIL; return VNIL;
}
static Value st_smatch(State*S,int argc,Value*argv){
  Str*src=argStr(S,argv[0],"string.match"); Str*pat=argStr(S,argv[1],"string.match");
  const char*from=str_from(S,src,argc,argv,2);
  if(from){
    PMS ms; pms_init(&ms,S,src);
    const char*pp=pat->p; int anchor=(*pp=='^'); if(anchor)pp++;
    for(const char*q=from;q<=ms.send;q++){
      ms.level=0;
      const char*e=p_match(&ms,q,pp);
      if(e){
        if(ms.level==0){ S->nret=1; S->retbuf[0]=VSTR(newStr(S,q,e-q)); return S->retbuf[0]; }
        S->nret=ms.level;
        for(int i=0;i<ms.level;i++)S->retbuf[i]=p_capval(&ms,i);
        return S->retbuf[0];
      }
      if(anchor)break;
    }
  }
  S->nret=1; S->retbuf[0]=VNIL; return VNIL;
}
static void gsub_rep(State*S,PMS*ms,Value repl,const char*mp,size_t mlen,char**buf,size_t*bsz,size_t*used){
  if(repl.tag==T_STR){
    Str*rs=repl.u.s;
    for(size_t i=0;i<rs->len;i++){
      char c=rs->p[i];
      if(c!='%'){ buf_append(buf,bsz,used,&c,1); continue; }
      if(++i>=rs->len)lx_rt_error(S,"invalid use of '%%' in replacement string");
      char d=rs->p[i];
      if(d=='%'){ buf_append(buf,bsz,used,"%",1); continue; }
      if(d<'0'||d>'9')lx_rt_error(S,"invalid use of '%%' in replacement string");
      int k=d-'0';
      if(k==0){ buf_append(buf,bsz,used,mp,mlen); continue; }
      if(k>ms->level)lx_rt_error(S,"invalid capture index %%%d in replacement string",k);
      Value cv=p_capval(ms,k-1); Str*cs=toStrx(S,cv);
      buf_append(buf,bsz,used,cs->p,cs->len);
    }
  } else if(repl.tag==T_TAB||repl.tag==T_FN||repl.tag==T_CFN){
    Value args[LX_MAXCAPS]; int na;
    if(ms->level==0){ args[0]=VSTR(newStr(S,mp,mlen)); na=1; }
    else { for(int i=0;i<ms->level;i++)args[i]=p_capval(ms,i); na=ms->level; }
    Value r=(repl.tag==T_TAB)?tget(repl.u.t,args[0]):callValue(S,repl,na,args);
    if(r.tag==T_STR||r.tag==T_NUM){ Str*rs=toStrx(S,r); buf_append(buf,bsz,used,rs->p,rs->len); }
    else buf_append(buf,bsz,used,mp,mlen); /* nil/false keeps the original match */
  } else {
    lx_rt_error(S,"bad argument #3 to 'gsub' (string/function/table expected)");
  }
}
static Value st_sgsub(State*S,int argc,Value*argv){
  Str*src=argStr(S,argv[0],"string.gsub"); Str*pat=argStr(S,argv[1],"string.gsub");
  Value repl=argv[2];
  if(repl.tag!=T_STR&&repl.tag!=T_TAB&&repl.tag!=T_FN&&repl.tag!=T_CFN)
    lx_rt_error(S,"bad argument #3 to 'gsub' (string/function/table expected)");
  long maxn=(argc>3&&argv[3].tag!=T_NIL)?num2int(S,argv[3]):-1;
  char*buf=NULL; size_t bsz=0,bu=0; long count=0;
  PMS ms; pms_init(&ms,S,src);
  const char*pp=pat->p; int anchor=(*pp=='^'); if(anchor)pp++;
  const char*sp=src->p;
  while(sp<=ms.send&&(maxn<0||count<maxn)){
    ms.level=0;
    const char*e=p_match(&ms,sp,pp);
    if(e){
      count++;
      gsub_rep(S,&ms,repl,sp,(size_t)(e-sp),&buf,&bsz,&bu);
      if(anchor)break;
      if(e>sp)sp=e;
      else { if(sp<ms.send)buf_append(&buf,&bsz,&bu,sp,1); sp++; } /* empty match: keep char, advance */
    } else {
      if(anchor)break;
      if(sp<ms.send)buf_append(&buf,&bsz,&bu,sp,1);
      sp++;
    }
  }
  if(sp<=ms.send)buf_append(&buf,&bsz,&bu,sp,(size_t)(ms.send-sp));
  S->nret=2;
  S->retbuf[0]=bu?VSTR(newStr(S,buf,bu)):VSTR(newStr(S,"",0));
  S->retbuf[1]=VNUM((double)count);
  if(buf)free(buf);
  return S->retbuf[0];
}
static Value st_sbyte(State*S,int argc,Value*argv){
  Str*s=argStr(S,argv[0],"string.byte");
  int i=argc>1?num2int(S,argv[1]):1, j=argc>2?num2int(S,argv[2]):i, len=(int)s->len;
  if(i<0)i+=len+1; if(i<1)i=1;
  if(j<0)j+=len+1; if(j>len)j=len;
  if(j-i+1>64)lx_rt_error(S,"too many results to 'string.byte'");
  S->nret=0;
  for(;i<=j;i++)S->retbuf[S->nret++]=VNUM((double)(unsigned char)s->p[i-1]);
  return S->nret?S->retbuf[0]:VNIL;
}
static Value st_schar(State*S,int argc,Value*argv){
  char*b=xalloc(S,(size_t)argc+1);
  for(int i=0;i<argc;i++){ int c=num2int(S,argv[i]); if(c<0||c>255)lx_rt_error(S,"bad argument #%d to 'string.char' (value out of range)",i+1); b[i]=(char)c; }
  S->nret=1; S->retbuf[0]=VSTR(newStr(S,b,(size_t)argc)); return S->retbuf[0];
}
static Value st_sreverse(State*S,int argc,Value*argv){
  Str*s=argStr(S,argv[0],"string.reverse");
  char*b=xalloc(S,s->len?s->len:1);
  for(size_t i=0;i<s->len;i++)b[i]=s->p[s->len-1-i];
  S->nret=1; S->retbuf[0]=VSTR(newStr(S,b,s->len)); return S->retbuf[0];
}
/* vsnprintf into the growable output: a width like %9999d would overflow the
 * stack scratch buffer — snprintf reports the would-be length, and appending
 * that many bytes from a 160B buffer is an out-of-bounds read. Re-render into
 * a heap buffer when the result doesn't fit. */
static void fmt_out(char**buf,size_t*bsz,size_t*bu,const char*fmt,...){
  char nb[160]; va_list ap;
  va_start(ap,fmt); int m=vsnprintf(nb,sizeof(nb),fmt,ap); va_end(ap);
  if(m<0)return;
  if(m<(int)sizeof(nb)){ buf_append(buf,bsz,bu,nb,(size_t)m); return; }
  char*big=malloc((size_t)m+1);
  if(!big)return;
  va_start(ap,fmt); vsnprintf(big,(size_t)m+1,fmt,ap); va_end(ap);
  buf_append(buf,bsz,bu,big,(size_t)m);
  free(big);
}
static Value st_sformat(State*S,int argc,Value*argv){
  Str*f=argStr(S,argv[0],"string.format");
  char*buf=NULL; size_t bsz=0,bu=0;
  int argi=1;
  for(size_t i=0;i<f->len;i++){
    if(f->p[i]!='%'){ buf_append(&buf,&bsz,&bu,f->p+i,1); continue; }
    size_t j=i+1; char spec[64]; int sl=0; spec[sl++]='%';
    while(j<f->len&&(f->p[j]=='-'||f->p[j]=='+'||f->p[j]==' '||f->p[j]=='#'||f->p[j]=='0')&&sl<48)spec[sl++]=f->p[j++];
    while(j<f->len&&f->p[j]>='0'&&f->p[j]<='9'&&sl<48)spec[sl++]=f->p[j++];
    if(j<f->len&&f->p[j]=='.'){ if(sl<48)spec[sl++]='.'; j++; while(j<f->len&&f->p[j]>='0'&&f->p[j]<='9'&&sl<48)spec[sl++]=f->p[j++]; }
    if(j>=f->len)lx_rt_error(S,"invalid conversion '%%' to 'format'");
    char conv=f->p[j]; spec[sl]=0;
    if(conv=='%'){ buf_append(&buf,&bsz,&bu,"%",1); i=j; continue; }
    if(conv=='q'){
      if(argi>=argc)lx_rt_error(S,"bad argument #%d to 'format' (no value)",argi);
      Str*s=toStrx(S,argv[argi++]);
      buf_append(&buf,&bsz,&bu,"\"",1);
      for(size_t k=0;k<s->len;k++){
        unsigned char c=(unsigned char)s->p[k]; char e[8];
        if(c=='"'||c=='\\'){ e[0]='\\'; e[1]=(char)c; buf_append(&buf,&bsz,&bu,e,2); }
        else if(c=='\n'){ buf_append(&buf,&bsz,&bu,"\\n",2); }
        else if(c=='\r'){ buf_append(&buf,&bsz,&bu,"\\r",2); }
        else if(c<32||c==127){ int m=snprintf(e,sizeof(e),"\\%03d",(int)c); buf_append(&buf,&bsz,&bu,e,m); }
        else buf_append(&buf,&bsz,&bu,s->p+k,1);
      }
      buf_append(&buf,&bsz,&bu,"\"",1);
      i=j; continue;
    }
    if(conv=='s'){
      if(argi>=argc)lx_rt_error(S,"bad argument #%d to 'format' (no value)",argi);
      Str*s=toStrx(S,argv[argi++]);
      size_t n=s->len;
      const char*dot=strchr(spec,'.');
      if(dot&&dot[1]>='0'&&dot[1]<='9'){ long p=0; const char*q=dot+1; while(*q>='0'&&*q<='9'){ p=p*10+(*q-'0'); q++; } if((size_t)p<n)n=(size_t)p; }
      /* width / '-' handling is manual so NUL bytes in the argument survive */
      long w=0; int k=1, left=0;
      while(k<sl&&(spec[k]=='-'||spec[k]=='+'||spec[k]==' '||spec[k]=='#'||spec[k]=='0')){ if(spec[k]=='-')left=1; k++; }
      for(;k<sl&&spec[k]>='0'&&spec[k]<='9';k++)w=w*10+(spec[k]-'0');
      if(!left&&w>0&&(size_t)w>n){ size_t rem=(size_t)w-n; char pad[64]; memset(pad,' ',sizeof(pad)); while(rem){ size_t c=rem<sizeof(pad)?rem:sizeof(pad); buf_append(&buf,&bsz,&bu,pad,c); rem-=c; } }
      buf_append(&buf,&bsz,&bu,s->p,n);
      if(left&&w>0&&(size_t)w>n){ size_t rem=(size_t)w-n; char pad[64]; memset(pad,' ',sizeof(pad)); while(rem){ size_t c=rem<sizeof(pad)?rem:sizeof(pad); buf_append(&buf,&bsz,&bu,pad,c); rem-=c; } }
      i=j; continue;
    }
    if(conv=='d'||conv=='i'||conv=='o'||conv=='x'||conv=='X'||conv=='c'||conv=='e'||conv=='E'||conv=='f'||conv=='g'||conv=='G'){
      if(argi>=argc)lx_rt_error(S,"bad argument #%d to 'format' (no value)",argi);
      char full[80];
      if(conv=='d'||conv=='i'||conv=='o'||conv=='x'||conv=='X'){
        long long v=(long long)num2int(S,argv[argi++]);
        snprintf(full,sizeof(full),"%sll%c",spec,conv);
        fmt_out(&buf,&bsz,&bu,full,v);
      } else if(conv=='c'){
        int v=num2int(S,argv[argi++]);
        snprintf(full,sizeof(full),"%sc",spec);
        fmt_out(&buf,&bsz,&bu,full,v);
      } else {
        double v=toNum(S,argv[argi++]);
        snprintf(full,sizeof(full),"%s%c",spec,conv);
        fmt_out(&buf,&bsz,&bu,full,v);
      }
      i=j; continue;
    }
    lx_rt_error(S,"invalid conversion '%c' to 'format'",conv);
  }
  S->nret=1; S->retbuf[0]=bu?VSTR(newStr(S,buf,bu)):VSTR(newStr(S,"",0));
  if(buf)free(buf);
  return S->retbuf[0];
}
/* ---------- math ---------- */
static unsigned long long rng_next(State*S){
  if(!S->rng_seeded){ S->rng=(unsigned long long)time(NULL)^0x9E3779B97F4A7C15ULL^(unsigned long long)(uintptr_t)S; S->rng_seeded=1; }
  S->rng+=0x9E3779B97F4A7C15ULL;
  unsigned long long z=S->rng;
  z=(z^(z>>30))*0xBF58476D1CE4E5B9ULL;
  z=(z^(z>>27))*0x94D049BB133111EBULL;
  return z^(z>>31);
}
static Value ma_floor(State*S,int argc,Value*argv){(void)argc;S->nret=1;S->retbuf[0]=VNUM(floor(toNum(S,argv[0])));return S->retbuf[0];}
static Value ma_ceil(State*S,int argc,Value*argv){(void)argc;S->nret=1;S->retbuf[0]=VNUM(ceil(toNum(S,argv[0])));return S->retbuf[0];}
static Value ma_abs(State*S,int argc,Value*argv){(void)argc;S->nret=1;S->retbuf[0]=VNUM(fabs(toNum(S,argv[0])));return S->retbuf[0];}
static Value ma_sqrt(State*S,int argc,Value*argv){(void)argc;double x=toNum(S,argv[0]);if(x<0)lx_rt_error(S,"math domain error");S->nret=1;S->retbuf[0]=VNUM(sqrt(x));return S->retbuf[0];}
static Value ma_max(State*S,int argc,Value*argv){ if(argc<1)lx_rt_error(S,"bad argument #1 to 'math.max' (number expected)"); double m=toNum(S,argv[0]); for(int i=1;i<argc;i++){double x=toNum(S,argv[i]); if(x>m)m=x;} S->nret=1;S->retbuf[0]=VNUM(m);return S->retbuf[0]; }
static Value ma_min(State*S,int argc,Value*argv){ if(argc<1)lx_rt_error(S,"bad argument #1 to 'math.min' (number expected)"); double m=toNum(S,argv[0]); for(int i=1;i<argc;i++){double x=toNum(S,argv[i]); if(x<m)m=x;} S->nret=1;S->retbuf[0]=VNUM(m);return S->retbuf[0]; }
static Value ma_random(State*S,int argc,Value*argv){
  S->nret=1;
  if(argc==0){ S->retbuf[0]=VNUM((double)(rng_next(S)>>11)*(1.0/9007199254740992.0)); return S->retbuf[0]; }
  if(argc==1){ long long m=(long long)num2int(S,argv[0]); if(m<1)lx_rt_error(S,"bad argument #1 to 'math.random' (interval is empty)"); S->retbuf[0]=VNUM((double)(1+(long long)(rng_next(S)%(unsigned long long)m))); return S->retbuf[0]; }
  long long m=(long long)num2int(S,argv[0]), n=(long long)num2int(S,argv[1]);
  if(n<m)lx_rt_error(S,"bad argument #2 to 'math.random' (interval is empty)");
  S->retbuf[0]=VNUM((double)(m+(long long)(rng_next(S)%(unsigned long long)(n-m+1))));
  return S->retbuf[0];
}
static Value ma_randomseed(State*S,int argc,Value*argv){ S->rng=(argc>0)?(unsigned long long)(long long)num2int(S,argv[0]):(unsigned long long)time(NULL); S->rng_seeded=1; S->nret=0; return VNIL; }
/* ---------- table.sort (bottom-up merge sort; stable; arena-allocated temp) ---------- */
static int sort_less(State*S,Value a,Value b,Value comp){
  if(comp.tag==T_FN||comp.tag==T_CFN){ Value args[2]={a,b}; return toBool(callValue(S,comp,2,args)); }
  if(a.tag==T_NUM&&b.tag==T_NUM)return a.u.num<b.u.num;
  if(a.tag==T_STR&&b.tag==T_STR){ size_t n=a.u.s->len<b.u.s->len?a.u.s->len:b.u.s->len; int c=memcmp(a.u.s->p,b.u.s->p,n); if(c)return c<0; return a.u.s->len<b.u.s->len; }
  lx_rt_error(S,"attempt to compare %s with %s",lx_typename(a),lx_typename(b));
  return 0;
}
static Value st_tsort(State*S,int argc,Value*argv){
  Table*t=argTab(S,argv[0],"table.sort");
  Value comp=(argc>1)?argv[1]:VNIL;
  if(comp.tag!=T_NIL&&comp.tag!=T_FN&&comp.tag!=T_CFN)lx_rt_error(S,"bad argument #2 to 'table.sort' (function expected)");
  int n=tlen(t);
  if(n>=2){
    Value*a=xalloc(S,(size_t)n*sizeof(Value)),*b=xalloc(S,(size_t)n*sizeof(Value));
    for(int i=0;i<n;i++)a[i]=tget(t,VNUM(i+1));
    for(int w=1;w<n;w*=2){
      for(int lo=0;lo<n;lo+=2*w){
        int mid=lo+w<n?lo+w:n, hi=lo+2*w<n?lo+2*w:n, i=lo, j=mid, k=lo;
        while(i<mid&&j<hi)b[k++]=sort_less(S,a[i],a[j],comp)?a[i++]:a[j++];
        while(i<mid)b[k++]=a[i++];
        while(j<hi)b[k++]=a[j++];
      }
      Value*sw=a;a=b;b=sw;
    }
    for(int i=0;i<n;i++)tset(S,t,VNUM(i+1),a[i]);
  }
  S->nret=0; return VNIL;
}
/* string.gmatch is defined in Lua on top of the C string.find: the iterator
 * closure tracks the scan position, advancing past each match (or one char
 * after an empty match, so empty-match patterns terminate). */
static const char* STRING_PRELUDE =
  "string.gmatch = function(s, p)\n"
  "  local pos = 1\n"
  "  return function()\n"
  "    if pos == nil then return nil end\n"
  "    local r = { string.find(s, p, pos) }\n"
  "    if r[1] == nil then pos = nil; return nil end\n"
  "    local st, en = r[1], r[2]\n"
  "    pos = (en >= st) and (en + 1) or (st + 1)\n"
  "    if #r > 2 then return table.unpack(r, 3) end\n"
  "    return string.sub(s, st, en)\n"
  "  end\n"
  "end\n";
static void regFn(State*S,Table*t,const char*name,Value(*fn)(State*,int,Value*)){tset(S,t,VSTR(newStr(S,name,strlen(name))),VCFN(mkCFn(S,name,fn)));}

/* ---------- declarative ui library ---------- */
/* ui.<type>{ props..., children... } → tags a table with __ui=<type> and returns it. */
static Value ui_ctor(State*S,const char*type,int argc,Value*argv){
  /* Only ui.text accepts string shorthand; other constructors keep their contract. */
  Table*t;
  if(argc>0 && argv[0].tag==T_STR && strcmp(type,"text")==0){
    t=newTable(S);
    tset(S,t,VSTR(newStr(S,"text",4)),argv[0]);
  } else t = (argc>0 && argv[0].tag==T_TAB) ? argv[0].u.t : newTable(S);
  tset(S,t,VSTR(newStr(S,"__ui",4)),VSTR(newStr(S,type,strlen(type))));
  S->nret=1; S->retbuf[0]=VTAB(t); return S->retbuf[0];
}
#define UICTOR(NM) static Value ui_##NM(State*S,int argc,Value*argv){ return ui_ctor(S,#NM,argc,argv); }
UICTOR(app) UICTOR(column) UICTOR(row) UICTOR(text) UICTOR(button) UICTOR(card)
UICTOR(input) UICTOR(image) UICTOR(spacer) UICTOR(divider) UICTOR(scrollview)
UICTOR(list) UICTOR(listitem) UICTOR(stack) UICTOR(page) UICTOR(switch)
UICTOR(box) UICTOR(slider) UICTOR(progress)
#undef UICTOR

static Table* package_loaded(State*S){
  Value pkg=tget(S->globals->vars,VSTR(newStr(S,"package",7)));
  if(pkg.tag!=T_TAB){
    Table*p=newTable(S);
    tset(S,S->globals->vars,VSTR(newStr(S,"package",7)),VTAB(p));
    Table*loaded=newTable(S);
    tset(S,p,VSTR(newStr(S,"loaded",6)),VTAB(loaded));
    return loaded;
  }
  Value ld=tget(pkg.u.t,VSTR(newStr(S,"loaded",6)));
  if(ld.tag!=T_TAB){
    Table*loaded=newTable(S);
    tset(S,pkg.u.t,VSTR(newStr(S,"loaded",6)),VTAB(loaded));
    return loaded;
  }
  return ld.u.t;
}
static int file_exists(const char*path){
  FILE*f=fopen(path,"rb"); if(!f) return 0; fclose(f); return 1;
}
static int resolve_module_path(State*S,const char*name,char*out,size_t outsz){
  char rel[512]; size_t j=0;
  for(size_t i=0; name[i] && j+1<sizeof(rel); i++){
    char c=name[i];
    if(c=='.') rel[j++]='/';
    else if(c=='/' || c=='\\') rel[j++]='/';
    else rel[j++]=c;
  }
  rel[j]=0;
  if(j==0) return 0;
  /* reject ".." path segments — require must not escape the module root */
  { const char*r=rel;
    while(*r){ const char*e=strchr(r,'/'); size_t sl=e?(size_t)(e-r):strlen(r);
      if(sl==2&&r[0]=='.'&&r[1]=='.') return 0;
      if(!e) break; r=e+1; } }
  const char* root = S->modroot[0] ? S->modroot : ".";
  char cand[1024];
  snprintf(cand,sizeof(cand),"%s/%s.lua", root, rel);
  if(file_exists(cand)){ snprintf(out,outsz,"%s",cand); return 1; }
  snprintf(cand,sizeof(cand),"%s/%s/init.lua", root, rel);
  if(file_exists(cand)){ snprintf(out,outsz,"%s",cand); return 1; }
  if(rel[0]!='/' ){
    snprintf(cand,sizeof(cand),"%s.lua", rel);
    if(file_exists(cand)){ snprintf(out,outsz,"%s",cand); return 1; }
  }
  return 0;
}
static int load_file_text(const char*path,char**out,size_t*outlen){
  FILE*f=fopen(path,"rb"); if(!f) return 0;
  if(fseek(f,0,SEEK_END)!=0){ fclose(f); return 0; }
  long n=ftell(f); if(n<0){ fclose(f); return 0; }
  if(fseek(f,0,SEEK_SET)!=0){ fclose(f); return 0; }
  char*buf=malloc((size_t)n+1); if(!buf){ fclose(f); return 0; }
  size_t rd=fread(buf,1,(size_t)n,f); fclose(f);
  buf[rd]=0; *out=buf; *outlen=rd; return 1;
}
static Value st_require(State*S,int argc,Value*argv){
  if(argc<1 || argv[0].tag!=T_STR) lx_rt_error(S,"bad argument #1 to 'require' (string expected)");
  const char* name=argv[0].u.s->p;
  size_t nlen=argv[0].u.s->len;
  if(nlen==2 && memcmp(name,"ui",2)==0){
    S->nret=1; S->retbuf[0]=tget(S->globals->vars,VSTR(newStr(S,"ui",2))); return S->retbuf[0];
  }
  Table*loaded=package_loaded(S);
  Value key=VSTR(newStr(S,name,nlen));
  Value cached=tget(loaded,key);
  if(cached.tag!=T_NIL){
    S->nret=1; S->retbuf[0]=cached; return cached;
  }
  char path[1024];
  if(!resolve_module_path(S,name,path,sizeof(path))){
    lx_rt_error(S,"module '%s' not found (modroot=%s)", name, S->modroot[0]?S->modroot:"(unset)");
  }
  char*src=NULL; size_t srclen=0;
  if(!load_file_text(path,&src,&srclen)){
    lx_rt_error(S,"cannot read module '%s' (%s)", name, path);
  }
  tset(S,loaded,key,VBOOL(1));
  Node*chunk=NULL;
  Env*env=NULL;
  struct Flow fl=F_NORMAL;
  jmp_buf outer; memcpy(&outer,&S->err,sizeof(outer));
  int depth=S->nstack;
  int cdepth=S->call_depth;
  if(setjmp(S->err)==0){
    chunk=parse(S,src,srclen);
    env=newEnv(S,S->globals);
    dbg_push_frame(S,"require",1,1);
    S->cur_env=env;
    fl=execChunk(S,env,chunk);
    dbg_pop_frame(S);
    free(src); src=NULL;
    memcpy(&S->err,&outer,sizeof(outer));
    S->nstack=depth;
    S->call_depth=cdepth;
  } else {
    free(src); src=NULL;
    memcpy(&S->err,&outer,sizeof(outer));
    S->nstack=depth;
    S->call_depth=cdepth;
    tset(S,loaded,key,VNIL);
    lx_rt_error(S,"error loading module '%s': %s", name, S->errmsg);
  }
  Value mod=VBOOL(1);
  if(fl.kind==1 && fl.nret>0) mod=fl.rets[0];
  tset(S,loaded,key,mod);
  S->nret=1; S->retbuf[0]=mod; return mod;
}

/* ---------- ui tree → json ---------- */
static void jappend(State*S,const char*s,size_t n){ buf_append(&S->json,&S->jsonsz,&S->jsonused,s,n); }
static void jstr(State*S,const char*p,size_t n){
  jappend(S,"\"",1);
  for(size_t i=0;i<n;i++){ unsigned char c=(unsigned char)p[i]; char e[8];
    switch(c){ case '"': jappend(S,"\\\"",2);break; case '\\': jappend(S,"\\\\",2);break;
      case '\n': jappend(S,"\\n",2);break; case '\t': jappend(S,"\\t",2);break; case '\r': jappend(S,"\\r",2);break;
      default: if(c<0x20){ int m=snprintf(e,sizeof(e),"\\u%04x",c); jappend(S,e,m);} else jappend(S,(char*)&c,1); } }
  jappend(S,"\"",1);
}
static void jnode(State*S,Table*t,int depth);
#define LX_MAX_JSON_DEPTH 128
static void jvalue(State*S,Value v,int depth){
  if(depth>LX_MAX_JSON_DEPTH) lx_rt_error(S,"ui tree too deep (possible cycle)");
  char buf[64];
  switch(v.tag){
    case T_NIL: jappend(S,"null",4); break;
    case T_BOOL: if(v.u.b)jappend(S,"true",4); else jappend(S,"false",5); break;
    case T_NUM:{ int n=snprintf(buf,sizeof(buf),"%.14g",v.u.num); jappend(S,buf,n); } break;
    case T_STR: jstr(S,v.u.s->p,v.u.s->len); break;
    case T_FN: case T_CFN:{ /* register handler, emit reference */
      int id=S->nhandlers<LX_MAX_HANDLERS ? S->nhandlers++ : -1; if(id>=0)S->handlers[id]=v;
      int n=snprintf(buf,sizeof(buf),"{\"__handler\":%d}",id); jappend(S,buf,n); } break;
    case T_TAB:{
      Str*ut=NULL; Value uv=tget(v.u.t,VSTR(newStr(S,"__ui",4))); if(uv.tag==T_STR)ut=uv.u.s;
      if(ut){ jnode(S,v.u.t,depth+1); }
      else { /* plain table → json array of its sequence part */
        int len=tlen(v.u.t); jappend(S,"[",1);
        for(int i=1;i<=len;i++){ if(i>1)jappend(S,",",1); jvalue(S,tget(v.u.t,VNUM(i)),depth+1); }
        jappend(S,"]",1);
      }
    } break;
  }
}
static void jnode(State*S,Table*t,int depth){
  if(depth>LX_MAX_JSON_DEPTH) lx_rt_error(S,"ui tree too deep (possible cycle)");
  Value uv=tget(t,VSTR(newStr(S,"__ui",4)));
  jappend(S,"{\"type\":",8);
  if(uv.tag==T_STR)jstr(S,uv.u.s->p,uv.u.s->len); else jappend(S,"\"unknown\"",9);
  /* props: string keys except __ui and except node-valued (those go to children implicitly? keep as props if named) */
  jappend(S,",\"props\":{",10);
  int first=1;
  for(int i=0;i<t->cap;i++){ if(!t->e[i].used)continue; Value k=t->e[i].k;
    if(k.tag!=T_STR)continue; if(k.u.s->len==4 && memcmp(k.u.s->p,"__ui",4)==0)continue;
    if(!first)jappend(S,",",1); first=0;
    jstr(S,k.u.s->p,k.u.s->len); jappend(S,":",1); jvalue(S,t->e[i].v,depth+1);
  }
  jappend(S,"}",1);
  /* children: sequence part entries that are ui nodes; a bare string in a
   * child position is sugar for a text node (parity with JS/Py engines) */
  jappend(S,",\"children\":[",13);
  int len=tlen(t); int cfirst=1;
  for(int i=1;i<=len;i++){ Value c=tget(t,VNUM(i));
    if(c.tag==T_STR){
      if(!cfirst)jappend(S,",",1); cfirst=0;
      jappend(S,"{\"type\":\"text\",\"props\":{\"text\":",(int)sizeof("{\"type\":\"text\",\"props\":{\"text\":")-1);
      jstr(S,c.u.s->p,c.u.s->len);
      jappend(S,"},\"children\":[]}",(int)sizeof("},\"children\":[]}")-1);
      continue;
    }
    if(c.tag!=T_TAB)continue;
    Value cu=tget(c.u.t,VSTR(newStr(S,"__ui",4))); if(cu.tag!=T_STR)continue;
    if(!cfirst)jappend(S,",",1); cfirst=0; jnode(S,c.u.t,depth+1);
  }
  jappend(S,"]}",2);
}


/* ---- cancel / stdin / io (no-root program mode) ---- */
static void io_init(State*S){
  if(S->io_inited) return;
  pthread_mutex_init(&S->io_mu,NULL);
  pthread_cond_init(&S->io_cv,NULL);
  S->io_inited=1;
}
static void stdin_queue_push(State*S,const char*line){
  if(!line) line="";
  size_t n=strlen(line);
  io_init(S);
  pthread_mutex_lock(&S->io_mu);
  size_t need=S->stdin_qused+n+1+1;
  if(need>S->stdin_qsz){
    size_t ns=S->stdin_qsz?S->stdin_qsz*2:256;
    while(ns<need) ns*=2;
    char*nb=realloc(S->stdin_q,ns);
    if(!nb){ pthread_mutex_unlock(&S->io_mu); return; }
    S->stdin_q=nb; S->stdin_qsz=ns;
  }
  memcpy(S->stdin_q+S->stdin_qused,line,n);
  S->stdin_qused+=n;
  S->stdin_q[S->stdin_qused++]='\n';
  S->stdin_q[S->stdin_qused]=0;
  pthread_cond_broadcast(&S->io_cv);
  pthread_mutex_unlock(&S->io_mu);
}
static int stdin_queue_pop_line(State*S,char*out,size_t outlen){
  io_init(S);
  pthread_mutex_lock(&S->io_mu);
  S->stdin_waiting=1;
  for(;;){
    if(S->cancel_flag){
      S->stdin_waiting=0;
      pthread_mutex_unlock(&S->io_mu);
      return -1;
    }
    size_t i=0;
    while(i<S->stdin_qused && S->stdin_q[i]!='\n') i++;
    if(i<S->stdin_qused){
      size_t take=i;
      if(take>=outlen) take=outlen-1;
      memcpy(out,S->stdin_q,take);
      out[take]=0;
      size_t rem=S->stdin_qused-(i+1);
      memmove(S->stdin_q,S->stdin_q+i+1,rem);
      S->stdin_qused=rem;
      S->stdin_q[S->stdin_qused]=0;
      S->stdin_waiting=0;
      pthread_mutex_unlock(&S->io_mu);
      return (int)take;
    }
    pthread_cond_wait(&S->io_cv,&S->io_mu);
  }
}
static Value st_io_read(State*S,int argc,Value*argv){
  (void)argc;(void)argv;
  char line[4096];
  int n=stdin_queue_pop_line(S,line,sizeof(line));
  if(n<0) lx_rt_error(S,"cancelled by user");
  S->nret=1; S->retbuf[0]=VSTR(newStr(S,line,(size_t)n));
  return S->retbuf[0];
}
static Value st_io_write(State*S,int argc,Value*argv){
  for(int i=0;i<argc;i++){
    Str*st=toStrx(S,argv[i]);
    buf_append(&S->out,&S->outsz,&S->outused,st->p,st->len);
    fwrite(st->p,1,st->len,stdout);
  }
  S->nret=0; return VNIL;
}
static Value st_io_flush(State*S,int argc,Value*argv){ (void)argc;(void)argv; fflush(stdout); S->nret=0; return VNIL; }
static Value st_input(State*S,int argc,Value*argv){
  if(argc>=1){
    Str*st=toStrx(S,argv[0]);
    buf_append(&S->out,&S->outsz,&S->outused,st->p,st->len);
    fwrite(st->p,1,st->len,stdout);
  }
  return st_io_read(S,0,NULL);
}
static Value st_os_getenv(State*S,int argc,Value*argv){
  if(argc<1){ S->nret=1; S->retbuf[0]=VNIL; return VNIL; }
  Str*k=toStrx(S,argv[0]);
  const char* v=getenv(k->p);
  S->nret=1;
  if(!v){ S->retbuf[0]=VNIL; return VNIL; }
  S->retbuf[0]=VSTR(newStr(S,v,strlen(v)));
  return S->retbuf[0];
}
static Value st_os_time(State*S,int argc,Value*argv){
  (void)argc;(void)argv;
  S->nret=1; S->retbuf[0]=VNUM((double)time(NULL)); return S->retbuf[0];
}
static Value st_os_clock(State*S,int argc,Value*argv){
  (void)argc;(void)argv;
  S->nret=1; S->retbuf[0]=VNUM((double)clock()/(double)CLOCKS_PER_SEC); return S->retbuf[0];
}
static void openLibs(State*S){
  Table*g=S->globals->vars;
  regFn(S,g,"print",st_print); regFn(S,g,"type",st_type); regFn(S,g,"tostring",st_tostring); regFn(S,g,"tonumber",st_tonumber);
  regFn(S,g,"input",st_input);
  Table*io=newTable(S); tset(S,g,VSTR(newStr(S,"io",2)),VTAB(io));
  regFn(S,io,"read",st_io_read); regFn(S,io,"write",st_io_write); regFn(S,io,"flush",st_io_flush);
  Table*os=newTable(S); tset(S,g,VSTR(newStr(S,"os",2)),VTAB(os));
  regFn(S,os,"getenv",st_os_getenv); regFn(S,os,"time",st_os_time); regFn(S,os,"clock",st_os_clock);
  regFn(S,g,"pairs",st_pairs); regFn(S,g,"next",st_next); regFn(S,g,"ipairs",st_ipairs);
  regFn(S,g,"setmetatable",st_setmt); regFn(S,g,"getmetatable",st_getmt); regFn(S,g,"rawget",st_rawget); regFn(S,g,"rawset",st_rawset); regFn(S,g,"rawequal",st_raweq);
  regFn(S,g,"assert",st_assert); regFn(S,g,"error",st_error); regFn(S,g,"pcall",st_pcall); regFn(S,g,"select",st_select);
  Table*tb=newTable(S); tset(S,g,VSTR(newStr(S,"table",5)),VTAB(tb)); regFn(S,tb,"insert",st_tinsert); regFn(S,tb,"remove",st_tremove); regFn(S,tb,"concat",st_tconcat); regFn(S,tb,"unpack",st_unpack);
  Table*sb=newTable(S); tset(S,g,VSTR(newStr(S,"string",6)),VTAB(sb)); regFn(S,sb,"len",st_slen); regFn(S,sb,"upper",st_supper); regFn(S,sb,"lower",st_slower); regFn(S,sb,"sub",st_ssub); regFn(S,sb,"rep",st_srep);
  regFn(S,sb,"find",st_sfind); regFn(S,sb,"match",st_smatch); regFn(S,sb,"gsub",st_sgsub);
  regFn(S,sb,"format",st_sformat); regFn(S,sb,"byte",st_sbyte); regFn(S,sb,"char",st_schar); regFn(S,sb,"reverse",st_sreverse);
  Table*mt=newTable(S); tset(S,g,VSTR(newStr(S,"math",4)),VTAB(mt));
  regFn(S,mt,"floor",ma_floor); regFn(S,mt,"ceil",ma_ceil); regFn(S,mt,"abs",ma_abs); regFn(S,mt,"sqrt",ma_sqrt);
  regFn(S,mt,"max",ma_max); regFn(S,mt,"min",ma_min); regFn(S,mt,"random",ma_random); regFn(S,mt,"randomseed",ma_randomseed);
  regFn(S,tb,"sort",st_tsort);
  regFn(S,g,"require",st_require); package_loaded(S);
  Table*ui=newTable(S); tset(S,g,VSTR(newStr(S,"ui",2)),VTAB(ui));
  regFn(S,ui,"app",ui_app); regFn(S,ui,"column",ui_column); regFn(S,ui,"row",ui_row);
  regFn(S,ui,"text",ui_text); regFn(S,ui,"button",ui_button); regFn(S,ui,"card",ui_card);
  regFn(S,ui,"input",ui_input); regFn(S,ui,"image",ui_image); regFn(S,ui,"spacer",ui_spacer);
  regFn(S,ui,"divider",ui_divider); regFn(S,ui,"scrollview",ui_scrollview);
  regFn(S,ui,"list",ui_list); regFn(S,ui,"listitem",ui_listitem);
  regFn(S,ui,"stack",ui_stack); regFn(S,ui,"page",ui_page); regFn(S,ui,"switch",ui_switch);
  regFn(S,ui,"box",ui_box); regFn(S,ui,"slider",ui_slider); regFn(S,ui,"progress",ui_progress);
  lx_dostring(S,STRING_PRELUDE,NULL,0); /* string.gmatch (defined in Lua over C find) */
}

/* ---------- API ---------- */
State* lx_new(void){ State*S=calloc(1,sizeof(State)); S->globals=xalloc(S,sizeof(Env)); S->globals->vars=newTable(S); S->globals->parent=NULL; S->step_limit=0; pthread_mutex_init(&S->dbg_mu,NULL); pthread_cond_init(&S->dbg_cv,NULL); S->dbg_inited=1; io_init(S); openLibs(S); return S; }
void lx_close(State*S){ if(!S)return; S->cancel_flag=1; if(getenv("LUAX_BC_STATS"))fprintf(stderr,"bc: %ld compiled calls, %ld fallbacks\n",S->bc_calls,S->bc_fallbacks); if(S->dbg_inited){ pthread_mutex_lock(&S->dbg_mu); S->dbg_cmd=3; S->dbg_paused=0; pthread_cond_broadcast(&S->dbg_cv); pthread_mutex_unlock(&S->dbg_mu); pthread_mutex_destroy(&S->dbg_mu); pthread_cond_destroy(&S->dbg_cv); } if(S->io_inited){ pthread_mutex_lock(&S->io_mu); pthread_cond_broadcast(&S->io_cv); pthread_mutex_unlock(&S->io_mu); pthread_mutex_destroy(&S->io_mu); pthread_cond_destroy(&S->io_cv); } for(int i=0;i<S->npages;i++)free(S->pages[i].p); free(S->pages); free(S->vstack); free(S->out); free(S->json); free(S->dbg_locals); free(S->dbg_stack); free(S->dbg_eval_buf); free(S->stdin_q); free(S); }
int lx_dostring(State*S,const char*src,char*errbuf,int errlen){
  if(setjmp(S->err)){ S->call_depth=0; if(errbuf)snprintf(errbuf,errlen,"%s",S->errmsg); return 1; }
  Node*chunk=parse(S,src,strlen(src));
  Env*env=newEnv(S,S->globals);
  execChunk(S,env,chunk);
  return 0;
}
int lx_dofile(State*S,const char*path,char*errbuf,int errlen){
  FILE*f=fopen(path,"rb"); if(!f){if(errbuf)snprintf(errbuf,errlen,"cannot open %s",path);return 1;}
  fseek(f,0,SEEK_END); long n=ftell(f); fseek(f,0,SEEK_SET); char*buf=malloc(n+1); fread(buf,1,n,f); buf[n]=0; fclose(f);
  int r=lx_dostring(S,buf,errbuf,errlen); free(buf); return r;
}

/* reset per-run scratch (print buffer, json buffer, handlers, step counter) */
static void lx_reset_run(State*S){
  S->cancel_flag=0;
  S->vtop=0;
  S->outused=0; if(S->out)S->out[0]=0;
  S->jsonused=0; if(S->json)S->json[0]=0;
  S->nhandlers=0; S->steps=0; S->has_view=false; S->app_view=VNIL;
  S->dbg_paused=0; S->dbg_cmd=0; S->pause_line=0; S->pause_reason=0; S->cur_env=NULL; S->nstack=0; S->call_depth=0;
  free(S->dbg_stack); S->dbg_stack=NULL;
  free(S->dbg_eval_buf); S->dbg_eval_buf=NULL;
}
/* serialize the current app_view (calling it if it's a function) into S->json */
static void lx_build_tree(State*S){
  S->jsonused=0; if(S->json)S->json[0]=0; S->nhandlers=0;
  Value v=S->app_view;
  if(v.tag==T_FN||v.tag==T_CFN){ v=callValue(S,v,0,NULL); }
  if(v.tag==T_TAB){ Value uv=tget(v.u.t,VSTR(newStr(S,"__ui",4)));
    if(uv.tag==T_STR){ jnode(S,v.u.t,0); return; } }
  jappend(S,"null",4);
}

void lx_set_step_limit(State*S,long n){ if(S)S->step_limit=n; }

/* run source; capture return value as app_view; serialize UI tree to json.
 * returns 0 on success. out_json/out_print point into engine-owned buffers. */
int lx_run(State*S,const char*src,char*errbuf,int errlen){
  lx_reset_run(S);
  if(setjmp(S->err)){ S->call_depth=0; if(errbuf)snprintf(errbuf,errlen,"%s",S->errmsg); return 1; }
  Node*chunk=parse(S,src,strlen(src));
  Env*env=newEnv(S,S->globals);
  dbg_push_frame(S,"main",1,1);
  S->cur_env=env;
  struct Flow fl=execChunk(S,env,chunk);
  dbg_pop_frame(S);
  if(fl.kind==1 && fl.nret>0){ S->app_view=fl.rets[0]; S->has_view=true; }
  lx_build_tree(S);
  return 0;
}

/* invoke a previously-registered handler by id. Re-render contract:
 *  - arg (when non-NULL) is passed to the handler as its first argument
 *    (event payload, e.g. input text or "true"/"false" for a switch);
 *  - if the handler RETURNS a ui tree (table tagged __ui, or a function
 *    returning one), that value becomes the new app_view — the declarative
 *    equivalent of returning a fresh screen from an event handler;
 *  - otherwise the previous app_view is re-serialized (calling it first when
 *    it is a view function), so mutating state a view-function reads still
 *    re-renders. */
int lx_invoke(State*S,int handler_id,const char*arg,char*errbuf,int errlen){
  if(handler_id<0||handler_id>=S->nhandlers){ if(errbuf)snprintf(errbuf,errlen,"invalid handler id %d",handler_id); return 1; }
  Value h=S->handlers[handler_id];
  S->outused=0; if(S->out)S->out[0]=0; S->steps=0;
  /* ABI §4.4: an invoke error preserves the current tree — including a view
   * function throwing during re-serialization. Detach the live json buffer
   * and snapshot the handler table (ids are positional, so the old tree's
   * __handler references must keep resolving) so both restore for free. */
  char* sj=S->json; size_t ssz=S->jsonsz,su=S->jsonused; int snh=S->nhandlers;
  Value sav=S->app_view; /* rollback too — a poisonous tree must not stick */
  Value* hs=malloc(sizeof(Value)*(size_t)snh);
  if(hs) memcpy(hs,S->handlers,sizeof(Value)*(size_t)snh);
  S->json=NULL; S->jsonsz=0; S->jsonused=0; /* detached BEFORE any throw —
      the error path frees only the scratch buffer, never the live tree */
  if(setjmp(S->err)){
    S->call_depth=0; S->app_view=sav;
    free(S->json); S->json=sj; S->jsonsz=ssz; S->jsonused=su;
    if(hs){ memcpy(S->handlers,hs,sizeof(Value)*(size_t)snh); free(hs); }
    S->nhandlers=snh;
    if(errbuf)snprintf(errbuf,errlen,"%s",S->errmsg); return 1;
  }
  Value argv[1]; int argc=0;
  if(arg){ argv[0]=VSTR(newStr(S,arg,strlen(arg))); argc=1; }
  Value r=callValue(S,h,argc,argv);
  if(r.tag==T_TAB){
    Value uv=tget(r.u.t,VSTR(newStr(S,"__ui",4)));
    if(uv.tag==T_STR) S->app_view=r;
  }
  lx_build_tree(S);
  free(sj);
  free(hs);
  return 0;
}

const char* lx_last_json(lx_State*S){ return S->json?S->json:"null"; }
const char* lx_last_output(lx_State*S){ return S->out?S->out:""; }
void lx_clear_output(lx_State*S){ if(!S)return; S->outused=0; if(S->out)S->out[0]=0; }
/* Interactive program line: keep globals, capture print for this line only. */
int lx_repl(lx_State*S,const char*src,char*errbuf,int errlen){
  if(!S){ if(errbuf&&errlen)snprintf(errbuf,errlen,"nil state"); return 1; }
  S->cancel_flag=0;
  lx_clear_output(S);
  S->steps=0;
  if(!src)src="";
  /* trim leading spaces */
  while(*src==' '||*src=='\t') src++;
  char buf[8192];
  const char* run = src;
  if(src[0]=='='){
    int n=snprintf(buf,sizeof(buf),"print(%s)",src+1);
    if(n<=0||n>=(int)sizeof(buf)){ if(errbuf)snprintf(errbuf,errlen,"line too long"); return 1; }
    run=buf;
  }
  if(setjmp(S->err)){ S->call_depth=0; if(errbuf)snprintf(errbuf,errlen,"%s",S->errmsg); return 1; }
  Node*chunk=parse(S,run,strlen(run));
  Env*env=newEnv(S,S->globals);
  struct Flow fl=execChunk(S,env,chunk);
  /* if chunk returned values and printed nothing, print first return */
  if(fl.kind==1 && fl.nret>0 && S->outused==0){
    for(int i=0;i<fl.nret;i++){
      Str*st=toStrx(S,fl.rets[i]);
      if(i) buf_append(&S->out,&S->outsz,&S->outused,"\t",1);
      buf_append(&S->out,&S->outsz,&S->outused,st->p,st->len);
    }
    buf_append(&S->out,&S->outsz,&S->outused,"\n",1);
  }
  return 0;
}

void lx_cancel(lx_State*S){
  if(!S)return;
  S->cancel_flag=1;
  if(S->dbg_inited){ pthread_mutex_lock(&S->dbg_mu); S->dbg_cmd=3; S->dbg_paused=0; pthread_cond_broadcast(&S->dbg_cv); pthread_mutex_unlock(&S->dbg_mu); }
  if(S->io_inited){ pthread_mutex_lock(&S->io_mu); pthread_cond_broadcast(&S->io_cv); pthread_mutex_unlock(&S->io_mu); }
}
void lx_clear_cancel(lx_State*S){ if(S) S->cancel_flag=0; }
int lx_is_cancelled(lx_State*S){ return S && S->cancel_flag ? 1 : 0; }
void lx_push_stdin(lx_State*S,const char*line){ if(S) stdin_queue_push(S,line); }
int lx_waiting_stdin(lx_State*S){ return S && S->stdin_waiting ? 1 : 0; }
void lx_set_rootfs(lx_State*S,const char*path){ if(!S)return; if(!path){ S->rootfs[0]=0; return; } snprintf(S->rootfs,sizeof(S->rootfs),"%s",path); }
void lx_set_modroot(lx_State*S,const char*path){ if(!S)return; if(!path){ S->modroot[0]=0; return; } snprintf(S->modroot,sizeof(S->modroot),"%s",path); }
const char* lx_modroot(lx_State*S){ return (S&&S->modroot[0])?S->modroot:""; }
const char* lx_rootfs(lx_State*S){ return (S&&S->rootfs[0])?S->rootfs:""; }

void lx_debug_enable(State*S,int enabled){ if(!S)return; S->debug_enabled=enabled?1:0; if(!enabled){ S->step_mode=0; S->step_out_depth=0; pthread_mutex_lock(&S->dbg_mu); S->dbg_cmd=1; S->dbg_paused=0; pthread_cond_broadcast(&S->dbg_cv); pthread_mutex_unlock(&S->dbg_mu);} }
void lx_debug_set_break_on_error(State*S,int enabled){ if(S) S->break_on_error=enabled?1:0; }
int lx_debug_pause_reason(State*S){ return S?S->pause_reason:0; }
const char* lx_debug_last_error(State*S){ return (S&&S->errmsg[0])?S->errmsg:""; }
void lx_debug_set_breakpoints(State*S,const int*lines,int n){
  if(!S)return;
  pthread_mutex_lock(&S->dbg_mu);
  S->nbp=0;
  if(lines&&n>0){
    if(n>256)n=256;
    for(int i=0;i<n;i++) if(lines[i]>0){
      S->breakpoints[S->nbp].line=lines[i];
      S->breakpoints[S->nbp].cond[0]=0;
      S->breakpoints[S->nbp].logmsg[0]=0;
      S->breakpoints[S->nbp].log_only=0;
      S->nbp++;
    }
  }
  pthread_mutex_unlock(&S->dbg_mu);
}
void lx_debug_set_breakpoints_ex(State*S,const int*lines,const char*const*conds,int n){
  if(!S)return;
  pthread_mutex_lock(&S->dbg_mu);
  S->nbp=0;
  if(lines&&n>0){
    if(n>256)n=256;
    for(int i=0;i<n;i++) if(lines[i]>0){
      S->breakpoints[S->nbp].line=lines[i];
      S->breakpoints[S->nbp].cond[0]=0;
      S->breakpoints[S->nbp].logmsg[0]=0;
      S->breakpoints[S->nbp].log_only=0;
      if(conds&&conds[i]&&conds[i][0]){
        snprintf(S->breakpoints[S->nbp].cond,sizeof(S->breakpoints[S->nbp].cond),"%s",conds[i]);
      }
      S->nbp++;
    }
  }
  pthread_mutex_unlock(&S->dbg_mu);
}
void lx_debug_set_breakpoints_full(State*S,const int*lines,const char*const*conds,const char*const*logs,const int*log_only,int n){
  if(!S)return;
  pthread_mutex_lock(&S->dbg_mu);
  S->nbp=0;
  if(lines&&n>0){
    if(n>256)n=256;
    for(int i=0;i<n;i++) if(lines[i]>0){
      S->breakpoints[S->nbp].line=lines[i];
      S->breakpoints[S->nbp].cond[0]=0;
      S->breakpoints[S->nbp].logmsg[0]=0;
      S->breakpoints[S->nbp].log_only=(log_only&&log_only[i])?1:0;
      if(conds&&conds[i]&&conds[i][0]){
        snprintf(S->breakpoints[S->nbp].cond,sizeof(S->breakpoints[S->nbp].cond),"%s",conds[i]);
      }
      if(logs&&logs[i]&&logs[i][0]){
        snprintf(S->breakpoints[S->nbp].logmsg,sizeof(S->breakpoints[S->nbp].logmsg),"%s",logs[i]);
      }
      S->nbp++;
    }
  }
  pthread_mutex_unlock(&S->dbg_mu);
}
void lx_debug_clear_breakpoints(State*S){
  if(!S)return;
  pthread_mutex_lock(&S->dbg_mu);
  S->nbp=0;
  pthread_mutex_unlock(&S->dbg_mu);
}
void lx_debug_continue(State*S){ if(!S)return; pthread_mutex_lock(&S->dbg_mu); S->dbg_cmd=1; S->step_mode=0; S->step_out_depth=0; pthread_cond_signal(&S->dbg_cv); pthread_mutex_unlock(&S->dbg_mu); }
void lx_debug_step(State*S){ if(!S)return; pthread_mutex_lock(&S->dbg_mu); S->dbg_cmd=2; S->step_mode=1; S->step_out_depth=0; pthread_cond_signal(&S->dbg_cv); pthread_mutex_unlock(&S->dbg_mu); }
void lx_debug_step_out(State*S){
  if(!S)return;
  pthread_mutex_lock(&S->dbg_mu);
  if(S->nstack<=1){
    S->dbg_cmd=1; S->step_mode=0; S->step_out_depth=0;
  }else{
    S->step_out_depth=S->nstack-1;
    S->step_mode=2;
    S->dbg_cmd=4;
  }
  pthread_cond_signal(&S->dbg_cv);
  pthread_mutex_unlock(&S->dbg_mu);
}
void lx_debug_stop(State*S){ if(!S)return; pthread_mutex_lock(&S->dbg_mu); S->dbg_cmd=3; pthread_cond_signal(&S->dbg_cv); pthread_mutex_unlock(&S->dbg_mu); }
int lx_debug_is_paused(State*S){ return S&&S->dbg_paused?1:0; }
int lx_debug_pause_line(State*S){ return S?S->pause_line:0; }
const char* lx_debug_locals(State*S){ return (S&&S->dbg_locals)?S->dbg_locals:"[]"; }
const char* lx_debug_stack(State*S){ return (S&&S->dbg_stack)?S->dbg_stack:"[]"; }
const char* lx_debug_eval(State*S,const char*expr){
  if(S){ free(S->dbg_eval_buf); S->dbg_eval_buf=NULL; }
  if(!S||!S->dbg_paused||!S->cur_env){
    char*b=strdup("{\"ok\":false,\"error\":\"not paused\"}");
    if(S) S->dbg_eval_buf=b;
    return b?b:"{\"ok\":false}";
  }
  Value v; char err[160];
  int ok=dbg_eval_value(S,S->cur_env,expr,&v,err,sizeof(err));
  char*buf=NULL; size_t sz=0, used=0;
  if(ok){
    static const char prefix[]="{\"ok\":true,\"value\":";
    buf_append(&buf,&sz,&used,prefix,sizeof(prefix)-1);
    dbg_append_value(S,&buf,&sz,&used,v,2);
    buf_append(&buf,&sz,&used,"}",1);
  }else{
    static const char prefix[]="{\"ok\":false,\"error\":\"";
    buf_append(&buf,&sz,&used,prefix,sizeof(prefix)-1);
    dbg_json_escape(&buf,&sz,&used,err,strlen(err));
    buf_append(&buf,&sz,&used,"\"}",2);
  }
  S->dbg_eval_buf=buf;
  return buf?buf:"{\"ok\":false}";
}


