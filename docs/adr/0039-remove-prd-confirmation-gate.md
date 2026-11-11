# 去确认门：首 Demo 触发平移＋版本自动记行＋取版读法归一

去确认门·上（2026-11-11，#462，承 #457 决策正本「机械守卫全部退役」）。退役对象＝ADR-0008 确立的整套确认门机制：#330「确认即成版」（POST /projects/{id}/prd/confirm 端点）、#331 两级门（PRJ_053 阻碍级拦截）、#332 生成加门（确认触发首个 Demo run）、#334 大改停派（parkDraftChange/consumeParkedDraftChange 停驻两列读法）。用户「确认此版 PRD」动作整段不复存在，PRD 产出即自动进入生成。定案四件：

**首 Demo 触发平移**：首个构建 run 的唯一触发从「确认动作」平移到「首次产 PRD」——`savePrd` 工具落盘回调（`PrdArtifactAdapter.onWritten`）观测 `prdProducedAt` 首次置位，即派首个构建 run（`GenerationAppService.dispatchFirstDemoOnPrdProduced`，原 `dispatchFirstDemoOnConfirm` 改名——派发本身幂等，已生成/在途静默跳过）。判定跟职责走：产出动作的发生地即派发触发点（「产出即自动推进」，与 #461 收敛令后「补全直陈」同向——平台判明确即动手，不再等用户按键）。轮收口观测（`MainAgentAppService.dispatchOnTurnClose`）再兜一轮：PRD 已产出＋未生成即派（覆盖平台轮/补产链/回调失败的兜底面），撞在途静默跳过。构造环破法：`PrdArtifactAdapter → GenerationAppService` 以 `@Lazy` 注入（ProfileToolkitSupplier→适配器→生成编排→CoderRunAttempts→AgentClient 回环），同 GenerationAppService 收 `MainAgentAppService` 的既有先例。

**首 Demo 回调不兜计划缺失（收口精化，同票落地）**：产 PRD 同转内协议有序（`savePrd`→`saveBuildPlan`，AgentProfile 第 5 条），回调发生在同转进行中、切片计划必未落轨道表——若回调侧照走计划缺失补产兜底，补产轮排在同转之后空跑一整轮主智能体（同转收口本携 `buildPlanFacts` 再派，补产纯烧 token＋对话面多一条无意义补产回复；live 冒烟实测命中此路径）。故 `dispatchFirstDemoOnPrdProduced` 在计划未落时静默返回 null，**补产兜底归轮收口单点**：标准路径同转收口计划交接物在场、直派全链零空烧；模型未守协议（收口无计划）时收口侧照常补产。派发从两处收敛为「收口单点为主、回调仅计划在场时先行」——与 ADR-0032 轮收口单点原则同向。

**版本自动记行**：`prj_prd_versions` 去门化（V60 加 `run_id` 列）——Demo 类 run（首构建/修正重建）起跑自动记行：快照当时工作区 PRD 全文＋来源 run_id，版本号＝**构建基准序**（第 N 个构建基准快照）。成版判定正本＝快照全文比对（同文幂等跳过，不产生新行——锚随每次 savePrd 刷新不作数）；全文来源＝PRD 读端点同缝（ProjectQueryAppService.prd，PRJ_015/WSP_002 口径复用）。记行 best-effort：PRD 读不出如实跳过（次生事实不反噬 run）；并发撞 (project_id, version_no) 唯一约束败者回读比对，同文即幂等。调用点两处：首构建派发（GenerationAppService.dispatchGeneration）与修正 run 起跑（IterationAppService.startFixTrack）——先记行、后捕获 Demo 版本锚（`latestPrdVersionNo`），故新草稿在修正 run 起跑时即成新构建基准。

**机械停派退役**（#334 读法注销的连贯后果）：`parkScopeDraftOrAwait/parkDraftChange/consumeParkedDraftChange` 删除——修正 run 需求基准恒为工作区 PRD 正本（`fixRunPrompt` 单形化，不再两形重载）；`PrdDraftBaseline` 组件整体注销（漂移判定失去最后消费者）；停驻两列 `prd_draft_opinion/prd_draft_summary` 留库不删、无人读写（Flyway 只增不改）。收口语义：已生成分支受理即派（范围级新草稿照旧派修正 run——受理即迭代，与 #360 意见载荷链同律）；未生成分支 PRD 已产出即派首次 run。PrdReadiness 阻碍级语义删除：`blocking` 恒空（响应形状保留兼容）、「阻碍/次要」锚只作界标、存量旧分组标题的条目落入统一待确认池。

**取版读法归一**：「最新已确认版」四处读法统一改读「最新版」（本表最新行，语义顺延、比对逻辑不动）：下单冻结（ORD_018 判据，#335 双确认引用集 `latestVersionRefs`）、归档沉淀取版（`OrderAppService.archivePrdContent`）、Demo 版本锚（`latestPrdVersionNo`）、迭代需求基准（工作区正本，不经版本表）。既有订单合同数据兼容：冻结引用集自含、不依赖版本表存亡。

## 补记（收口契约衔接，同票落地）

- **ADR-0032 收口契约**：「合法收口形二＝PRD 已产出」的达成判定不变（savePrd 事实），但其派发含义从「确认后再派」变为「产出即派（回调触发）＋收口再兜」——读收口代码会看到未生成分支无门判据。
- **ADR-0036 纠偏链**：「平台判可出 PRD 的自动落 PRD 兜底」形态裁决为**统一纠偏微指令**——CORRECTIVE_REDIRECT_PROMPT 一条覆盖「正文提问」与「说可出 PRD 但未调 savePrd」两形（不解析自由文本），纠偏轮①②不分形；prdSaveFailed 不再选指令、只进播报文案分形。
- **前端**：确认门组件（两段式摘要层＋useConfirmPrd）整段退役；首个构建基准记行（版本清单空→非空）即自动切 Demo 体验页（seen-ref 先例：仅转换点跳一次、可切回）；徽标/阶段门文案随「构建基准序」顺延（判定函数不动）。

## Considered Options

- **保留确认门、只把触发挪到收口**——拒绝：门的心脏是「用户动作触发」；只挪触发不留确认动作，门退化为无触发器的空壳，不如整段退役干净（#457「机械守卫全部退役」正本同向）。
- **版本记行挂生成收口（而非起跑）**——拒绝：起跑记行使「修正 run 的新草稿＝新构建基准」即刻成立（#333 Demo 版本锚读起跑时基准的需求）；挂收口则修正 run 全程基准悬空，与「版本号＝构建基准序」词表矛盾。
- **PrdDraftBaseline 换源（按 #450 字面）**——拒绝：记行先行后，漂移判定（草稿 vs 基线）失去全部消费者（fixRunPrompt 单形化、停派退役）——换源是给死代码续命；注销是停派退役的连贯后果。
- **停驻两列随退役删除**——拒绝：Flyway 只增不改（存量列留库不删、无人读写）；列删除若需整理归独立迁移票。
- **判可出的兜底走自动落 PRD（平台代调 savePrd）**——拒绝（用户裁决）：代写 PRD 越过主智能体产出职责、且内容质量无把关；确定性微指令把选择权交还模型，与 ADR-0036 纠偏链同构。
