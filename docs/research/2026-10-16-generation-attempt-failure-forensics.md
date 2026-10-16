# 生成轨失败首试根因取证报告

> 票 [#314](https://github.com/ZhangColin/aiplatform/issues/314)（wayfinder 地图 #307）调研输入，2026-10-15/16。背景：用户追问「超时、首试失败到底是什么、怎么引起的——做出用户能体验的东西，各种报错、系统跑不起来不可接受」。承重结论已并入 #314 决议（可靠性地基梯队＋机制检查纲领）；本报告为取证细节正本。

数据源：dev 库 `prj_conversation_entries.closing`（kind=5 生成切片）11 条失败首试逐条实测 ＋ `/tmp/aiplatform-backend.log`（覆盖 2026-10-13 10:49 → 10-15 10:33，含 10-14 两条失败首试与后续三连败全程）＋ 两仓代码链路。对 [#313 报告](./demo-track-vs-production-split.md) §1.2(d) 有一处**关键修正**（见结论 2）。

## 一、失败首试全景（DB 实测，精确值）

11 条失败首试（llmMs=0、toolsMs={}、selfTestMs=0 全同形），**按形态分两族**：

| 族 | 条数 | durationMs | 判定 |
|---|---|---|---|
| **30 分钟墙钟族** | 5 | 1800386–1800539（全部 ≈1800.0s） | blockLast(30m) 总墙钟掐死 |
| **提前失败族** | 6 | 322/371/816/824/1030/1123 s | 模型流故障提前炸出 |

合计作废 13488s ≈ 224.8 分钟（均值 20.4 分钟）。第二试 436–1307s 全过（对墙钟族显著更快；对 322s 快败族，第二试 436s 反而更长——「携现场反而快」主要对墙钟族成立）。

## 二、llmMs=0 的真实语义（修正 #313 §1.2(d)）

#313 报告把 llmMs=0 解读为「模型流从未完成计量（零产出超时）」——**这半对半错**：

- **对的部分**：llmMs=0 确实不代表任何模型工作证据。
- **错的部分**：它**不是**「模型从未流」的证明。`AgentscopeAgentClient.runTurn` 的 catch 块（`aiplatform-server/src/main/java/com/aieducenter/aiplatform/base/agentscope/AgentscopeAgentClient.java:348-352`）在 converse 抛异常时返回 `StageDurations.zero()`——阶段耗时事实**随异常整体弃置**（`CoderRunAttempts.java:397-403` javadoc 明文：「中段崩的尝试 = 起跑到异常点（含全部在途），桶为零」）。事实是逐事件增量收集的（`StageDurationFacts.onEvent`，`AgentscopeAgentClient.java:393` 每事件实时掐表），异常时被主动丢弃。
- **实测推翻**：日志内 16:25:06→16:55:06 那条 30 分钟失败首试，`AgentTraceMiddleware` 全程 INFO 追迹显示执行体**每一两分钟完成一次推理-工具循环、16:54:34 还在委派 self-test**——模型流一直在完成，30 分钟里干了大量活，被墙钟整段作废、账面归零。「零产出超时」这个定性不成立，成立的是「**超时即弃账**」。

**顺带发现的独立真 bug（计量丢包）**：`met_usage_events` 校准显示**成功 run 也全部恰好只有 1 条计量事件**（连 llmMs=438s、755s 的 run 也是）。机制：`MeteringScope` 是 ThreadLocal（`AgentscopeAgentClient.java:326` 在 scope-session-3 线程 enter），而模型流装配/收口跑在 boundedElastic 线程（迹线实证：PRE_REASONING 首次在 scope-session-3、后续全在 undedElastic-8x），`MeteredModel.stream()` 的 `MeteringScope.current()`（`MeteredModel.java:48`）在弹性线程上取到 null → 不上报。**每 run 只有恰好落在订阅线程上的首个流被计量**，其余全部漏报。修 llmMs 弃置问题时此 bug 应一并开票。

## 三、代码链路定谳

1. **30 分钟墙钟 = blockLast 总墙钟**：`runTurn`（`AgentscopeAgentClient.java:334`）`blockLast(30m)`。reactor 源码（BlockingSingleSubscriber.blockingGet）：CountDownLatch `await(timeout)`——**从订阅到流终结的总时长**，超时 `dispose()`（取消上游）＋抛 `IllegalStateException("Timeout on blocking read for 1800…")`。日志实证 16:25:06.446 → 16:55:06.831 = 精确 1800.385s。
2. **框架「5 分钟超时＋3 次退避」实际从未重试过**：`ModelUtils.applyTimeoutAndRetry`（`agentscope-java/agentscope-core/.../model/ModelUtils.java:84-135`）沉默超时 fallback 抛 `ModelException(msg, model, provider)`——**无 cause、非 TimeoutException/IOException/HttpTransportException 子类**，`RETRYABLE_ERRORS`（`ExecutionConfig.java:99-128`）判为不可重试；传输层错误 `OpenAIException`（statusCode=null 的流失败）→ `isRetryableHttpStatus()`=false 同样不可重试。全日志 **`Retrying model request` 零次出现**。退避重试只覆盖「干净 HTTP 429/5xx 响应」——恰好是流式半途死亡不产生的错误形态。**假设 (b)（退避耗尽后空转）不成立：根本没有退避发生。**
3. **沉默超时不限总时长**：`.timeout(5min)` 是**信号间隔**超时——滴流（chunk 间隔 <5 分钟）永不触发；总时长无上限，只能等 30 分钟墙钟或 TCP 层死亡。
4. **工具执行有超时、无卡死面**：`Toolkit.callTools` 合并 `TOOL_DEFAULTS`（`Toolkit.java:514-521`）→ `ToolExecutor.applyTimeout`（`ToolExecutor.java:419-431`，5 分钟、1 次）。实证：成功 attempt 里 `agent_send: 300029ms`（精确 300s＝超时回给模型）＋模型随后 `task_cancel`——工具挂死 5 分钟被切、模型继续。**假设 (c)（容器命令卡死）排除**。
5. **压缩/记忆抽取失败被静默吞掉**：`CompactionMiddleware: "Compaction failed, continuing without compaction"`（10-14 20:07/21:16/22:49、10-15 00:51 四次 PT5M）——这些中间件模型流死亡不杀 attempt，但挤占墙钟。
6. **blockLast 计时器起点晚于订阅**：`blockLast` 先同步 `subscribe()` 再 `blockingGet(30m)`——订阅路径上的同步工作（中间件装配、状态恢复、管道内模型调用）**不计入 30 分钟**。实证：10-14 21:16:30 attempt 2 的 PRE_CALL 后 **109 分钟**才失败（23:05:38，传输错）、23:05:39 attempt 3 又 **106 分钟**（00:51:44）——期间整场连一次 PRE_REASONING 都没出现过，30 分钟界没掐，唯一兼容 reactor 语义的解释是计时器尚未起表。

## 四、错误签名分布（日志＋DB 时间窗）

- **签名 A**（墙钟族，5 条）：`Timeout on blocking read for 1800000000000 NANOSECONDS`——turn 30 分钟没干完被掐。日志内现场重建：**全程在干活**（推理循环间隙 77–190s＝正常长推理＋写码），尾部正在跑 self-test 被掐。定性：**不是故障，是预算不够**——中位成功切片净耗 15–25 分钟，30 分钟预算对尾部切片（叠加 self-test 5–8 分钟＋偶发慢流）天然不够。
- **签名 C**（提前失败族对应，日志内 3 条）：`HTTP transport error during streaming: SSE/NDJSON stream failed: java.io.IOException: Operation timed out`——macOS TCP 级重传耗尽（量级 ~15 分钟）或更快炸出。叠加签名 B（PT5M 沉默超时，在压缩/记忆调用上四次出现）＝ **DeepSeek 侧流式通道劣化的直接证据**：部分流全静默（5 分钟沉默超时兜住）、部分滴流（躲过沉默超时、死于 TCP 层或墙钟）。10-14 晚 21:16→10-15 00:51 同一片三连败转终态失败＝劣化窗口内重试也救不了（且重试间隔里无退避逻辑）。09-18/09-22/09-23 的 6 条提前失败（322–1123s）在日志窗外，按时长形态（322/371s ≈ 首流＋5 分钟沉默超时；816/824/1030/1123s ≈ 干一段活后流死）归并同族——**此归并是推测**（签名未亲见）。
- **假设 (a)（长流断连无错误 surfaced 直到墙钟）**：部分成立——「滴流躲过沉默超时」确凿（109 分钟案），但墙钟族主因是预算非断连。**假设 (d)（计量 bug）**：llmMs=0 本身是弃置口径（非「没跑」）；但计量 ThreadLocal 丢包是真 bug（§二）。

## 五、第二试为什么过（携了什么）

- 重试 prompt（`GenerationAppService.retryRunPrompt:163-166`）＝错误现场原文＋「**已落盘成果仍有效、不要重做、先检查现状再继续修完本段**」；对墙钟族，错误现场是平台内部超时消息（`Timeout on blocking read…`）对执行体近乎无用——**真正起作用的是三件**：同会话续跑（sliceSession 同 sessionId，执行体上下文在）＋工作区文件全保留＋**30 分钟预算重置**。16:55 案 attempt 2 接着 attempt 1 的现场 10.7 分钟收口（640s），现场连续性在迹线上肉眼可见。外加第三因素（推测但证据指向）：模型侧健康度在重试时点恢复。

## 六、修法选项表（带锚点，不拍板）

| # | 选项 | 层面/锚点 | 针对 | 代价/风险 |
|---|---|---|---|---|
| 1 | **超时改「进度感知」而非纯墙钟**：turn 内有事件流动（推理/工具完成）即续期，或墙钟提到 45–60 分钟＋保留进度判死（如 N 分钟无 POST_REASONING 才判死） | 平台 `runTurn`（`AgentscopeAgentClient.java:334`）或 `GenerationProperties.timeout`（:22） | 墙钟族（5/11，浪费 150 分钟） | 纯调大有「真挂死拖更久」风险，需与 #2/#3 配套 |
| 2 | **失败尝试保留耗时账**：catch 块改用 `prepared.durations().snapshot()`（事实已增量在手，`AgentscopeAgentClient.java:351` 一行）＋修 MeteringScope 线程丢包（reactor context 传播） | `AgentscopeAgentClient.java:348-352`；`MeteredModel.java:48` | 可观测性（本次「llmMs=0」疑案正是它的盲区） | 低工程量；观察面口径变更需同步 `StageDurationFacts` javadoc |
| 3 | **让重试真正生效**：超时 fallback 带 `new TimeoutException()` cause 或 retryOn 增认 ModelException/无状态传输错（`ModelUtils.java:88-92`、`ExecutionConfig.java:99-128`）；另给模型流加**总时长上限**（如 8 分钟，堵滴流） | agentscope-java 框架层 | 提前失败族＋109 分钟案 | 跨仓改框架；总时长上限需实测最长合法流（单迭代推理可达 ~3 分钟）定值 |
| 4 | **HTTP 层读超时/连接池健康检查**（WebClient responseTimeout/读空闲断连，替代等 TCP 15 分钟自然死） | OpenAIClient（openai 扩展） | 滴流/半死连接 | 需区分「慢而活」与「死」——过紧会误杀长推理 |
| 5 | **墙钟族错误现场重写**：超时类失败的重试 prompt 不带内部异常串，改携「已运行 X 分钟、最后进展是 Y（迹线可取）」的进展现场 | `GenerationAppService.retryRunPrompt:163` | 重试效率 | 中低 |
| 6 | **订阅路径纳入计时或加预检**：blockLast 前无界阻塞段（109 分钟案）至少加日志/上限 | `AgentscopeAgentClient.runTurn` | 109 分钟异常案 | 先观测（#2 落地后可见）再动 |

**证据确凿**：两族形态分布及精确值；llmMs=0 弃置语义；墙钟族「干活被掐」现场；零重试＋不可重试分类；工具 5 分钟超时在位；计量 ThreadLocal 丢包；压缩失败静默吞；重试三要素（同会话/文件保留/预算重置）。
**推测**：09 月 6 条提前失败的签名归并；滴流细节（chunk 间隔 <5min）为兼容性推断非直接观测；109 分钟案的订阅阻塞具体卡点（记忆/压缩管道模型调用最可疑）未定位到行。

另注：日志里还有第 12–14 条失败（10-14 夜三连败转终态失败，不在「32 成功切片」统计内）——**劣化窗口里 3 次重试全烧完、用户必须手工「继续生成」**，真实可靠性图景比 11/32 更差；修法 #3 对此窗口直接有效。
