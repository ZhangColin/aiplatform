# 内核 v0 迭代实证资产（#586，抛弃分支不合并）

三腿真跑（2026-11-22）：小改×2 连锁（v0-run1 工单看板，同容器）＋中改×1（v0-run2 儿童拼音）。
迭代语义＝#577 正本：蒸馏自足指令落 docs/PRD.md 迭代节→既有工作区重跑内核（新上下文）。
实证正本见 #586 决议评论。

## 腿与协议

| 腿 | 容器 | 改动 | 催续协议 | 轮次 | 墙钟 | prompt tok |
|---|---|---|---|---|---|---|
| 小改① | v0-run1 | 主色青→靛紫（单项） | A（＝首建催续句） | 142 | 16.6min | 10.60M |
| 小改② | v0-run1 | 三项修正链（调亮/清青残留/品牌改名） | B（催续句带完成短路） | 79 | 7.3min | 4.59M |
| 中改 | v0-run2 | 错题重练模块（全栈新功能） | B | 68 | 5.0min | 3.13M |

协议 A→B 差异仅催续句一行（v0-iter-harness.py 现为 B 态）：
- A：`继续，直到完成迭代记录全部修改并在 8081 重新起服务。`
- B：`若迭代记录已全部落实、pnpm build 通过且 http://localhost:8081 有响应，就直接结束、不要再做额外核对；只有还有未落实的修改时才继续。`

实证：A 下完成后尾巴占 82% 墙钟/88% token（r39 已宣告完成，r40–142 全为催续逼出的复核循环）；B 下尾巴归零。

## 跑前快照（git 预演数据）

- naive ignore（node_modules/.next）：git add 19.8s、37622 对象——`.pnpm-store`（36594 文件）被收。
- 正本 ignore 集 `node_modules/ + .next/ + .pnpm-store/ + data/`：首次全量 156ms（run1，102 文件）/104ms（run2，87 文件）；迭代前 delta commit 121ms。
- `data/pg`＝活 PostgreSQL 数据目录，不可 git 快照；DB 半边回滚语义未解（观察点）。
- 模型对 .git：零分心、反向受益（git diff 两快照＝修正链语境源；git checkout 还原跑题文件；无自提交）。

## 文件

- `v0-iter-harness.py`：迭代驱动（#576 harness 仅换任务句＋催续句；协议 B 态）
- `v0-iter-summary.py`：log 汇总（轮次/墙钟/token/git 触碰/工具直方图）
- `prd-run1-iter.md`／`prd-run1-current.md`（=迭代②输入）／`prd-run2-iter.md`：三腿 PRD 输入
- `v0-v0-run1-iter1-log.jsonl`／`v0-v0-run1-iter2-log.jsonl`／`v0-v0-run2-iter1-log.jsonl`：逐轮日志
- `shots-run1-after-iter1/`／`shots-run1-after-iter2/`／`shots-run2-after-iter1/`：after 截图（before＝#576 资产）
- `shot-after.js`／`shot-after-run2.js`：截图脚本（容器内跑）
