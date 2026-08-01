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
#include <pthread.h>
#include <time.h>

enum { T_NIL, T_BOOL, T_NUM, T_STR, T_TAB, T_FN, T_CFN };
typedef struct Str Str; typedef struct Table Table; typedef struct Value Value;
typedef struct Node Node; typedef struct Env Env; typedef struct Func Func;
typedef struct Closure Closure; typedef struct CFn CFn; typedef struct State State;

struct Str    { size_t len; char* p; };
struct Value  { int tag; union { bool b; double num; Str* s; Table* t; Closure* f; CFn* c; } u; };
struct Table  { int cap, n; struct TEntry { Value k, v; int used; } *e; Table* meta; };
struct Env    { Table* vars; Env* parent; };
struct Func   { int nparam; char** params; bool vararg; Node* body; Env* env; int line; };
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
  K_BINOP,K_UNOP,K_TABLE,K_FUNC,K_FIELD };
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
static void lx_error(State* S,const char* fmt,...){ va_list ap; va_start(ap,fmt);
  vsnprintf(S->errmsg,sizeof(S->errmsg),fmt,ap); va_end(ap); longjmp(S->err,1); }
/* Runtime error: auto-prefixes "line N: " from S->curLine (tracked as statements
 * execute), so failures like "attempt to call a nil value" are locatable in the
 * editor. Parser errors use lx_error directly since they already know their line. */
static void dbg_pause_now(State*S,Env*env,int line,int reason);
static void lx_rt_error(State* S,const char* fmt,...){
  char msg[400]; va_list ap; va_start(ap,fmt); vsnprintf(msg,sizeof(msg),fmt,ap); va_end(ap);
  if(S->curLine>0) snprintf(S->errmsg,sizeof(S->errmsg),"line %d: %s",S->curLine,msg);
  else snprintf(S->errmsg,sizeof(S->errmsg),"%s",msg);
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
static Str* readStr(Lex*L,char q){ char buf[4096];size_t m=0;
  while(L->n&&L->s[0]!=q){ char c=*L->s++;L->n--; if(c=='\n')L->line++;
    if(c=='\\'&&L->n){char e=*L->s++;L->n--;
      switch(e){case'n':c='\n';break;case't':c='\t';break;case'r':c='\r';break;case'\\':c='\\';break;
        case'"':c='"';break;case'\'':c='\'';break;case'a':c='\a';break;case'b':c='\b';break;
        case'f':c='\f';break;case'v':c='\v';break;case'x':c=(char)readHex(L);break;
        case'z':while(L->n&&isspace((unsigned char)L->s[0])){if(L->s[0]=='\n')L->line++;L->s++;L->n--;}continue;
        default:if(isdigit((unsigned char)e)){L->s--;L->n++;c=(char)readDec(L);}else c=e;}}
    if(m<sizeof(buf))buf[m++]=c; }
  if(!L->n)lx_error(L->S,"line %d: unterminated string",L->line); L->s++;L->n--; return newStr(L->S,buf,m); }
static void skipLong(Lex*L,int sep){ while(L->n){ if(L->s[0]==']'){int k=0;const char*p=L->s;size_t nn=L->n;while(nn&&p[0]==']'){k++;p++;nn--;}if(k==sep+1){L->s=p;L->n=nn;return;}} if(L->s[0]=='\n')L->line++;L->s++;L->n--; } }
static Str* longStr(Lex*L,int sep){ if(L->n&&L->s[0]=='\r'){L->s++;L->n--;} if(L->n&&L->s[0]=='\n'){L->s++;L->n--;L->line++;}
  char*buf=NULL;size_t m=0,cap=0;
  while(L->n){ if(L->s[0]==']'){int k=0;const char*p=L->s;size_t nn=L->n;while(nn&&p[0]==']'){k++;p++;nn--;}if(k==sep+1){L->s=p;L->n=nn;Str*s=newStr(L->S,buf?buf:"",m);if(buf)free(buf);return s;}} if(L->s[0]=='\n')L->line++; if(m+1>cap){cap=cap?cap*2:64;buf=realloc(buf,cap);} buf[m++]=*L->s++;L->n--; } lx_error(L->S,"line %d: unterminated long string",L->line); return NULL; }
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
static Node* expr(P*);static Node* block(P*);static Node* tablecons(P*);static Node* funcbody(P*);
static Node* suffix(P*p,Node*base){ State*S=p->S;
  while(1){ int k=p->L.cur.kind;
    if(k=='.'){pnext(p);Node*idx=node(S,K_INDEX);idx->a=base;char*nm=p->L.cur.name;idx->b=node(S,K_STR);idx->b->str=newStr(S,nm,strlen(nm));pnext(p);base=idx;}
    else if(k=='['){pnext(p);Node*idx=node(S,K_INDEX);idx->a=base;idx->b=expr(p);expect(p,']',"']'");base=idx;}
    else if(k==':'){pnext(p);char*m=p->L.cur.name;pnext(p);Node*mc=node(S,K_METHODCALL);mc->a=base;mc->method=m;Node*args=node(S,0);
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
  if(p->L.cur.kind=='('){pnext(p);base=expr(p);expect(p,')',"')'");base=suffix(p,base);}
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
  while(p->L.cur.kind!=')'&&p->L.cur.kind!=T_EOF){if(p->L.cur.kind==T_DOTS){pnext(p);f->vararg=1;break;}pn(S,f,p->L.cur.name);pnext(p);if(!accept(p,','))break;}
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
      else{Node*names=node(S,0);pn(S,names,first);while(accept(p,',')){pn(S,names,p->L.cur.name);pnext(p);}expect(p,T_IN,"'in'");Node*ex=node(S,0);pl(S,ex,expr(p));while(accept(p,','))pl(S,ex,expr(p));expect(p,T_DO,"'do'");Node*body=block(p);expect(p,T_END,"'end'");Node*n=node(S,K_GFOR);n->names=names->names;n->nnames=names->nnames;n->list=ex->list;n->nlist=ex->nlist;n->body=body;out=n;break;}}
    lx_error(S,"line %d: bad for",line);}
  case T_FUNCTION:{pnext(p);Node*target=node(S,K_NAME);target->name=p->L.cur.name;pnext(p);bool isMethod=false;
    while(p->L.cur.kind=='.'||p->L.cur.kind==':'){ if(p->L.cur.kind==':')isMethod=true; pnext(p);char*nm=p->L.cur.name;pnext(p);Node*idx=node(S,K_INDEX);idx->a=target;idx->b=node(S,K_STR);idx->b->str=newStr(S,nm,strlen(nm));target=idx; }
    Node*fd=node(S,K_FUNCDECL);fd->a=target;fd->body=funcbody(p);
    if(isMethod){Node*f=fd->body;char**np=xalloc(S,(f->nnames+1)*sizeof(char*));np[0]="self";if(f->nnames)memcpy(np+1,f->names,f->nnames*sizeof(char*));f->names=np;f->nnames++;}
    out=fd;break;}
  case T_LOCAL:{pnext(p);
    if(accept(p,T_FUNCTION)){Node*fd=node(S,K_FUNCDECL);fd->isLocal=1;Node*target=node(S,K_NAME);target->name=p->L.cur.name;pnext(p);fd->a=target;fd->body=funcbody(p);out=fd;break;}
    Node*n=node(S,K_LOCAL);pn(S,n,p->L.cur.name);pnext(p);while(accept(p,',')){pn(S,n,p->L.cur.name);pnext(p);}Node*vals=node(S,0);if(accept(p,'=')){pl(S,vals,expr(p));while(accept(p,','))pl(S,vals,expr(p));}n->list=vals->list;n->nlist=vals->nlist;out=n;break;}
  case T_RETURN:{pnext(p);Node*n=node(S,K_RET);if(p->L.cur.kind!=';'&&p->L.cur.kind!=T_END&&p->L.cur.kind!=T_ELSE&&p->L.cur.kind!=T_ELSEIF&&p->L.cur.kind!=T_UNTIL&&p->L.cur.kind!=T_EOF){Node*e=node(S,0);pl(S,e,expr(p));while(accept(p,','))pl(S,e,expr(p));n->list=e->list;n->nlist=e->nlist;}accept(p,';');out=n;break;}
  case T_BREAK:pnext(p);out=node(S,K_BREAK);break;
  case T_GOTO:{pnext(p);Node*n=node(S,K_GOTO);n->name=p->L.cur.name;pnext(p);out=n;break;}
  case T_DBCOLON:{pnext(p);Node*n=node(S,K_LABEL);n->name=p->L.cur.name;pnext(p);out=n;break;}
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
static bool toBool(Value v){return !(v.tag==T_NIL||(v.tag==T_BOOL&&!v.u.b)); }
static void envDeclareFn(State*S,Env*e,const char*name,Value v){ Str*st=newStr(S,name,strlen(name)); tset(S,e->vars,VSTR(st),v); }
static void envAssignFn(State*S,Env*e,const char*name,Value v){ Str*st=newStr(S,name,strlen(name)); for(Env*p=e;p;p=p->parent){Value*f=tfind(p->vars,VSTR(st));if(f){*f=v;return;}} tset(S,S->globals->vars,VSTR(st),v); }
static Value envGetFn(State*S,Env*e,const char*name){ Str*st=newStr(S,name,strlen(name)); for(Env*p=e;p;p=p->parent){Value*f=tfind(p->vars,VSTR(st));if(f)return *f;} Value*f=tfind(S->globals->vars,VSTR(st)); return f?*f:VNIL; }

static Value eval(State*S,Env*env,Node*e);
static struct Flow exec(State*S,Env*env,Node*st);
static struct Flow execChunk(State*S,Env*env,Node*chunk);
static void dbg_pause_now(State*S,Env*env,int line,int reason);
static Value callValue(State*S,Value f,int argc,Value*argv);
static Str* toStrx(State*S,Value v);

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
  for(int depth=0;depth<100;depth++){
    if(t.tag==T_TAB){Value v=tget(t.u.t,k);if(v.tag!=T_NIL)return v;if(t.u.t->meta){Value mt=tget(t.u.t->meta,VSTR(newStr(S,"__index",7)));if(mt.tag==T_TAB){t=mt;continue;}if(mt.tag!=T_NIL){Value args[2]={t,k};return callValue(S,mt,2,args);}}return VNIL;}
    lx_rt_error(S,"attempt to index a %s value",lx_typename(t));
  }
  return VNIL;
}
static void newIndex(State*S,Value t,Value k,Value v){
  if(t.tag==T_TAB){ if(tget(t.u.t,k).tag!=T_NIL){tset(S,t.u.t,k,v);return;} if(t.u.t->meta){Value mt=tget(t.u.t->meta,VSTR(newStr(S,"__newindex",10)));if(mt.tag==T_TAB){newIndex(S,mt,k,v);return;}if(mt.tag!=T_NIL){Value args[3]={t,k,v};callValue(S,mt,3,args);return;}} tset(S,t.u.t,k,v);return; }
  lx_rt_error(S,"attempt to index a %s value",lx_typename(t));
}
static void evalInto(State*S,Env*env,Node*e,Value*out,int*nout){
  if(e->kind==K_CALL||e->kind==K_METHODCALL){ Value v=eval(S,env,e); int n=S->nret; if(n>64)n=64; for(int i=0;i<n;i++)out[i]=S->retbuf[i]; *nout=n; return; }
  out[0]=eval(S,env,e);*nout=1;
}
static int buildArgs(State*S,Env*env,Node**args,int n,Value*argv){
  int na=0; for(int i=0;i<n;i++){ if(i==n-1){int m;evalInto(S,env,args[i],argv+na,&m);na+=m;}else{argv[na++]=eval(S,env,args[i]);} } return na;
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
static Value callValue(State*S,Value f,int argc,Value*argv){
  if(f.tag==T_CFN){
    const char*nm=f.u.c&&f.u.c->name?f.u.c->name:"[C]";
    if(S->nstack>0) dbg_touch_top(S,S->curLine);
    dbg_push_frame(S,nm,S->curLine,0);
    Value r=f.u.c->fn(S,argc,argv);
    dbg_pop_frame(S);
    return r;
  }
  if(f.tag==T_FN){
    Func*fn=f.u.f->f; Env*e=newEnv(S,fn->env);
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
  case K_INDEX:{ Value t=eval(S,env,e->a); Value k=eval(S,env,e->b); return indexVal(S,t,k); }
  case K_CALL:{ Value f=eval(S,env,e->a); Value argv[256]; int na=buildArgs(S,env,e->list,e->nlist,argv); return callValue(S,f,na,argv); }
  case K_METHODCALL:{ Value o=eval(S,env,e->a); Value f=indexVal(S,o,VSTR(newStr(S,e->method,strlen(e->method)))); Value argv[256]; argv[0]=o; int na=1+buildArgs(S,env,e->list,e->nlist,argv+1); return callValue(S,f,na,argv); }
  case K_FUNC:{ Func*fn=xalloc(S,sizeof(Func)); fn->nparam=e->nnames; fn->params=e->names; fn->vararg=e->vararg; fn->body=e->body; fn->env=env; fn->line=e->line>0?e->line:(e->body&&e->body->line>0?e->body->line:S->curLine); Closure*cl=xalloc(S,sizeof(Closure)); cl->f=fn; return VFN(cl); }
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
      case'|':return VNUM((double)((int64_t)x|(int64_t)y));case'&':return VNUM((double)((int64_t)x&(int64_t)y));case'~':return VNUM((double)((int64_t)x^(int64_t)y));case T_SHL:return VNUM((double)((int64_t)x<<((int64_t)y&63)));case T_SHR:return VNUM((double)((int64_t)x>>((int64_t)y&63)));} }
  }
  return VNIL;
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
    else if(fl.kind==1) v=VNIL;
    if(out) *out=v;
    ok=1;
    if(err&&errlen) err[0]=0;
  }else{
    if(err&&errlen) snprintf(err,errlen,"%s",S->errmsg);
    ok=0;
  }
  memcpy(&S->err,&outer,sizeof(outer));
  S->nstack=depth;
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
    default: buf_append(buf,sz,used,"null",4); break;
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
      if(st->nlist){ for(int i=0;i<st->nlist;i++){ if(i==st->nlist-1){int m;evalInto(S,env,st->list[i],vals+nval,&m);nval+=m;}else vals[nval++]=eval(S,env,st->list[i]); } }
      for(int i=0;i<st->nnames;i++) envDeclareFn(S,env,st->names[i], i<nval?vals[i]:VNIL);
      break; }
  case K_ASSIGN:{ Value vs[64]; int nv=0; if(st->nlist2){ for(int i=0;i<st->nlist2;i++){ if(i==st->nlist2-1){int m;evalInto(S,env,st->list2[i],vs+nv,&m);nv+=m;}else vs[nv++]=eval(S,env,st->list2[i]); } } for(int i=0;i<st->nlist;i++) assignTarget(S,env,st->list[i], i<nv?vs[i]:VNIL); break; }
  case K_CALLSTAT:{ int dummy; Value tmp[1]; evalInto(S,env,st->a,tmp,&dummy); break; }
  case K_DO:{ Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; for(int i=0;i<st->body->nlist;i++){fl=exec(S,ne,st->body->list[i]);if(fl.kind)return fl;} return F_NORMAL; }
  case K_IF:{ if(toBool(eval(S,env,st->a))){ Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; for(int i=0;i<st->body->nlist;i++){fl=exec(S,ne,st->body->list[i]);if(fl.kind)return fl;} return F_NORMAL; }
      for(int i=0;i<st->nlist;i++){ Node*eli=st->list[i]; if(toBool(eval(S,env,eli->a))){ Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; for(int j=0;j<eli->body->nlist;j++){fl=exec(S,ne,eli->body->list[j]);if(fl.kind)return fl;} return F_NORMAL; } }
      if(st->b){ Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; for(int i=0;i<st->b->nlist;i++){fl=exec(S,ne,st->b->list[i]);if(fl.kind)return fl;} } break; }
  case K_WHILE:{ while(toBool(eval(S,env,st->a))){ STEP(); Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; bool brk=false; for(int i=0;i<st->body->nlist;i++){fl=exec(S,ne,st->body->list[i]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; } break; }
  case K_REPEAT:{ while(1){ STEP(); Env*ne=newEnv(S,env); struct Flow fl=F_NORMAL; bool brk=false; for(int i=0;i<st->body->nlist;i++){fl=exec(S,ne,st->body->list[i]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; if(toBool(eval(S,env,st->a)))break; } break; }
  case K_NFOR:{ double s=toNum(S,eval(S,env,st->a)), en=toNum(S,eval(S,env,st->b)), step=st->c?toNum(S,eval(S,env,st->c)):1;
      if(step>0){ for(double i=s;i<=en;i+=step){ STEP(); Env*ne=newEnv(S,env); envDeclareFn(S,ne,st->name,VNUM(i)); struct Flow fl=F_NORMAL; bool brk=false; for(int j=0;j<st->body->nlist;j++){fl=exec(S,ne,st->body->list[j]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; } }
      else { for(double i=s;i>=en;i+=step){ STEP(); Env*ne=newEnv(S,env); envDeclareFn(S,ne,st->name,VNUM(i)); struct Flow fl=F_NORMAL; bool brk=false; for(int j=0;j<st->body->nlist;j++){fl=exec(S,ne,st->body->list[j]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; } } break; }
  case K_GFOR:{ Value fs[8]; int nf=0; if(st->nlist){ for(int i=0;i<st->nlist;i++){ if(i==st->nlist-1){int m;evalInto(S,env,st->list[i],fs+nf,&m);nf+=m;}else fs[nf++]=eval(S,env,st->list[i]); } } if(nf<1)break;
      Value it=fs[0],state=fs[1],ctrl=fs[2];
      while(1){ STEP(); Value args[2]={state,ctrl}; Value r=callValue(S,it,2,args); int n=S->nret; if(n==0||(n>=1&&r.tag==T_NIL))break; ctrl=r;
        Value vals[8]; vals[0]=r; for(int i=1;i<n&&i<8;i++)vals[i]=S->retbuf[i]; int nv=n>0?n:1;
        Env*ne=newEnv(S,env); for(int i=0;i<st->nnames;i++)envDeclareFn(S,ne,st->names[i], i<nv?vals[i]:VNIL);
        struct Flow fl=F_NORMAL; bool brk=false; for(int j=0;j<st->body->nlist;j++){fl=exec(S,ne,st->body->list[j]);if(fl.kind==1)return fl;if(fl.kind==2){brk=true;break;}} if(brk)break; }
      break; }
  case K_RET:{ struct Flow fl; fl.kind=1; fl.nret=0; if(st->nlist){ for(int i=0;i<st->nlist;i++){ if(i==st->nlist-1){int m;evalInto(S,env,st->list[i],fl.rets+fl.nret,&m);fl.nret+=m;}else fl.rets[fl.nret++]=eval(S,env,st->list[i]); } } return fl; }
  case K_BREAK:{ struct Flow fl; fl.kind=2; fl.nret=0; return fl; }
  case K_FUNCDECL:{ Node*t=st->a; Value fn=eval(S,env,st->body); if(st->isLocal&&t->kind==K_NAME) envDeclareFn(S,env,t->name,fn); else assignTarget(S,env,t,fn); break; }
  }
  return F_NORMAL;
}
static struct Flow execChunk(State*S,Env*env,Node*chunk){ struct Flow fl=F_NORMAL; for(int i=0;i<chunk->nlist;i++){fl=exec(S,env,chunk->list[i]);if(fl.kind)break;} return fl; }

/* ---------- stdlib ---------- */
static CFn* mkCFn(State*S,const char*name,Value(*fn)(State*,int,Value*)){CFn*c=xalloc(S,sizeof(CFn));c->fn=fn;c->name=name;return c;}
static Value st_next(State*S,int argc,Value*argv);
static Value st_ipiter(State*S,int argc,Value*argv);

static Value st_print(State*S,int argc,Value*argv){ for(int i=0;i<argc;i++){ Str*st=toStrx(S,argv[i]); if(i){fputs("\t",stdout); buf_append(&S->out,&S->outsz,&S->outused,"\t",1);} fwrite(st->p,1,st->len,stdout); buf_append(&S->out,&S->outsz,&S->outused,st->p,st->len);} fputc('\n',stdout); buf_append(&S->out,&S->outsz,&S->outused,"\n",1); S->nret=0; return VNIL; }
static Value st_type(State*S,int argc,Value*argv){ const char*n=lx_typename(argv[0]); S->nret=1; S->retbuf[0]=VSTR(newStr(S,n,strlen(n))); return S->retbuf[0]; }
static Value st_tostring(State*S,int argc,Value*argv){ S->nret=1; S->retbuf[0]=VSTR(toStrx(S,argv[0])); return S->retbuf[0]; }
static Value st_tonumber(State*S,int argc,Value*argv){ Value v=argv[0]; if(v.tag==T_NUM){S->nret=1;S->retbuf[0]=v;return v;} if(v.tag==T_STR){char*e;double d=strtod(v.u.s->p,&e);if(e!=v.u.s->p){S->nret=1;S->retbuf[0]=VNUM(d);return S->retbuf[0];}} S->nret=1; S->retbuf[0]=VNIL; return VNIL; }
static Value st_next(State*S,int argc,Value*argv){ Table*t=argv[0].u.t; Value k=argc>1?argv[1]:VNIL; int from=0;
  if(k.tag!=T_NIL){ unsigned h=hashVal(k)&(t->cap-1); int found=-1; for(int i=0;i<t->cap;i++){int j=(h+i)&(t->cap-1);if(!t->e[j].used)break;if(valEq(t->e[j].k,k)){found=j;break;}} if(found<0)lx_rt_error(S,"invalid key to 'next'"); from=found+1; }
  for(int i=from;i<t->cap;i++){ if(t->e[i].used){ S->nret=2; S->retbuf[0]=t->e[i].k; S->retbuf[1]=t->e[i].v; return S->retbuf[0]; } } S->nret=1; S->retbuf[0]=VNIL; return VNIL; }
static Value st_pairs(State*S,int argc,Value*argv){ S->nret=3; S->retbuf[0]=VCFN(mkCFn(S,"next",st_next)); S->retbuf[1]=argv[0]; S->retbuf[2]=VNIL; return S->retbuf[0]; }
static Value st_ipiter(State*S,int argc,Value*argv){ Table*t=argv[0].u.t; int i=(int)argv[1].u.num+1; Value v=tget(t,VNUM(i)); if(v.tag==T_NIL){S->nret=1;S->retbuf[0]=VNIL;return VNIL;} S->nret=2; S->retbuf[0]=VNUM(i); S->retbuf[1]=v; return S->retbuf[0]; }
static Value st_ipairs(State*S,int argc,Value*argv){ S->nret=3; S->retbuf[0]=VCFN(mkCFn(S,"iter",st_ipiter)); S->retbuf[1]=argv[0]; S->retbuf[2]=VNUM(0); return S->retbuf[0]; }
static Value st_setmt(State*S,int argc,Value*argv){ if(argv[0].tag!=T_TAB)lx_rt_error(S,"bad argument #1 to 'setmetatable'"); if(argv[1].tag!=T_TAB&&argv[1].tag!=T_NIL)lx_rt_error(S,"bad argument #2 to 'setmetatable'"); argv[0].u.t->meta=argv[1].tag==T_TAB?argv[1].u.t:NULL; S->nret=1; S->retbuf[0]=argv[0]; return argv[0]; }
static Value st_getmt(State*S,int argc,Value*argv){ Table*m=argv[0].tag==T_TAB?argv[0].u.t->meta:NULL; S->nret=1; S->retbuf[0]=m?VTAB(m):VNIL; return S->retbuf[0]; }
static Value st_rawget(State*S,int argc,Value*argv){ S->nret=1; S->retbuf[0]=argv[0].tag==T_TAB?tget(argv[0].u.t,argv[1]):VNIL; return S->retbuf[0]; }
static Value st_rawset(State*S,int argc,Value*argv){ tset(S,argv[0].u.t,argv[1],argv[2]); S->nret=1; S->retbuf[0]=argv[0]; return S->retbuf[0]; }
static Value st_raweq(State*S,int argc,Value*argv){ S->nret=1; S->retbuf[0]=VBOOL(valEq(argv[0],argv[1])); return S->retbuf[0]; }
static Value st_assert(State*S,int argc,Value*argv){ if(!toBool(argv[0]))lx_rt_error(S,argc>1&&argv[1].tag==T_STR?argv[1].u.s->p:"assertion failed!"); for(int i=0;i<argc&&i<64;i++)S->retbuf[i]=argv[i]; S->nret=argc; return argc?argv[0]:VNIL; }
static Value st_error(State*S,int argc,Value*argv){ Str*st=toStrx(S,argv[0]); lx_rt_error(S,"%.*s",(int)st->len,st->p); return VNIL; }
static Value st_pcall(State*S,int argc,Value*argv){ Value f=argv[0]; jmp_buf outer; memcpy(&outer,&S->err,sizeof(outer));
  int depth=S->nstack;
  if(setjmp(S->err)==0){ Value r=callValue(S,f,argc-1,argv+1); int n=S->nret; Value tmp[64]; tmp[0]=r; for(int i=1;i<n&&i<64;i++)tmp[i]=S->retbuf[i];
    memcpy(&S->err,&outer,sizeof(outer)); S->nstack=depth; S->retbuf[0]=VBOOL(1); for(int i=0;i<n&&i<63;i++)S->retbuf[1+i]=tmp[i]; S->nret=n+1; return S->retbuf[0]; }
  memcpy(&S->err,&outer,sizeof(outer)); S->nstack=depth; S->retbuf[0]=VBOOL(0); S->retbuf[1]=VSTR(newStr(S,S->errmsg,strlen(S->errmsg))); S->nret=2; return S->retbuf[0]; }
static Value st_select(State*S,int argc,Value*argv){ if(argv[0].tag==T_STR&&argv[0].u.s->len==1&&argv[0].u.s->p[0]=='#'){S->nret=1;S->retbuf[0]=VNUM(argc-1);return S->retbuf[0];} int n=(int)toNum(S,argv[0]); if(n<0)n=argc+n-1; else if(n==0)lx_rt_error(S,"bad argument #1 to 'select'"); S->nret=argc-n; for(int i=0;i+n<argc;i++)S->retbuf[i]=argv[n+i]; return S->nret?S->retbuf[0]:VNIL; }
static Value st_unpack(State*S,int argc,Value*argv){ Table*t=argv[0].u.t; int i=(int)(argc>1?toNum(S,argv[1]):1); int j=(int)(argc>2?toNum(S,argv[2]):tlen(t)); S->nret=0; for(;i<=j;i++)S->retbuf[S->nret++]=tget(t,VNUM(i)); return S->nret?S->retbuf[0]:VNIL; }
static Value st_slen(State*S,int argc,Value*argv){S->nret=1;S->retbuf[0]=VNUM((double)argv[0].u.s->len);return S->retbuf[0];}
static Value st_supper(State*S,int argc,Value*argv){Str*s=argv[0].u.s;char*b=xalloc(S,s->len);for(size_t i=0;i<s->len;i++)b[i]=toupper((unsigned char)s->p[i]);S->nret=1;S->retbuf[0]=VSTR(newStr(S,b,s->len));return S->retbuf[0];}
static Value st_slower(State*S,int argc,Value*argv){Str*s=argv[0].u.s;char*b=xalloc(S,s->len);for(size_t i=0;i<s->len;i++)b[i]=tolower((unsigned char)s->p[i]);S->nret=1;S->retbuf[0]=VSTR(newStr(S,b,s->len));return S->retbuf[0];}
static Value st_ssub(State*S,int argc,Value*argv){Str*s=argv[0].u.s;int len=(int)s->len;int i=(int)toNum(S,argv[1]);if(i<0)i+=len+1;if(i<1)i=1;int j=argc>2?(int)toNum(S,argv[2]):-1;if(j<0)j+=len+1;if(j>len)j=len;S->nret=1;S->retbuf[0]=(i<=j)?VSTR(newStr(S,s->p+i-1,j-i+1)):VSTR(newStr(S,"",0));return S->retbuf[0];}
static Value st_srep(State*S,int argc,Value*argv){Str*s=argv[0].u.s;int n=(int)toNum(S,argv[1]);if(n<0)n=0;char*b=xalloc(S,(size_t)n*s->len+1);for(int k=0;k<n;k++)memcpy(b+k*s->len,s->p,s->len);S->nret=1;S->retbuf[0]=VSTR(newStr(S,b,(size_t)n*s->len));return S->retbuf[0];}
static Value st_tinsert(State*S,int argc,Value*argv){Table*t=argv[0].u.t;if(argc==2){int n=tlen(t);tset(S,t,VNUM(n+1),argv[1]);}else{int p=(int)toNum(S,argv[1]);int n=tlen(t);for(int i=n;i>=p;i--)tset(S,t,VNUM(i+1),tget(t,VNUM(i)));tset(S,t,VNUM(p),argv[2]);}S->nret=0;return VNIL;}
static Value st_tremove(State*S,int argc,Value*argv){Table*t=argv[0].u.t;int n=tlen(t);int p=argc>1?(int)toNum(S,argv[1]):n;if(p<1)p=1;if(p>n){S->nret=1;S->retbuf[0]=VNIL;return VNIL;}Value v=tget(t,VNUM(p));for(int i=p;i<n;i++)tset(S,t,VNUM(i),tget(t,VNUM(i+1)));tset(S,t,VNUM(n),VNIL);S->nret=1;S->retbuf[0]=v;return v;}
static Value st_tconcat(State*S,int argc,Value*argv){Table*t=argv[0].u.t;Str*sep=argc>1&&argv[1].tag==T_STR?argv[1].u.s:NULL;int i=argc>2?(int)toNum(S,argv[2]):1;int j=argc>3?(int)toNum(S,argv[3]):tlen(t);char*buf=NULL;size_t m=0,cap=0;for(;i<=j;i++){Value v=tget(t,VNUM(i));if(v.tag!=T_STR&&v.tag!=T_NUM)lx_rt_error(S,"invalid value (table.concat)");Str*s=toStrx(S,v);if(m+s->len>cap){cap=cap?cap*2:64;while(m+s->len>cap)cap*=2;buf=realloc(buf,cap);}memcpy(buf+m,s->p,s->len);m+=s->len;if(i<j&&sep){if(m+sep->len>cap){cap=cap?cap*2:64;while(m+sep->len>cap)cap*=2;buf=realloc(buf,cap);}memcpy(buf+m,sep->p,sep->len);m+=sep->len;}}S->nret=1;S->retbuf[0]=m?VSTR(newStr(S,buf,m)):VSTR(newStr(S,"",0));if(buf)free(buf);return S->retbuf[0];}
static void regFn(State*S,Table*t,const char*name,Value(*fn)(State*,int,Value*)){tset(S,t,VSTR(newStr(S,name,strlen(name))),VCFN(mkCFn(S,name,fn)));}

/* ---------- declarative ui library ---------- */
/* ui.<type>{ props..., children... } → tags a table with __ui=<type> and returns it. */
static Value ui_ctor(State*S,const char*type,int argc,Value*argv){
  Table*t = (argc>0 && argv[0].tag==T_TAB) ? argv[0].u.t : newTable(S);
  tset(S,t,VSTR(newStr(S,"__ui",4)),VSTR(newStr(S,type,strlen(type))));
  S->nret=1; S->retbuf[0]=VTAB(t); return S->retbuf[0];
}
#define UICTOR(NM) static Value ui_##NM(State*S,int argc,Value*argv){ return ui_ctor(S,#NM,argc,argv); }
UICTOR(app) UICTOR(column) UICTOR(row) UICTOR(text) UICTOR(button) UICTOR(card)
UICTOR(input) UICTOR(image) UICTOR(spacer) UICTOR(divider) UICTOR(scrollview)
UICTOR(list) UICTOR(listitem) UICTOR(stack) UICTOR(page) UICTOR(switch)
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
  } else {
    free(src); src=NULL;
    memcpy(&S->err,&outer,sizeof(outer));
    S->nstack=depth;
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
static void jnode(State*S,Table*t);
static void jvalue(State*S,Value v){
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
      if(ut){ jnode(S,v.u.t); }
      else { /* plain table → json array of its sequence part */
        int len=tlen(v.u.t); jappend(S,"[",1);
        for(int i=1;i<=len;i++){ if(i>1)jappend(S,",",1); jvalue(S,tget(v.u.t,VNUM(i))); }
        jappend(S,"]",1);
      }
    } break;
  }
}
static void jnode(State*S,Table*t){
  Value uv=tget(t,VSTR(newStr(S,"__ui",4)));
  jappend(S,"{\"type\":",8);
  if(uv.tag==T_STR)jstr(S,uv.u.s->p,uv.u.s->len); else jappend(S,"\"unknown\"",9);
  /* props: string keys except __ui and except node-valued (those go to children implicitly? keep as props if named) */
  jappend(S,",\"props\":{",10);
  int first=1;
  for(int i=0;i<t->cap;i++){ if(!t->e[i].used)continue; Value k=t->e[i].k;
    if(k.tag!=T_STR)continue; if(k.u.s->len==4 && memcmp(k.u.s->p,"__ui",4)==0)continue;
    if(!first)jappend(S,",",1); first=0;
    jstr(S,k.u.s->p,k.u.s->len); jappend(S,":",1); jvalue(S,t->e[i].v);
  }
  jappend(S,"}",1);
  /* children: sequence part entries that are ui nodes */
  jappend(S,",\"children\":[",13);
  int len=tlen(t); int cfirst=1;
  for(int i=1;i<=len;i++){ Value c=tget(t,VNUM(i)); if(c.tag!=T_TAB)continue;
    Value cu=tget(c.u.t,VSTR(newStr(S,"__ui",4))); if(cu.tag!=T_STR)continue;
    if(!cfirst)jappend(S,",",1); cfirst=0; jnode(S,c.u.t);
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
  regFn(S,g,"require",st_require); package_loaded(S);
  Table*ui=newTable(S); tset(S,g,VSTR(newStr(S,"ui",2)),VTAB(ui));
  regFn(S,ui,"app",ui_app); regFn(S,ui,"column",ui_column); regFn(S,ui,"row",ui_row);
  regFn(S,ui,"text",ui_text); regFn(S,ui,"button",ui_button); regFn(S,ui,"card",ui_card);
  regFn(S,ui,"input",ui_input); regFn(S,ui,"image",ui_image); regFn(S,ui,"spacer",ui_spacer);
  regFn(S,ui,"divider",ui_divider); regFn(S,ui,"scrollview",ui_scrollview);
  regFn(S,ui,"list",ui_list); regFn(S,ui,"listitem",ui_listitem);
  regFn(S,ui,"stack",ui_stack); regFn(S,ui,"page",ui_page); regFn(S,ui,"switch",ui_switch);
}

/* ---------- API ---------- */
State* lx_new(void){ State*S=calloc(1,sizeof(State)); S->globals=xalloc(S,sizeof(Env)); S->globals->vars=newTable(S); S->globals->parent=NULL; S->step_limit=0; pthread_mutex_init(&S->dbg_mu,NULL); pthread_cond_init(&S->dbg_cv,NULL); S->dbg_inited=1; io_init(S); openLibs(S); return S; }
void lx_close(State*S){ if(!S)return; S->cancel_flag=1; if(S->dbg_inited){ pthread_mutex_lock(&S->dbg_mu); S->dbg_cmd=3; S->dbg_paused=0; pthread_cond_broadcast(&S->dbg_cv); pthread_mutex_unlock(&S->dbg_mu); pthread_mutex_destroy(&S->dbg_mu); pthread_cond_destroy(&S->dbg_cv); } if(S->io_inited){ pthread_mutex_lock(&S->io_mu); pthread_cond_broadcast(&S->io_cv); pthread_mutex_unlock(&S->io_mu); pthread_mutex_destroy(&S->io_mu); pthread_cond_destroy(&S->io_cv); } for(int i=0;i<S->npages;i++)free(S->pages[i].p); free(S->pages); free(S->out); free(S->json); free(S->dbg_locals); free(S->dbg_stack); free(S->dbg_eval_buf); free(S->stdin_q); free(S); }
int lx_dostring(State*S,const char*src,char*errbuf,int errlen){
  if(setjmp(S->err)){ if(errbuf)snprintf(errbuf,errlen,"%s",S->errmsg); return 1; }
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
  S->outused=0; if(S->out)S->out[0]=0;
  S->jsonused=0; if(S->json)S->json[0]=0;
  S->nhandlers=0; S->steps=0; S->has_view=false; S->app_view=VNIL;
  S->dbg_paused=0; S->dbg_cmd=0; S->pause_line=0; S->pause_reason=0; S->cur_env=NULL; S->nstack=0;
  free(S->dbg_stack); S->dbg_stack=NULL;
  free(S->dbg_eval_buf); S->dbg_eval_buf=NULL;
}
/* serialize the current app_view (calling it if it's a function) into S->json */
static void lx_build_tree(State*S){
  S->jsonused=0; if(S->json)S->json[0]=0; S->nhandlers=0;
  Value v=S->app_view;
  if(v.tag==T_FN||v.tag==T_CFN){ v=callValue(S,v,0,NULL); }
  if(v.tag==T_TAB){ Value uv=tget(v.u.t,VSTR(newStr(S,"__ui",4)));
    if(uv.tag==T_STR){ jnode(S,v.u.t); return; } }
  jappend(S,"null",4);
}

void lx_set_step_limit(State*S,long n){ if(S)S->step_limit=n; }

/* run source; capture return value as app_view; serialize UI tree to json.
 * returns 0 on success. out_json/out_print point into engine-owned buffers. */
int lx_run(State*S,const char*src,char*errbuf,int errlen){
  lx_reset_run(S);
  if(setjmp(S->err)){ if(errbuf)snprintf(errbuf,errlen,"%s",S->errmsg); return 1; }
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

/* invoke a previously-registered handler by id; rebuilds the tree afterward. */
int lx_invoke(State*S,int handler_id,char*errbuf,int errlen){
  if(handler_id<0||handler_id>=S->nhandlers){ if(errbuf)snprintf(errbuf,errlen,"invalid handler id %d",handler_id); return 1; }
  Value h=S->handlers[handler_id];
  S->outused=0; if(S->out)S->out[0]=0; S->steps=0;
  if(setjmp(S->err)){ if(errbuf)snprintf(errbuf,errlen,"%s",S->errmsg); return 1; }
  callValue(S,h,0,NULL);
  lx_build_tree(S);
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
  if(setjmp(S->err)){ if(errbuf)snprintf(errbuf,errlen,"%s",S->errmsg); return 1; }
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


