# Code-Canvas 前端调研（charting 资产）

> 2026-08-19 wayfinder charting 期间的探索报告。Code-Canvas = 同命题早期尝试（Replit 环境 pnpm monorepo，React 19 + Vite SPA）。
> 定位：**视觉与交互基准（用户已认可其呈现与样式）**，技术栈不继承。所有路径在 `~/workspace/aiplatform/Code-Canvas/`（下称 `CC/`）。

## 技术栈与结构（参考，不继承）

- 前端 `CC/artifacts/workbench/`：React 19 + TS 5.9 + **Vite 7 SPA + wouter 路由** + TanStack Query 5 + shadcn/ui(Radix) + Tailwind v4 + react-markdown/remark-gfm + sonner。**无全局状态库**（服务端状态归 Query，本地 UI state 归组件——流式状态也放组件 state，是坑，见下）。
- 业务代码约 3,700 行；shadcn 模板约 5,700 行（大部分未用）。
- 核心文件：`pages/workbench/{index,chat-panel,workspace-panel,stage-panel}.tsx`、`lib/api.ts`（唯一网络出口，类型来自 OpenAPI 生成的 `lib/platform-api`）、`lib/hooks.ts`。
- 契约纪律：`CC/contracts/openapi.yaml`（手写）→ 生成 TS 类型，前端不手写请求类型。设计文档 `CC/docs/design/`（12 章）+ `CC/.agents/memory/`（实测坑记录）。

## 核心 UI 形态

- **三栏布局**（`workbench/index.tsx`）：左对话区（30%，320–400px）/ 中栏页签（预览·文件·文档·时间线）/ 右阶段面板（20%，280–340px）；顶栏项目名 + 阶段徽章。**<1024px 折叠为三页签**。
- **对话流**（`chat-panel.tsx`）：用户消息纯文本右泡；agent 消息 Markdown（react-markdown+GFM）；system 消息居中胶囊；消息头带检查点 hash 徽章 + 回放按钮。
- **SSE 直播**：每工作台一条 EventSource；命名事件（`message.delta / thinking / tool.start / tool.result / task.done / failed / cancelled / approval.* / gate.* / stage.changed / checkpoint.created / preview.updated / …`）。
- **运行回放**：`RunReplayDialog` 拉全量事件，相邻同类 delta/thinking 合并，时间线 + 中文事件标签。

## 关键模式（T2/T3/T4 票的输入）

1. **SSE 事件只做 `queryClient.invalidateQueries`，不在前端合成状态**——服务端是唯一真相源；流式气泡用本地 streams 表（text/thinking/tools/failed）。
2. `{runId}:{seq}` 事件持久化 id + Last-Event-ID 断线补发 + 前端 Set 去重（上限清空）。
3. 15s 轻轮询兜底（SSE 组件未挂载时）；30s 轮询 usage。
4. **双表 HITL**：决策门（stage_gates）与工具审批（approval_actions）分表分端点，UI 统一「待我处理 N 项」，卡片并列渲染在消息流底部。
5. 审批卡：工具入参 pre 展示 + 30min 过期告知 + **「终止任务」逃生口**（防「拒绝≠停止，LLM 换形式重试形成审批循环」实测坑）。
6. 闸门卡：驳回必填意见且「原样转给智能体」；阶段迁移只能人触发、智能体只能申请；decide 校验 `stage==from_stage` 防陈旧闸门。
7. **202 受理 + SSE 回报 + toast** 的长操作模式（回滚等异步操作）。
8. **预览安全**：iframe `sandbox="allow-scripts allow-forms allow-popups"` 故意不给 allow-same-origin；HEAD 探测入口；nonce 强制重载；「有更新点击刷新」而非自动刷新。
9. Checkpoint：消息旁 hash 徽章 + 时间线卡 + owner 专属回滚（AlertDialog 讲清 revert 语义 + 「DB 不回退」警告）。
10. 文件：保存/建/删 = commit，baseCommit 乐观并发（在线编辑是 textarea，无高亮无 diff——缺口）。
11. 计量薄实现：项目级 token 用量 + 配额卡（进度条/用尽变红/owner 改配额）。
12. 角色显隐 `useMyRole` + 服务端强制 + 无权限项目渲染 404。
13. query key 工厂 + ApiError 带后端中文 message + 处处重试 + `data-testid` 全覆盖。
14. 首页一句话输入即建项目进工作台（零摩擦入口）；需求变更必填说明转给澄清智能体。

## 坑（不继承）

- wouter+Vite SPA、Replit 插件、5.7k 行全量 shadcn 拷贝、Express 壳。
- **流式状态放在面板组件 useState 里**（窄屏切页签即丢）——应进全局 store。
- `window.prompt/confirm` 做文件操作、`window.CustomEvent` 跨面板通信（用 store/事件总线替代）。
- `find(c=>c.title==='主线程')` 找会话的 hack；消息整表 invalidate 而非追加。
- 无语法高亮、无 diff、无真文件树；阶段面板底部假状态文案。

## 与本仓库的映射

- CC 设计文档 `docs/design/10-前端设计.md` 的**四端演进蓝图**（统一工作台→需求方门户/专业工作台/运营后台，「前端只是过滤视图，权限在服务端」）直接对应三门户：user=owner 子集、dev=developer、opc≈admin。
- 我们 = Next.js App Router + Zustand + Tailwind；服务端状态策略（TanStack Query 引入与否）是 T2 票的决策，CC 的 SSE→invalidate 模式是主要论据。
