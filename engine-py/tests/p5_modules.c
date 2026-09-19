/* p5_modules.c — stdlib surface: json (already configured) and the newly
 * vendored re module (extmod/modre.c + lib/re1.5). */
#include "../mpy_x.h"
#include <stdio.h>
#include <string.h>

static int fails;
#define CHK(c, m) do { if(!(c)){ fprintf(stderr,"FAIL %s\n", m); fails++; } } while(0)
static int has(const char* s, const char* sub){ return s && strstr(s, sub); }

int main(void) {
    char err[512];
    MpyX* x = mpyx_new();
    CHK(x != NULL, "engine created");

    /* --- json round-trip --- */
    int rc = mpyx_run(x,
        "import json\n"
        "d = json.loads('{\"a\": 1, \"b\": [2, 3]}')\n"
        "print('a', d['a'], 'b0', d['b'][0])\n"
        "print('rt', json.loads(json.dumps({'x': [1, 'y']}))['x'][1])\n",
        err, sizeof(err));
    CHK(rc == 0, "json runs");
    CHK(has(mpyx_last_output(x), "a 1 b0 2"), "json loads");
    CHK(has(mpyx_last_output(x), "rt y"), "json round-trip");

    /* --- re: match/search/groups/span/sub/compile --- */
    rc = mpyx_run(x,
        "import re\n"
        "m = re.match(r'(\\d+)-(\\d+)', '12-34')\n"
        "print('m', m.group(0), m.group(1), m.group(2))\n"
        "s = re.search(r'[a-z]+', '  abc  ')\n"
        "print('s', s.group(0))\n"
        "print('sub', re.sub(r'\\d+', '#', 'a1b22c'))\n"
        "print('com', re.compile(r'x+').match('xxxy').group(0))\n",
        err, sizeof(err));
    CHK(rc == 0, "re runs");
    const char* o = mpyx_last_output(x);
    CHK(has(o, "m 12-34 12 34"), "re match + groups");
    CHK(has(o, "s abc"), "re search");
    CHK(has(o, "sub a#b#c"), "re sub");
    CHK(has(o, "com xxx"), "re compile");

    /* --- re no-match returns None, invalid pattern errors --- */
    rc = mpyx_run(x,
        "import re\n"
        "print('none', re.match(r'z', 'abc'))\n",
        err, sizeof(err));
    CHK(rc == 0, "no-match runs");
    CHK(has(mpyx_last_output(x), "none None"), "no-match None");
    rc = mpyx_run(x, "import re\nre.compile('([')\n", err, sizeof(err));
    CHK(rc == 1, "bad pattern errors");

    mpyx_free(x);
    if (fails) { fprintf(stderr, "p5-modules: %d failure(s)\n", fails); return 1; }
    printf("p5-modules ok\n");
    return 0;
}
