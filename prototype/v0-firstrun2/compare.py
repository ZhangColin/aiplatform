# -*- coding: utf-8 -*-
"""#590 新旧对照表（只读）。用法：python compare.py <old-log...> -- <new-log...>
口径与 #590 审计评论一致：「含 python3 的改文件轮」＝本轮任一 bash 命令里出现 python3 的轮；
杀／起服务轮＝命令命中 kill / start 正则的轮；构建轮＝命令含 pnpm build 的轮。
旧脚本第一次说「做完」＝第一条不调工具的轮。"""
import json, sys, io, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
RE_KILL = re.compile(r"\bpkill\b|\bkill\b[ -]|\bkillall\b|fuser -k")
RE_BUILD = re.compile(r"\bpnpm build\b|\bnext build\b|\bnpm run build\b")
RE_START = re.compile(r"(nohup|setsid)\b.*(\bpnpm (start|dev)\b|\bnext (start|dev)\b|node scripts/)|(^\s*(cd [^&;|]*&&\s*)?(\bpnpm (start|dev)\b|\bnpx next (start|dev)\b))")
RE_PY = re.compile(r"python3")

def load(path):
    rounds, final = [], None
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            o = json.loads(line)
            if "final_text" in o:
                final = o
            elif "round" in o and "calls" in o:
                rounds.append(o)
            elif "round" in o and "text" in o:
                rounds.append(dict(o, calls=[]))
    return rounds, final

def cmds(o):
    out = []
    for c in o.get("calls", []):
        m = re.search(r" \[[^\]]*\]( \{soft\})?$", c)
        c = c[:m.start()] if m else c
        tool, _, arg = c.partition(":")
        if tool == "bash":
            out.append(arg)
    return out

def analyze(path):
    rounds, final = load(path)
    name = path.replace("\\", "/").split("/")[-1].replace("-log.jsonl", "")
    cum, first_done = 0.0, None
    py = py_t = kill = start = build = 0
    for o in rounds:
        el = o.get("elapsed", 0) or 0
        if not o.get("calls") and first_done is None:
            first_done = cum
        cum += el
        cs = cmds(o)
        if any(RE_PY.search(c) for c in cs):
            py += 1; py_t += el
        if any(RE_KILL.search(c) for c in cs):
            kill += 1
        if any(RE_START.search(c) for c in cs):
            start += 1
        if any(RE_BUILD.search(c) for c in cs):
            build += 1
    total = final.get("total_sec") if final else cum
    n = len(rounds)
    u = (final or {}).get("usage_total", {})
    print("%-10s %-12s %5.1f min %4d rounds | first-done %5s | py3-edit rounds %3d (%4.1f%% of rounds, %4.1f%% of time) | kill %2d start %2d build %2d | prompt %5.1fM out %6.1fK" % (
        name, ((final or {}).get("stop_kind") or (final or {}).get("stopped") or "?")[:12], total / 60.0, n,
        ("%.1f min" % (first_done / 60.0)) if first_done is not None else "  n/a",
        py, 100.0 * py / n if n else 0, 100.0 * py_t / total if total else 0, kill, start, build,
        u.get("prompt", 0) / 1e6, u.get("completion", 0) / 1e3))

args = sys.argv[1:]
sep = args.index("--") if "--" in args else len(args)
print("OLD (#576, 催续)");  [analyze(p) for p in args[:sep]]
print("NEW (#590, 对齐版)"); [analyze(p) for p in args[sep + 1:]]
