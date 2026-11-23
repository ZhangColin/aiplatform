# -*- coding: utf-8 -*-
"""由第二遍的核对说明生成第三遍的：新应用部分的检查要求逐字不变，只换容器／端口／输出路径；
旧应用已核对过两遍，这次只在需要比较的具体条目上查。用法：python make_brief_pass3.py"""
import io
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
BASE = "C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/"

for name, n in (("helpdesk", 1), ("pinyin", 2), ("warehouse", 3)):
    s = io.open(BASE + "eval-brief-pass2-%s.txt" % name, encoding="utf-8").read()

    def rep(old, new):
        """替换一次；找不到就报错，防止说明文字改了而这里没跟上。"""
        global s
        assert old in s, "not found: %r" % old[:70]
        s = s.replace(old, new, 1)

    def line(prefix):
        return [l for l in s.split("\n") if l.startswith(prefix)][0]

    s = s.replace("v0r2b-q", "v0r2c-q")
    rep("localhost:1812%d" % n, "localhost:1813%d" % n)
    rep("eval2-q%d.md" % n, "eval3-q%d.md" % n)
    rep("shots-new2-q%d/" % n, "shots-new3-q%d/" % n)
    rep("## The two apps", "## The apps")
    rep(line("- **OLD app** —"),
        "- **OLD app** — container `v0-run%d`, http://localhost:1809%d. Generated on 2026-11-21 by the earlier approach and kept as-is "
        "for the owner's review. It has already been checked twice by other reviewers, so do not go through it item by item this time: "
        "check it only where you need it in order to decide whether the NEW app is worse or better on a specific point "
        "(read-only; logging in is fine; no business-data writes)." % (n, n))
    rep("## What to check, for BOTH apps", "## What to check (NEW app in full; OLD app only for specific comparisons)")
    rep("For each item and each app give a verdict — 完成 / 部分 / 未做 — plus concrete evidence",
        "For each item give a verdict for the NEW app — 完成 / 部分 / 未做 — plus concrete evidence")
    rep(line("- Other verification jobs are running"),
        "- Other verification jobs may be running at the same time against other containers (names starting with `v0-run`, `v0r2-`, "
        "`v0r2b-`, `v0r2c-`): do not touch any container other than `v0-run%d` and `v0r2c-q%d`.\n"
        "- Do not open the earlier review reports in the host folder (`eval-q*.md`, `eval2-q*.md`) until your own checks are finished: "
        "every verdict must come from what you observed yourself." % (n, n))
    rep("## Screenshots (both apps)", "## Screenshots (NEW app)")
    rep(line("- OLD app → `"),
        "Screenshots of the OLD app already exist in `C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/shots-old-q%d/` and "
        "`.../shots-old2-q%d/`; open them only when you need one for a specific comparison." % (n, n))
    rep("the item-by-item table (条目｜旧应用｜新应用｜证据)",
        "the item-by-item table (条目｜新应用｜证据; add a 「与旧应用比」 remark only where you checked it)")
    rep("the counts of 完成／部分／未做 for each app; every item where the NEW app is worse than the OLD app, and every item where "
        "it is better; the most serious defects in each app; and the file paths of the 4–6 most important screenshots per app.",
        "the counts of 完成／部分／未做 for the NEW app; every item where the NEW app is worse than the OLD app and every item where "
        "it is better (each such comparison checked by you on the OLD app); the most serious defects in the NEW app; and the file "
        "paths of the 4–6 most important screenshots of the NEW app.")

    for bad in ("`v0r2b-q", "localhost:1812", "shots-new2-", "eval2-q%d.md" % n):   # 「别碰的容器」那行会特意提到 v0r2b-，所以只查 v0r2b-q
        assert bad not in s, "leftover: %r" % bad
    io.open(BASE + "eval-brief-pass3-%s.txt" % name, "w", encoding="utf-8", newline="\n").write(s)
    print("written eval-brief-pass3-%s.txt (%d chars)" % (name, len(s)))
