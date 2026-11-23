# -*- coding: utf-8 -*-
"""#590 内核对齐版 run 汇总：时长／轮次／token／结束方式／各事件／工具占比／服务进程类命令／编辑工具成败。
用法：python summarize3.py <log.jsonl> [...]   只读。"""
import json, sys, io, re

RE_KILL = re.compile(r"\bpkill\b|\bkill\b[ -]|\bkillall\b|fuser -k")
RE_BUILD = re.compile(r"\bpnpm build\b|\bnext build\b|\bnpm run build\b")
RE_START = re.compile(r"(nohup|setsid)\b.*(\bpnpm (start|dev)\b|\bnext (start|dev)\b|node scripts/)|(^\s*(cd [^&;|]*&&\s*)?(\bpnpm (start|dev)\b|\bnpx next (start|dev)\b))")
RE_TEST = re.compile(r"\bpnpm test\b|vitest|\.test\.|acceptance|smoke")
RE_TC = re.compile(r"\btsc\b|\bpnpm lint\b|eslint|\bpnpm typecheck\b")
RE_CURL = re.compile(r"\bcurl\b")
RE_DIAG = re.compile(r"\bps (aux|-eo|-ef|ax|www)\b|/proc/(net|[0-9])|app\.log|netstat|lsof|\bss -|pgrep")
RE_PYEDIT = re.compile(r"python3 -|\bsed -i\b|\bperl -p|\bawk -i")


def call_info(c):
    """'name:arg [head]' -> (tool, arg, head)"""
    head = ""
    m = re.search(r" \[([^\]]*)\]( \{soft\})?$", c)
    if m:
        head = m.group(1)
        c = c[:m.start()]
    tool, _, arg = c.partition(":")
    return tool, arg, head


def bash_cat(cmd):
    if RE_KILL.search(cmd):
        return "KILL"
    if RE_BUILD.search(cmd):
        return "BUILD"
    if RE_START.search(cmd):
        return "START"
    if RE_TEST.search(cmd):
        return "TEST"
    if RE_TC.search(cmd):
        return "TYPECHECK"
    if RE_CURL.search(cmd):
        return "CURL"
    if RE_PYEDIT.search(cmd):
        return "SCRIPT-EDIT"
    if RE_DIAG.search(cmd):
        return "DIAG"
    if re.search(r"\bcat\b|sed -n|\bhead\b|\btail\b|\bls\b|\bfind\b|\bgrep\b|\bwc\b|mkdir", cmd):
        return "READ"
    return "OTHER"


def analyze(path):
    rounds, final, events, errs, system = [], None, [], 0, None
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            o = json.loads(line)
            if "meta" in o:
                system = o["meta"].get("system")
            if "api_error" in o:
                errs += 1
            if "event" in o:
                events.append((o.get("round"), o["event"], o.get("consecutive")))
            if "final_text" in o:
                final = o
            if "round" in o and "calls" in o:
                infos = [call_info(c) for c in o["calls"]]
                cats = [(t if t != "bash" else bash_cat(a)) for t, a, h in infos]
                rounds.append((o["round"], o["elapsed"], infos, cats, o["usage"]))
            elif "round" in o and "text" in o:
                rounds.append((o["round"], o["elapsed"], [], ["TEXT"], o["usage"]))
    name = path.replace("\\", "/").split("/")[-1]
    print("=" * 100)
    print("%s  stop_kind=%s  rounds=%s  wall=%s s (%s min)  api_errors=%d" % (
        name, final.get("stop_kind") if final else "RUNNING/UNKNOWN", final.get("rounds") if final else len(rounds),
        final.get("total_sec") if final else "n/a", "%.1f" % (final["total_sec"] / 60.0) if final else "n/a", errs))
    if final:
        u = final["usage_total"]
        print("  tokens: prompt=%s completion=%s" % (format(u["prompt"], ","), format(u["completion"], ",")))
    if events:
        from collections import Counter
        print("  events: %s" % dict(Counter(e[1] for e in events)))
    else:
        print("  events: none")
    T = sum(r[1] for r in rounds)
    by = {}
    for _, el, infos, cats, us in rounds:
        k = cats[0] if cats else "OTHER"
        d = by.setdefault(k, [0, 0.0, 0])
        d[0] += 1
        d[1] += el
        d[2] += us.get("completion_tokens", 0)
    print("  [rounds by first-call category] cat         rounds    time  %time  mean_s  mean_out_tok")
    for k, (n, t, c) in sorted(by.items(), key=lambda kv: -kv[1][1]):
        print("                                 %-12s %5d %7.0fs %5.1f%% %6.1f %10.0f" % (k, n, t, 100.0 * t / T if T else 0, t / n, c / n))
    # tools
    tc = {}
    tfail = {}
    for _, _, infos, _, _ in rounds:
        for t, a, h in infos:
            tc[t] = tc.get(t, 0) + 1
            bad = h not in ("ok",) and not h.startswith("ok") and not h.startswith("rc=0")
            if bad:
                tfail[t + ":" + h[:24]] = tfail.get(t + ":" + h[:24], 0) + 1
    print("  tool calls: %s" % tc)
    print("  non-success results: %s" % (dict(sorted(tfail.items(), key=lambda kv: -kv[1])) if tfail else "none"))
    flow = [(r, el, infos) for r, el, infos, cats, _ in rounds if any(c in ("KILL", "START", "DIAG") for c in cats)]
    ft = sum(x[1] for x in flow)
    kills = sum(1 for _, _, infos, _, _ in rounds for t, a, h in infos if t == "bash" and RE_KILL.search(a))
    starts = sum(1 for _, _, infos, _, _ in rounds for t, a, h in infos if t == "bash" and RE_START.search(a))
    builds = sum(1 for _, _, infos, _, _ in rounds for t, a, h in infos if t == "bash" and RE_BUILD.search(a))
    print("  service-process commands: kill=%d start=%d | build=%d | rounds touching kill/start/diag=%d time=%.0fs (%.1f%%)" % (
        kills, starts, builds, len(flow), ft, 100.0 * ft / T if T else 0))
    pm = [us.get("prompt_tokens", 0) for _, _, _, _, us in rounds]
    if pm:
        print("  prompt tokens per request: first=%sK max=%sK last=%sK" % (pm[0] // 1000, max(pm) // 1000, pm[-1] // 1000))
    if final:
        print("  final_text head: %s" % (final.get("final_text") or "")[:260].replace("\n", " "))
    return final


if __name__ == "__main__":
    for p in sys.argv[1:]:
        analyze(p)
