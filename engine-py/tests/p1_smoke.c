/* mpy_x_test.c — desktop smoke: run a python script with the ui DSL + invoke. */
#include "mpy_x.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

int main(void){
  char err[512];
  MpyX* x = mpyx_new();
  if (!x) { printf("no engine\n"); return 1; }

  const char* src =
    "count = 0\n"
    "def view():\n"
    "    return ui.app({'title': 'py'},\n"
    "        ui.column({},\n"
    "            ui.text({'text': 'n=' + str(count)}),\n"
    "            ui.button({'text': '+1', 'onClick': lambda: bump())))"  /* invalid on purpose fix below */
    ;
  /* note: fixed source below */
  const char* src2 =
    "count = 0\n"
    "def bump():\n"
    "    global count\n"
    "    count += 1\n"
    "def view():\n"
    "    return ui.app({'title': 'py'}, ui.column({},\n"
    "        ui.text({'text': 'n=' + str(count)}),\n"
    "        ui.button({'text': '+1', 'onClick': bump})))\n"
    ;
  int rc = mpyx_run(x, src2, err, sizeof(err));
  printf("run rc=%d err=%s\n", rc, rc ? err : "-");
  printf("json: %.160s\n", mpyx_last_json(x));

  /* find handler id 1 in the json */
  const char* j = mpyx_last_json(x);
  const char* h = j ? strstr(j, "__handler") : NULL;
  if (h) {
    int id = atoi(strchr(h, ':') + 1);
    rc = mpyx_invoke(x, id, NULL, err, sizeof(err));
    printf("invoke rc=%d err=%s\n", rc, rc ? err : "-");
    const char* j2 = mpyx_last_json(x);
    printf("after tap: %s\n", strstr(j2, "n=1") ? "n=1 OK" : "MISSED");
    /* regression guard: invoke must REPLACE the json, not append — the json
     * buffer carrying a stale prefix means the host would render the OLD tree */
    if (strstr(j2, "n=0")) { printf("REGRESSION: stale tree prefix still in json\n"); return 1; }
    printf("json2: %.160s\n", j2);
  } else {
    printf("no handler found\n");
  }
  mpyx_free(x);

  /* --- keep-tree contract: non-tree return and handler error preserve the
   * previous view (ABI §4); payloads with newlines must reach the handler --- */
  MpyX* y = mpyx_new();
  const char* src3 =
    "last = '?'\n"
    "def on_tap(v):\n"
    "    global last\n"
    "    last = v\n"
    "    # returns None → old tree must be kept\n"
    "def boom():\n"
    "    raise Exception('kaput')\n"
    "def view():\n"
    "    return ui.app({'title': 'keep'}, ui.column({},\n"
    "        ui.input({'label': 'in', 'onSubmit': on_tap}),\n"
    "        ui.button({'text': 'x', 'onClick': boom}),\n"
    "        ui.text({'text': 'v=' + last})))\n";
  rc = mpyx_run(y, src3, err, sizeof(err));
  printf("keep-run rc=%d err=%s\n", rc, rc ? err : "-");
  j = mpyx_last_json(y);
  if (!strstr(j, "\"title\":\"keep\"")) { printf("FAIL: initial keep tree missing\n"); return 1; }
  /* handler returns None → tree kept (was: json wiped to empty) */
  int id = -1;
  const char* h2 = strstr(j, "__handler");
  if (h2) id = atoi(strchr(h2, ':') + 1);
  rc = mpyx_invoke(y, id, "line1\nline2", err, sizeof(err));
  printf("invoke-nl rc=%d err=%s\n", rc, rc ? err : "-");
  j = mpyx_last_json(y);
  if (rc || !strstr(j, "\"title\":\"keep\"")) { printf("FAIL: tree lost after non-tree invoke\n"); return 1; }
  if (!strstr(j, "line1\\nline2")) { printf("FAIL: newline payload did not survive escaping: %.200s\n", j); return 1; }
  /* handler error → tree kept. NOTE: handler ids are re-issued on every
   * re-render, so the boom handler id must be looked up in the CURRENT
   * json (the pre-invoke pointer is stale — the old buffer was freed). */
  const char* oc = strstr(j, "\"onClick\"");
  const char* h3 = oc ? strstr(oc, "__handler") : NULL;
  if (h3) id = atoi(strchr(h3, ':') + 1);
  rc = mpyx_invoke(y, id, NULL, err, sizeof(err));
  printf("invoke-err rc=%d err=%s\n", rc, rc ? err : "-");
  if (rc != 1 || !strstr(err, "kaput")) { printf("FAIL: handler error not surfaced\n"); return 1; }
  j = mpyx_last_json(y);
  if (!strstr(j, "\"title\":\"keep\"")) { printf("FAIL: tree lost after handler error\n"); return 1; }
  printf("keep-tree contract OK\n");
  mpyx_free(y);

  /* --- string sugar + box/slider/progress constructors --- */
  MpyX* z = mpyx_new();
  const char* src4 =
    "def view():\n"
    "    return ui.app({'title': 'sugar'}, ui.column({},\n"
    "        'hello',\n"
    "        ui.text('hi'),\n"
    "        ui.button({'text': 'plain'}),\n"
    "        ui.box({}, ui.progress({'value': 50})),\n"
    "        ui.slider({'min': 0, 'max': 100})))\n";
  rc = mpyx_run(z, src4, err, sizeof(err));
  if (rc) { printf("FAIL: sugar run: %s\n", err); return 1; }
  j = mpyx_last_json(z);
  if (!strstr(j, "\"type\":\"text\",\"props\":{\"text\":\"hello\"}")) { printf("FAIL: string child not wrapped as text node: %.240s\n", j); return 1; }
  if (!strstr(j, "\"props\":{\"text\":\"hi\"}")) { printf("FAIL: ui.text('hi') shorthand: %.240s\n", j); return 1; }
  if (!strstr(j, "\"type\":\"box\"") || !strstr(j, "\"type\":\"slider\"") || !strstr(j, "\"type\":\"progress\"")) { printf("FAIL: box/slider/progress missing: %.240s\n", j); return 1; }
  printf("string sugar + box/slider/progress OK\n");
  mpyx_free(z);
  return 0;
}
