# -*- coding: utf-8 -*-
"""打印一份日志里命中正则的轮（轮号／累计分钟／命令前 150 字）。用法：python seq.py <log> <regex> [from_round]  只读"""
import json, sys, io, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
path, rx = sys.argv[1], re.compile(sys.argv[2])
frm = int(sys.argv[3]) if len(sys.argv) > 3 else 0
cum = 0.0
with io.open(path, encoding="utf-8") as f:
    for line in f:
        o = json.loads(line)
        if "round" in o and ("calls" in o or "text" in o):
            cum += o.get("elapsed", 0) or 0
            if o["round"] < frm:
                continue
            if not o.get("calls"):
                print("r%-4d %5.1f min  [no tool] %s" % (o["round"], cum / 60, (o.get("text") or "")[:110].replace("\n", " ")))
                continue
            for c in o["calls"]:
                m = re.search(r" \[[^\]]*\]( \{soft\})?$", c)
                if rx.search(c):
                    print("r%-4d %5.1f min  %s" % (o["round"], cum / 60, c[:170].replace("\n", " ")))
        elif "stopped" in o or "final_text" in o:
            print("END: %s" % json.dumps({k: o[k] for k in o if k in ("stopped", "rounds", "total_sec", "stop_kind")}, ensure_ascii=False))
