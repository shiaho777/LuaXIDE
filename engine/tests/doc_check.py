#!/usr/bin/env python3
"""doc_check.py — every ```lua block in docs/LUAX.md must execute cleanly.

Keeps the language reference honest: docs and implementation cannot drift
apart silently (the "execution is truth" rule applied to documentation).
Blocks whose fence carries an annotation (```lua no-run) are skipped — use
that only for code that cannot run in the CLI harness (e.g. blocking io.read).
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DOC = os.path.join(HERE, "..", "..", "docs", "LUAX.md")
LX = os.path.join(HERE, "..", "lx")


def blocks(path):
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r"```lua([^\n`]*)\n(.*?)```", text, re.S):
        yield m.group(1).strip(), m.group(2)


def main():
    ran = skipped = fails = 0
    for tag, code in blocks(DOC):
        if tag:
            skipped += 1
            continue
        ran += 1
        with tempfile.NamedTemporaryFile("w", suffix=".lua", delete=False) as f:
            f.write(code)
            tmp = f.name
        try:
            r = subprocess.run([LX, tmp], capture_output=True, text=True, timeout=30)
            if r.returncode != 0 or r.stderr.strip():
                fails += 1
                print(f"FAIL: block did not execute cleanly\n---\n{code}---\nstderr: {r.stderr.strip()}\n")
        finally:
            os.unlink(tmp)
    print(f"doc-check: {ran} block(s) executed, {skipped} skipped")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
