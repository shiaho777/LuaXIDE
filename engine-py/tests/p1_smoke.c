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
    printf("json2: %.160s\n", j2);
  } else {
    printf("no handler found\n");
  }
  mpyx_free(x);
  return 0;
}
