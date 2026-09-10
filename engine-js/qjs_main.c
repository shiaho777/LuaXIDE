/* qjs_main.c — desktop CLI for the LuaXIDE JS engine facade.
 * Usage: qjsx <file.js> [--ui] [--invoke <id>] */
#include "qjs_x.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char* read_file(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char* buf = malloc((size_t)n + 1);
    if (!buf) { fclose(f); return NULL; }
    size_t r = fread(buf, 1, (size_t)n, f);
    (void)r;
    buf[n] = 0;
    fclose(f);
    return buf;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: qjsx <file.js> [--ui] [--invoke <id>]\n");
        return 2;
    }
    const char* path = argv[1];
    int show_ui = 0, invoke_id = -1;
    for (int i = 2; i < argc; i++) {
        if (strcmp(argv[i], "--ui") == 0) show_ui = 1;
        else if (strcmp(argv[i], "--invoke") == 0 && i + 1 < argc) invoke_id = atoi(argv[++i]);
    }
    char* src = read_file(path);
    if (!src) { fprintf(stderr, "cannot read %s\n", path); return 2; }

    QjsX* x = qjsx_new();
    if (!x) { fprintf(stderr, "qjsx_new failed\n"); return 1; }
    char err[512];
    if (qjsx_run(x, src, err, sizeof(err)) != 0) {
        fprintf(stderr, "ERROR: %s\n", err);
        qjsx_free(x);
        free(src);
        return 1;
    }
    const char* out = qjsx_last_output(x);
    if (out && *out) fputs(out, stdout);
    if (show_ui) {
        const char* json = qjsx_last_json(x);
        printf("%s\n", (json && *json) ? json : "(no ui tree)");
    }
    if (invoke_id >= 0) {
        qjsx_clear_output(x);
        if (qjsx_invoke(x, invoke_id, NULL, err, sizeof(err)) != 0) {
            fprintf(stderr, "INVOKE ERROR: %s\n", err);
            qjsx_free(x);
            free(src);
            return 1;
        }
        out = qjsx_last_output(x);
        if (out && *out) fputs(out, stdout);
        if (show_ui) {
            const char* json = qjsx_last_json(x);
            printf("after invoke %d: %s\n", invoke_id, (json && *json) ? json : "(no ui tree)");
        }
    }
    qjsx_free(x);
    free(src);
    return 0;
}
