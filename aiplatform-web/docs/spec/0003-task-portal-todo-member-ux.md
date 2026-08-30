# Spec 0003 · 任务平台门户 + 待办中心 + 成员页 UX

> 产地：wayfinder T6（[#8](https://github.com/ZhangColin/aiplatform-web/issues/8)，grilling 票）。
> 真实实现等后端 A2（账号 + 待办六型骨架）/ A4（任务系统）对接 issue；对接时照本 spec 落地。
> 本票无原型：三块均为常规形态（列表 / 详情 / 表单 / 表格），壳与组件语言已被 spec 0001/0002 原型锁定；若实现时观感不对再补原型票。

## 1. 定稿结论：菜单场景化（修订 spec 0002 §2）

**菜单框架归通用层，菜单内容归场景层**——「菜单不因门户而异」的旧口径作废（T6 修订）。

- **框架（通用层，全平台一套）**：品牌位门户切换器 + 分组结构（项目组 / 平台组）+ footer（用户 + 主题切换）。
- **内容（场景层，每门户一份配置）**：门户菜单 = 场景配置。将来按登录角色过滤菜单、或拆独立前端项目，这套配置即拆分缝（角色过滤 = 配置加谓词，拆项目 = 配置随仓库走）。
- **切换器**：维持品牌位 dropdown（sidebar icon 收起态可用，不挤占导航空间），四选项 = 需求端 / 开发平台 / 任务平台 / 后台（简易后台 `/admin`，2026-08-25 增）；语义 = 选中即换场景（**菜单 + 落地页一起换**）。

### 场景菜单分配

| 菜单项 | 需求端 | 开发平台 | 任务平台（OPC）|
|---|---|---|---|
| 项目组（项目项 + 阶段徽章）| ✅ | ✅ | ❌（OPC 不见项目，只见任务）|
| 项目列表 / 新建项目 | ✅ | ✅ | ❌ |
| 我的任务 | ❌ | ❌ | ✅（落地页）|
| 待办中心 (N) | ❌（「等你」嵌场景内容，spec 0002 §7 不变）| ✅（4 型）| ✅（2 型）|
| 成员 | ❌ | ✅ | ❌ |

> **需求方流程修订（2026-08-25 grilling）**：需求端菜单收为两项——「首页」（`/`，新建 hero 落地页）+「我的项目」（项目列表页）。上表需求端列读法更新：项目组 ❌（不直列项目）；「项目列表」✅（作为「我的项目」）、「新建项目」❌（唯一入口 = 首页 hero）；待办中心、成员维持 ❌。开发平台 / 任务平台两列不变。

### 落地页分配

- 需求端 → 新建项目 hero（= 菜单「首页」项，spec 0002 §3）；后台 → `/admin` 引擎配置单页（2026-08-25 增，CONTEXT.md「简易后台」）
- 开发平台 → 项目列表页
- 任务平台 → 我的任务

## 2. OPC 任务门户

### 2.1 我的任务列表（门户落地页）

- 卡片网格（全平台列表视觉语言，同 spec 0002 项目列表口径）。
- 卡片 = 任务标题 + 所属项目名（最小上下文）+ 状态徽章 + 创建时间。**A4 勘定（#22）**：`TaskCardResponse` 无类型（测试/复测）判别字段、无 Bug 计数、无 updatedAt——卡片收缩为上述字段；首轮/复测判别发生在详情页提交表单（bugs 非空 → 复测形状，§2.3）。
- 状态徽章口径：新任务 amber / 执行中 / **被驳回 destructive（需返工）** / 已提交 / 已确认。
- 空态：「暂无任务」。
- 文案口径：OPC 是专业测试人员，技术词汇直用（Bug / 复现步骤 / 严重级），**不走**需求端禁词约束。

### 2.2 任务详情（非工作台单页，内部双栏）

spec 0001 §2 标准页（页头 = Trigger + 任务标题 + 状态徽章），内容区双栏：

- **左栏**：任务信息（最小项目上下文 = 项目名 / 预览地址 + 任务描述；**A4 勘定（#22）**：`ProjectBrief` 只有 name + previewUrl，需求摘要砍；附件字段未出，随 §2.5 截图降级一并不呈现）+ Bug 工作区（§2.3）。
- **右栏**：预览 iframe——复用通用预览组件，sandbox / 手动刷新 / 独立打开口径 = spec 0001 §5 不变。测试场景刚需：边操作预览边录 Bug。
- `<md` 退化：上下两段，预览收起为独立打开外链。

### 2.3 Bug 工作区（按任务类型两种形态）

- **首轮测试任务**：多条 Bug 卡片式录入（动态增删），每条 = 标题 / 描述 / 复现步骤 / 严重级 / 截图附件；底部测试结果说明。
- **复测任务**：Bug 清单（已修复态）逐条勾「复测通过 / 不通过」，不通过必填说明；底部结果说明。**A4 勘定（#22）**：复测清单 = 详情 `bugs[]` 全量（后端权威集合，issue 前端约定「复测表单的 bugId 来源 = `GET /api/tasks/{taskId}` 的 `bugs[]`」），前端不再按已修复态二次过滤，行内亮 Bug 状态徽章兜底呈现。
- **动作**：「开始测试」显式按钮（已发布 → 执行中留痕）；**提交 = 一次性整任务提交**（Bug 清单 + 结果说明）。

### 2.4 驳回返工

- 详情页顶部 destructive alert 卡呈现驳回说明（原样转给 OPC）。
- Bug 清单原样保留、可编辑，改后重新提交（状态已回执行中）。
- 列表红徽章 + 待办中心「被驳回」卡双入口。

### 2.5 截图上传（前后端协作项）

- 表单**按有上传通道设计**（截图位就位）。
- 后端 A4 现口径：v1 文字复现、attachments 字段留缝，上传通道在 fog——本票决议**发 issue 推 aiplatform-server 把上传通道提为 v1**（§6）。
- 后端若不接：截图位降级隐藏，表单其余不动。
- **A4 到达（#22）**：`BugPayload` 无 attachments 字段 → **降级生效**——Bug 卡 = 标题 / 描述 / 复现步骤 / 严重级（1=致命 2=严重 3=一般 4=轻微），无截图位。

### 2.6 可见性

- 任务查询按 assignee 归属过滤（后端 A4 口径）；OPC 场景不出项目组 / 项目列表 / 新建项目（§1 菜单分配）。

### 2.7 对接落地（issue #22，后端 aiplatform-server #26）

- **路由**：`/opc` = 我的任务列表（替换 #21 骨架）；`/opc/tasks/{taskId}` = 任务详情；成员页 `/dev/members`（§4）。
- **端点面**：`GET /api/tasks`（assignee=me 卡片，新→旧）/ `GET /api/tasks/{taskId}`（task + ProjectBrief + bugs[]；非指派且非 owner → 403 `TASK_004`）/ `POST /api/tasks/{taskId}/start`（仅指派本人）/ `submit` / `confirm|reject|cancel`（dev/owner 守卫 `TASK_009`）/ `GET+POST /api/projects/{id}/tasks`（建任务指派不存在 404 `TASK_008`）/ `GET /api/projects/{id}/bugs` / `GET /api/accounts`。
- **提交载荷两形状**（同给 / 同缺 / 缺报告 → 400 `TASK_006`）：首轮 `{ report, bugs: BugPayload[] }`（bugs 可空数组 = 测试全过）；复测 `{ report, results: { bugId, pass, note }[] }`（bugId 非本项目 Bug → 404 `TASK_005`）。表单形状判别 = 详情 `bugs[]` 非空。
- **ID 类型转换约定**：响应侧 `bugId` / `accountId` 为 string，提交侧（RetestResultPayload / CreateTaskCommand）为 int64——`Number()` 转换收口在提交构造处。
- **驳回重交**：驳回 → 状态回执行中；首轮被驳回时 Bug 尚未入库（confirm 才入库），重交仍为首轮形状；表单预填来源 = `task.submittedPayload`（Map 形态对象，断言取 `bugs`）。
- **枚举口径**：任务 status 1=已发布 2=执行中 3=已提交 4=已确认 5=已取消（已提交只能驳回不能取消，409 `TASK_002`）；severity 1=致命 2=严重 3=一般 4=轻微；Bug status 1=待修复 2=已修复 3=复测通过（服务端有 statusName）。
- **dev 面板落点**：项目详情页新增任务面板（建任务表单 title / content / assigneeAccountId + 项目任务全量列表，已提交任务呈现 submittedPayload 明细并给 confirm / reject 裁决，reject reason 必填）+ Bug 面板（三态行；fixRunId / fixNote 内容随 aiplatform-server #27，字段占位）。**A4 勘定（#22）**：落点形态 = 主面板 tabs（spec 0001 §2 既定模式）——文档 / 任务 / Bug 三 tab，tab 条收编右栏开关；项目详情页双端共享（v1 单账号不分角色）。
- **SSE**：`task-updated`（通知通道，payload `{ projectId, taskId, status }`）失效映射 #21 已预挂（tasks / projects / todos 三域）。

## 3. 待办中心

- **同一页面组件，场景配置过滤类型**：开发平台 4 型（智能体等答复 / 任务待确认 / 可发复测 / 门待拍板）+ OPC 2 型（新任务 / 被驳回）。双端统一呈现（CC「双表分端点、UI 统一呈现」）；OPC 两型本质 = 任务列表的「需我注意」过滤视图。
- **v1 不分组不过滤**：时间倒序单列表 + 类型徽章；卡 = 类型 + 来源（项目名 / 任务号）+ 时间 + 摘要。
- **点击去向**：智能体等答复 → 该项目工作台对话模式；任务待确认 / 可发复测 → 该项目工作台（右栏测试任务卡直达与否到功能点再定）；门待拍板 → 该项目工作台；新任务 / 被驳回 → 任务详情。
- 菜单徽章 (N) = 场景类型计数和。
- 待办 = **计算式投影**（后端 A2 口径，不落库）；数据经 SSE → invalidate（ADR 0002/0003），无新增。

### 3.1 对接落地（issue #21，后端 aiplatform-server #25）

- **端点**：`GET /api/todos?view=dev|opc`（缺省 `dev`，不分页，新者在前）；信封 `data: TodoItem[]`（`type`/`projectId`/`refId`/`title`/`createdAt`）。dev v1 两型：`AGENT_WAIT`（refId=waitId）/ `GATE_PENDING`（refId=projectId）；`view=opc` v1 恒空（任务三型随 A4）；非法 view → 400 统一信封；无会话 401 统一信封（同 `/api/me`）。
- **SSE 失效口径（对 issue 措辞的勘定）**：`wait-raised` / `wait-settled` 在 **agent 流通道**（正本《SSE事件清单》），`stage-changed` / `task-updated` 在通知通道——todos 域失效两边都挂（bridge 通知注册表 + agent 侧 `AGENT_EVENT_INVALIDATIONS`）。agent 通道按 ADR 0003 工作台建连；因失效源跨双通道，待办轮询兜底 = **双通道任一未连即 15s**（非列表类通用的只看通知通道）——骨架期 agent 通道未挂载即等价常开，验收④的 wait-raised 活体验证随工作台建连票解锁。refId（AGENT_WAIT=waitId）的消费同随工作台深链落地。
- **落地路由**：`/dev`（开发平台落地 = 项目列表，#20 组件场景化取用）+ `/dev/todos`（待办中心）+ `/opc`（任务平台落地 = 我的任务骨架，v1 消费 `view=opc` 恒空，任务卡片网格随 A4 替换）；切换器三选项 href 化。壳组件：`components/dev-portal/`、`components/opc-portal/`（对齐 `user-portal` 的场景目录拆分缝）。菜单「成员」项与 `/api/accounts`（§4）随 A2/A4 后端就绪到达后挂入——本票 issue 端点面未含，书面豁免。
- **v1 点击去向（骨架壳）**：两型均跳 `/projects/{projectId}` 详情页（门按钮 #19 已在）；AGENT_WAIT 的工作区等待点深链（waitId/refId 消费）到工作台功能点再定（同 §7 口径）。
- **A4 类型落定（#22）**：dev 四型 = `AGENT_WAIT`（refId=waitId）/ `GATE_PENDING`（refId=projectId）/ `TASK_SUBMITTED`（refId=taskId，存在已提交任务）/ `RETEST_READY`（refId=projectId，存在 FIXED Bug ∧ 无进行中任务 ∧ 无 in-flight 修复）；opc 两型 = `NEW_TASK`（refId=taskId，指派给我 ∧ 已发布）/ `TASK_REJECTED`（refId=taskId，被驳回待重交——重新提交即离开）。新两型点击去向沿 v1 口径跳 `/projects/{projectId}`；opc 两型 → 任务详情 `/opc/tasks/{taskId}`；opc 菜单补挂「待办中心」项（§1 分配兑现）。

## 4. 成员页

- v1 极简**只读**表格（shadcn Table）：头像 + 显示名 + 外部 ID + 创建时间；零操作。
- 数据 = `GET /api/accounts`（后端 A2/A4）。**A4 到达（#22）**：随任务平台对接票顺手挂入（`AccountResponse` = accountId: string + displayName，建档顺序）；同一端点兼任 dev 建任务的指派下拉源（§2.7）。**A4 勘定（#22）**：创建时间字段未出 → 列清单收缩为头像 + 显示名 + 外部 ID（mono），后端补字段后回填。
- 角色列与维护动作（将来 staff/opc 经成员页维护）在此页生长留缝。
- 场景菜单中仅开发平台入口（页面本身仍通用壳标准页）。

## 5. 数据与状态口径

ADR 0002 / 0003 指向；A4 `task-updated` SSE 触发任务列表 / 详情 / 待办 invalidate。**A4 勘定（#22）**：`preview-ready` 失效映射连带 tasks 域——OPC 任务卡片 / 详情的 previewUrl 只能经任务端点重拉点亮，这是唯一新增。

- **todos 域（#21 落位）**：key 工厂 `todos.list(view)`（view 进 key）；`useTodoList` 挂 15s 门控轮询（通知通道）。`dispatchAgentEvent` 随之加 queryClient 参数（agent 桥失效的载体，工作台挂连接时传入）。

## 6. 前后端协作动作

- **已发 issue 给 aiplatform-server**：[任务附件/截图上传通道：建议从 fog 提为 v1](https://github.com/ZhangColin/aiplatform-server/issues/31)。理由：Bug 无截图对复测效率影响大；attachments 字段已留缝，通道是主要缺口。后端不接则截图位降级隐藏（§2.5）。

## 7. 到功能点再定（不在本 spec 锁死）

- 待办卡 → 工作台右栏测试任务卡的深链形态。
- T4/T5 原型分支的菜单统一（按 §1 场景化改造；执行杂务，随首个对接 issue 顺手做）。
