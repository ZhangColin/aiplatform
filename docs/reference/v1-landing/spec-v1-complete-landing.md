# Spec · v1 平台完整落地：Agent 工作台 + 对话建项目 + HITL 接线 + 门拍板 + 测试收尾

> 产地：wayfinder [**#25**（v1 平台功能完整落地）](https://github.com/ZhangColin/aiplatform-web/issues/25) + phase-b 三路盘点（`docs/research/phase-b-01/02/03`）。
> 上源：[#2](https://github.com/ZhangColin/aiplatform-web/issues/2) T1–T7 决议全部有效不重开；spec 0001–0004 为既有形态契约；原型 `prototype/t4-workbench`、`prototype/t5-user-portal` 为正式版地板；deepseek-harness demo（`App.vue`）与 CC 为交互基准。
> **单一红线：正式版 ≥ 原型。** phase-b 盘点证实「决策与原型都已就位，但主链在用户视角只剩列表 + 详情页」——本 spec 把 Agent 运行时链路与门拍板从「死代码 / 零接线」接到真实数据。

## Problem Statement

前端当前是「半成品」：项目列表、任务外包循环、用量、知识命中、六步旅程都是真数据真交互，但**用户从一句话到交付的主链断在最后几厘米**——根页是 redirect 而非对话建项目入口；Agent 对话区三模式整体缺席；HITL 全家（问答卡 / 审批卡 / 转任务）端点零消费；四扇门拍板组件 `GateCard` 写好了却全仓无挂载（通过/驳回 hooks 成死代码）；驳回理由经 SSE 桥写入 store 却无人显示；终端 exec、下任务、Bug 关闭/派发修复两个 mutation 端点零调用。deepseek-harness demo 已验证的 agent 运行时链路（直播画面 / HITL / 终端 exec）没有做成平台功能；T4/T5 原型表达过的交互形态一个没落到正式版。

结果：正式版达不到原型，验收剧本（八幕）走不通「对话建项目 → 需求梳理问答 → 审批进开发 → 预览 → 交付 → 知识闭环」的主链。

## Solution

把三门户正式版补到 ≥ 原型：一条业务流轴贯穿——**对话建项目 → 七步四门 → 测试外包循环 → 验收交付**全程真实可操作；deepseek-harness demo 已验证的 agent 运行时链路（agent 直播画面全量 / HITL / 终端 exec）全部接到后端真实端点；T4/T5 原型表达过的交互（三栏工作台 / 三模式 Agent 区 / 门卡 / 问答卡 / 审批卡 / 安心卡）全部落地。后端端点已在 swagger（`localhost:8888/v3/api-docs`），对接是机械活；要修改 / 缺失的以 issue 提给 aiplatform-server 并跟进。

## User Stories

### 对话建项目

1. As a 需求端用户（不懂技术的人），I want 在门户首页一个一句话输入框里描述我要做什么，so that 零摩擦创建项目、不用理解「工作区/阶段/引擎」。
2. As a 需求端用户，I want 创建时可选「项目类型模板」（官网 / 电商），so that 平台按模板预置阶段链，我少填东西。
3. As a 开发平台人员，I want 创建时选引擎（opencode / dsh，来自 `GET /api/agent-engines`），so that 同一需求能用不同引擎跑对比。
4. As a 需求端用户，I want 一句话提交后直接进入该项目工作台的顾问对话，so that 第一轮需求梳理问答卡当场就位，不用回到列表再点。
5. As a 需求端用户，I want 首页看到「最近的项目」最多 4 条 + 「查看全部」入口，so that 项目少时少点一次，项目多时不堆满首页。
6. As a 开发平台人员，I want 项目列表页顶部也有「新建项目」入口（同一 hero 形态），so that dev 场景不绕需求端也能建项目。

### Agent 工作台（三栏壳，通用层）

7. As a 开发平台人员，I want 项目工作台是 resizable 三栏（左 Agent 区 / 中主面板 tabs / 右阶段·任务面板），so that 对话、产物、阶段决策同屏不跳页。
8. As a 开发平台人员，I want 右侧阶段·任务面板用显式图标开关收起/展开，so that 需要专注看产物时能切成两栏。
9. As a 开发平台人员，I want 顶栏常驻 LIVE 脉冲 + 计时 + 终止按钮，so that 任何模式下都知道「关了浏览器也在跑」并能在需要时喊停。
10. As a 开发平台人员，I want 屏幕 <1024px 时退化为三页签（对话 / 工作区 / 阶段），so that 窄屏仍可操作。
11. As a 需求端用户，I want 我的项目详情就是同一套三栏壳的「顾问对话单模式」配置，so that 我不用学另一套界面。

### Agent 区三模式（对话 / 直播 / 待处理）

12. As a 开发平台人员，I want 与智能体频繁来回时切「对话」模式，so that 消息流 + HITL 卡嵌流底 + 下任务输入在一处完成。
13. As a 开发平台人员，I want 人低频参与时切「直播」模式看舞台时间线，so that 关了浏览器回来看仍能看到 agent 干活全过程。
14. As a 开发平台人员，I want 攒下决策项时「待处理」模式显示计数徽章引导，so that 我不漏掉等待我拍板的每一件。
15. As a 开发平台人员，I want 三种模式 tab 切换不丢状态，so that 来回切不重拉不闪烁。

### Agent 直播画面（全量渲染）

16. As a 开发平台人员，I want 直播画面渲染流式文本气泡，so that 看到 agent 正在说的话。
17. As a 开发平台人员，I want 直播画面渲染工具 chip（icon + 名称 + 入参截断 + 进行中 spinner / 已执行 ✓），so that 看到 agent 正在用什么工具。
18. As a 开发平台人员，I want patch 渲染为 diff 行级块（+/- 染色 + 摘要行），so that 看到代码改了哪里。
19. As a 开发平台人员，I want 思考（reasoning）可折叠，so that 长思考不淹没主流程。
20. As a 开发平台人员，I want 知识命中渲染为横幅双卡（来源 + 所属项目 + chunk 摘要两行），so that 看到平台注入了哪段历史沉淀。
21. As a 开发平台人员，I want step 边界与 role 段、error、finish 分段可见，so that 运行生命周期完整可读。

### HITL（问答卡 / 审批卡 / 转任务）

22. As a 开发平台人员，I want 智能体提问以「问答卡」呈现（一卡多题、单选 / 多选 / 可选自定义输入、选项带 description），so that 多题一次答完。
23. As a 开发平台人员，I want 智能体申请工具权限以「审批卡」呈现（工具 + 入参 `pre` 展示 + 允许 / 拒绝 + 30 分钟过期告知），so that 我知道它要干嘛再决定。
24. As a 开发平台人员，I want 审批卡上有「终止任务」逃生口（destructive），so that 拒绝不会演变成 LLM 换形式重试的审批死循环。
25. As a 开发平台人员，I want 等待点可「转任务」交给 OPC 测试（deferred），so that 我能把需要人干活的事派出去而不是自己答。
26. As a 开发平台人员，I want 刷新 / 切项目后 HITL 等待点仍能找回（待办中心 + 工作台待处理），so that 轮询不随任务停止就丢待办。
27. As a 开发平台人员，I want 处理后的等待点收为「已处理」一行并联动待办计数，so that 队列干净。

### 四扇门拍板 + 验收

28. As a 开发平台人员，I want 决策门卡（通过 / 驳回必填意见原样回传智能体）挂在对话模式流底 + 右栏阶段面板，so that 门就绪时当场可拍板。
29. As a 开发平台人员，I want 门未就绪（locked）时显示解锁条件（如「测试循环还有 N 条未关闭 Bug」），so that 我知道为什么不能推进。
30. As a 需求端用户，I want 三扇门（确认需求清单 / 确认原型 / 验收）以卡片呈现、驳回必填说明，so that 拍板与反馈一体。
31. As a 需求端用户，I want 验收门有专门的验收动作界面（验收通过 / 驳回反馈），so that 交付前我能最终把关。
32. As a 开发平台人员，I want 门驳回后看到驳回理由（顶栏横幅 / 卡上），so that 知道为什么被打回、改哪里。

### 下任务 + 终端

33. As a 开发平台人员，I want 对话模式底部输入框给智能体下任务（可选角色卡 1–6，缺省取阶段默认角色），so that 我能驱动 agent 干活。
34. As a 开发平台人员，I want 主面板有「终端」tab 在工作区环境内执行命令（命令 → stdout/stderr/exitCode），so that 能进容器排查或手工操作。

### 测试外包循环收尾

35. As a 开发平台人员，I want Bug 面板能逐条关闭 Bug（`close`），so that 修复闭环到 Bug 三态推进。
36. As a 开发平台人员，I want 「派发修复」按钮把已关闭/待修复 Bug 自动派给开发智能体逐条修复（`dispatch-fixes`），so that 修复不用手动逐条下任务。
37. As a 开发平台人员，I want 测试循环跑通后开发完成确认门解锁，so that 主链能继续推进到验收。

### 预览 + 驳回理由 + 深链

38. As a 需求端用户 / 开发平台人员，I want 主面板「预览」tab 用安全 iframe 呈现产物（手动刷新 + 独立浏览器页打开），so that 看到 agent 做出来的东西。
39. As a 开发平台人员，I want 待办中心「智能体等答复」点击直达该项目工作台对话模式并定位到等待点，so that 从待办到处理零绕路。
40. As a 开发平台人员，I want 待办「门待拍板 / 任务待确认 / 可发复测」点击直达对应工作台面板，so that 待办即行动入口。

## Implementation Decisions

### 架构取向（不新开架构层，复用既有 seam）

- **正式版 = 原型的搬移，不是重写**：`prototype/t4-workbench` 的 `variant-d`（三栏壳 + 三模式 Agent 区）与 `prototype/t5-user-portal` 的 `variant-d`（顾问单对话模式）是直接实现契约；`prototype/shared/shell.tsx` 的 `PortalWorkbench` / `WorkbenchFrame` / `RightPanelToggle` 是通用层同构参考，落成正式版 app 级 layout + 工作台 layout 组件。
- **通用层 / 场景层**（spec 0002 §1 / 0003 §1 不变）：三栏工作台框架、Agent 对话区、预览、门卡、HITL 卡、文档面板 = 通用层；开发平台配置 = 三模式 Agent 区 + 全量面板；需求端配置 = 顾问单对话模式 + 用户侧文案。菜单内容仍归场景（spec 0003 §1 分配表不动）。
- **状态三分法**（ADR 0002/0003 不变）：服务端状态走 TanStack Query（key 工厂 `keys.ts` 增补），流式渲染状态归 `agent-streams` store（桥是唯一写入方），一次性局部态归组件 state。**直播全量渲染是纯读改动**——分段模型已齐全，桥已把 8 类平台事件 + 引擎透传全量写进 store，只差呈现层。

### API 契约（后端已在 swagger，对接为机械活）

- **对话建项目** = `POST /api/projects`：`{ name, type?: number(1=官网/2=电商), engine?: string, requirement?: string }` → `{ project, runId?, accepted? }`。响应 `runId` 即 BA 自动运行的 agent 流锚（`/api/agent-events?runId=`）。hero 一句 requirement → 项目名（可选）/ 引擎下拉（`GET /api/agent-engines`）/ 类型模板下拉（1 官网 / 2 电商）→ 建即直进工作台对话模式。
- **下任务** = `POST /api/projects/{id}/agent/task`：`{ prompt, role?: number(1=BA 2=DEV 3=DELIVERY 4=ARCH 5=TEST 6=DEMO) }` → `{ runId, sessionId, engine, role, roleName, stage, accepted }`。角色缺省取当前阶段默认角色（无默认角色的阶段需显式指定，409 兜底）。
- **HITL settle** = `POST /api/projects/{id}/agent/waits/{waitId}/settle`，三型载荷（decision-rich，来自 swagger）：

```ts
type ProjectWaitSettleCommand =
  | { type: "answer"; answers: string[][] }        // 问答：按题序，每项=选中标签列表（custom 也作标签）
  | { type: "permission"; approve: boolean }       // 权限：true 批准(once) / false 拒绝
  | { type: "deferred"; task: { title: string; content?: string; assigneeAccountId: number } }; // 转任务：关等待点 + 建任务
```

  HITL 查询 = `GET /api/projects/{id}/agent/waits`（跨会话聚合，仅 PENDING，`kind=QUESTION|PERMISSION` 统一承载，`body` 为引擎载荷原样 + `summary` 中性短文本）+ `GET /api/projects/{id}/agent/waits/{waitId}`（含终态行）。问答卡形状 = demo `pendingQuestions`（一卡多题、单选/多选、选项带 title、自定义文本）。
- **终端 exec** = `POST /api/workspaces/{workspaceId}/exec`：`{ command }` → `{ stdout, stderr, exitCode }`；`workspaceId` 取自 `ProjectDetailResponse.workspaceId`（项目详情已随附）。非 0 exitCode 是命令失败非环境故障。
- **门拍板** = `POST /api/projects/{id}/stage/approve`（无体）与 `POST /api/projects/{id}/stage/reject` `{ reason }`；`GateCard` 已接 `useApproveStage/useRejectStage`，只差挂载。`gate: { actor, ready }` 为渲染源；`ready===false` 锁操作钮。
- **Bug 收尾** = `POST /api/projects/{id}/bugs/{bugId}/close`（关闭单条）+ `POST /api/projects/{id}/bugs/dispatch-fixes`（无体，自动派开发智能体逐条修复）。两个 mutation 补进 tasks 域 hook。
- **驳回理由展示** = SSE `stage-changed`（`rejected:true, reason`）已由桥写入 `project-notices` store，只差消费组件（`StageRejectionBanner` 死代码挂载）。
- **预览** = `GET /api/projects/{id}/preview` → `{ url }`，iframe `sandbox="allow-scripts allow-forms allow-popups"`（不给 same-origin），手动刷新不自动，独立浏览器页打开（spec 0002 §6 / spec 0001 §5 不变）。

### 待办深链

- 待办类型 → 去向（spec 0003 §3 已定，落成深链）：`AGENT_WAIT`（refId=waitId）→ 该项目工作台对话模式并定位等待点；`GATE_PENDING` → 工作台门卡；`TASK_SUBMITTED` / `RETEST_READY` → 工作台任务面板；`NEW_TASK` / `TASK_REJECTED` → 任务详情 `/opc/tasks/{taskId}`。等待点深链消费 `waitId`（工作台「待处理」模式与对话模式嵌流底共用一个「当前等待点」定位参数）。

### 需与后端确认 / 可能发 issue 的缺口

- **终止运行（顶栏「终止」+ 审批卡逃生口）**：swagger 未见独立「终止 run」端点；当前语义只有「权限拒绝累计达上限平台终止」。前端按「有终止端点则接、无则发 issue 给 aiplatform-server 要求补」处理——逃生口 UI 先就位，动作空转或降级为权限拒绝。**执行票内发 issue 确认并跟进。**
- 引擎列表 `GET /api/agent-engines` 与类型模板枚举（1/2）作为运营位，v1 只做下拉呈现，后续运营化进雾。

### 文案口径（spec 0002 §5 红线）

- 需求端六步（聊需求 / 看原型 / 制作中 / 质检中 / 验收 / 交付）+ 三扇门（确认需求清单 / 确认原型 / 验收）+ 禁词（阶段 / 状态机 / 智能体 / 期 / OPC / HITL / Demo）；称谓：顾问（需求梳理）/ 团队（开发）/ 质检团队（测试）。OPC 侧技术词直用，不走禁词。

## Testing Decisions

**好测试的标准**：只测外部行为（给定输入 → 期望输出 / 状态迁移），不测实现细节；浏览器 API（EventSource / fetch / window）在各测试内 stub，不引 DOM 环境；组件用 `renderToStaticMarkup` 做 SSR 断言（既有模式）。

**沿用既有 seam（最高点，不新增架构层）**：
- `agent-streams` store（直播渲染与三模式的数据读口）——已有 store 驱逐测试；新增分段全量渲染不引新 seam，只读现有 store。
- SSE 桥（`bridge.ts`，HITL `wait-raised/wait-settled` → invalidate + store 分发）——已有失效桥测试；HITL 接线复用，无需新测 seam。
- `src/lib/*` 纯映射层（`demand-pool.ts` / `task.ts` / `todo.ts` / `usage.ts` 的既有模式）——新增 HITL settle 三型载荷构造、对话建项目载荷、下任务/角色载荷、exec 结果解析、Bug close/dispatch 载荷，各配纯逻辑单测。

**新增薄映射模块（跟随既有 `src/lib/*` 形态，node 环境纯逻辑单测）**：
- 对话建项目：`CreateProjectCommand` 构造（一句话 / 引擎 / 类型模板 → 载荷）+ 响应 `runId` 消费。
- HITL settle：三型载荷判别与构造（answer 二维 / permission / deferred task）；空 custom 归一、approve 布尔、deferred 必填 assignee 校验。
- 下任务：`ProjectAgentTaskCommand` 构造（prompt + 角色缺省语义）。
- 终端：exec 命令载荷 + `stdout/stderr/exitCode` 结果归化。
- Bug 收尾：close / dispatch-fixes mutation 并入 tasks 域（已有 task.test 先例）。

**组件断言（renderToStaticMarkup）**：门卡 locked 态、问答卡多题渲染、审批卡「终止任务」逃生口存在、直播分段（tool/patch/knowledge/error）各有其渲染块、待办深链 href。不测交互时序（留 e2e 剧本）。

**回归护栏**：`pnpm test` 全绿 + `pnpm typecheck` + `pnpm lint`；正式版搬移原型时以「正式版 ≥ 原型」为验收口径（无头浏览器走八幕剧本，配方见 wayfinder #25 Notes）。

## Out of Scope

- 管理后台 admin（v1 不建）。
- 移动端适配。
- 角色权限与可见性（v1 单账号不分角色；菜单已场景化留缝）。
- 付款 / 订单计费 UI。
- Phase B 托管上线（域名 / 生产部署 / 完整系统预览）。
- CC 独有超集——文件树 / 在线编辑 / diff 视图 / 产物版本化回退 / 运行回放 / Skill 市场（demo 与原型均不含，v1 业务过程不依赖；八幕走完若平台感不足，毕业成票再议）。
- 多期流程 UI（真有二期才显示期）。
- e2e 自动化深度（验收剧本之外的 Playwright / CI 化）。

## Further Notes

- **执行纪律**：本 spec 是「把已就位的决策与原型变成产品」的收口票，不重开 T1–T7 任何决议；落差只在「后端端点与 spec 假设不符」时才短 grill 回填。
- **协作**：后端端点已在 swagger，直接接；终止 run 等缺口发 issue 给 aiplatform-server（先例 #31 / #32）并在票内记录链接跟进。
- **验收回路**：无头浏览器配方见 agent memory（假 cookie 过 proxy + playwright chromium）；真实数据走查需后端起服务 + 真账号。dev 环境：前端 3333 · 后端 8888 · app-registry 8088 · identity 10001。
- **原型资产落点**：生产构建整体渲染 null（NODE_ENV gate）——正式版落地后原型页仍保留供回溯，不删。
