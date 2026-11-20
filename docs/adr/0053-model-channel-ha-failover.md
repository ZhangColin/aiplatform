# 模型通道高可用：账号接入实例、候选队列与链式 failover（内层重试收口平台独占）

模型通道高可用裁决（2026-11-19，spec #552 grilling 定稿；承 ADR-0031 通道化基座〔本票 ③ 种子翻转即火山下线收尾〕、ADR-0030 模型路由〔升档与 failover 两轴正交〕、#321 流层重试平台独占〔本票 ① 是其在 OpenAI 兼容工厂路径的补漏〕）。动因一：2026-11-19 火山方舟 apiKey 供应商侧被删、ark 全线 401，单 key 失效即平台对话面中断——目标是对单一供应商/账号故障免疫。动因二：同日实证 agentscope 2.0.1 内层重试关闭失效（#552 ①），字节码三连实证：平台 `INNER_RETRY_DISABLED` 压的 `maxAttempts=1` 确实到达模型 configuredOptions；但内核 `ReActAgent.assembleModelConfig` 无条件构造 modelConfig（maxRetries 缺省 3）经 `buildGenerateOptions` 烧进每次调用的 callOptions，`ModelUtils.applyTimeoutAndRetry` 的 `mergeOptions(callOptions, configuredOptions)` 第一参字段级优先压过之；且 retryOn 为 null 时内核挂恒真 filter（401 也被重试），耗尽抛 `RetryExhaustedException` 裹走真错误——上游截至 2.1.0-BETA1 全未修（open bug #2861、修复 PR #2862 未合并）。定案：

**① 内层重试关闭补漏——框架旋钮而非包装对抗**：`buildAgent`/`subagentFactory` 两处 `HarnessAgent.Builder.maxRetries(1)`（经 modelConfig.maxRetries→callOptions executionConfig，与平台 configuredOptions 一致）；配「非 null callOptions 带 `ec(maxAttempts=3)`」注入用例盖住回归缺口（现测试传 null，从未复现内核构造 callOptions 这一层）。fallback（`RetryingModel` 内圈剥 executionConfig）记票备查；不走升级修缺陷（上游没修＋2.0.4 删公共 API——跟随升级另票评估）。

**② 通道行＝供应商账号接入实例**（非任意 key 池）：一行＝endpoint×账号 apiKey（`deepseek`=账号A、`deepseek-2`=账号B，同 baseUrl、各自 key/余额/并发配额）。领域事实：供应商账户内多 key 共享余额、并发限制是账户级——同账号多 key 无备胎价值，不特判建模（真要配就多建行）。加 **vendor 列**归一供应商（deepseek/deepseek-2 同填 deepseek）：按账号统计＝按通道行（计量 provider 腿）、按模型统计（跨账号同名模型合并）＝按 vendor+model；单价表与峰谷规则匹配键 provider→vendor（供应商定价本就是 vendor 级——新账号行零价格复制）；`met_usage_events` 落库冗余 vendor（通道行将来可删、历史事件维度须自持——volcengine 行已删的教训），历史行回填同值。

**③ 模型配置（旧称档位）＝有序候选队列**：主备配在档位（使用侧引用档位键、备选须匹配能力档）；形态＝子表 `prj_model_tier_candidates`（tier_key、provider_key、model_name、priority、enabled），主备只是队列前两名；档位行现列保留＝首选候选（resolve 读面兼容、迁移软着陆）。failover 收口在**模型包装层**（新 FailoverModel 持候选队列、懒装配候选），链式升级语义：候选内 `RetryingModel` 按 #321 分类重试（超时/IO/5xx/429）→ 401/403/402（认证失效/余额）跳过候选内重试立即切、候选内耗尽亦切 → **首块未流出才切**（已发块后中断、保「可重发」——与 #321 已发块护栏同界）→ 全队列尽报「本轮回复中断」＋末次真错误（① 修后真错误裸露）。压缩档/子智能体/边缘点等五个 resolve 面免费获得同一切换语义（不侵入 run 编排层）。切候选写 append-only 切换留痕表（何时/从哪到哪/触发错误/run 上下文）——烧备账号运营可见。

**④ V79（②③ 同迁移落）**：volcengine 通道行/档位行幂等清理（先子后父；生产已手工清＝no-op，开发库 V44 残留一并收敛）；全新库首启种 deepseek 单通道行＋flash/pro→deepseek 官方模型（与生产手工切换后一致、与单价行模型名对齐），不种 volcengine/doubao-flagship；② schema（候选表、vendor 列、计量回填）同批落——V44 不动（Flyway 只增不改）。`ModelTierSeed`/placeholder/部署文档同步。

**后台 UI 面**（admin 三连票：本仓 API→semgravio-admin 对接→admin-web 伴侣）：vendor 受控下拉＋可新建（不建独立供应商维护页——防脏够用，档案管理是伪需求）；创建通道 vendor 下拉、base_url 按 vendor 预填、key 掩码粘贴；远端模型清单从「对照页」重做为「选择源」；模型配置的所属通道/实际模型名改级联下拉不手填。概念名「模型配置」UI/词条/ADR 即换；代码层全链改名（ModelTier/tier_key、API 路径、既有引用）另票挂投产前窗口。

## Considered Options

- **key 池表（通道×key 两层主备）**——拒绝：两张新表、嵌套展开、key 级计量不可见（同 provider 前缀）还得给计量加腿；「key 即通道行」把账号级故障与供应商级故障统一进同一候选序列——切换单元恰好是故障的物理单元（endpoint×key）。
- **档位行加备胎列**——拒绝：单备胎天花板（限额/并发场景多 key 轮换是真实预期）、无行级启停/留痕粒度；候选子表无上限、与留痕表风格一致。
- **通道层挂主备（备通道指针全档共享）**——拒绝：档位是能力档（#454 升档在档轴上），pro 的备不能是 flash 级模型——备选必须按能力档配，通道层无能力语义。
- **升级 agentscope 修 ①**——拒绝：三缺陷上游至 2.1.0-BETA1 未修（各版本源码 diff 实证）；2.0.4 删公共 API（`OpenAIMessage.cacheControl` 等）＋ReActAgent 并发模型变化，升级回归独立成票（未投产窗口期跟随升级是方向）。
- **RetryingModel 内圈剥 executionConfig**——备选 fallback：对抗 merge 语义的包装 hack；框架旋钮已够（平台未用事件面改 options），旋钮失效再启用。
- **已发块后整段重发（用户面无感到底）**——拒绝：重发＝输出重复/倒带，体验劣于既有「可重发」中断；续传拼接需上游前缀续写不可行。首块边界与 #321 已发块护栏同一条线。
- **通道健康熔断与恢复探测**——非目标另票：无状态线性 failover 先行（单机演示阶段、通道数个位数、一次额外 401 失败延迟对秒~分钟级生成请求占比可忽略）；熔断状态机/冷却/探测等有真实通道数再上。
- **failover 硬性消费上限（通道级日限额自动停）**——非目标另票：停机语义（停哪档/多久/谁恢复）是独立设计题；本票软面＝切换留痕＋按账号计量可见（备账号被烧运营即见）。
- **vendor 独立维护页（供应商档案 CRUD）**——拒绝（demo 阶段）：vendor 的真实需求是防脏数据（错字→单价 miss/统计分裂），受控下拉＋可新建够用；档案管理（商务信息）投产前运营化再议。
- **failover 决策侵入 run 编排层（resolve 面各自循环切候选）**——拒绝：压缩/子智能体/边缘等五个 resolve 面要各自实现切换；模型包装层收口一处生效全局。

## Consequences

- 单通道/账号故障（401/403/402/5xx/超时）下平台命令面持续可用、无人工改档；全部候选耗尽才报中断、文案带末次真错误（不再被 `RetryExhaustedException` 裹走）。
- 内核重试面＝1，重试与切换全在平台层（#321 独占原则闭合）；agentscope 升级后此语义须回归验证（升级票验收项）。
- 计量双维度：`met_usage_events` 加 vendor 列（事件落库冗余）；按账号（provider）/按模型（vendor+model）统计双原生；单价表/时段规则匹配键迁移 provider→vendor（数据同值映射，历史成本不漂移）。
- 「模型档位」概念正名「模型配置」：词条/ADR/UI 即换；代码与库层（ModelTier/tier_key、API 路径、既有引用）随改名票投产前统一——期间文档他处「模型档位」字样＝旧称指同物；英文名定稿 ModelConfig／config_key、引用腿列族（model_id 等）随改名名实对齐（#554）。
- V79 后 dev/test 库与生产收敛同态（volcengine 零残留）；`ModelTierSeed` 假 key 改挂 deepseek 行、`FLASH_MODEL_STRING` 去 volcengine 前缀。
- 后台接口变更→semgravio-admin 对接票互链、admin-web 伴侣票随后（admin 仓惯例）；demo-stage-model-tier.md 改档 SOP 随 ② 更新。
- 验收面：WireMock 假 OpenAI 端点（默认车道单测断言请求次数/切换序列/真错误裸露——CI 可跑）＋live 一门真通道假 key 注入（按 live 车道约定票内留痕）。
