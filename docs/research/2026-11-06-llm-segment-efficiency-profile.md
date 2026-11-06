# 调研快照：LLM 段效率画像——路由信号误判与 prompt 面积（2026-11-06）

> 结论关联：wayfinder #411（本票）、map #404（LLM 段效率，本票只回填本票不动画图正文）。锚案例＝生产项目 `376467559906353574`（2026-11-05，12 片，LLM 净合计 ~83 分钟）。
> 口径：**生产库只读 SELECT 实测画像，不做档位裁决**。样本＝全量 7 项目 77 张收尾卡（`prj_conversation_entries` kind=5）＋ 6544 条 executor 计量事件（`met_usage_events`，2026-10-29～11-05）。代码锚点＝本仓 2026-11-06 快照；锚案例所跑生成轨代码自 2026-10-30（65ae677 品牌更名）后未再改动，即**锚案例行为＝当前代码行为**。
> 方法注记：迭代数＝run 内 executor 计量事件数（MeteringScope 每次模型 `stream()` 收口恰一条，含 verify 委派子智能体调用——与执行体共享 run_id，故迭代数是「模型调用次数」口径而非纯执行体 ReAct 步数）。**单调用上下文＝input＋cache_read**（DeepSeek 协议映射 `AgentscopeUsageMapper`：input=prompt−hit、cacheRead=hit——单看 input 列会低估面积 ~6 倍）。llm＋verify 为「LLM 净相关」上界口径，两窗可嵌套交叠（§2.4 注）。生产库访问走 `docs/deploy/prod-db-access.md` 通道。

## 结论速览

1. **路由三信号在生产上只燃两个，且最弱的一环恰是漏判主力**：77 张收尾卡 attempts 全部＝第 1 次尝试第 1 轮——历史失败信号（重试/回滚升档）**零触发**；实际升档 18 次＝片复杂度 15 次（4～11 项）＋文件面 3 次（67/70/71 ≥ 60）。票面锚定的误判方向坐实：**片复杂度信号（切片描述枚举段数）对「描述短、功能重」的大片系统性低估**——全库最慢的 6 个 flash 大片（llm＋verify 16～38 分钟）signals 全部为 1～2 项 / 47～59 文件，无一越阈，全部被留在快档。
2. **快档不总快，且不是锚案例孤例**：flash 每调用确实快——实质 run（≥20 次调用）llm 秒/调用中位 **4.1s vs pro 5.2s（快 ~21%）**；但大片上 flash 迭代数爆炸（锚 ord8 365 次 vs 同项目两个 pro 片 79/92 次），每调用优势被 3～4 倍迭代数完全倒挂。全库 LLM 净相关最大片：**flash 38.3 分钟 > pro 22.0 分钟**；同项目内 flash 38.3 vs pro 8.2～11.7 分钟。flash 大片尾部的均值也被拖高（实质 run llm 均值 flash 398s vs pro 488s 的「pro 更慢」是选择效应——pro 接的全是难活，条件可比的只有锚项目内对照）。
3. **verify 委派窗是被票面低估的第三桶**：占 LLM 净相关时间 flash 37.5% / pro 31.8%；锚 ord8 单片 verify 窗 1164s **超过** llm 窗 1133s（占该片 51%）。verify 与主循环同模型同计量口径，模型/轮次预算是独立效率杠杆。
4. **prompt 面积真相＝票面前提已过时**：PRD **不内联**——锚日期版本（＝当前版本）每片 prompt＝任务句＋PRD 引用句（重读 `docs/PRD.md`）＋全量切片计划清单＋前片交接摘要，PRD/工作区文件/设计稿全走工作区自读（`GenerationAppService.segmentContext`）。实测单调用上下文中位 ~17K token、run 内**平坦或下降**（无随迭代增长曲线）、缓存命中 84～88%——**面积大头不是 prompt 拼装，而是迭代次数本身**（p50 83 / p90 154 / max 365 次）× 每调用全历史重发。
5. **压缩空间重排**：票面三候选中「PRD 按片裁剪」不适用（已不内联）、「工作区文件代替内联」已是现状、「摘要瘦身」边际（交接摘要占 prompt 比例小）；真杠杆三条——①**迭代密度**（与路由误判同源同治：大片 flash 迭代 3～4 倍爆炸既是时延主因也是面积主因）；②**大文件读入历史后的驻留**（45 次 >5 万 token 的单调用＝4.5% 总面积；fix run 实测 24.4 万 token×10 连发平台，compaction 阈值前的面积放大器）；③**verify 窗**（模型/轮次预算）。

## 1. 数据源与样本

| 项 | 值 |
|---|---|
| 收尾卡（含 attempts[] 与 modelTier 留档） | `prj_conversation_entries` kind=5，77 张（7 项目，2026-10-29～11-05） |
| 逐调用计量 | `met_usage_events`：executor 6544 条、main 168、naming 27、classify 13；模型仅 2 个实际在用：`deepseek-v4-flash-ga-260731`（5270 条 executor）与 `deepseek-v4-pro-ga-260813`（1274 条）——`doubao-flagship` 档位零调用 |
| 片与 signals | `prj_generation_segments`（ord/description/status/run_id）× closing.`modelTier.signals`（fileCount/sliceFeatures/attempt/round 快照） |
| 迭代数 | 每 run 的 executor 计量事件数（含 verify 委派子智能体调用，§口径注记） |
| 全局 executor 合计 | 6544 次调用、117.15M token 上下文（input＋cache_read）、2.77M output；单调用均值 17.9K token |

锚案例片序映射（票面编号 → 库 ord，run_id 供复核）：票「s10 拼写听力」＝**ord 8**（run `376482080907696408`，session `coder-…-slice-8`）；票「s6/s12」＝ord 5（单词本管理，features=5 → pro）与 ord 12（家长时长，fileCount=67 → pro）。票面「s10＝llm 1134s＋verify 1164s＝34 分钟」实为：llm 1133s＋verify 1164s＝2297s（LLM 净相关上界，两窗有交叠），run 墙钟 2028s＝33.8 分钟（票面 34 分钟是墙钟口径）。

## 2. 路由信号误判画像（Q1）

### 2.1 机制与留档（代码事实）

- 三信号任一越阈即升强档（OR 硬阈值，无评分层）：`attempt>1` 或 `round>1`（历史失败）、`fileCount≥60`（文件面）、`sliceFeatures≥4`（切片描述按 `[、，,;；/／+＋]` 切分的非空段数）；无信号回落人工档（EXECUTOR 座缺省 flash）。`ModelRoutingAppService.decide()`（aiplatform-server/src/main/java/com/semgravio/aiplatform/business/project/application/ModelRoutingAppService.java L158-202）；阈值/强档缺省见 V42/V43 迁移与 ADR-0030（含 #361 信号重定史）。
- 决策留档三面：closing.`modelTier`（档位＋来源＋升档理由＋signals 快照）、closing.`durationBreakdown.attempts[].modelId`（逐尝试档位）、结构化日志。无独立路由决策表（ADR-0030 明确拒绝）。
- 档位→模型（V44 种子，生产计量已验证在用）：flash＝volcengine:deepseek-v4-flash-ga-260731；pro＝volcengine:deepseek-v4-pro-ga-260813。

### 2.2 生产升档分布（77 张收尾卡）

| 升档路径 | 次数 | 明细 |
|---|---|---|
| 片复杂度 ≥4 项 | **15** | 4 项×8、5 项×3、6 项×2、10 项×1、11 项×1 |
| 文件面 ≥60 | **3** | 67（锚 ord12）、70、71（两个 fix run） |
| 历史失败（attempt/round） | **0** | 全部 77 run 均第 1 次尝试第 1 轮收口 |
| 回落 flash | 59 | — |

### 2.3 误留快档样本（flash 且 LLM 净相关 ≥15 分钟，全库降序）

| 项目 | 片 | llm＋verify | signals（项/文件） | 调用数 | 判读 |
|---|---|---|---|---|---|
| 376467（锚） | ord8 拼写题与听力题测试 | **2297s＝38.3min**（llm 1133/verify 1164） | **1 项 / 54 文件** | **365** | 全库最大片；描述只枚举 1 段但功能面＝双题型测试引擎；文件面差 6 个到阈 |
| 374842 | ord3 维护球馆、场地及可订时段模板 | 1535s＝25.6min | 2 项 / 49 文件 | 183 | 同型：多实体 CRUD 被描述压成 2 段 |
| 376641 | ord8 查看联系方式并提交合作意向 | 1370s＝22.8min | 1 项 / **59 文件** | 211 | 文件面差 1 个到阈 |
| 376467（锚） | ord9 到期复习提醒一键进入测试 | 1232s＝20.5min | 1 项 / 54 文件 | 176 | — |
| 376641 | ord4 管理企业档案 | 1171s＝19.5min | 1 项 / 47 文件 | 170 | verify 611s>llm 560s |
| 374391 | ord5 查看 K 线图并切换周期 | 964s＝16.1min | 1 项 / 47 文件 | 146 | verify 571s≫llm 393s |

共性：**信号全在阈值下方但实际工作量最大**；其中 3 片 verify 窗 ≳ llm 窗。对照组（pro 大片）最慢仅 22.0 分钟（374150「转接」features=4，133 次调用）与 18.4 分钟（376641「发布供需帖」features=4，178 次调用）——**flash 大片尾部（38.3）超出 pro 大片尾部（22.0）74%**。

### 2.4 flash vs pro 实际时差画像

实质 run（调用数 ≥20，n=69；剔除 6 个 ≤10 次调用的「空转快收口」——两档各 3 个，收口在 12～56s 内，属「活已被前片干完」形态）：

| 档 | run 数 | 均调用数 | llm 均值 | verify 均值 | llm 秒/调用（均值/中位） | LLM 净相关均值 | 最大片 |
|---|---|---|---|---|---|---|---|
| flash | 54 | 95 | 398s | 239s | 4.4 / **4.1** | 637s | **38.3min** |
| pro | 15 | 91 | 488s | 228s | 5.3 / **5.2** | 716s | 22.0min |

锚项目内对照（同 PRD/同代码基/同日，条件最可比的一组）：

| 片 | 档 | 调用数 | llm | verify | 合计分钟 |
|---|---|---|---|---|---|
| ord8 拼写听力 | flash | **365** | 18.9min | 19.4min | **38.3** |
| ord5 单词本管理（features=5→pro） | pro | 92 | 6.2min | 5.5min | 11.7 |
| ord12 家长时长（files=67→pro） | pro | 79 | 5.4min | 2.8min | 8.2 |
| ord3 手动录词（flash，次慢） | flash | 197 | 8.1min | 9.7min | 17.8 |

读法：pro 每调用慢 ~27%（中位 5.2 vs 4.1s），但在重活上以**少一半到四分之三的迭代数**收口，总时反而短一量级；flash 的「快」只在每调用层面成立，重活上被迭代数倒挂——**「快档未必总快」在生产上成立，且方向系统性偏向大片误留**。

> 口径注：llm 窗＝执行体模型等待、verify 窗＝委派子智能体墙钟，两窗可嵌套交叠（ord8 墙钟 2028s < 两窗之和 2297s），LLM 净相关合计是上界口径；锚项目 12 片 llm 合计 79.9 分钟（＋阶段 0 共 80.6≈票面 ~83 分钟）、verify 另计 56.9 分钟——**verify 不进「llm 桶」但同占墙钟，票面的 83 分钟低估了 LLM 段真占用**。

### 2.5 误判机理与信号缺陷归纳

1. **片复杂度信号度量的是「描述的枚举密度」，不是「功能的实现面积」**：主智能体「宁粗勿碎」写片（#355 已实证）时，一句话大功能（「拼写题与听力题测试（按熟练度切换题型）」＝1 项）与一句话小功能在信号上不可区分。#361 把信号从字数改成条目数修掉了「恒燃」（全升强档），但矫枉到「恒暗」一侧——生产 15 次升档全部来自描述写法枚举丰富的片，而真正最重的 6 个大片全部漏判。
2. **文件面信号 60 阈值卡在基座增长曲线的腰部**：生产切片期 fileCount 落在 30～59 区间的占绝对多数（锚项目全程 42→67 单调爬升），60 阈值只在项目后期才燃（3 次，其中 1 次是误判救场——ord12 features=1 靠文件面进了 pro）。59 文件的 22.8 分钟大片差 1 个文件被留在 flash。
3. **历史失败信号零样本**：77 run 无一重试/回滚，升档反馈回路在生产上从未闭合（好消息：一次过率高；坏消息：该信号对「首跑慢」无能为力，而首跑慢正是本票痛点）。

## 3. prompt 面积与迭代密度（Q2）

### 3.1 前提修正：PRD 不内联

票面「每片 prompt 内联 PRD 全文＋前片摘要」前半不成立：当前（＝锚案例当日）每片首试 prompt＝`slicePrompt()`＝任务句＋`segmentContext()`，后者＝PRD **引用句**（「先重读工作区 docs/PRD.md」）＋全量切片计划清单（每片一行）＋前片交接摘要（前片收口终文，无长度上限）＋交接约定 ~90 字（`GenerationAppService` L200-299）。PRD 正文、工作区文件、设计稿内容均不进 prompt——执行体经工具自读（设计稿只列标题＋路径清单）。「160 迭代/turn」的旧实测出处：research/demo-track 分支 `docs/research/demo-track-vs-production-split.md` L20/L26/L57（#313 报告，引 #25 活体对照与 `AgentscopeProperties#maxIters` 注释；上限 300 见 `application.yml` max-iters）。

### 3.2 迭代密度分布（77 run，含 verify 委派调用）

| 指标 | 值 |
|---|---|
| p50 / p90 / max | **83 / 154 / 365** 次调用每 run |
| 空转快收口（≤10 次） | 6 个（flash 3、pro 3；12～56s 收口，活已被前片干完） |
| 校准 | #313 的「~160 迭代/turn」≈ 本次 p90；重片可到 365（旧估值 ×2.3） |

### 3.3 单调用上下文与缓存（executor 全量）

| 指标 | 值 |
|---|---|
| 单调用上下文（input＋cache_read） | run 均值 p50 **17.1K** / p90 20.7K；全局均值 17.9K；单调用最大 **244.5K** |
| 缓存命中 | flash **83.7%** / pro **87.6%**（命中部分成本面已缓解，时延面仍有重发开销） |
| >5 万 token 的大调用 | 45 次＝5.28M token＝**4.5%** 总面积（117.15M） |

### 3.4 上下文增长曲线实测：平坦为主，锯齿为害

- **切片 run 内不增长**：锚 ord8（365 次调用）按时间十等分的段均上下文 15.7K→18.9K 平坦波动（段内 max 34.9K）；锚 ord3（197 次）反而**下行**（首段 28.1K→末段 11.1K）——ReAct 历史经框架压缩/裁剪后，单调用面积稳定在 ~15-20K 平台，不存在「第 N 次调用 vs 首次」的膨胀曲线。
- **fix run 呈典型锯齿**（376624920395538414，102 次）：6.9K 爬到 41K → 截断回 5K → 再爬 38K → **一次大文件读入使 10 连发平台在 235-244K** → compaction 截断回 21K → 之后 ~25K 周期锯齿。compaction 触发阈 400K 是**框架字符估算口径**（中文低估 1.25-2.5×，`AgentscopeProperties` L47-56），实际 token 平台可坐实在 24 万级——大文件读入后的历史驻留是唯一实测到的面积放大器。

### 3.5 压缩空间评估

| 候选（票面） | 判定 | 依据 |
|---|---|---|
| PRD 按片裁剪引用 | **不适用** | PRD 已不内联（§3.1）；引用句只占 prompt ~20 字 |
| 交接摘要瘦身 | **边际** | 摘要＝前片收口终文，相对 15-20K 的单调用平台与 117M 总面积占比小；且摘要质量直接决定跨片连续性 |
| 工作区文件代替 prompt 内联 | **已是现状** | PRD/文件/设计稿全部工作区自读；prompt 只带引用句与清单 |

真杠杆（按实测面积/时延排序）：

1. **迭代密度**（与 §2 同源同治）：大片 flash 365 次调用×17K 上下文重发＝单片 6.2M token 面积与 38 分钟时延的主体。路由修好（重片进强档）迭代数减半即同时砍时延与面积——一石二鸟，优先级最高。
2. **大文件读入的驻留控制**：大调用只 45 次却占 4.5% 面积，且单次可把后续 ~10 次调用抬到 24 万 token 平台。候选：read_file 大结果读后摘要/滚动丢弃（工具结果裁剪，SHELL_OUTPUT_LIMIT 10 万字符仅管 shell 不管历史驻留）、或 compaction 阈值按真实 token 计（修中文估算偏差）。
3. **verify 窗**：占 LLM 净相关 32-38%（ord8 51%）。verify 子智能体与主循环同模型同预算，重片 verify 窗可超过 llm 窗——模型档/轮次预算/触发条件是独立杠杆（例：verify 用边缘点档、或信任前片自测结果跳过重复 verify）。
4. （次级）**空转快收口的判别**：6 个 12-56s 收口片两档都有，「活已干完」与「假完成」在前端不可分（收尾卡不留判别依据）——效率画像的噪声源，非本票主诉。

## 4. 数据附录（关键查询结果）

附录 A：锚项目逐片画像（ord→档/调用/llm/verify 分钟，13 run）：

| ord | 档 | 调用 | llm | verify |
|---|---|---|---|---|
| 0 阶段0 | flash | 13 | 0.7 | 0.0 |
| 1 界面骨架 | flash | 50 | 3.0 | 2.1 |
| 2 注册登录 | flash | 89 | 4.1 | 3.1 |
| 3 手动录词 | flash | 197 | 8.1 | 9.7 |
| 4 OCR 录词 | flash | 29 | 2.6 | 0.0 |
| 5 单词本管理 | **pro** | 92 | 6.2 | 5.5 |
| 6 学单词 | flash | 96 | 4.6 | 2.8 |
| 7 选择题测试 | flash | 1 | 0.1 | 0.0 |
| 8 拼写听力测试 | flash | **365** | **18.9** | **19.4** |
| 9 复习提醒 | flash | 176 | 12.8 | 7.7 |
| 10 绑定孩子 | flash | 111 | 6.3 | 1.2 |
| 11 家长进度 | flash | 158 | 7.8 | 2.6 |
| 12 家长时长 | **pro** | 79 | 5.4 | 2.8 |

（另有 2 个 fix run：pro，82/102 次调用，llm 7.1/8.8 分钟，其一出现 §3.4 的 24 万 token 平台。12 片 llm 合计 79.9 分钟≈票面 83。）

附录 B：全库 77 run 的 attempts×signals 明细即 §2.2/§2.3 表；逐 run 数据可由 §1 三表按 run_id 复算。

## 5. 证据清单

- 代码：`aiplatform-server/src/main/java/com/semgravio/aiplatform/business/project/application/ModelRoutingAppService.java`（decide/featureCountOf/阈值）、`CoderRunAttempts.java`（attemptPayload/durationBreakdown 装配/路由调用点）、`GenerationAppService.java`（slicePrompt/segmentContext/交接落盘）、`ModelTierAppService.java`（档位解析）、`base/agentscope/AgentscopeProperties.java`（maxIters=300 注释/compaction 400K 口径）、`base/agentscope/MeteringScope.java`（逐流上报粒度）、`base/agentscope/AgentscopeUsageMapper.java`（input/cacheRead 口径）、`src/main/resources/application.yml`（max-iters: 300）。
- 迁移/ADR：`db/migration/V42__model_routing.sql`、`V43__model_routing_slice_feature.sql`、`V44__model_provider_channel.sql`（档位种子）、`V2__conversation_history.sql`（kind=5）、`V14__generation_segment_track.sql`；`docs/adr/0030-model-routing.md`（三信号/拒绝评分制/拒绝决策表，#361 修订史）。
- 旧调研：`docs/research/demo-track-vs-production-split.md`（research/demo-track 分支，#313——「~160 迭代/turn」「LLM 31%＋间隙 22%、工具仅 2.5%」）。
- 生产库（只读，2026-11-06 查询）：`prj_conversation_entries`（kind=5）、`prj_generation_segments`、`met_usage_events`。
