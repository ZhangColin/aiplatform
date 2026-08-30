# 统一底座调研：workspace 组织与引擎栈收敛

> 调研票：[#12](https://github.com/ZhangColin/aiplatform/issues/12)，上游决议 [#3](https://github.com/ZhangColin/aiplatform/issues/3)（Resolution 评论：单容器 all-in-one、TS 全栈、数据落 workspace 可重建、平台约定 system prompt 注入且须可渐进演进）。产出供「后端裁剪与重组方案」消费。
>
> 信息源（一手优先）：
> - 本仓库代码（`aiplatform-server` @ d808fb0）——现状事实输入，全部带 file:line
> - `agentscope-harness` 2.0.1 jar 本体（`~/.m2/repository/io/agentscope/agentscope-harness/2.0.1/`，javap/unzip 反查）——能力面一手证据
> - AgentScope 官方文档站（java.agentscope.io / doc.agentscope.io）与官方 GitHub（agentscope-ai/agentscope-java、agentscope-ai/agentscope，含 releases API 实测）
> - opencode 官方文档站（opencode.ai/docs）与官方 GitHub（anomalyco/opencode——已从 sst 转入 Anomaly 公司名下，旧地址重定向；含源码与 gh API 实测）
>
> 调研日期：2026-08-30。约束（票面原话）：不受现有实现过度设计的影响，按现在的业务稳步推进；现状代码仅作事实输入。完整信源 URL 清单见 §六。

## TL;DR

- **目录组织**：workspace 根保持容器内 `/workspace` 不动（三引擎已同锚此根）；在此根下补三层约定——`docs/`（PRD 等业务产物，已有）、应用代码占根（交付物）、`.platform/`（平台产物：引擎会话/日志/skills/rules，抄 AgentScope harness 的 `.agentscope/` 布局）与 `data/`（pg 等中间件数据目录，#3 决议「数据目录映射进 workspace」的落点）。**单容器 all-in-one 后，唯一持久化物 = 挂在 `/workspace` 的那一个卷**，容器随时 `docker rm` 重建不丢数据；现状的最大缺口恰恰是**两处状态在卷外**（pg 数据在独立卷 `vol-pg-*`、opencode 会话在容器层 `/root/.local/share/opencode`）。
- **三引擎统一绑定**：平台已有事实锚点 `WorkspaceHandle`（workspaceId 派生的确定性命名）与「容器内根恒 `/workspace`」的单一事实（`ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT`）。统一方案 = 把散在三处的「路径/会话/凭据」约定收成一张**工作区绑定契约**：根路径、`.env` 注入、平台产物目录、会话命名（BA=`ba-{projectId}` 已有；编码 run 会话落 `agt_agent_sessions` 已有，但引擎侧会话体在容器层，需随目录组织一并收进 `.platform/`）。
- **引擎栈收敛**：建议**目标态收敛 AgentScope 单栈，路径是「端口内并行、验收后切默认」而非推倒重来**。决定性事实：`agentscope-harness` 2.0.1 本体就是完整编码智能体 harness（`ShellExecuteTool`、docker sandbox、subagents、Plan Mode、skill 沉淀与晋升门、会话压缩、HITL），且现有 `DockerExecFilesystem` 只差一个 `execute()` 方法就满足其 `AbstractSandboxFilesystem` 契约——编码引擎换内核是**加一个 `CodingAgentAdapter` 实现**（注册表天然支持多引擎），不是重写。opencode 保留为过渡期默认引擎，用真实 DEMO run 对照验收后降级移除。
- **平台约定层演进**：从「每消息 system 字段注入」演进为「置备时写 `/workspace/AGENTS.md`」——opencode 与 AgentScope harness **都原生读取工作区 AGENTS.md**（前者是官方 rules 机制，后者是 `WorkspaceContextMiddleware`），平台技术约定变成 workspace 里的版本化文件，换引擎不动、渐进演进不推倒重来。角色卡（BA/DEV/…）继续走 systemPrompt 入参，两者正交。

---

## 一、事实清单（现状，仅事实不作结论）

### 1.1 docker 置备（base.workspace）

| # | 事实 | 出处 |
|---|---|---|
| W1 | 一个工作区 = **4 个 docker 资源**：dev 容器 + 专属 network + 独立 pg 容器 + 独立 redis 容器 | `base/workspace/infrastructure/docker/DockerEnvironmentBackend.java:40-44`（类注释）、`provisionResources` L170-213 |
| W2 | dev 容器镜像 `aiplatform/dev:0.4`：node:22-bookworm + 全局 npm 装 `opencode-ai`、`@ai-sdk/openai-compatible`、`@deepseek-ai/dsh` + 内置静态预览服务器 `/opt/serve.js` | `DockerEnvironmentBackend.java:51`、`src/main/resources/docker/workspace/dev-image.Dockerfile:1-22` |
| W3 | dev 容器以 `sleep infinity` 常开；**命名卷 `vol-ws-{id}-dev` 挂 `/workspace` 且 `-w /workspace`**；宿主随机映射两个端口（engine 4096 / preview 8081） | `DockerEnvironmentBackend.java:228-233`（`startDevContainer`）、端口常量 `domain/port/EnvironmentBackend.java:20-27` |
| W4 | pg/redis 是**独立容器**接入同一 network；pg 数据在**另一个命名卷** `vol-pg-{id}`（`/var/lib/postgresql/data`）——**不在 workspace 卷内**；redis 无卷 | `DockerEnvironmentBackend.java:184-194` |
| W5 | 中间件连接串写 `/workspace/.env`（`DATABASE_URL`/`REDIS_URL`，docker exec printf）——**agent 从工作区文件读环境**，这是「平台环境信息落 workspace」的既有先例 | `DockerEnvironmentBackend.java:198-201`、`writeEnvFile` L216-219 |
| W6 | 预览 = 容器内 `node /opt/serve.js /workspace <port>` 静态文件服务器（示意级，非真实应用服务；#3 决议要改造） | `DockerEnvironmentBackend.java:150-162`、`serve.js` 全文 |
| W7 | `packSource` = 容器内 `tar czf - --exclude=./.env --exclude=./node_modules -C /workspace .`——**源码包语义 = /workspace 减机密减可重建物** | `DockerEnvironmentBackend.java:118-147` |
| W8 | 确定性命名：容器 `ws-{id}-dev`、network `net-{id}`，是 workspaceId 纯函数——销毁级联与幂等重建的根基 | `domain/model/WorkspaceNaming.java:17-24` |
| W9 | 置备异步化：后台线程池、失败自动重试（默认 3 次）、重启对遗留 PROVISIONING 续置备（幂等预清同名残留） | `application/WorkspaceProvisionAppService.java:120-159, 174-213` |
| W10 | 端口语义：`EnvironmentBackend` 四能力面 createWorkspace/destroyWorkspace/exec/exposePort + packSource；**后端可替换（本地 Docker → 上云 TKE），接口不动** | `domain/port/EnvironmentBackend.java:16-31` |

### 1.2 AgentScope（BA 会话，base.chatagent）

| # | 事实 | 出处 |
|---|---|---|
| A1 | 内核 = `io.agentscope:agentscope-harness:2.0.1`（AgentScope Java），**跑在平台 JVM 进程内**（非容器、非独立服务） | `aiplatform-server/pom.xml:24,144-152`；`AgentscopeChatAgentClient.java:41`（「平台进程内 HarnessAgent」） |
| A2 | 文件访问：`HarnessAgent.Builder.abstractFilesystem(new DockerExecFilesystem(containerName))` 逃生舱——**全部文件工具（ls/read/write/edit/grep/glob/delete/move）经 `docker exec` 落到 dev 容器 `/workspace`**，与编码引擎同视图同物理落点 | `AgentscopeHarnessAgentFactory.java:102-108`；`DockerExecFilesystem.java:34-49`（类注释）、`DockerExecCommand` L439-484 |
| A3 | ProjectDev 形态关闭 subagents / memory hooks / memory tools——理由：**源码包是交付物，记忆文件不进包**；AGENTS.md 与 workspace tools.json 读取照常 | `AgentscopeHarnessAgentFactory.java:22-26, 106-108` |
| A4 | 会话状态：`PostgresAgentStateStore` 落**平台主库** `cat_agent_state` 表，按 `(userId, sessionId)` 槽位——平台重启后同一会话恢复续跑；替换框架缺省的本地 JSON 实现（`~/.agentscope/state/`） | `PostgresAgentStateStore.java:21-31`、`AgentscopeHarnessAgentFactory.java:28-31` |
| A5 | BA 会话寻址：`sessionId = ba-{projectId}`（无表派生）、`userId = 项目 owner`——谁触发都续同一条会话 | `business/project/application/BaInterviewAppService.java:41-44, 59-60, 112`（`SESSION_PREFIX`） |
| A6 | 平台自有工具经 `Toolkit.registerAgentTool` 注册：`ask_user`（所有形态）+ `savePrd`（仅 ProjectDev）——工具入参 schema/权限自检在工具类自持 | `AgentscopeHarnessAgentFactory.java:119-128`、`SavePrdTool.java:29-68` |
| A7 | `savePrd` 效果 = docker exec 覆盖写 `/workspace/docs/PRD.md` + 回调置「PRD 已产出」状态位 + 发 `document-updated`；**PRD 路径正本 = `ProjectMainChain.PRD_ARTIFACT`** | `SavePrdTool.java:16-27, 83-97`；`business/project/infrastructure/PrdArtifactAdapter.java:44-47` |
| A8 | HITL 桥：AgentScope `RequireUserConfirmEvent` → 平台 `wait-raised` 帧（软终点，不发 task-finish）；settle 经 `ConfirmResult` 续跑（同 `(userId, sessionId)` 恢复上下文） | `AgentscopeChatAgentClient.java:51-56, 304-310` |
| A9 | 计量：`ModelCallEndEvent` 五桶累加，run 结束报一条 `UsageEvent`（engine=agentscope） | `AgentscopeChatAgentClient.java:46-49, 301-303` |
| A10 | agent 复用键 = `name + model + sysPrompt + workspace.identity()`（进程内缓存，AutoCloseable 统一释放） | `AgentscopeHarnessAgentFactory.java:65-72` |

### 1.3 opencode（编码引擎，base.agentengine）

| # | 事实 | 出处 |
|---|---|---|
| O1 | 接线：平台后端 --HTTP--> dev 容器内 `opencode serve`（容器 4096 → 宿主随机 hostPort，Basic Auth=随机密码）；serve 由 `OpenCodeServeBootstrap` 探活拉起 | `infrastructure/opencode/OpenCodeAdapter.java:49-50`；`OpenCodeServeBootstrap.java:30-43` |
| O2 | serve 拉起三步：写 provider 配置到容器内 `/root/.config/opencode/opencode.json`（classpath 模板，API key 经 `{env:DEEPSEEK_API_KEY}`）→ 随机密码落 `/root/.opencode/serve-password` → `docker exec -d` 起 serve（日志 `/tmp/opencode-serve.log`） | `OpenCodeServeBootstrap.java:53-55, 149-170` |
| O3 | 文件访问：**引擎自身进程在容器内、工作目录即 `/workspace`**——建会话 `POST /session?directory=/workspace`；平台不代理文件操作 | `OpenCodeAdapter.java:57`（API 注释）、`createSession` L356-366 |
| O4 | 会话：opencode session id 由引擎生成，平台登记进 `agt_agent_sessions`（跨重启可查）；**会话体存引擎自身存储 = 单机 SQLite `~/.local/share/opencode/opencode.db`（WAL），容器内即容器层、不在 /workspace 卷——容器重建即丢**；跨容器共享无集群语义（搬库或 export/import）。数据目录按 XDG 派生，`XDG_DATA_HOME` / `OPENCODE_DB` 可重定向（§2.2 治法） | `application/AgentSessionAppService.java:19-21, 63-77`；`OpenCodeServeBootstrap.java`（无任何 XDG 重定向）；opencode 源码 `packages/core/src/global.ts` + `database.ts`（xdg-basedir + `opencode.db`，官方文档 https://opencode.ai/docs/cli/ `opencode db path`） |
| O5 | run 语义：`POST /session/{id}/message`（parts + model + system），**同步返回、parts 在 run 结束整批透传**——逐 part 流式需订 /event SSE，已知限制未做 | `OpenCodeAdapter.java:52-55, 194-218` |
| O6 | system prompt = 角色卡经 message body 的 `system` 字段**每消息注入**（适配层零角色概念）；模型经 `{providerID, modelID}` 显式指定 | `OpenCodeAdapter.java:192-204` |
| O7 | 等待点：问答走全局 `GET /question`（que_ 机制按 sessionID 过滤）+ 权限走 `GET /permission`，`OpenCodeWaitWatcher` 轮询盯守 → `wait-raised` | `OpenCodeAdapter.java:58-70, 277-330` |
| O8 | 终止：`POST /session/{id}/abort`；用量：step-finish 增量累加，run 级恰一条 UsageEvent（engine=opencode） | `OpenCodeAdapter.java:64, 72-75, 332-347` |
| O9 | 第二编码引擎 `dsh`（DeepSeek Harness，ADR-0004）：容器内 `dsh --profile headless "<task>"` 一次性任务模式，无监听端口无交互面 | `dev-image.Dockerfile:12-18`；`infrastructure/dsh/DshAdapter.java:57` |
| O10 | 引擎注册表显式注册（不靠 bean 名），缺省引擎 = opencode；**换/加引擎 = 实现 `CodingAgentAdapter` 端口 + 落 bean，注册表与前端选项自动出现** | `application/AgentEngineRegistry.java:32-45` |

### 1.4 dispatch 链路与平台约定注入（business.project）

| # | 事实 | 出处 |
|---|---|---|
| D1 | 角色卡 = 枚举 `RolePreset`（BA/DEV/DELIVERY/ARCH/TEST/DEMO），systemPrompt/modelId 代码配置不落库；「角色多了要运营管理再 preset 落库，接口不变」 | `business/project/domain/model/RolePreset.java:11-18` |
| D2 | BA 路由：dispatchTask 解析出 BA 角色即改走对话轨道（`BaInterviewAppService` 续 BA 会话），引擎零交互；其余角色走编码引擎 | `business/project/application/ProjectAgentTaskAppService.java:106-129` |
| D3 | G1 通过自动 Demo：`ProjectGateAppService.approve`（G1 谓词 = 「PRD 已产出」状态位）→ 自动 dispatch DEMO 角色 run（`DEMO_KICKOFF_PROMPT`：读 `/workspace/docs/PRD.md` 产原型） | `ProjectGateAppService.java:100-157`（自动 Demo L146-153）；`RolePreset.DEMO_KICKOFF_PROMPT` |
| D4 | 修正 run 复用会话：`dispatchDemoCorrectionRun` 续项目工作区**最近一次本引擎会话**（`latestEngineSessionOf`），无会话则新起兜底 | `ProjectAgentTaskAppService.java:169-182, 197-210` |
| D5 | **平台技术约定目前没有任何独立载体**：DEV 角色卡只有一句「先读 /workspace/docs/PRD.md 按需求开发」；「TS 全栈」约定尚未存在于代码（#3 决议后才有的要求，现状无此事实） | `RolePreset.DEV` systemPrompt |
| D6 | 知识分块：`knw_chunks` 表（平台主库 pgvector）+ 本机 fastembed 服务，`injectForRun` 全局跨项目相似命中前置注入 prompt——**知识是平台级资产，不在 workspace** | `application.yml`（app.embedding/app.knowledge）；`base/knowledge/infrastructure/persistence/PgvectorChunkStore.java`；V4__base_knowledge.sql:9 |
| D7 | PRD 单一事实源口径（ADR-0002）：PRD 是工作区的一部分，编码智能体自主可读，零平台注入搬运；读端点直读工作区不建表 | `docs/adr/0002-agentscope-conversational-agent.md`（「PRD = 工作区事实源」节） |
| D8 | 双轨分野（ADR-0002，2026-08-25）：对话智能体（AgentScope，平台级）vs 编码引擎（opencode/dsh，项目级）；`Project.engine` 只指编码引擎 | `docs/adr/0002-agentscope-conversational-agent.md`（「双轨分野」节） |

### 1.5 agentscope-harness 2.0.1 能力面（jar 本体反查，一手证据）

| # | 事实 | 出处 |
|---|---|---|
| H1 | jar 内含完整编码智能体部件：`tool/ShellExecuteTool`、`tool/FilesystemTool`、`tool/TaskTool`、`tool/AgentSpawnTool`、`tool/PlanModeTools`、`tool/ProposeSkillTool`/`SkillManageTool`、memory 三工具 | jar class 清单 `io/agentscope/harness/agent/tool/*` |
| H2 | **shell 的契约缝**：`ShellExecuteTool` 构造器收 `AbstractSandboxFilesystem`；该接口 = `AbstractFilesystem` + `id()` + `execute(ctx, command, timeout)`——**现有 `DockerExecFilesystem` 补一个 `execute()` 即满足** | javap `ShellExecuteTool`、`AbstractSandboxFilesystem` |
| H3 | Builder 开关证明部件默认在：`disableShellTool`/`disableFilesystemTools`/`disableSubagents`/`disableMemoryTools`/`disableSessionPersistence`/`disableWorkspaceContext`/`disableToolsConfig`…（平台 BA 只用了 disable 后三个 + subagents/memory） | javap `HarnessAgent$Builder` 字段清单 |
| H4 | sandbox 体系：`Sandbox` 接口（start/stop/exec/persistWorkspace/hydrateWorkspace）+ **官方 docker 实现 `sandbox/impl/docker/DockerSandbox`** + workspace layout 声明（BindMount/GitRepo/LocalDir/File entries）+ 快照（Local/Remote/Noop） | jar class 清单 `agent/sandbox/**` |
| H5 | skill 体系：`WorkspaceSkillRepository`（工作区技能目录）+ **SkillCurator 自动沉淀**（候选→安全扫描→晋升门 AllowList/Canary/LocalApproval→audit log）+ SkillUsageStore | jar class 清单 `agent/skill/**` |
| H6 | **harness 自带工作区布局约定**（`WorkspaceConstants` 常量值）：`AGENTS.md`、`MEMORY.md`、`tools.json` + 目录 `memory/`、`skills/`、`knowledge/`（KNOWLEDGE.md）、`rules/`、`agents/`、`sessions/`（sessions.json + *.jsonl + *.log.jsonl）、`tasks/`；缺省根 `.agentscope/workspace` | javap -v `WorkspaceConstants` 常量池 |
| H7 | 中间件管线：PlanMode / Compaction（会话压缩）/ Subagents / DynamicSubagents / Skill / WorkspaceContext（读 AGENTS.md）/ SandboxLifecycle / MemoryFlush / Inbox | jar class 清单 `agent/middleware/*` |
| H8 | 多智能体与网关：`DefaultAgentManager` + subagent 声明/工厂/远程协议 + `HarnessGateway`（channel 路由：ChatUI channel 等）+ MessageBus + 异步工具注册表 | jar class 清单 `agent/subagent/**`、`agent/gateway/**`、`agent/bus/**` |
| H9 | 会话面：`sessions/` 目录 + `SessionTree`/`SessionFreshnessEvaluator` + `SessionSearchTool`——harness 有工作区本地会话树与检索（平台 BA 现走 `stateStoreOverride` = PG，未用本地会话目录） | jar class 清单 `agent/memory/session/*`、H6 |

（AgentScope / opencode 官方文档面的事实见 §四引用。）

---

## 二、子问题 ①：workspace 目录组织

### 2.1 现状盘点：什么在 workspace、什么不在

以「容器重建（docker rm + 同卷重建）后什么存活」为判据盘点：

| 内容 | 现在落在哪 | 重建后 | 评价 |
|---|---|---|---|
| PRD | `/workspace/docs/PRD.md`（workspace 卷） | **存活** | 已符合 #3 决议 |
| 系统代码 | `/workspace` 根（workspace 卷） | **存活** | 已符合 |
| 平台注入的连接串 | `/workspace/.env`（卷内，但每次置备重写） | 重写即恢复 | 可重建，非数据 |
| **pg 数据** | **独立卷 `vol-pg-{id}`**（`/var/lib/postgresql/data`，W4） | 存活但**在 workspace 外**——「取走工作区」拿不到它 | **违背 #3「数据目录映射进 workspace」** |
| **opencode 会话体** | **容器层** `/root/.local/share/opencode`（O4） | **丢失**——修正 run 的会话续跑（D4）失效 | **违背「容器无状态重建不丢数据」** |
| opencode 配置/密码 | 容器层 `/root/.config/opencode/`、`/root/.opencode/`（O2） | 丢失，但 bootstrap 探活失败会重写重拉（幂等） | 可重建，非数据 |
| BA 会话状态 | 平台主库 `cat_agent_state`（A4） | 存活（在平台库，非 workspace） | 合理——平台级资产，见 §2.4 |
| serve/预览日志 | 容器层 `/tmp/opencode-serve.log` | 丢失 | 平台产物未落 workspace（小项） |
| 知识分块 | 平台主库 `knw_chunks`（D6） | 存活（平台级） | 合理，不搬 |

结论：**#3 决议的两个「必须」各有一个现状违背点**——pg 数据在 workspace 外、opencode 会话在容器层。目录组织方案要同时治这两条。

### 2.2 方案对比

**方案 A：单卷单根——一切持久物收进 `/workspace`**

```
/workspace                 ← 唯一持久化根（一个命名卷，或上云后一块云盘）
├── AGENTS.md             ← 平台技术约定（§4.4：两引擎原生同读，放根）
├── docs/PRD.md           ← 业务产物（PRD；未来 ARCH.md/TEST.md/DELIVERY.md 同层）
├── <应用代码>             ← 交付物占根（package.json/src/…，TS 全栈应用本体）
├── data/                 ← 中间件数据目录（#3「数据目录映射进 workspace」）
│   └── pg/               ←   容器内 postgres 的 data_directory（chown postgres）
├── .env                  ← 平台生成的连接串（机密，packSource 已排除，W7）
└── .platform/            ← 平台产物（对齐 harness 布局 H6，引擎无关）
    ├── skills/  rules/  agents/   ← harness 原生目录名（H6）
    ├── sessions/          ← 引擎会话体（opencode XDG_DATA_HOME 重定向到此）
    └── logs/              ← serve/应用日志
```

- 优点：**一个卷 = 全部持久化语义**，「容器无状态」从口号变成可验收断言（`docker rm` + 重建 + 应用照跑）；packSource 语义只需在排除表加 `./data`、`./.platform`；与 harness 布局命名兼容（skills/rules/agents/sessions 同名，H6）。
- 代价：pg 数据进 workspace 卷要注意三点——pg 大版本随镜像锁死（升级 = 停机 dump/restore，可接受）；卷内文件属主是 postgres uid（宿主打包需 root/恰当 uid）；`data/` 必须进 packSource 排除表（W7 的排除表是单一修改点）。
- 证据支撑：`.env` 先例（W5）已证明「平台环境信息落 workspace、agent 从文件读」成立；`WorkspaceConstants`（H6）证明 harness 生态按工作区目录约定组织产物。

**方案 B：双卷——`/workspace`（代码+文档）+ `/data`（数据+平台产物）**

- 优点：交付物卷干净，数据卷可独立快照/扩容；「取走工作区」天然不含数据。
- 缺点：**持久化语义劈成两半**——「workspace = 唯一持久化根」（#3 原话）被打破，重建/备份/迁移要同时照顾两个卷；与票面定义直接冲突。
- 判：否。#3 已定「workspace = 一个项目唯一持久化根」，B 是对上游决议的再谈判，无足够收益。

**方案 C：bind mount 宿主目录（本地开发形态）**

- 优点：宿主直读直改，调试方便。
- 缺点：耦合宿主路径；上云（TKE，W10 演化路径）没有对应物；权限/属主问题更尖锐。
- 判：作为本地 dev 的可选后端形态保留（`EnvironmentBackend` 可替换，W10），不作为方案本体。

**建议：方案 A。** 落地动作（后端裁剪票消费）：
1. `DockerEnvironmentBackend` 单容器化：pg/redis 从独立容器改为**同容器多进程**（镜像装 postgres + supervisord/s6 或 entrypoint 脚本拉起；数据目录 `PGDATA=/workspace/data/pg`）；删 network/pg/redis 容器编排（W1、W4 的 `provisionResources` 整段重写）；redis 按需保留（当前生成的应用未必用，可作为镜像内置按 .env 开关）。
2. `packSource` 排除表追加 `--exclude=./data --exclude=./.platform`（W7 单点改）。
3. opencode 会话收进卷：serve 启动环境加 `XDG_DATA_HOME=/workspace/.platform`（O2 的 startServe 单点改）——会话体（SQLite `opencode.db` + WAL + undo 快照 git 仓库）整目录落卷，重建不丢，D4 的会话续跑跨重建存活。（`OPENCODE_DB` 可单指库文件，但整目录 XDG 重定向连快照一起搬，更完备。）
4. 预览改造（#3「真实应用服务+自动联动」）属 #10 地盘，本票只留位：单容器后应用端口即预览端口来源，`exposePort` 能力面不变（W10）。

### 2.3 容器无状态、随时重建不丢数据的落地方式

- **重建流程**（复用既有幂等根基 W8/W9）：`docker rm -f ws-{id}-dev` → `docker run`（同卷同镜像同端口策略）→ bootstrap 幂等重拉 serve（O2 已幂等：探活→密码文件→重拉）→ `.env` 重写（W5）→ pg 从 `/workspace/data/pg` 自愈。全程不需要新机制，只依赖「状态都在卷里」这一条。
- **验收断言**（建议写进裁剪票验收）：置备 → 跑一轮 Demo run 写库 → `docker rm -f` 重建 → pg 数据可读、PRD 在、修正 run 能续上旧会话。
- **风险**：pg 大版本升级、卷内属主、`.platform/sessions` 与引擎版本的存储格式兼容（opencode 升级可能换存储格式——收敛 AgentScope 后此风险自然消除，见 §四）。

### 2.4 平台产物与平台级资产的分界

「日志、知识分块」不该一刀切进 workspace：

- **进 workspace（`.platform/`）**：与**这个项目**绑定的过程产物——引擎会话体、serve/应用日志、（将来）项目内 skills/rules。
- **留平台库**：**跨项目**资产——知识分块（D6：全局跨项目相似命中，本来就是平台级）、BA 会话状态（A4，平台进程内智能体、生命周期与项目容器无关）、计量。BA 状态若将来要求「随项目迁走可重建」再议，当前业务无此要求。

---

## 三、子问题 ②：三引擎统一以 workspace 为根的绑定

### 3.1 现状：三个引擎各自怎么访问文件与会话

| 引擎 | 进程位置 | 文件面 | 会话面 | 凭据/配置 |
|---|---|---|---|---|
| AgentScope（BA） | **平台 JVM 内**（A1） | `DockerExecFilesystem` 经 docker exec 读写容器 `/workspace`（A2） | 平台 PG `cat_agent_state`，`(userId, ba-{projectId})`（A4/A5） | 模型 key 平台环境变量 |
| docker 置备 | 平台 JVM 子进程（docker CLI） | 卷挂 `/workspace`、`.env` 写入（W3/W5） | 无（资源生命周期=workspaceId 确定性命名，W8） | pg 密码随机生成进连接串 |
| opencode（编码） | **dev 容器内** serve 进程（O1） | 引擎进程原生文件工具，cwd=`/workspace`（O3） | 引擎自管，体在容器层（O4，§2.1 已判为违背）+ 平台侧 `agt_agent_sessions` 登记 | provider 配置+密码文件，容器层可重建（O2） |

三个引擎已经**事实上共享 `/workspace` 这个根**（W3 的 `-w /workspace`、A2 的 CONTAINER_ROOT、O3 的 `directory=/workspace` 三处同锚），缺的不是「根统一」，而是**绑定契约的显式化**：路径约定、产物目录、会话寻址、可重建性各自为政。

### 3.2 统一绑定方案（建议）

**契约本体 = `WorkspaceHandle` + 一张工作区布局表**，落成代码里的一等事实（建议形态：`base/workspace` 内 `WorkspaceLayout` 常量类 + `EnvironmentBackend` 契约注释升级），内容五条：

1. **根**：容器内根恒 `/workspace`（现状三处同锚，收成单一常量源——现在 `EnvironmentBackend` 注释、`ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT`、`OpenCodeAdapter` 字符串三处各写各的）。
2. **布局**：§2.2 方案 A 的目录表（`docs/`、`data/`、`.env`、`.platform/{skills,rules,agents,sessions,logs}`），目录名与 harness `WorkspaceConstants`（H6）对齐——AgentScope 原生认，opencode 无成本兼容。
3. **环境注入**：平台生成的连接串/机密只经 `/workspace/.env`（W5 先例），引擎与生成应用都从文件读——置备是唯一写者。
4. **会话寻址分层**：平台侧登记（`agt_agent_sessions` / `cat_agent_state`）继续按 workspaceId/projectId 派生（`ba-{projectId}` 先例，A5）；引擎侧会话体一律落 `.platform/sessions`（卷内，O4 缺口的治法）。
5. **可重建性**：凡不在 `/workspace` 的容器内状态，必须可由 bootstrap 幂等重建（serve 密码/配置已是，O2）；不可重建的状态不允许落在卷外（本条即 §2.3 验收断言的反面表述）。

**为什么不建「文件面网关」**（把三引擎文件操作全收平台代理）：BA 的 docker exec 文件面（A2）已按 `AbstractFilesystem` SPI 收敛到 harness 契约；opencode 的文件面在引擎进程内、性能与语义都是引擎内生能力，平台再代理一层只添延迟与第二事实源。**绑定的统一在「根与约定」，不在「通路」**——这正是现状三引擎能平滑共存的原因，别动它。

---

## 四、子问题 ③：引擎栈收敛（AgentScope 单栈 vs 保留 opencode-in-docker）

（本节引用官方文档事实，来源标注于各条；本地 jar 证据见 §1.5。）

### 4.1 AgentScope（Java 2.x）官方能力面

版本基线：Java **v2.0.1**（2026-08-06；2.0 GA 2026-07-10）＝平台在用版本；Python 2.0.7（对照用）。

| # | 事实 | 来源 |
|---|---|---|
| S1 | **会话**：引擎完全无状态，会话数据（历史/压缩摘要/权限规则/Plan/todo/工具组）在 `AgentState`，按 `(userId, sessionId)` 每次 call 入口加载、出口持久化到 `AgentStateStore`；官方 store：InMemory / JsonFile（**HarnessAgent 缺省，单机 `~/.agentscope/state/`**）/ Redis / MySQL / OSS——平台自写 PG 实现（A4）语义对齐官方 MySQL 方言 | java.agentscope.io/v2/en/docs/building-blocks/context.html、/integration/session/overview.html |
| S2 | **跨进程恢复是官方一等场景**（"Real-time resume across processes and machines"）：分布式 store 下另一 JVM 用同 `(userId, sessionId)` 首个 call 即自动恢复（failover/滚动发布/跨端续接）；同会话并发按 per-session gate FIFO 串行 | 同上 context.html |
| S3 | **编码工具集开箱即在**：HarnessAgent 自带 `execute`（shell）、`read_file`/`write_file`/`edit_file`/`grep_files`/`glob_files`/`list_files`（Claude Code 风格）+ memory/session 检索 + `agent_spawn`/`agent_send` + plan 三工具；官方博客《Coding Agent: The Second Half》**明确以此为目标场景**（对标 Claude Code/Open SWE），示例 `agentscope-examples/agents/agentscope-codingagent` 可直接改 | java.agentscope.io/v2/en/docs/building-blocks/tool.html、/harness/filesystem.html；/v2/en/blogs/agentscope-v2-coding-agent.html |
| S4 | **沙箱是一等公民**：统一 `FilesystemSpec` 三模式——Local+shell（host `sh -c`）/ **Sandbox（Docker/K8s/E2B/Daytona，shell 在容器内，`new DockerFilesystemSpec().image(...)` 一行切换）** / Remote（共享 KV 无 shell）；**跨调用快照链**（容器在→直接续；没了→从快照恢复；无→冷启动；官方例「npm install 后下次 call 不用重装」）；`IsolationScope` 多租户隔离 + 分布式执行锁；agent 代码换模式零改动 | java.agentscope.io/v2/en/docs/harness/filesystem.html、/harness/sandbox.html |
| S5 | **skill 体系对标 Claude Code**：`skills/<name>/SKILL.md`（+references/scripts），skill 仓库支持 **Git/Nacos/MySQL/PostgreSQL/classpath** 多源叠加；自学习闭环（成功模式起草 → 审核门 → curator 归档老化——jar 证据 H5 的 AllowList/Canary 晋升门） | java.agentscope.io/v2/en/docs/harness/skill.html；jar `agent/skill/curator/*` |
| S6 | **workspace = 声明式 agent 定义中心**：`AGENTS.md`（persona/规则）、`knowledge/`、`skills/`、subagents 声明、`tools.json`（MCP + allow/deny）、`MEMORY.md`——**文件与 builder API 完全对等**；每轮推理前 `WorkspaceContextMiddleware` 重组 system prompt（session 上下文 + `<agents_context>`(AGENTS.md) + `<memory_context>` + `<domain_knowledge_context>`），**改文件即时生效** | java.agentscope.io/v2/en/docs/harness/workspace.html |
| S7 | **workflow（渐进演进判据的关键事实）**：①Python 版**并没有 Workflow/DAG 类**——编排原语是 pipeline 模块（MsgHub/sequential/fanout，2026-08 新增 GoalPipeline executor-verifier 循环），官方立场「靠模型推理与工具使用，不做 opinionated orchestration」；②Java 2.0 = 模型驱动编排：subagent（workspace 声明 + `agent_spawn/agent_send`，同步或后台）+ Agent Teams（Lead/Worker + 共享 Task Board/Mailbox）+ Plan Mode（只读阶段 + HITL 确认退出）；③确定性 DAG/循环在 Java 侧由 **Spring AI Alibaba**（外部项目，StateGraph/CompiledGraph）提供 | doc.agentscope.io/tutorial/task_pipeline.html + 仓库源码树（无 workflow 模块）；java.agentscope.io/v2/en/docs/harness/{subagent,plan-mode}.html；java.agentscope.io/v1/en/docs/multi-agent/workflow.html |
| S8 | **进程形态**：嵌入式库优先（Spring Boot starters 官方提供）；可选服务化——`agentscope-extensions-chat-completions-web`（**OpenAI 兼容端点**）、A2A server/client、AG-UI、IM channels；**AgentScope Service**（2026-08 发布）= 托管控制面（注册发现/Dashboard/Managed Agents，Brain-Hands 分离：工具执行可放自有沙箱）。架构取向：**agent 进程（Brain）外挂沙箱容器（Hands）**——与「opencode 整个塞进容器」相反 | 仓库模块树；java.agentscope.io/v2/en/blogs/agentscope-service-release.html |
| S9 | **成熟度**：Java 仓库 2025-09 创建、5.3k stars（Python 30k）；v2.0 GA 至今约 2 个月，**节奏快但 API 仍在动**（2.0 已整体废弃 1.x Memory 体系）；Java 缺 Python 的 realtime/voice、Tuner、Evaluation，**无内置 web_search（需 MCP/自建）**；官方 evolution path：本地 → 沙箱 → 多副本分布式 → 可观测/限流 | GitHub API + releases 实测（2026-08-30）；两站文档比对 |
| S10 | **对本评估最要紧的三条**：①目标场景就是「组织级编码 agent」，沙箱/快照/压缩/多租户/权限 HITL/AGENTS.md/skills 全内建，且有官方编码 agent 示例可抄；②Brain-Hands 形态与平台现状天然同构（平台 JVM 托管 agent + dev 容器做 Hands——A2 的 DockerExecFilesystem 就是这个形态的手工版）；③年轻（GA≈2 个月）是主要风险，需要真实 run 验收兜底 | S3/S4/S8/S9 汇总 |

### 4.3 方案对比

| 判据 | 方案一：收敛 AgentScope 单栈（一步到位） | 方案二：保留 opencode-in-docker（不收敛） | 方案三：端口内并行 → 验收 → 切默认（渐进收敛） |
|---|---|---|---|
| 编码能力现状 | harness 部件全在（S3/H1-H3）但**平台未在真实 run 验证过**；shell 缝 = `DockerExecFilesystem` 补 `execute()`（H2） | 已在生产路径跑（demo 同构），能力面成熟（P10） | 先并行后切，风险后置到验收门后 |
| 会话持久化 | stateStore SPI + 平台 PG 实现已有（A4/S1），容器重建零影响 | 单机 SQLite，需 XDG 重定向进卷（P2，§2.2 治法） | 同左两栏各自成立 |
| 流式过程 | 28 类事件流已桥接（A8/A9），逐帧 | parts 整批透传（O5 已知限制），/event SSE 未接 | 并行期可直接对照流式体验 |
| HITL/权限 | RequireUserConfirm + permission 模式已桥（A8/S3） | question/permission 轮询已接（O7/P7） | 两者都已接，等价 |
| workflow 演进 | subagents + Teams + Plan Mode 模型驱动（S7）；确定性 DAG 靠 Spring AI Alibaba（外部依赖） | subagent + Task tool（P1）；无编排引擎 | 编排主责本就在平台侧（主链/门/dispatch，D2/D3），引擎层只需 subagent 能力——两者都有 |
| skill 演进 | SKILL.md + 多源仓库 + curator 自学习闭环（S5/H5） | SKILL.md 生态，兼容 `.claude/.agents`（P6） | **SKILL.md 格式两边同源**，技能资产不锁引擎 |
| 配置/约定演进 | AGENTS.md + rules/ + tools.json，改文件即时生效（S6） | AGENTS.md + opencode.json + instructions（P5） | AGENTS.md 为公共载体（§4.4） |
| 栈一致性 | 单 JVM 栈：同进程、同 PG、同计量、同事件桥 | Node/Bun 第二运行时进容器 + serve 生命周期管理（O1/O2） | 收敛后归一 |
| 依赖/治理风险 | **Java 2.0 GA≈2 个月，API 可能动**（S9） | 治理转 Anomaly 公司、日近 2 版、autoupdate 默认开、存储/SDK 代际切换中（P9） | 并行期两边风险都可控，切换由验收数据定 |
| 改造量 | 新增 `AgentscopeCodingAdapter`（`CodingAgentAdapter` 端口，O10）+ `execute()` 补齐；**不动置备/门/dispatch 编排** | 零改造（维持现状缺口：会话在容器层、双运行时永续） | 与方案一相同，只是分两步走 |

**结论：方案三。** 理由：

1. **技术上收敛成立**：决定性证据是 S3/S4（官方目标场景即编码 agent，沙箱一等公民）+ H2（现有 `DockerExecFilesystem` 距 `AbstractSandboxFilesystem` 只差一个 `execute()`）+ O10（引擎注册表天然多引擎）。收敛不是重写，是**在既有端口下加第二个编码引擎实现**。
2. **架构上收敛更对**：Brain-Hands 形态（S8）与平台现状同构——BA 已经这么跑了（A2）；会话状态归平台 store（A4/S2）天然满足「容器无状态重建不丢数据」（§2.3），而 opencode 的会话体要靠 XDG 重定向打补丁（P2）；单 JVM 栈消除容器内第二运行时的生命周期管理（O1/O2 的 bootstrap 全套）。
3. **演进上收敛不锁死**：skill 资产是 SKILL.md（两边同源，S5/P6）；约定层是 AGENTS.md（两边原生读，S6/P5）；编排主责在平台侧不随引擎变（D2/D3）。未来要确定性 workflow，AgentScope 路线 = Spring AI Alibaba StateGraph 或平台自研，均不绑定 opencode。
4. **为什么不是一步到位**：S9（GA 年轻）+ 平台从未用 harness 跑过真实编码 run——用「并行注册 → 真实 DEMO run 对照（产出质量/耗时/token 成本/流式体验）→ 达标切 `AgentEngineRegistry.DEFAULT_ENGINE` → opencode 降级可选 → 移除」的路径，每步可回退，符合「稳步推进、不推倒重来」约束。方案二否决：它把 P2/P9 两个已知缺口（会话单机、治理动荡）固化为永久成本。

**验收口径建议**（切默认的门）：同一 PRD 起两个真实项目，DEMO run 对比——原型可预览、run 正常收口率、修正 run 续会话（含容器重建后续）、token 成本、流式帧完整度（task-start→…→task-finish）。

### 4.4 平台约定层的演进空间（system prompt → workspace 文件）

现状：技术约定零载体（D5），角色卡每消息经 system 字段注入（opencode 侧 O6；AgentScope 侧 sysPrompt builder 入参，`AgentscopeHarnessAgentFactory.java:90-95`）。#3 决议 v1 用 system prompt 注入——成立，但**演进终点应是 `/workspace/AGENTS.md`**：

- **两引擎原生同读**：opencode 把 AGENTS.md 拼进 system 上下文（P5），AgentScope `WorkspaceContextMiddleware` 每轮重组 `<agents_context>`（S6）——平台约定写成 workspace 文件后，**换引擎、改约定都不动平台代码**。
- **与角色卡正交**：AGENTS.md 装平台级约定（TS 全栈、目录结构、构建命令、交付物口径）；systemPrompt 入参继续装角色卡（BA/DEV 访谈协议与职责）——两层语义不混。
- **渐进路径**：v1 置备时写一份平台 AGENTS.md 模板（`EnvironmentBackend.createWorkspace` 的副作用，W5 `.env` 先例同形）→ v2 约定内容模板化/版本化（甚至进 `knowledge/`，S6）→ v3 平台侧统一治理（一份模板源服务所有 workspace）。system prompt 注入在 v1 可并行保留做兜底，迁移完成后摘除。
- **注意**：opencode 项目级 AGENTS.md findUp 只取第一命中（P5）——平台文件放 workspace 根即全容器生效，无叠加风险；AgentScope 侧同理（根目录 AGENTS.md）。

### 4.2 opencode 官方能力面

| # | 事实 | 来源 |
|---|---|---|
| P1 | 会话：session 含 id/title/directory/parent_id；`--continue`/`--session`/`--fork` 续接分叉；subagent 经 Task tool 自动建 child session（`parent_id` + `GET /session/:id/children`） | opencode.ai/docs/cli/、/tui/、/server/ |
| P2 | **存储 = 单机 SQLite**：`~/.local/share/opencode/opencode.db`（stable 通道；`OPENCODE_DB` 可覆盖、支持 `:memory:`），WAL + drizzle 迁移；undo 快照是 data 目录下的内部 git 仓库（`snapshot: false` 可关）；目录按 XDG 派生（`Global.Path.data`） | 源码 `packages/core/src/global.ts`、`database.ts`、`session/sql.ts`；opencode.ai/docs/config/ |
| P3 | server：`opencode serve` 默认 `127.0.0.1:4096`，**发布 OpenAPI 3.1（`/doc`）并据此生成 SDK**；Basic Auth（`OPENCODE_SERVER_PASSWORD`）；消息有同步 `POST /session/:id/message` 与 `prompt_async`（204 立返）；`GET /event` SSE；权限 API `POST /session/:id/permissions/:id`；`POST /session/:id/init` 可生成 AGENTS.md | opencode.ai/docs/server/ |
| P4 | 配置：`opencode.json` 层级合并（remote → global → `OPENCODE_CONFIG` → project → `.opencode/` 目录 → `OPENCODE_CONFIG_CONTENT` 内联 → 系统托管）；`{env:VAR}` 变量替换——容器化注入友好（现状 O2 用的 global 层只是其一） | opencode.ai/docs/config/ |
| P5 | **AGENTS.md（rules）**：项目根 AGENTS.md 进 system 上下文（`"Instructions from: <path>"` 数组）；项目级 findUp **只取第一个命中**；全局 `~/.config/opencode/AGENTS.md`；无 AGENTS.md 时 fallback CLAUDE.md；`instructions: [...]` 可追加文件/glob/URL | opencode.ai/docs/rules/；源码 `packages/opencode/src/session/instruction.ts` |
| P6 | skills：`.opencode/skills/<name>/SKILL.md`（**兼容 `.claude/skills/`、`.agents/skills/`**），frontmatter name+description，按需加载（skill 名录进 native skill tool，调用才载全文）；permission 支持 skill 键 glob | opencode.ai/docs/skills/ |
| P7 | 权限：`allow/ask/deny` 三动作 + 对象粒度 glob（如 `bash: {"git push": "ask", "rm *": "deny"}`，最后匹配生效）；headless 旁路 = `--auto` 或整体 `"permission": "allow"` | opencode.ai/docs/permissions/ |
| P8 | 自定义工具/插件/MCP：`.opencode/tools/*.ts`（Zod schema，**与内置同名时自定义优先**）；plugins（tool.execute.before/after 拦截、permission hooks、注入 shell.env）；MCP local/remote + 运行时 `POST /mcp`；LSP 内置 30+ 语言服务器（默认关） | opencode.ai/docs/custom-tools/、/plugins/、/mcp-servers/、/lsp/ |
| P9 | 治理与节奏：**已从 sst 社区转入 Anomaly 公司**（github.com/anomalyco/opencode，重定向实测）；MIT；202k stars；**867 个 release（2025-05 → 2026-08，均值日近 2 版）**；`autoupdate` 默认 true（**容器内自动升级是稳定性风险，需显式关**）；核心 API 稳定但内部存储/SDK 正处代际切换（effect 化 v2 core、sdk-next 实验中）；云服务（Zen/Go）全部可选，核心纯本地可跑 | gh API 实测；opencode.ai/docs/zen/、/config/；CONTRIBUTING.md |
| P10 | 对本评估最要紧的两条：①会话/权限/rules/skills 能力面完整且契约化（OpenAPI），**作为编码引擎是成熟件**；②会话存储**无集群语义**——多容器共享要搬 SQLite 或 export/import，且其会话生态（分享/undo 快照）都在单机假设下设计 | P2/P3/P9 汇总 |


---

## 五、开放问题（不阻塞建议，裁剪票留意）

1. **dsh 引擎去留**：ADR-0004 引入的第二编码引擎，收敛方向下无独立存在必要——建议裁剪票一并处理（注册表摘除 + 镜像瘦身），不在本票强推。
2. **`.platform` vs `.agentscope` 命名**：harness 缺省根是 `.agentscope/workspace`（H6）；若想让 AgentScope **零配置**认平台产物目录，可直接用 `.agentscope/` 命名替代 `.platform/`。代价是目录名被框架口味绑定。建议裁剪票二选一写死，别两套并存。
3. **单容器资源面**：all-in-one 后 pg 与 node 同容器，内存/CPU 竞争需要镜像与 run 参数兜底（本地单项目规模无虞，上云多项目密度上来再议）。
4. **会话体格式兼容**：opencode 若在收敛完成前升级换存储格式，`.platform/sessions` 内旧数据可能失效——收敛时间线越短此风险越小；过渡期建议镜像锁版本 + serve 启动显式关 `autoupdate`（P9：默认开，容器内自动升级是稳定性风险）。
5. **AgentScope Java 版本钉住**：2.0 GA≈2 个月、API 仍在动（S9）——平台侧升级 harness 大版本应作为显式决策（依赖锁定 + 变更日志过目），不随引擎升级漂移。
6. **harness 快照体系是否启用**：官方 sandbox 自带跨调用快照链（S4）；平台已有「容器常开 + workspace 卷」的持久化形态，快照暂无必要——除非将来走「按需起容器省资源」路线再启用。

## 六、信源清单

- 本仓库 `aiplatform-server` @ d808fb0（正文 file:line 即引用）
- `agentscope-harness-2.0.1.jar` / `agentscope-core-2.0.1.jar` / `agentscope-runtime-sandbox-core-1.0.2.jar`（本机 Maven 仓库，javap/jar 反查：§1.5、S 系注）
- AgentScope Java 官方文档站 https://java.agentscope.io/v2/en/ （docs/building-blocks/{agent,tool,context}.html、docs/harness/{workspace,filesystem,sandbox,memory,skill,subagent,plan-mode}.html、integration/session/overview.html、blogs/{agentscope-v2-coding-agent,agentscope-service-release}.html、v1/en/docs/multi-agent/workflow.html）
- AgentScope 官方 GitHub https://github.com/agentscope-ai/agentscope-java 、https://github.com/agentscope-ai/agentscope （releases API 2026-08-30 实测；Python 文档站 https://doc.agentscope.io/ ）
- opencode 官方文档站 https://opencode.ai/docs/ （cli/tui/server/sdk/config/agents/permissions/rules/skills/custom-tools/plugins/mcp-servers/lsp/zen/share）+ 官方 GitHub https://github.com/anomalyco/opencode （源码 packages/core/src/{global,database}.ts、packages/opencode/src/session/instruction.ts；gh API 2026-08-30 实测）
- 上游决议：issue #3 Resolution（约束输入）
