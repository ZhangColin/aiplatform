# -*- coding: utf-8 -*-
"""打印各 run 的收尾自述全文（模型最后一段话）。用法：python finaltext.py <log.jsonl> [...]  只读"""
import json, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
for p in sys.argv[1:]:
    last = None
    with io.open(p, encoding="utf-8") as f:
        for line in f:
            try:
                o = json.loads(line)
            except Exception:
                continue
            if "final_text" in o:
                last = o
    print("=" * 90)
    if not last:
        print(p, "-> no final record")
        continue
    print("%s  stop=%s rounds=%s %.1f min" % (p, last.get("stop_kind"), last.get("rounds"), last.get("total_sec", 0) / 60.0))
    print(last.get("final_text") or "")
