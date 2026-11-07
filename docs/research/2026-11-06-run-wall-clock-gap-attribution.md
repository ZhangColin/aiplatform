# 调研报告：未归因间隙归因——run 墙钟里「消失的 ~35%」去哪了（2026-11-06）

> 票 [#410](https://github.com/ZhangColin/aiplatform/issues/410)（wayfinder 地图 #404 research 子票），承接 [#313](../../commit/5b44568) 旧调研「未计量间隙 ≈22%」的追问。
> 方法：生产库只读 SQL 实测（77 张收尾卡全量）＋埋点代码面逐行对照＋agentscope-harness 2.0.1 内核反编译取证。数字给量级不给假精确；生产库数据截至 2026-11-06。

## 结论速览

1. **「durationMs − 桶计 ≈ 35-40% 未归因」在生产库中不存在——方向反了**。77 张收尾卡（7 项目，2026-10-29→11-05）全量实测：四桶合计为 durationMs 的 **102–113%**（切片口径 −11.3% 差值），不是 60-65%。票面观察是**读数口径 artifact**：锚案例 llmMs 单桶占比 58.6%，恰是票面「四桶合计 ~60-65%」的来源——即当时的读法实际只计了 llm 桶（toolsMs 读成 `{}`、verifyMs 未入和）。
2. **toolsMs 在库内并非恒空**：锚案例 15 张卡中 14 张 toolsMs 有值（read_file/write_file/command.other/agent_send 等分桶齐全）；唯一 `{}` 的是一张 9 秒的极简片（本就无工具调用）。容器命令**有计量**，但归组几乎全落 `other`（分组正则盖不住 build/npx/node/curl 等形态）——全量 `command.other` 合计 59.8min，占切片 tools 桶的大头。
3. **真正的计账问题是重叠双计，不是漏计**：① `verifyMs` 的口径是「首→末 source=verify 事件的单窗跨距」（`StageDurationFacts.subagentWindows` 每源一窗），而内核 `agent_spawn` 默认 30s 超时、verify 实跑 2-10min，**必然被超时提升为后台任务**（内核文档串原文），父流继续推理、verify 事件长跨度回流——窗口把中间的父流 LLM 时间也圈进去了。run 越长超计越多（时长 Q1 桶差值 −2.2% → Q4 桶 −13.0%）。② 委派族工具 `agent_send`（3231s）/`wait_async_results`（797s）未被 `toolsPayload` 除名（除名表只有已改名的 `agent_spawn`，全量恰为 0），等待屏障与 verify 窗重叠——双计第二来源。
4. **run 内真·未归因只有 5-12%（中位 ~10%）**，且只在 verify=0 的 run 上可见（10 张干净样本）：逐模型迭代边界 0.4-1.6s 的内核/管道开销（上下文重组、事件管道、工具结果处理），加 run 入口 1-2s（配置/路由解析＋工厂缓存命中＋会话状态装载——状态仅 33-68KB，非源）与收口尾序 <1s（closingMs 实测 0.26-0.8s，探活+lint+git 成版都在内）。
5. **片间交接空档（不在任何 run 账内、在用户墙钟内）实测：串行边界中位 18.4s / 均值 21.4s / 带 4.5-52.4s**（24 个串行边界）；并行波边界 43 个 ≈ 0（fork 重叠跑）；>300s 的 3 个是用户离开（8h/30h/22min，更新轨用户发起）。锚案例 12+1 片用户墙钟 105.7min = Σ片墙钟 138min（并行赚回 ~32min）＋串行交接 ~65s（≈1%）。交接路径代码枚举（交接摘要落盘 1 次 docker exec＋回滚基准捕获 2-3 次＋路由文件面探针 1 次＋DB 读）只解释 ~1-3s，且 stage0→片1 同款路径实测 ≈1s——**剩余 ~13-18s 无静态解释**，簇状分布（4.5/16/20s 三簇）疑固定退避或容器 CPU 争抢（next dev 重编译期）拖慢 docker CLI 子进程，需一条 run 入口时间戳日志定分（见 §5 建议）。
6. **可收窄面排序**：计量口径修复（verify 窗/委派族去重叠，零运行时代价、归因面立刻清晰）＞ LLM 主矛盾（57-60%，模型路由已有 #354 面）＞ verify 面裁剪（真实并行收益已发生 ≈8-13% 墙钟，进一步收窄靠提前发/裁验证项）＞片间交接（~1% 用户墙钟，上限分钟级/项目）＞框架开销（5-12%，ReAct 串行迭代的结构成本为主）。**失败片成本本数据集不可见**（77 卡全部 attempts=1；失败片无收尾卡、run-failed 无落库面）——#313 时代 33% 失败作废是否复存，需另开票测。

---

## 1. 数据与口径

- **事实源**：生产库 `prj_conversation_entries`（kind=5 收尾卡，`closing.durationMs` / `closing.durationBreakdown{llmMs,toolsMs,verifyMs,closingMs,attempts[]}`）；`met_usage_events`（模型边界逐调用计量，含 ts）；`cat_agent_state`（会话状态体积）。全部只读 SELECT。
- **样本**：77 张收尾卡 = 7 个 stage0 + 67 张切片 + 2 张更新 + 1 张咨询轮，7 个项目，2026-10-29 → 11-05。锚案例 376467559906353574（2026-11-05）15 张：1 起服 + 12 切片 + 2 更新（更新轨 8h 后用户发起，不计入生成墙钟）。
- **时间口径**：`created_at` 为 UTC+8 朴素时间，`met_usage_events.ts` 为 UTC——差 8h 已对齐验证。片间空档 = （下一片 `created_at` − 其 `durationMs` − `closingMs`）− 上一片 `created_at`；`closingMs` 实测仅 0.26-0.8s，估计误差 <1s。
- **埋点口径**（代码正本）：`StageDurationFacts.java`（llmMs=模型调用 Start→End 按 replyId 配对；toolsMs=ToolCallEnd→ToolResultEnd 按 toolCallId；command 工具按归组；source 事件只记首末窗）；收口装配 `CoderRunAttempts.durationBreakdown`（四桶+逐尝试）；规格 `docs/spec/SSE事件清单.md` 收口扩载节。

## 2. Q1 差值画像（durationMs − 桶计）

### 2.1 按片型（77 卡全量）

| 片型 | n | 时长合计 | llm | tools | verify窗 | closing | **差值（dur−桶计）** | 差值中位 |
|---|---|---|---|---|---|---|---|---|
| stage0 | 7 | 57.3min | 75.3% | 7.0% | 11.7% | ~0% | **+6.0%** | +4.0% |
| 切片 | 67 | 739.2min | 57.5% | 18.6% | 35.1% | ~0% | **−11.3%** | −7.9% |
| 更新 | 2 | 24.4min | 65.2% | 17.0% | 26.2% | ~0% | **−8.4%** | −9.2% |

### 2.2 按时长分位（切片 67 张，四分位）

| 分位 | 时长带 | llm | tools | verify窗 | **差值** |
|---|---|---|---|---|---|
| Q1 | 9-362s | 60.3% | 17.6% | 24.1% | **−2.2%** |
| Q2 | 409-617s | 59.3% | 17.1% | 32.8% | **−9.3%** |
| Q3 | 624-856s | 60.5% | 15.8% | 31.4% | **−7.8%** |
| Q4 | 886-2028s | 57.8% | 19.3% | 35.9% | **−13.0%** |

**读法**：桶计不是少了 35%，而是多了 2-13%，且 run 越长超计越重——超计与 verify 窗同步膨胀，指向 verify 窗与父流重叠（§3.2）。stage0 差值为正（+6%），因为 stage0 的 verify 窗小（11.7%）、无委派重叠，露出的是真·框架残差。

### 2.3 票面观察的还原

锚案例 13 张生成卡：llmMs 合计 4851s / durationMs 合计 8277s = **58.6%**——与票面「四桶合计只占 ~60-65%」吻合到小数位；toolsMs={} 恰只匹配 9 秒极简片。结论：票面的「~35-40% 未归因」是把 llm 桶（可能+极小 closingMs）当成四桶和的读数口径问题（toolsMs 读作空对象、verifyMs 未入和），不是库内事实。API 读面（`ConversationEntryResponse.closing`）是 closing JSONB 原样透传，服务端无收窄——artifact 在消费侧读法。

## 3. Q2 可归因项逐项拆解（代码面 × 数据面对照）

### 3.1 toolsMs「恒空」与容器命令计量

- 事实：锚案例 14/15 卡 toolsMs 非空。工具面构成：`read_file/list_files/grep_files/glob_files`（docker exec 文件读）、`write_file/edit_file`、`update_plan`、`command.other`（内核 shell）、`agent_send/task_list/task_output/wait_async_results`（委派族）。
- `command` 归组几乎全落 `other`（77 卡 `command.other` 合计 3589s，install/dev/test 组近零）：分组正则（`StageDurationFacts` INSTALL/DEV/TEST 三族）盖不住 `next build`/`npx`/`node xxx.mjs`/`curl`/`psql` 等形态。**命令有计量、归组失真**——修归组即可把「容器命令未计量部分」这格清零（真实量级：占 tools 桶大头、占墙钟 ~7%）。

### 3.2 verifyMs 重叠双计（超计主因）

- 机制链（代码+内核取证）：
  1. `StageDurationFacts.subagentWindows`：每个 source 只记「首事件→末事件」单窗（快照折算跨距）——**多轮委派/长跨度回流都被圈进一窗**。
  2. 内核 `AgentSpawnTool`（agentscope-harness 2.0.1，`javap` 取证）：`agent_spawn` timeout「0=fire-and-forget, default=30, max=600」，超时**自动提升为后台任务**、结果以 `<system-reminder>` 推回；`agent_send` 同语义（0=异步，>0=等回复）。
  3. verify 子智能体（`ProfileSubagentSupplier`，真浏览器 Playwright＋psql＋日志三面）实跑 2-10min >> 30s → 必然后台化 → 父流继续迭代（llmMs 继续累积）→ verify 事件长跨度穿插回流 → 窗口把父流时间圈入。
- 数据指纹：差值随 run 变长恶化（−2.2%→−13.0%）与 verify 窗膨胀（24.1%→35.9%）同步；`agent_spawn` 在 toolsMs 全量恰为 0（`toolsPayload` 除名生效——全量经此路径委派），`agent_send` 3231s＋`wait_async_results` 797s 未除名、与 verify 窗重叠（第二重双计）。
- **verify 的真实并行收益 ≈ 超计部分（墙钟的 8-13%）**——verify 已事实上与父流并行，这是改版（#352 三面验证）相对串行自测的隐性提速，当前口径下不可见。

### 3.3 run 内真·未归因（只在 verify=0 的 run 可见）

10 张 verify=0 干净卡（llm+tools+closing 与 dur 对账）：

| 卡 | dur | llm | tools | 模型调用数 | 残差 | 残差/调用边界 |
|---|---|---|---|---|---|---|
| 127 (stage0) | 354s | 250s | 31s | 45 | 20.4% | ~1.6s |
| 172 (stage0) | 558s | 429s | 63s | 71 | 11.7% | ~0.9s |
| 181 | 675s | 403s | 205s | 50 | 9.8% | ~1.3s |
| 297 | 209s | 154s | 30s | 29 | 11.9% | ~0.9s |
| 136 | 333s | 168s | 147s | 41 | 5.2% | ~0.4s |
| 250/300/350/358/88 | 9-105s | — | — | 1-14 | −4.3%~+3% | — |

- 构成（代码枚举＋边界实测）：**逐迭代边界 0.4-1.6s** 的内核开销（工具结果回填、上下文重组、事件发射管道——365 次调用的大片外推 ~3-6min）＋ run 入口 1-2s（`agentConfigs.effectiveOf`/`modelTiers.resolve`/路由信号 `git ls-files | wc -l` exec＋工厂缓存命中＋`PostgresAgentStateStore` 装载）＋ 收口 <1s。会话状态仅 33-68KB（`cat_agent_state` 实测），状态读写非源。
- 卡 88 残差为负（−0.7%）：并行工具调用下 ToolCallEnd 先于 ModelCallEnd（参数落定早于模型收口）造成的 tool∩llm 小重叠——与 3.2 同族但量级小。

### 3.4 片间交接空档（用户墙钟内、run 账外）

- 串行边界（0<gap≤300s）24 个：**中位 18.4s / 均值 21.4s / min 4.5 / max 52.4**，簇状分布（~4.5s、~16s、~20s 三簇，跨项目复现）。
- 并行波边界 43 个 ≈ 0（#351 fork 切片重叠跑，锚案例 295+296、301+302 同刻起跑——met_usage_events 首调同秒实证）。
- 用户离开 3 个（28700s/109674s/1311s）——更新轨用户发起，非平台时间。
- 锚案例墙钟对账：04:35:21→06:21:01 = **105.7min 用户墙钟 = Σ片 durationMs 138min（并行赚 ~32min）+ 串行交接 ~65s（~1%）**。
- 交接路径枚举（`GenerationAppService`）：`placeSliceHandoff`（1 次 docker exec）→ 波调度（`reloadClosedOrds` DB＋`SliceSchedule.parallelWaves`）→ `captureRollbackBase`（2-3 次 docker exec）→ `coderRunAttempts.run` 入口（配置/档位/路由 DB 读）。合计应 ~1-3s；**stage0→片1 同款路径实测 ≈1s**，而片1→片2 起为 16-21s——差值无静态解释。疑点：`docker exec` 走 `ProcessBuilder("docker",...)` CLI 子进程（`DockerEnvironmentBackend:320`），切片收口后 next dev 对新文件重编译、容器 CPU 争抢可把每次 exec 拖至秒级；簇状量级（4.5/16/20s ≈ 1-2/4-5/5-6 次 exec）与此吻合但无日志实证（生产容器 11-05 23:38 重启，锚案例日志已失）。

## 4. Q3 各项量级与可收窄面

| 项 | 量级（切片墙钟口径） | 性质 | 可收窄面 |
|---|---|---|---|
| LLM 等待（llmMs） | 57.5-60% | 纯等待（模型侧） | 模型路由/快档（#354 已有面）；提示瘦身 |
| verify 窗（真实并行） | 窗口径 35%、真实并行收益 ≈8-13% 墙钟 | 已并行的等待 | 提前发 verify、裁验证项；**计量修复优先**（改 Σ verify 自身调用/工具口径，或窗口去重叠），否则归因面永远糊 |
| 委派族等待（agent_send/wait_async） | tools 内 ~8%（3231+797s / 821min） | 等待屏障 | 与 verify 计量二选一去重；执行体协议改为非阻塞收检 |
| 工具执行（文件读/命令） | ~10-12%（剔除委派族后） | 真实工作 | docker exec 文件读批量化（读放大明显：read_file+list_files 合计 44min） |
| run 内框架残差 | 5-12%（0.4-1.6s/迭代） | 结构成本为主 | 内核迭代管道增量化——量级有限、工程重 |
| 片间交接（串行位） | ~1% 用户墙钟（18.4s/边界 × 串行边界数） | 平台胶水 | 交接 exec 合并单次/波间预热；上限分钟级/项目 |
| 失败片/重试作废 | **不可见**（77 卡全 attempts=1，失败片无卡） | 未知 | 先补观测面（run-failed 落库或日志对账）再谈收窄——#313 时代此项 33%，若复存则仍是第一矛盾 |

## 5. 建议（按性价比）

1. **计量口径修复（零运行时代价，先做）**：① `verifyMs` 弃单窗跨距，改「verify 源模型调用+其工具执行」或首末窗去父流重叠；② `toolsPayload` 除名表补 `agent_send`/`wait_async_results`（或反向：verify 窗弃用、全走工具桶）；③ command 归组正则补 build/npx/node/psql/curl。修完「桶计+差值=durationMs」的缝测守卫（SSE事件清单§收口扩载）才真正成立。
2. **片间交接埋一条 INFO**（run 入口/出口各一时间戳，或 APM span）：定位 16-21s 里的 ~13-18s 无静态解释段；若坐实 docker exec 在重编译期被拖慢，交接 exec 合并＋错峰即收。
3. **失败片观测面**：run-failed 载荷带逐尝试墙钟落库（或日志结构化对账），把「重试作废」重新拉回可归因面。

## 6. 证据与来源索引

- **生产库实测**（只读 SELECT，2026-11-06）：`prj_conversation_entries` kind=5 全量 77 卡（逐卡 dur/四桶/attempts/verification.rounds/toolsMs 分桶）；`met_usage_events`（锚案例 15 run 逐调用数与首末 ts、agentKind 分布）；`cat_agent_state`（锚案例 15 会话状态 33-68KB）；片间空档 LAG 窗口函数（24 串行/43 并行/3 用户离开）。
- **代码**：`StageDurations.java`/`StageDurationFacts.java`（桶口径与单窗语义）；`CoderRunAttempts.java`（`durationBreakdown`/`toolsPayload` 除名表/`AGENT_SPAWN_TOOL`/closingMs 窗）；`AgentscopeAgentClient.java`（attempt 墙钟起点=配置解析前、runTurn 事件窗、`PostgresAgentStateStore`）；`GenerationAppService.java`（序前段/并行波主循环、`placeSliceHandoff`、`captureRollbackBase`、`closeGenerationStage`、`emitPreviewReady`）；`ModelRoutingAppService.java`（文件面探针 `git ls-files | wc -l` exec）；`ProfileSubagentSupplier.java`（verify 声明/`VERIFY_NAME`）；`DockerEnvironmentBackend.java:320`（exec=docker CLI 子进程）；`ConversationEntryResponse.java`（closing 原样透传）。
- **内核取证**：agentscope-harness 2.0.1 jar（本机 ~/.m2）`javap` 反编译——`AgentSpawnTool.agentSpawn/agentSend`（timeout 0/30/600 语义）、`SubagentsMiddleware` 系统提示串（后台任务/超时提升/system-reminder 推回/并行工具默认开）、`WaitAsyncResultsTool`/`TaskTool`。
- **旧调研**：#313 `docs/research/demo-track-vs-production-split.md`（commit 5b44568，2026-10-15，未计量间隙 ~22% 旧口径——彼时无 verify/异步委派，口径已变，不可直接比）。
