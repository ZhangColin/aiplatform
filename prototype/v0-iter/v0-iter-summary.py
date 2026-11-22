# -*- coding: utf-8 -*-
"""#586 迭代实证 log 汇总：轮次/墙钟/token/git 触碰/工具直方图。用法：python v0-iter-summary.py <log.jsonl>"""
import json, sys, io

path = sys.argv[1]
rounds = 0
wall = 0
u_prompt = u_completion = 0
tool_hist = {}
git_rounds = []
idle_texts = []
final_text = ""
stopped = None
with io.open(path, encoding="utf-8") as f:
    for line in f:
        try:
            o = json.loads(line)
        except Exception:
            continue
        if "meta" in o:
            print("meta:", json.dumps(o["meta"], ensure_ascii=False))
        if "stopped" in o:
            stopped = o
        if "final_text" in o:
            wall = o["total_sec"]; rounds = o["rounds"]; final_text = o["final_text"]
        if "round" in o and "usage" in o:
            u = o["usage"]
            u_prompt += u.get("prompt_tokens", 0)
            u_completion += u.get("completion_tokens", 0)
        if "round" in o and "calls" in o:
            joined = "; ".join(o["calls"])
            for c in o["calls"]:
                fn = c.split(":", 1)[0]
                tool_hist[fn] = tool_hist.get(fn, 0) + 1
            if "git" in joined:
                git_rounds.append((o["round"], joined[:160]))
        if "round" in o and "text" in o:
            idle_texts.append((o["round"], (o.get("text") or "")[:80]))

print("rounds=%d wall=%ds (%.1fmin) prompt_tok=%d completion_tok=%d" % (
    rounds, wall, wall / 60.0, u_prompt, u_completion))
if stopped:
    print("STOPPED:", json.dumps(stopped, ensure_ascii=False)[:200])
print("tools:", json.dumps(tool_hist, ensure_ascii=False))
print("git-touching rounds: %d" % len(git_rounds))
for r, j in git_rounds:
    print("  r%d: %s" % (r, j))
print("idle text rounds: %d" % len(idle_texts))
for r, t in idle_texts:
    print("  r%d: %s" % (r, t))
print("final_text head: %s" % final_text[:300].replace("\n", " "))
