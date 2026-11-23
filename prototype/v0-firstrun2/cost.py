# -*- coding: utf-8 -*-
"""按 #591 决议的价格口径折算每次运行的成本（只读）。
口径（#591 决议评论）：deepseek-flash 峰时价 输入 ¥2/M、输出 ¥8/M、缓存读 ¥0.04/M；
「最坏」＝全部 prompt 按输入价的上界；「实算」＝按日志里逐轮的缓存命中／未命中分开计。"""
import json, sys, io, os, glob
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
P_IN, P_OUT, P_HIT = 2.0, 8.0, 0.04

def cost(path):
    hit = miss = comp = prompt = 0
    seen_keys = set()
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            try:
                o = json.loads(line)
            except Exception:
                continue
            u = o.get("usage")
            if not isinstance(u, dict):
                continue
            seen_keys |= set(u.keys())
            prompt += u.get("prompt_tokens", 0) or 0
            comp += u.get("completion_tokens", 0) or 0
            hit += u.get("prompt_cache_hit_tokens", 0) or 0
            miss += u.get("prompt_cache_miss_tokens", 0) or 0
    worst = prompt / 1e6 * P_IN + comp / 1e6 * P_OUT
    real = miss / 1e6 * P_IN + hit / 1e6 * P_HIT + comp / 1e6 * P_OUT
    has_split = (hit + miss) > 0
    return prompt, comp, hit, miss, worst, real, has_split

print("%-10s %8s %8s %10s %12s %12s" % ("run", "prompt", "output", "cache miss", "worst ¥", "actual ¥"))
for path in sys.argv[1:]:
    prompt, comp, hit, miss, worst, real, has_split = cost(path)
    name = os.path.basename(path).replace("-log.jsonl", "")
    print("%-10s %7.2fM %7.1fK %9s %12.2f %12s" % (
        name, prompt / 1e6, comp / 1e3,
        ("%.2fM (%.0f%% hit)" % (miss / 1e6, 100.0 * hit / (hit + miss))) if has_split else "n/a",
        worst, ("%.2f" % real) if has_split else "n/a (log has no cache split)"))
