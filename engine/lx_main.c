/* lx_main.c — CLI runner for the LuaX engine. */
#include "lx.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

static char* read_all(const char* path){
    FILE* f=fopen(path,"rb"); if(!f)return NULL;
    fseek(f,0,SEEK_END); long n=ftell(f); fseek(f,0,SEEK_SET);
    char* b=malloc(n+1); fread(b,1,n,f); b[n]=0; fclose(f); return b;
}

int main(int argc, char** argv){
    const char* file = NULL;
    const char* eval = NULL;
    int ui_mode = 0;
    int bc_dump = 0;
    for(int i=1;i<argc;i++){
        if(strcmp(argv[i],"-e")==0 && i+1<argc){ eval=argv[++i]; }
        else if(strcmp(argv[i],"--ui")==0){ ui_mode=1; }
        else if(strcmp(argv[i],"--bc-dump")==0){ bc_dump=1; }
        else if(argv[i][0]!='-'){ file=argv[i]; }
        else { fprintf(stderr,"usage: lx [file] [-e src] [--ui] [--bc-dump]\n"); return 2; }
    }
    lx_State* S=lx_new();
    lx_set_step_limit(S, 50000000L);
    char err[512];
    int r;

    if(bc_dump){
        char* src=NULL; int owned=0;
        if(eval){ src=(char*)eval; }
        else if(file){ src=read_all(file); owned=1; if(!src){fprintf(stderr,"luax: cannot open %s\n",file);lx_close(S);return 1;} }
        else { char* buf=malloc(1<<20); size_t n=fread(buf,1,(1<<20)-1,stdin); buf[n]=0; src=buf; owned=1; }
        r=lx_bc_disassemble(S,src,err,sizeof(err));
        if(owned)free(src);
        if(r<0)fprintf(stderr,"luax: %s\n",err);
        lx_close(S);
        return r<0?1:0;
    }

    if(ui_mode){
        char* src=NULL; int owned=0;
        if(eval){ src=(char*)eval; }
        else if(file){ src=read_all(file); owned=1; if(!src){fprintf(stderr,"luax: cannot open %s\n",file);lx_close(S);return 1;} }
        else { char* buf=malloc(1<<20); size_t n=fread(buf,1,(1<<20)-1,stdin); buf[n]=0; src=buf; owned=1; }
        r=lx_run(S,src,err,sizeof(err));
        if(owned)free(src);
        if(r){ fprintf(stderr,"luax: %s\n",err); }
        else { printf("---OUTPUT---\n%s---TREE---\n%s\n", lx_last_output(S), lx_last_json(S)); }
        lx_close(S);
        return r?1:0;
    }

    if(eval){ r=lx_dostring(S,eval,err,sizeof(err)); }
    else if(file){ r=lx_dofile(S,file,err,sizeof(err)); }
    else {
        /* read stdin */
        char buf[1<<20]; size_t n=fread(buf,1,sizeof(buf)-1,stdin); buf[n]=0;
        r=lx_dostring(S,buf,err,sizeof(err));
    }
    if(r){ fprintf(stderr,"luax: %s\n",err); }
    lx_close(S);
    return r?1:0;
}
