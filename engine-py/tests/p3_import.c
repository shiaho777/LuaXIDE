/* p3_import.c — multi-file import through mpyx_set_modroot: the facade
 * rebuilds sys.path from the module root before each run, so `import x`
 * resolves sibling files next to the project entry point (ABI §10). */
#include "../mpy_x.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s, sub); }

static char dir[256];

static void write_file(const char* name, const char* body) {
    char path[512];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE* f = fopen(path, "w");
    if (!f) { fprintf(stderr, "cannot write %s\n", path); exit(1); }
    fputs(body, f);
    fclose(f);
}

int main(void) {
    char err[512];
    strcpy(dir, "/tmp/luaxpy-import-XXXXXX");
    CHK(mkdtemp(dir) != NULL, "mkdtemp");

    /* flat sibling module + a package (dir with __init__ and a submodule) */
    write_file("helper.py",
        "VALUE = 7\n"
        "def add(a, b):\n"
        "    return a + b\n");
    {
        char pkg[512];
        snprintf(pkg, sizeof(pkg), "%s/pkg", dir);
        mkdir(pkg, 0755);
    }
    write_file("pkg/__init__.py", "PKG = 'init-ok'\n");
    write_file("pkg/mod.py", "NAME = 'pkg-mod'\n");

    MpyX* x = mpyx_new();
    CHK(x != NULL, "engine created");
    mpyx_set_modroot(x, dir);
    CHK(has(mpyx_modroot(x), dir), "modroot getter");

    /* --- 1. flat module resolves via modroot --- */
    int rc = mpyx_run(x,
        "import helper\n"
        "print('sum', helper.add(2, 3), helper.VALUE)\n",
        err, sizeof(err));
    CHK(rc == 0, "flat import runs");
    CHK(has(mpyx_last_output(x), "sum 5 7"), "flat import values");

    /* --- 2. package import (dir + submodule) --- */
    rc = mpyx_run(x,
        "import pkg\n"
        "import pkg.mod\n"
        "print('pkg', pkg.PKG, pkg.mod.NAME)\n",
        err, sizeof(err));
    CHK(rc == 0, "package import runs");
    CHK(has(mpyx_last_output(x), "pkg init-ok pkg-mod"), "package import values");

    /* --- 3. import inside a ui handler uses the same sys.path --- */
    rc = mpyx_run(x,
        "h = '?'\n"
        "def tap(v):\n"
        "    global h\n"
        "    import helper\n"
        "    h = 'h=' + str(helper.VALUE)\n"
        "def view():\n"
        "    return ui.app({'title': 'i'}, ui.column({},\n"
        "        ui.input({'label': 'go', 'onSubmit': tap}),\n"
        "        ui.text({'text': h})))\n",
        err, sizeof(err));
    CHK(rc == 0, "handler import setup");
    const char* j = mpyx_last_json(x);
    const char* h = j ? strstr(j, "__handler") : NULL;
    CHK(h != NULL, "handler registered");
    if (h) {
        int id = atoi(strchr(h, ':') + 1);
        rc = mpyx_invoke(x, id, "x", err, sizeof(err));
        CHK(rc == 0, "handler invoke");
        /* view() re-render carries the handler's import side effect */
        CHK(has(mpyx_last_json(x), "h=7"), "import inside handler");
    }

    /* --- 4. modroot cleared → the same import must fail --- */
    mpyx_set_modroot(x, "");
    rc = mpyx_run(x, "import no_such_mod_xyz\n", err, sizeof(err));
    CHK(rc == 1, "missing module errors");
    CHK(has(err, "module not found"), "import error text");

    /* --- 5. fresh engine without modroot: sibling file is NOT visible --- */
    MpyX* y = mpyx_new();
    CHK(y != NULL, "second engine");
    rc = mpyx_run(y, "import helper\n", err, sizeof(err));
    CHK(rc == 1, "no modroot -> import fails");
    mpyx_free(y);

    mpyx_free(x);
    {
        char cmd[600];
        snprintf(cmd, sizeof(cmd), "rm -rf %s", dir);
        if (system(cmd)) {}
    }
    if (fails) { fprintf(stderr, "p3-import: %d failure(s)\n", fails); return 1; }
    printf("p3-import ok\n");
    return 0;
}
