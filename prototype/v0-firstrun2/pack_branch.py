# -*- coding: utf-8 -*-
"""把要留档的资产拷进抛弃分支的工作副本。用法：python pack_branch.py <旧题面所在目录> <工作副本根目录>
不拷的：tickets/（取回的 issue 原文）、src/ 与 dsh/（第三方源码副本，只在本地核对用）、
probe*/analyze*（#590 审计阶段的辅助脚本）、NOTES.md、决议草稿、全量截图原图。"""
import glob
import io
import os
import shutil
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
OLD, WT = sys.argv[1], sys.argv[2]
DST = os.path.join(WT, "prototype", "v0-firstrun2")
os.makedirs(DST, exist_ok=True)

files = [
    "README.md",
    "v0-harness3.py", "v0-iter3.py", "setup.sh", "run_batch.sh",
    "summarize3.py", "compare.py", "cost.py", "finaltext.py", "seq.py", "check_texts.py",
    "verify-followup-q1.py", "verify-followup-q3.py", "line-check.js", "button-check.js",
    "pack_shots.py", "convert-shots.js", "make_brief_pass3.py", "pack_branch.py",
    "followup-q1.txt", "followup-q2.txt", "followup-q3.txt",
    "eval-q1.md", "eval-q2.md", "eval-q3.md", "eval2-q1.md", "eval2-q2.md", "eval2-q3.md",
    "eval3-q1.md", "eval3-q2.md", "eval3-q3.md",
]
files += sorted(os.path.basename(p) for p in glob.glob(os.path.join(HERE, "eval-brief-pass*-*.txt")))
files += sorted(os.path.basename(p) for p in glob.glob(os.path.join(HERE, "selftest-*-log.jsonl")))
for prefix in ("v0r2-q", "v0r2b-q", "v0r2c-q", "v0r2i-q"):
    for n in "123":
        files += ["%s%s-log.jsonl" % (prefix, n), "%s%s.out" % (prefix, n)]

n, missing = 0, []
for f in files:
    src = os.path.join(HERE, f)
    if not os.path.isfile(src):
        missing.append(f)
        continue
    shutil.copy2(src, os.path.join(DST, f))
    n += 1
for f in ("prd-run1-helpdesk.md", "prd-run2-kids.md", "prd-run3-warehouse.md"):
    shutil.copy2(os.path.join(OLD, f), os.path.join(DST, f))
    n += 1

shots_root = os.path.join(HERE, "_staging", "out")
for root, _, names in os.walk(shots_root):
    rel = os.path.relpath(root, shots_root)
    os.makedirs(os.path.join(DST, rel), exist_ok=True)
    for name in names:
        shutil.copy2(os.path.join(root, name), os.path.join(DST, rel, name))
        n += 1
print("copied %d files into %s" % (n, DST))
print("missing:", missing if missing else "none")
print("has '{{':", any("{{" in io.open(os.path.join(DST, f), encoding="utf-8").read() for f in files if f.endswith(".md") and os.path.isfile(os.path.join(DST, f))))
