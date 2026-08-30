# Phase B charting 资产 ①：当前 app 实况盘点（2026-08-22）

> 三路盘点之一（另见 ② deepseek-harness 标尺、③ Code-Canvas 功能面）。结论服务于「业务过程完整了吗」的判定。

## 一句话结论

不是纯空壳——任务外包循环、用量、知识命中、列表/旅程都是真数据真交互；但「对话建项目」零实现、「四门拍板」有全套装好的组件却没接线，Agent 对话区整体缺席，使主链在用户视角只剩「列表 + 详情页」。

## 业务过程完成度

| 业务环节 | 状态 | 证据 |
|---|---|---|
| 对话建项目 | ❌ 零实现 | 根页 redirect（`src/app/page.tsx:7-9`）；无 hero、无对话区；`POST /api/workspaces`、`POST /api/projects` 零调用，项目只能从后端来 |
| 七步呈现 | ✅ 走通（只读） | `stages[]` 数据驱动六步旅程，列表卡/顶栏/右栏三处真数据（`journey.ts:17-24`、`stages.ts:34-59`） |
| 四扇门拍板 | ❌ 断在最后一厘米 | 顶栏有 amber「等你」badge、待办有 GATE_PENDING，但详情页没挂 GateCard，无任何通过/驳回按钮；`useApproveStage/useRejectStage` 成死 hooks |
| agent 交互（梳理/Demo/开发中） | ❌ Agent 对话区整体缺席 | 对话/直播/待处理三模式不存在；直播 tab 只有知识命中 + 一行状态 |
| HITL | ❌ 全家零接线 | `waits/settle`、`questions/reply`、`permissions` 端点零消费；AGENT_WAIT 待办只能看标题 |
| 测试外包循环 | ⚠️ 基本走通（最实的一块） | dev 建任务指派 → OPC 开始/提交 → dev 确认/驳回（必填理由）→ 复测 → 确认；缺 `bugs/{bugId}/close`、`bugs/dispatch-fixes` 两个 mutation |
| 验收 | ❌ 缺动作 UI | 验收门同四门问题，无专门验收界面 |
| 交付 | ⚠️ 浅走通 | 终态换「交付说明」+ 源码包真下载（`doc-panel.tsx:36-60`） |
| 知识命中可见 | ✅ 最小实现 | 直播 tab 知识命中卡，SSE 真数据（`live-panel.tsx:127-189`） |
| token 用量可见 | ✅ 完整 | A6 升级版用量卡：五档 token + 平台成本分桶 + 未配价 + 按期/按模型 |

## 死代码四件套（写了、没挂载）

| 组件 | 位置 | 状态 |
|---|---|---|
| `GateCard` 决策门卡 | `src/components/main-chain/gate-card.tsx:51` | 通过/驳回表单齐全、接了 `stage/approve\|reject`，全仓无 import |
| `StageTimeline` / `StageBreadcrumb` | `src/components/main-chain/stage-views.tsx:15,67` | dev 口径段呈现，无消费方 |
| `StageRejectionBanner` | `src/components/main-chain/rejection-banner.tsx:28` | 桥写入的驳回理由无人显示 |
| `PreviewPanel` | `src/components/main-chain/preview-panel.tsx:26` | 项目详情场景预览，无消费方（仅 `PreviewChrome` 被 OPC 用了） |

设计有、组件完全不存在：Agent 对话区三模式、HITL 卡、问答卡、权限批准卡。

## 后端已有、前端零接线端点

`POST /api/workspaces`（建项目）、`POST …/exec`（终端）、`GET/POST …/agent/waits(/{waitId}/settle)`（HITL，workspaces 与 projects 两套）、`POST …/agent/tasks`、`GET …/agent/sessions`、`GET …/questions` + `POST …/questions/{requestId}/reply`、`POST …/permissions/{permissionId}`、`POST …/bugs/{bugId}/close`、`POST …/bugs/dispatch-fixes`。（schema.d.ts 全有，hooks 里 grep 不到）

## 已接线端点（健康面，不用动）

`GET /api/me`、`GET /api/projects` + archive、`GET /api/projects/{id}`、stage approve/reject（死等挂载）、preview（同）、usage、demand-pool、todos、tasks 全套（start/submit/confirm/reject/cancel + 建/指派）、bugs 查询、accounts、source-package（`<a download>` 直链）。

## SSE 层实况

- 通道一（`/api/events`）root 常开，五事件 → 失效注册表；`stage-changed` 驳回理由、`preview-ready` 写 project-notices store——**rejection 无 UI 消费，previewUpdate 只有 OPC PreviewChrome 在读**。
- 通道二（`/api/agent-events?projectId=`）工作台挂载，桥把 8 类平台事件 + 引擎透传（text/reasoning/patch/tool/step）**全量**写进 agent-streams store，分段模型齐全——但 `LivePanel` 只渲染 knowledge 段 + role 段一行状态；wait 段只用来失效 todos。

## 页面实况速览

- `/` → redirect `/projects`；`/dev` 与需求端共用 `ProjectListView` 只换标题，无 dev 专属工作台
- `/(portal)/projects/[id]`：两栏（主面板 tabs 文档/任务/Bug/直播 + 右栏信息/旅程/用量/想法池），**不是设计的三栏**；「文档」tab 交付前只有一句占位（`doc-panel.tsx:62-66`）
- `/opc/tasks/[taskId]`：全应用最完整页面（任务信息 + Bug 工作区 + 预览 iframe，全交互）
- `/dev/members`：只读表格；`/dev/todos`、`/opc/todos`：真数据，点击跳转但（门类）无事可做
- `/prototype/*` 四组变体（workbench ~2071 行、user-portal ~2924 行）含三栏对话中心、HITL 卡、直播舞台、决策收件箱——**正式版主要搬走了只读展示，demo 里最核心的交互形态一个没落地**

## 根因（为什么决策与原型没变成产品）

1. 执行轴 = 后端块（A1-A6 对接 issue），不是业务流：后端有端点但没人发对接 issue 的，永远进不了队列
2. 验收按端点收口不按旅程：#19 关闭时 GateCard 未挂载也算交付
3. 原型被当决策资产而非实现契约，无机制强制「正式版 ≥ 原型」
