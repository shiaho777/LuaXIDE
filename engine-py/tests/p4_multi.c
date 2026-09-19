/* p4_multi.c — several engines per process: each MpyX owns an independent
 * MicroPython ctx (swapped under a mutex), so heaps, globals, handler tables
 * and sys.path stay private while engines run interleaved — even from
 * different threads. */
#include "../mpy_x.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s, sub); }

static int handler_id(const char* json) {
    const char* h = json ? strstr(json, "__handler") : NULL;
    return h ? atoi(strchr(h, ':') + 1) : -1;
}

struct job { MpyX* x; int failed; };

static void* worker(void* p) {
    struct job* j = p;
    char e[256], s[256];
    for (int i = 0; i < 8; i++) {
        snprintf(s, sizeof(s), "print('w%d')\n", i);
        if (mpyx_run(j->x, s, e, sizeof(e))) { j->failed = 1; return NULL; }
    }
    return NULL;
}

static void make_script(char* buf, size_t n, int base, const char* title) {
    snprintf(buf, n,
        "count = %d\n"
        "def bump():\n"
        "    global count\n"
        "    count += 1\n"
        "def view():\n"
        "    return ui.app({'title': '%s'}, ui.column({},\n"
        "        ui.text({'text': 'n=' + str(count)}),\n"
        "        ui.button({'text': '+', 'onClick': bump})))\n",
        base, title);
}

int main(void) {
    char err[512];
    char src[1024];

    /* --- 1. sequential interleave: state stays in the right engine --- */
    MpyX* a = mpyx_new();
    MpyX* b = mpyx_new();
    CHK(a && b, "two engines");
    make_script(src, sizeof(src), 100, "A");
    CHK(mpyx_run(a, src, err, sizeof(err)) == 0, "A runs");
    make_script(src, sizeof(src), 7, "B");
    CHK(mpyx_run(b, src, err, sizeof(err)) == 0, "B runs");

    CHK(has(mpyx_last_json(a), "\"title\":\"A\""), "A tree");
    CHK(has(mpyx_last_json(b), "\"title\":\"B\""), "B tree");
    CHK(has(mpyx_last_json(a), "n=100"), "A initial state");
    CHK(has(mpyx_last_json(b), "n=7"), "B initial state");

    int ida = handler_id(mpyx_last_json(a));
    int idb = handler_id(mpyx_last_json(b));
    CHK(ida > 0 && idb > 0, "handlers registered");
    /* bump A twice, B once — each must only touch its own counter */
    CHK(mpyx_invoke(a, ida, NULL, err, sizeof(err)) == 0, "A invoke 1");
    CHK(mpyx_invoke(b, idb, NULL, err, sizeof(err)) == 0, "B invoke");
    CHK(mpyx_invoke(a, handler_id(mpyx_last_json(a)), NULL, err, sizeof(err)) == 0, "A invoke 2");
    CHK(has(mpyx_last_json(a), "n=102"), "A counted alone");
    CHK(has(mpyx_last_json(b), "n=8"), "B counted alone");

    /* --- 2. run() re-run on one engine must not touch the other's globals --- */
    make_script(src, sizeof(src), 100, "A");
    CHK(mpyx_run(a, src, err, sizeof(err)) == 0, "A re-run");
    CHK(has(mpyx_last_json(a), "n=100"), "A reset on re-run");
    CHK(has(mpyx_last_json(b), "n=8"), "B untouched by A re-run");

    /* --- 3. concurrent windows: two threads, own engine each --- */
    {
        pthread_t ta, tb;
        struct job ja = {a, 0}, jb = {b, 0};
        pthread_create(&ta, NULL, worker, &ja);
        pthread_create(&tb, NULL, worker, &jb);
        pthread_join(ta, NULL);
        pthread_join(tb, NULL);
        CHK(ja.failed == 0 && jb.failed == 0, "concurrent windows ran clean");
        CHK(has(mpyx_last_output(a), "w7"), "A kept its own output");
        CHK(has(mpyx_last_output(b), "w7"), "B kept its own output");
        /* and state is still per-engine afterwards */
        CHK(mpyx_run(a, "import helper_should_fail\n", err, sizeof(err)) == 1,
            "A still functional after concurrency");
    }

    mpyx_free(a);
    mpyx_free(b);

    /* --- 4. engine freed mid-life does not corrupt survivors --- */
    MpyX* c = mpyx_new();
    MpyX* d = mpyx_new();
    make_script(src, sizeof(src), 1, "C");
    CHK(mpyx_run(c, src, err, sizeof(err)) == 0, "C runs");
    mpyx_free(c);
    CHK(mpyx_run(d, "print('d alive')\n", err, sizeof(err)) == 0, "D alive after C freed");
    CHK(has(mpyx_last_output(d), "d alive"), "D output after C freed");
    mpyx_free(d);

    if (fails) { fprintf(stderr, "p4-multi: %d failure(s)\n", fails); return 1; }
    printf("p4-multi ok\n");
    return 0;
}
