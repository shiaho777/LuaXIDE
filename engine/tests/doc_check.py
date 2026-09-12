#!/usr/bin/env python3
"""doc_check.py — every ```lua block in docs/LUAX.md must execute cleanly,
and docs/LUAX.zh-CN.md must carry byte-identical lua blocks.

Keeps the language reference honest: docs and implementation cannot drift
apart silently (the "execution is truth" rule applied to documentation).
Blocks whose fence carries an annotation (```lua no-run) are skipped — use
that only for code that cannot run in the CLI harness (e.g. blocking io.read).

The zh-CN translation mirrors LUAX.md; its ```lua fences are checked for
exact parity (tags included) so a stale/mutated example in the translation
is caught without executing the same code twice.
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DOC = os.path.join(HERE, "..", "..", "docs", "LUAX.md")
DOC_ZH = os.path.join(HERE, "..", "..", "docs", "LUAX.zh-CN.md")
LX = os.path.join(HERE, "..", "lx")


def blocks(path):
    text = open(path, encoding="utf-8").read()
    return [(m.group(1).strip(), m.group(2)) for m in
            re.finditer(r"```lua([^\n`]*)\n(.*?)```", text, re.S)]


def parity(en_blocks, zh_blocks):
    if len(en_blocks) != len(zh_blocks):
        print(f"FAIL: lua block count differs: LUAX.md has {len(en_blocks)}, "
              f"LUAX.zh-CN.md has {len(zh_blocks)}")
        return 1
    bad = 0
    for i, (en, zh) in enumerate(zip(en_blocks, zh_blocks)):
        if en != zh:
            bad += 1
            print(f"FAIL: lua block #{i + 1} differs between LUAX.md and LUAX.zh-CN.md")
    return bad


def main():
    en_blocks = blocks(DOC)
    fails = parity(en_blocks, blocks(DOC_ZH))
    ran = skipped = 0
    for tag, code in en_blocks:
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
