# Spec 0001 · 开发平台工作台 UX 与主 Layout

> 产地：wayfinder T4（[#6](https://github.com/ZhangColin/aiplatform-web/issues/6)，prototype 票）。
> 可点原型（primary source）：分支 `prototype/t4-workbench` 的 `src/app/prototype/workbench/`
> （?variant=A|B|C|D，D 为定稿形态；另有 `/prototype/portal` 非工作台页示意、`/prototype/preview` 独立预览示意）。
> 真实实现等后端 A3 对接 issue；对接时照本 spec 落地，面板内容分配到功能点再定。

## 1. 定稿结论

工作台 = **D 融合壳**：平台导航 sidebar + resizable 多栏 + Agent 区场景化模式 + 主面板 tabs。
A/B/C 三变体不是三选一，而是同一 Agent 区的三种场景模式：

- 频繁来回对话 → **对话**模式（A 形态：消息流 + HITL 卡嵌流底 + 下任务输入）
- 人低频参与、AI 自动干活 → **直播**模式（B 形态：舞台时间线；任务在服务端跑，人关了浏览器不中断，回来继续看——「直播」语义）
- 人不在时攒下决策项 / 多角色协作 → **待处理**模式（C 形态：决策队列卡 + 徽章计数）

## 2. 主 Layout（平台级，工作台与非工作台页共用）

- 骨架：`SidebarProvider` + `Sidebar collapsible="icon"` + `SidebarInset`（shadcn sidebar）。
- Sidebar 内容 = 平台导航：项目组（项目列表 + 阶段徽章）+ 平台组（待办中心、成员……随功能生长）；**菜单最终因角色而异**（v1 单账号全显，代码按角色留缝）。
- **light/dark 切换放 sidebar footer**（用户行旁），全平台一致。
- 非工作台页（待办中心 / 成员 / 消息等）复用同壳：`SidebarInset` 内换标准页面（页头 = SidebarTrigger + 标题 + 说明，内容区常规布局），不再有三栏。待办中心为跨项目聚合的 HITL 等待 / 任务确认 / 门卡队列，卡上标注来源项目。
- 移动端（<768px）sidebar 自动 offcanvas（组件内置）。

## 3. 工作台布局（D）

```
┌─ Sidebar ─┬─ SidebarInset ──────────────────────────────────────┐
│ 平台导航   │ 顶栏：Trigger·项目名·阶段徽章·stepper │LIVE 计时·终止│发测试任务
│ (icon 收起)├──────────┬──────────────────┬───────────────────────┤
│           │ Agent 区  │ 主面板 tabs        │ 阶段·任务面板          │
│           │ 模式 tab: │ 预览│文档│时间线    │ 期步骤·决策门·测试任务  │
│           │ 对话/直播/ │ 工具条：刷新        │（显式开关收起 → 两栏态）│
│           │ 待处理(N) │ ·浏览器打开·◧      │                       │
└───────────┴──────────┴──────────────────┴───────────────────────┘
```

- **resizable 三栏**：Agent 区默认 380px / 最小 260px（collapsible）；主面板自适应（最小 320px）；右栏默认 320px / 最小 220px。
- **右侧阶段·任务面板 = 显式图标开关**：按钮在主面板工具条上、「浏览器打开」右侧（与该区域一体的视觉）；收起后右栏完全不可见（两栏态），点击展开。左栏（Agent 区）由拖拽折叠（collapsible）。
- **顶栏运行状态常驻**：LIVE 脉冲 + 计时（mm:ss）+ 终止按钮——任何模式下可见任务存活；这是「关了浏览器也在跑」的锚点。
- `<1024px`：退化为主面板三页签（对话 / 工作区 / 阶段），顶栏运行状态保留。

## 4. Agent 区三模式

同一任务的三副面孔；tab 切换不丢状态；默认「对话」，有等待项时「待处理」显示计数徽章引导。

### 4.1 对话
消息流自上而下：系统胶囊（阶段迁移等）/ 用户右泡 / agent 段落（Markdown，实现时选型）/ 工具 chip / 思考折叠 / patch 摘要 / 知识命中卡；**HITL 卡并列渲染在消息流底部**（CC 模式：双表统一「待我处理」）。底部：运行条（任务号 + 计时 + 终止）+ 下任务输入框。

### 4.2 直播
无气泡的舞台时间线，事件为大块：工具卡（icon + 名称 + 入参 + spinner→✓）/ patch 为 diff 行级块（+/- 染色 + 摘要行）/ 知识命中横幅（双卡网格）/ 思考折叠。语义 = 回看与围观执行过程。

### 4.3 待处理
决策队列大卡纵排，每卡头部 = 类型（提问/审批/任务确认）+ 来源（任务号 · 角色或执行方）+ 时间；处理后收为「已处理」一行，计数联动。空态 =「一切自动运行中」+ 当前任务摘要。

## 5. 元素规格

- **工具 chip**：icon（read/edit/bash…）+ 名称 + 入参截断 + 状态（进行中 spinner / 已执行 ✓）。
- **问答卡**（demo `pendingQuestions` 形状：一卡多题）：单选 RadioGroup / 多选 Checkbox / 可选自定义输入；选项可带 description tooltip。
- **审批卡**：工具 + 入参 `pre` 展示 + 允许 / 拒绝 + 过期告知（默认 30min）+ **「终止任务」逃生口**（destructive ghost，防「拒绝≠停止」的审批循环）。
- **门卡**：通过 / 驳回必填意见且原样回传智能体；locked 态展示解锁条件（如「测试循环还有 N 条未关闭 Bug」）；`decide` 校验 stage==from_stage 防陈旧（CC 借鉴）。
- **测试任务卡**：执行方 + 状态 + Bug 清单（三态：待修复 / 已修复·待复测 / 复测通过）+ 动作：确认（入库 + 自动派修复）/ 驳回（退回返工，必附说明）/ 发复测（存在待修复 Bug 时 disabled 并说明原因）。
- **知识命中卡**：来源（PRD/Bug 记录等）+ 所属项目 + chunk 摘要两行；叙事点，不许缩水。
- **预览**：iframe `sandbox="allow-scripts allow-forms allow-popups"`（**不给 allow-same-origin**）；「有更新 · 点击刷新」手动刷新不自动；**可在独立浏览器页打开**（工作台外无壳全屏）。

## 6. 数据与状态口径（指向既有 ADR）

- 服务端状态 + SSE→invalidate：ADR 0002；SSE 连接拓扑 / streams store / 重连：ADR 0003。
- 流式渲染状态（气泡增量、工具进行中）归 Zustand streams store，禁止组件 state（CC 实测坑）。
- 长操作（终止、回滚类）= 202 受理 + SSE 回报 + toast。

## 7. 到功能点再定（不在本 spec 锁死）

- 主面板各 tab 的具体内容分配（用户明示：内容放哪块面板到具体功能点思考）。
- agent 消息 Markdown 渲染选型（react-markdown + GFM 为 CC 参考）。
- 运行回放（CC `RunReplayDialog` 借鉴与否）、消息 checkpoint 徽章。
- token 用量呈现（地图 fog，等 A6）。

## 8. 原型资产

- 分支 `prototype/t4-workbench`：`src/app/prototype/workbench/`（canned.ts 罐头数据 + variant-a/b/c/d + switcher）、`src/app/prototype/portal/`、`src/app/prototype/preview/`。生产构建整体渲染为 null（`NODE_ENV` gate）。
- 罐头流事件类型对齐 demo：`task-start / text / reasoning / step-start / step-finish / knowledge-retrieved / patch / error`。
