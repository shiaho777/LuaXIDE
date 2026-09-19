#!/usr/bin/env python3
"""fuzz_gen.py — deterministic LuaX program generator for the bc-diff suite.

Emits N seeded programs where every interesting construct lives inside called
function bodies (only function bodies reach the bytecode VM; top-level chunks
are always tree-walked). The Makefile bc-fuzz step runs each program with the
VM on and off (LUAX_NO_BC=1) and diffs the output — any byte difference is a
VM/tree-walk divergence bug.

Design constraints baked in:

- All generated functions are GLOBAL (`function f(...)`) so bodies referencing
  them resolve through the global table. Chunk-level local references compile
  to env-chain ops (GETENV/SETENV) — upvalues no longer refuse compilation.
- Nested closures (`closure_stmt`) exercise capmode env ops: capture of params
  and locals, mutation of shared bindings, per-iteration loop-var envs, and
  closures escaping `do` blocks.
- Locals are tracked on a scope stack; expressions only reference variables
  visible at that point, and a `local x = <expr>` declares x only after its
  initializer is generated.
- Programs are deterministic: no math.random/os.time/io.read; every loop has a
  literal bound or a counting local, so termination is guaranteed.
- Error paths are covered by "risky" functions invoked only under pcall —
  both modes must print the same caught error text.
- Vararg "dirty" functions still force compile refusal; they must produce
  identical output via the tree-walk fallback.
"""

import argparse
import os
import random

NUM_BIN = ["+", "-", "*", "/", "%", "//", "^", "&", "|", "~", "<<", ">>"]
CMP = ["==", "~=", "<", "<=", ">", ">="]
UN_NUM = ["-", "~"]
WORDS = ["a", "bb", "xy", "key", "luax", "z9"]


class Fn:
    """One generated function body with a lexical scope stack."""

    def __init__(self, rng):
        self.rng = rng
        self.lines = []
        self.scopes = [{"num": [], "tab": [], "str": [], "mt": []}]
        self.fresh = 0
        self.depth = 0

    # ----- scope tracking -----
    def push(self):
        self.scopes.append({"num": [], "tab": [], "str": [], "mt": []})

    def pop(self):
        self.scopes.pop()

    def add(self, kind):
        v = "v%d" % self.fresh
        self.fresh += 1
        self.scopes[-1][kind].append(v)
        return v

    def add_named(self, kind, name):
        self.scopes[-1][kind].append(name)
        return name

    def vars(self, kind):
        out = []
        for s in self.scopes:
            out.extend(s[kind])
        return out

    def emit(self, s, rel=0):
        self.lines.append("  " * (self.depth + rel) + s)

    # ----- expression trees -----
    def num_expr(self, d=0):
        r = self.rng
        if d > 3 or r.random() < 0.32:
            return self.num_leaf()
        k = r.randrange(10)
        if k <= 3:
            op = r.choice(NUM_BIN)
            b = self.num_expr(d + 1)
            if op == "^":
                b = r.choice(["2", "3", "4", "0.5"])
            return "(%s %s %s)" % (self.num_expr(d + 1), op, b)
        if k == 4:
            return "(%s %s)" % (r.choice(UN_NUM), self.num_expr(d + 1))
        if k == 5:
            return "%s(%s)" % (r.choice(["math.floor", "math.ceil", "math.abs"]), self.num_expr(d + 1))
        if k == 6:
            return "%s(%s, %s)" % (r.choice(["math.max", "math.min"]), self.num_expr(d + 1), self.num_expr(d + 1))
        if k == 7:
            return "math.sqrt((%s) %% 97 + 1)" % self.num_expr(d + 1)
        if k == 8:
            ts = self.vars("tab") + self.vars("mt")
            if ts:
                return "(%s[%d] or 0)" % (r.choice(ts), r.randrange(1, 4))
            return self.num_leaf()
        if k == 9:
            ms = self.vars("mt")
            if ms and r.random() < 0.6:
                return "(%s.%s or 0)" % (r.choice(ms), r.choice(["nn", "inc", "base"]))
            return "string.len(%s)" % self.str_expr(d + 1)
        return "(tonumber(%s) or %d)" % (self.str_expr(d + 1), r.randrange(9))

    def num_leaf(self):
        r = self.rng
        vs = self.vars("num")
        if vs and r.random() < 0.55:
            return r.choice(vs)
        n = r.randrange(-20, 40)
        if r.random() < 0.15:
            return "%.2f" % (n / 4.0)
        return str(n)

    def str_expr(self, d=0):
        r = self.rng
        if d > 2 or r.random() < 0.42:
            return self.str_leaf()
        k = r.randrange(7)
        if k <= 1:
            return "(%s .. %s)" % (self.str_expr(d + 1), self.str_expr(d + 1))
        if k == 2:
            return "string.rep(%s, %d)" % (self.str_leaf(), r.randrange(1, 4))
        if k == 3:
            return "string.sub(%s, 1, %d)" % (self.str_expr(d + 1), r.randrange(1, 3))
        if k == 4:
            return "string.upper(%s)" % self.str_expr(d + 1)
        if k == 5:
            return "string.lower(%s)" % self.str_expr(d + 1)
        return "tostring(%s)" % self.num_expr(d + 1)

    def str_leaf(self):
        r = self.rng
        vs = self.vars("str")
        if vs and r.random() < 0.4:
            return r.choice(vs)
        return '"%s"' % r.choice(WORDS)

    def bool_expr(self, d=0):
        r = self.rng
        k = r.randrange(8)
        if k <= 3:
            return "(%s %s %s)" % (self.num_expr(d + 1), r.choice(CMP), self.num_expr(d + 1))
        if k == 4:
            return "(%s %s %s)" % (self.str_expr(d + 1), r.choice(["==", "~="]), self.str_expr(d + 1))
        if k == 5:
            return "(not %s)" % self.bool_expr(d + 1)
        if k == 6:
            return "(%s and %s or %s)" % (self.bool_expr(d + 1), self.num_expr(d + 1), self.num_expr(d + 1))
        vs = self.vars("num") + self.vars("str") + self.vars("tab")
        if vs:
            return "(%s ~= nil)" % r.choice(vs)
        return r.choice(["true", "false"])

    # ----- statements -----
    def local_decl(self):
        r = self.rng
        k = r.randrange(6)
        if k <= 2:
            e = self.num_expr()
            self.emit("local %s = %s" % (self.add("num"), e))
        elif k == 3:
            e = self.str_expr()
            self.emit("local %s = %s" % (self.add("str"), e))
        elif k == 4:
            e1, e2 = self.num_expr(), self.num_expr()
            a, b = self.add("num"), self.add("num")
            self.emit("local %s, %s = %s, %s" % (a, b, e1, e2))
        else:
            if r.random() < 0.35:
                t = self.add("mt")
                self.emit("local %s = %s(%s)" % (t, r.choice(["mkA", "mkB"]), self.num_expr()))
                return
            t = self.add("tab")
            items = ", ".join(self.num_expr() for _ in range(r.randrange(1, 4)))
            if r.random() < 0.3:
                items += ", tag = %s" % self.str_expr()
            self.emit("local %s = {%s}" % (t, items))

    def assign(self):
        r = self.rng
        vs = self.vars("num")
        if vs and r.random() < 0.7:
            self.emit("%s = %s" % (r.choice(vs), self.num_expr()))
            return
        ss = self.vars("str")
        if ss:
            self.emit("%s = %s" % (r.choice(ss), self.str_expr()))
            return
        self.local_decl()

    def global_assign(self):
        self.emit("gx%d = %s" % (self.rng.randrange(3), self.num_expr()))

    def loop_stmt(self):
        r = self.rng
        k = r.randrange(5)
        if k == 0:
            iv = self.add_named("num", "i%d" % self.fresh)
            self.fresh += 1
            self.emit("for %s = 1, %d do" % (iv, r.randrange(1, 6)))
            self.push(); self.depth += 1
            self.stmt_block(r.randrange(1, 3))
            if r.random() < 0.3:
                self.emit("break")
            self.depth -= 1; self.pop()
            self.emit("end")
            self.scopes[-1]["num"].remove(iv)
        elif k == 1:
            step = r.choice([-2, -1, 2, 3])
            lo, hi = (1, r.randrange(4, 8)) if step > 0 else (r.randrange(4, 8), 1)
            iv = self.add_named("num", "i%d" % self.fresh)
            self.fresh += 1
            self.emit("for %s = %d, %d, %d do" % (iv, lo, hi, step))
            self.push(); self.depth += 1
            self.stmt_block(r.randrange(1, 3))
            self.depth -= 1; self.pop()
            self.emit("end")
            self.scopes[-1]["num"].remove(iv)
        elif k == 2:
            c = self.add("num")
            self.emit("local %s = 0" % c)
            self.emit("while %s < %d do" % (c, r.randrange(1, 6)))
            self.push(); self.depth += 1
            self.emit("%s = %s + 1" % (c, c))
            self.stmt_block(r.randrange(1, 2))
            self.depth -= 1; self.pop()
            self.emit("end")
        elif k == 3:
            c = self.add("num")
            self.emit("local %s = 0" % c)
            self.emit("repeat")
            self.push(); self.depth += 1
            self.emit("%s = %s + 1" % (c, c))
            self.stmt_block(r.randrange(1, 2))
            self.depth -= 1; self.pop()
            self.emit("until %s >= %d" % (c, r.randrange(1, 5)))
        else:
            self.emit("do")
            self.push(); self.depth += 1
            self.stmt_block(r.randrange(1, 3))
            self.depth -= 1; self.pop()
            self.emit("end")

    def if_stmt(self):
        r = self.rng
        self.emit("if %s then" % self.bool_expr())
        self.push(); self.depth += 1
        self.stmt_block(r.randrange(1, 3))
        self.depth -= 1; self.pop()
        if r.random() < 0.4:
            self.emit("elseif %s then" % self.bool_expr())
            self.push(); self.depth += 1
            self.stmt_block(r.randrange(1, 2))
            self.depth -= 1; self.pop()
        if r.random() < 0.5:
            self.emit("else")
            self.push(); self.depth += 1
            self.stmt_block(r.randrange(1, 2))
            self.depth -= 1; self.pop()
        self.emit("end")

    def generic_for(self):
        r = self.rng
        ts = self.vars("tab") + self.vars("mt")
        if not ts:
            return self.local_decl()
        t = r.choice(ts)
        it = r.choice(["pairs", "ipairs"])
        acc = self.add("num")
        self.emit("local %s = 0" % acc)
        kv, vv = "k%d" % self.fresh, "g%d" % self.fresh
        self.fresh += 1
        self.emit("for %s, %s in %s(%s) do" % (kv, vv, it, t))
        self.push(); self.depth += 1
        if it == "pairs":
            self.emit("if type(%s) == 'number' then %s = %s + %s end" % (vv, acc, acc, vv))
        else:
            self.emit("%s = %s + %s" % (acc, acc, vv))
        self.depth -= 1; self.pop()
        self.emit("end")

    def call_stmt(self):
        r = self.rng
        ts = self.vars("tab")
        if not ts:
            return self.local_decl()
        t = r.choice(ts)
        k = r.randrange(4)
        if k == 0:
            self.emit("table.insert(%s, %s)" % (t, self.num_expr()))
        elif k == 1:
            self.emit("table.sort(%s)" % t)
        elif k == 2:
            self.emit("table.remove(%s, 1)" % t)
        else:
            e = "table.concat(%s, ',')" % t
            self.emit("local %s = %s" % (self.add("str"), e))

    def method_stmt(self):
        r = self.rng
        ms = self.vars("mt")
        if not ms:
            return self.local_decl()
        t = r.choice(ms)
        e = "(%s:mm(%s) or 0)" % (t, self.num_expr())
        self.emit("local %s = %s" % (self.add("num"), e))

    def pcall_stmt(self, risky_names):
        r = self.rng
        fn = r.choice(risky_names)
        e1, e2 = self.num_expr(), self.num_expr()
        ok, rv = "pc%d" % self.fresh, "pr%d" % self.fresh
        self.fresh += 1
        # results are untyped (boolean + error string / value): print, never re-read
        self.emit("local %s, %s = pcall(%s, %s, %s)" % (ok, rv, fn, e1, e2))
        self.emit("print(%s, %s)" % (ok, rv))

    def closure_stmt(self):
        """Nested functions capturing enclosing-scope vars — capmode env ops."""
        r = self.rng
        k = r.randrange(4)
        cn = "cn%d" % self.fresh
        self.fresh += 1
        caps = self.vars("num")
        if k == 0:
            # closure mutates a captured num var (shared mutable binding)
            if not caps:
                return self.local_decl()
            v = r.choice(caps)
            self.emit("local function %s(p) %s = %s + p return %s end" % (cn, v, v, v))
            a = self.add("num")
            self.emit("local %s = %s(%s)" % (a, cn, self.num_expr()))
        elif k == 1:
            # closure reads captured var + own param + own local
            use = r.choice(caps) if caps else str(r.randrange(1, 9))
            self.emit("local function %s(p) local q = p * 2 return q + %s end" % (cn, use))
            a = self.add("num")
            self.emit("local %s = %s(%s)" % (a, cn, self.num_expr()))
        elif k == 2:
            # per-iteration capture: each loop round binds a fresh env
            t = self.add("tab")
            acc = self.add("num")
            iv = "i%d" % self.fresh
            self.fresh += 1
            self.emit("local %s = {}" % t)
            self.emit("for %s = 1, %d do %s[%s] = function() return %s * 2 end end"
                      % (iv, r.randrange(1, 3), t, iv, iv))
            self.emit("local %s = 0" % acc)
            self.emit("for %s = 1, #%s do %s = %s + %s[%s]() end" % (iv, t, acc, acc, t, iv))
        else:
            # escaped env: closure outlives the do-block that declared q
            q = "q%d" % self.fresh
            self.fresh += 1
            self.emit("local %s" % cn)
            self.emit("do local %s = %s %s = function() return %s + 1 end end"
                      % (q, self.num_expr(), cn, q))
            a = self.add("num")
            self.emit("local %s = %s()" % (a, cn))

    def stmt_block(self, n, risky_names=()):
        r = self.rng
        for _ in range(n):
            k = r.randrange(15)
            if k <= 3:
                self.local_decl()
            elif k <= 5:
                self.assign()
            elif k == 6:
                self.loop_stmt()
            elif k == 7:
                self.if_stmt()
            elif k == 8:
                self.generic_for()
            elif k == 9:
                self.call_stmt()
            elif k == 10:
                self.method_stmt()
            elif k == 11 and risky_names:
                self.pcall_stmt(risky_names)
            elif k == 12:
                self.global_assign()
            elif k == 13:
                self.closure_stmt()
            else:
                self.local_decl()

    def finish(self, nstats, risky_names):
        self.stmt_block(nstats, risky_names)
        outs = [self.num_expr() for _ in range(self.rng.randrange(1, 3))]
        self.emit("return %s" % ", ".join(outs))
        return "\n".join(self.lines)


def gen_risky(rng, name):
    """Hits runtime-error paths; only ever invoked under pcall."""
    bad = [
        "return a + nil",
        "return (nil).x",
        "return #nil",
        "return a % 0",
        "return a // 0",
        "return {} .. 'x'",
        "return a()",
        "error('boom' .. a)",
        "assert(false, 'asrt' .. b)",
        "return tostring(nil + 1)",
        "local t = nil return t[1]",
        "return ('x')()",
        "return a < nil",
        "return -'q'",
        "return nil .. a",
        "return #b.x",
    ]
    return ("function %s(a, b)\n  if b > 0 then %s else %s end\n"
            "  return 0\nend") % (name, rng.choice(bad), rng.choice(bad))


def gen_dirty(rng, name):
    """Nested defs / varargs. Nested defs compile under capmode now; only
    vararg bodies still force the tree-walk fallback path."""
    k = rng.randrange(3)
    if k == 0:
        return ("function %s(a)\n  local function inner(x) return x * 2 + a end\n"
                "  return inner(a) + 1\nend") % name
    if k == 1:
        return ("function %s(...)\n  local n = select('#', ...)\n"
                "  local s = 0\n  for i = 1, n do s = s + (select(i, ...) or 0) end\n"
                "  return s, n\nend") % name
    return ("function %s(a)\n  local f = function(x) return x + a end\n"
            "  return f(a)\nend") % name


MAKERS = """function mkA(b)
  local t = setmetatable({nn = b or 0}, {__index = {base = 50, inc = 7}})
  function t:mm(x) return self.nn + self.inc + x end
  return t
end

function mkB(b)
  local t = {nn = b or 0, arr = {4, 5}}
  function t:mm(x) return self.nn + (self.arr[1] or 0) + x end
  return t
end
"""


def gen_program(seed, idx):
    rng = random.Random(seed * 100003 + idx)
    parts = ["-- fuzz %d (seed %d)" % (idx, seed), "", MAKERS, ""]

    risky = ["risk%d" % i for i in range(rng.randrange(1, 3))]
    dirty = ["dirt%d" % i for i in range(rng.randrange(0, 2))]
    for r_ in risky:
        parts.append(gen_risky(rng, r_)); parts.append("")
    for d_ in dirty:
        parts.append(gen_dirty(rng, d_)); parts.append("")

    clean = []
    for i in range(rng.randrange(3, 6)):
        name = "f%d" % i
        nparams = rng.randrange(1, 4)
        fn = Fn(rng)
        for p in ["a", "b", "c"][:nparams]:
            fn.add_named("num", p)
        body = fn.finish(rng.randrange(4, 10), risky)
        clean.append((name, nparams))
        parts.append("function %s(%s)\n%s\nend" % (name, ", ".join(["a", "b", "c"][:nparams]), body))
        parts.append("")

    # top-level driver: call everything, print everything observable
    for name, np_ in clean:
        args = ", ".join(str(rng.randrange(-9, 15)) for _ in range(np_))
        parts.append("print(%s(%s))" % (name, args))
    for r_ in risky:
        parts.append("print(pcall(%s, %d, %d))" % (r_, rng.randrange(-5, 9), rng.randrange(-5, 9)))
    for d_ in dirty:
        parts.append("print(%s(%s))" % (d_, ", ".join(str(rng.randrange(1, 9)) for _ in range(rng.randrange(1, 4)))))
    for _ in range(rng.randrange(0, 3)):
        name, np_ = rng.choice(clean)
        args = ", ".join(str(rng.randrange(-9, 15)) for _ in range(np_))
        parts.append("local _r = { %s(%s) }" % (name, args))
        parts.append("for i = 1, #_r do io.write(tostring(_r[i]), ';') end")
        parts.append("io.write('\\n')")
    parts.append("")
    return "\n".join(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=60)
    ap.add_argument("--seed", type=int, default=20260919)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for i in range(a.n):
        path = os.path.join(a.out, "fuzz_%04d.lua" % i)
        with open(path, "w") as fh:
            fh.write(gen_program(a.seed, i))
    print("generated %d programs in %s (seed %d)" % (a.n, a.out, a.seed))


if __name__ == "__main__":
    main()
