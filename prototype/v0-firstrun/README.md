# 内核 v0 首建实证（#576）——原型资产快照

**抛弃分支，不合并。** 实证正本＝[#576 决议评论](https://github.com/ZhangColin/aiplatform/issues/576)；本分支只存资产原件供回溯。

## 内容

- `v0-harness.py`：驱动 harness。腿④ `bare-harness.py` 原样，仅两处差：废 `MAX_ROUNDS=150` 截停（#575 干到做完决议）；加 300 轮/75min 实验保护线（运营护栏非内核语义，触发即停并记 `OPERATIONAL GUARDRAIL`）。模型/提示词/3 工具/超时参数零改动。
- `prd-run1-helpdesk.md`／`prd-run2-kids.md`／`prd-run3-warehouse.md`：三题面。题1＝腿④工单 PRD 原文照搬（含「风格方向：科技前沿」）；题2＝新拟＋具体风格词；题3＝新拟＋零风格词。
- `v0-run{1,2,3}-log.jsonl`：逐轮日志（调用摘要＋usage）。
- `v0-run{1,2,3}.out`：harness stdout（回合进度行）。
- `shots-run{1,2,3}/`：容器内 chromium（/opt/render playwright-core）1366×900 截图。
- `shots-leg4/`：同题跨跑对照——腿④ NEXUS（pf-leg4/18083 容器）截图。

## 运行形态

- 三干净容器 `v0-run1/2/3`（`aiplatform/dev:0.18`，无挂载卷，宿主端口 18091/18092/18093→8081），`.env` 两行连接串（PG/Redis 容器内回环），题面经 `docker cp` 落 `/workspace/docs/PRD.md`（＝壳的输入接缝动作）。
- 三 harness 并行直跑 deepseek-flash（`api.deepseek.com`，裸 HTTP 无平台机制）。

## 复核入口（容器未清理前）

- 题1 NOVA 服务台：http://localhost:18091 （/board /list /stats /members）
- 题2 儿童拼音：http://localhost:18092 （/parent /pinyin /levels）
- 题3 仓管家：http://localhost:18093 （/products /inbound /outbound /inventory /stocktakes /reports）
- 腿④ NEXUS：http://localhost:18083
