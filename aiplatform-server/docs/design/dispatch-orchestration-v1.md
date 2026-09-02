# 派发机制设计 v1（编排代码化 · 判定下放）

依据：ADR 0003、CONTEXT.md「派发」「迭代」「问答」词条、调研结论（agentscope-java 2.0.1 无图编排原语，subagent 为父 LLM 工具调用式派发、不适用于派发骨架；SAA graph v1 不引入）。编排落在现有外层 Java 代码（IterationAppService 一族），HarnessAgent 继续作单智能体执行器。

## 1. 编排总图

```
用户消息（指令区）
 │
 ├─ [代码] 入口判定：意见 / 咨询 / 兜底
 │
 ├─ 咨询 ──→ [助理] 查事实、直接作答 ──→ 终止（零产物）
 ├─ 兜底 ──→ [代码] 轻量引导回复 ──→ 终止（禁止产物变更）
 │
 └─ 意见 ──→ [BA] 需求侧判定
              ├─ 要追问 → 问答卡，链挂起；答复即续跑本环节
              └─ 要改 PRD → savePrd（需求变更；不改则跳过）
              [代码] turn 收口：观测工具事实
              │
              [代码] 组装交接物 → 派修正 run
              （守卫：已归档拒；未生成 → 止于 BA；在途 run → 排队合并）
              │
              [CODER] 开发侧判定：动系统？
              ├─ 动 → 修改系统 → finish_fix(changed=true, summary)
              └─ 不动 → finish_fix(changed=false, reason)
              │
              [代码] 收口：状态条 / 直播 / 预览刷新；「不动」如实呈现
```

生成前（`generatedAt == null`）意见链止于 BA；咨询、兜底与生成状态无关。

## 2. 判定契约：结果从工具事实观测，LLM 不自报

LLM 只做判定动作（调不调工具）；判定结果由平台从工具调用事实观测——不新增任何"请输出 JSON 结论"的自报面。

| 判定点 | 判定者 | LLM 动作 | 平台观测（机器可读结果） |
|---|---|---|---|
| 消息类型 | 入口判定（轻量 LLM） | 结构化分类调用 | `type ∈ {OPINION, INQUIRY, FALLBACK}`；失败兜底=OPINION |
| 要不要追问 | BA | `ask_user` | `question-raised` 事件 → 链挂起，答复续跑 |
| 要不要改 PRD | BA | `savePrd` | 调用事实+修订说明 → `prdUpdated`、`prdChangeSummary` |
| 要不要动系统 | CODER | `finish_fix(changed, text)` 结束工具 | run 收口读事实 → `systemChanged`、摘要/原因 |

## 3. 各环节规格

### 3.1 入口判定
- 位置：`postMessage` 之后、BA 会话之前（新 Dispatch 逻辑，替换 `BaInterviewAppService.turn()` 里 `RolePreset.BA` 硬编码入口）。
- flash 级模型；**失败/超时兜底=按意见**——误入意见链有 BA 把关、代价小；误判为咨询会丢变更，不可接受。
- 兜底类：轻量引导回复（含下单意图引导到「确认下单」），禁止任何产物变更。

### 3.2 BA 环节
- 工具集：`ask_user`、`savePrd`（**`startFixRun` 撤除**——派发权归平台）。
- 角色卡协议第 9 条改写：BA 不再决定是否派修正，只判追问与改 PRD。
- turn 收口（无追问挂起）即触发下游派发；追问挂起走现有 ConfirmResult 续跑。

### 3.3 交接物与派发
- 交接物 = **用户意见原文**（本轮+排队合并各条）+ **BA 判定结果**（`prdUpdated`、`prdChangeSummary`）+ PRD 引用（CODER 自读工作区 `docs/PRD.md`）。
- `fixRunPrompt`（IterationAppService）改为交接物的结构化拼装。
- 守卫沿用：PRJ_013 归档拒、PRJ_019 未生成止于 BA；在途 run 排队合并（现有 while 状态机不动）。

### 3.4 CODER 环节
- 新增结束工具 `finish_fix(changed: bool, text: string)`：判定不动系统时也**必须**调用（`changed=false` + 原因），run 收口以它为准。
- 「不动」呈现：收口时如实说明（如"纯文档性修订 / 系统现状已满足，未动系统"+原因）——防「主色调式困惑」换位重现。
- 修正 run 重试超限终态保留恢复出口（仅异常态出现，正常态全自动）。

### 3.5 助理职能体（咨询）
- 新 `RolePreset.ASSISTANT`，同构于 BA/CODER（角色卡+工具集+会话 `assist-{projectId}`）。
- 工具：工作区**只读**（文件树/文件内容，复用现有读路径）+ 项目事实查询；不知就答不知。
- 零产物；不受修正 run 排队影响，随时可答。

## 4. 工具面按角色收紧

`BaToolkitSupplier` 按工作区发放改为按角色：BA=`{ask_user, savePrd}`；CODER=∅（业务工具空，编码工具由 harness 内核自带）；ASSISTANT=只读集。修复 CODER 名义上挂着 `ask_user/savePrd/startFixRun` 的泄漏。

## 5. SSE 最小新增

- `dispatch-stage` 帧（阶段状态条数据源）：`analyzing / clarifying / updating-prd / dispatching / fixing / done(changed|not-changed) / answered`。
- `role-assigned` 扩展 assistant；前端 `command-area` 硬编码「需求分析师」改为随 role 帧呈现。
- 渐进预览刷新属预览工作线（另文），接口已备：CODER 的 `live-step` 事件即"一次完整修改"信号源。

## 6. 状态与持久化（v1 最小）

- 判定结果**不新建表**：以事件帧广播（重放缓冲）+ runId 锚定；跨会话恢复以查询接口为准（现状口径）。
- 迭代判定记录表为可选实施项（保扩展点，不预支）。

## 7. 实施切片（每片独立可测）

| 片 | 内容 | 直接效果 |
|---|---|---|
| S1 | 撤 `startFixRun`；BA turn 收口观测 `savePrd` 事实 → 代码自动派修正 run | **修复「改主色调只改 PRD」** |
| S2 | CODER `finish_fix` + 「不动」如实呈现 | 链尾闭环，困惑不换位重现 |
| S3 | 入口判定 + 助理职能体 | 咨询零产物短路 |
| S4 | 工具面按角色收紧 | 修 CODER 工具泄漏 |
| S5 | `dispatch-stage` 状态条 | 「正在分析您的意见…→需求已更新，正在修改系统…」 |

S1 优先（用户实测痛点，且不依赖 S3–S5）。

## 8. 演进接口（只留扩展，不实施）

入口判定点即 Leader 编排智能体的种子；届时优先评估 agentscope-java `team/coordination` 模块（未发版，Agent Teams 任务板+邮箱）与 SAA graph 分层（ADR 0003 触发信号为准）。运行内委托（如 CODER 派 reviewer 子任务）可用 subagent 的事件转发（SubagentEventBus 带源标签入 SSE 桥），不作派发骨架。
