# -*- coding: utf-8 -*-
"""挑出关键截图，拷到 _staging/ 供 convert-shots.js 转 jpg 后存进抛弃分支。
选的是各核对报告点名的关键截图＋硬伤证据；全量原图只在本地。用法：python pack_shots.py"""
import io
import os
import shutil
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
SELECTED = {
    # 第一遍核对的截图（新旧都核了）
    "shots-new-q1": ["03-board.png", "04-list.png", "05-stats.png", "07-detail-processing-overdue.png", "10-detail-notfound.png", "13-eng-board.png"],
    "shots-old-q1": ["03-board.png", "04-list.png", "05-stats.png", "07-detail-processing-overdue.png", "13-eng-board.png", "17-stats-trend-card.png"],
    "shots-new-q2": ["01-home-before.png", "04-recognize-1-loaded.png", "09-match-3-round-done.png", "02-parent-before.png", "30-levelsmap-clip1.png", "42-level1-quiz-result.png"],
    "shots-old-q2": ["01-home.png", "03-recognize-2-after-click.png", "05-match-2-one-pair.png", "07-find-2-after-correct.png", "15-parent.png"],
    "shots-new-q3": ["00-home.png", "03-inbound-new.png", "08-stock.png", "12-reports.png"],
    "shots-old-q3": ["00-home.png", "02-products-new.png", "05-inbound-new.png"],
    # 第二遍核对：新应用各 4–5 张；旧应用只补硬伤证据
    "shots-new2-q1": ["new-01-lead-board-full.png", "new-02-lead-list.png", "new-03-lead-stats-full.png", "new-04-lead-members.png", "new-13-eng-detail-other.png"],
    "shots-old2-q1": ["old-12-eng-members.png", "old-15-stats-trend.png"],
    "shots-new2-q2": ["00-home.png", "06-map.png", "10-parent.png", "26-match-result.png", "50-match-lines-after-followup.png"],
    "shots-old2-q2": ["02-match.png"],
    "shots-new2-q3": ["new-00-home-full.png", "new-04-inventory.png", "new-w-4c-stocktake-completed.png", "new-w-6-outbound-shortage-blocked.png"],
    "shots-old2-q3": ["old-zoom-pn.png", "old-i-inbound-new.png"],
    # 第三遍核对：只核新应用
    "shots-new3-q1": ["n3-02-board-manager.png", "n3-02f-board-col-inprogress.png", "n3-03-list-manager.png", "n3-05-stats-manager.png", "n3-07-detail-inprogress-overdue.png", "n3-13-board-engineer-mine.png"],
    "shots-new3-q2": ["00-home-full.png", "06-game-renyiren.png", "07d-lianyilian-correct.png", "08-game-zhaoyizhao.png", "02-levels-full.png", "05-parent-full.png", "12-play-zhaoyizhao-result.png"],
    "shots-new3-q3": ["01-home-full.png", "03-inventory-full.png", "18-outbound-new-shortage.png", "13-stocktake-1-completed-full.png", "15-reports-full.png"],
    # 修正轮（照 DSH 改齐）：只核新应用；q2 的文件在 shots/ 子目录里
    "shots-new4-q1": ["01-board.png", "08-tickets-inprogress-red.png", "03-stats.png", "05-ticket-detail.png", "06-ticket-detail-review.png", "07-board-scrolled.png"],
    "shots-new4-q2/shots": ["01-home.png", "09-play-zhao.png", "11-levels-full.png", "10-parent-full.png", "15-zhao-result.png", "16-home-after.png"],
    "shots-new4-q3": ["home.png", "inventory.png", "reports.png", "stocktake-1.png", "stocktake-2.png", "inbound-new.png"],
}

staging = os.path.join(HERE, "_staging", "in")
if os.path.isdir(os.path.join(HERE, "_staging")):
    shutil.rmtree(os.path.join(HERE, "_staging"))
lines, missing = [], []
for folder, names in sorted(SELECTED.items()):
    for name in names:
        src = os.path.join(HERE, folder, name)
        if not os.path.isfile(src):
            missing.append(folder + "/" + name)
            continue
        dst = os.path.join(staging, folder, name)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        lines.append("%s/%s" % (folder, name))
io.open(os.path.join(staging, "list.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
print("selected: %d files, %.1f MB png" % (len(lines), sum(os.path.getsize(os.path.join(staging, l)) for l in lines) / 1048576.0))
print("missing:", missing if missing else "none")
