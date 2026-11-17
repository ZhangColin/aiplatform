# 四家 AI 生成平台「生成失败/中断时的费用处理」取证（#513）

- 日期：2026-11-15
- 服务对象：wayfinder 票 #513（余额与扣减域模型＋计费面）——Q5「失败也扣」改判的竞品佐证与风险面输入；退款分界口径（D4）的直接来源。
- 取证范围：官方文档/help center/定价页/changelog＋官方人员在社区论坛的回复，均为外部公开一手来源，每条附 URL。

## 一、Replit（Agent checkpoint / effort-based 计费）

**1. 生成失败扣不扣？有无退款？**
- 扣。官方明文："Agent and AI usage charges are non-refundable, because each charge reflects compute that Replit already used to run your tasks."（Agent/AI 用量不退款，因为每笔费用对应 Replit 已实际消耗的算力。）理由口径＝「算力已花掉」。来源：<https://docs.replit.com/help/billing-and-refunds>
- 人工例外通道：仅 "charge was made in error"（计费错误）可带 payment intent ID 找 support 人工核查；订阅费退款与用量退款是两条线（用量一律不退，订阅费 30 天内个案评估）。同上来源。

**2. 用户主动中止/取消？**
- 官方文档无「中止不计费」条款（查不到）。回滚（rollback）只恢复项目状态、不退已扣 credits——官方文档未承诺回滚退款，社区与第三方一致确认「Rollback restores files...credits spent on later checkpoints（不退）」。来源：<https://docs.replit.com/billing/ai-billing>、社区实证 <https://www.reddit.com/r/replit/comments/1ioanzd/ive_run_out_of_credits_due_to_replit_getting>

**3. Checkpoint 消耗型产品对「没产出的那次」怎么算？**
- 2025-06 起 effort-based pricing：每请求至多一个 checkpoint，按消耗算力计价；文档明示 "Plan Mode can perform billable reasoning even when it does not change code"（纯推理不改代码也计费）——即「没改出代码」≠「不计费」。来源：<https://docs.replit.com/billing/ai-billing>、<https://replit.com/blog/effort-based-pricing>
- 例外＝平台自身计费事故：2025-07-11 计费计算错误导致多扣（影响约 6% 付费用户），Replit 官方博客承认并**自动全额退款/补偿**（"There is nothing that these users need to do to initiate the refund. The total cost of all impacted checkpoints will be reimbursed."），另全员送 $10 credits。来源：<https://replit.com/blog/effort-based-pricing-recap>

**4. 「失败重试免费/只对成功收费」承诺或反例？**
- 无任何此类承诺。反例充分：Reddit「Why the 'Mistakes' Might Be the Business Model」直指「修 Agent 自己搞坏的东西也要花 credits」且官方文档确认无退款政策（<https://www.reddit.com/r/replit/comments/1llxxzf/replits_ai_agent_why_the_mistakes_might_be_the>）；「I've run out of credits due to Replit getting things wrong」同类。

## 二、Lovable（message/credit 计费）

**1. 生成失败扣不扣？有无退款？**
- 条款面：订阅费与 credits 一律不可退（"Subscription fees and credits are non-refundable, except where the law requires a refund"），但 support 会个案审查 "billing errors"（计费错误）。人工申请、非自动。来源：<https://docs.lovable.dev/introduction/support-policy>
- 官方文档**无**「失败消息自动返还 credits」条款（查不到）；credits-and-usage 页 FAQ/正文通篇没有失败退款机制。社区抱怨存在："roll back changes but don't get their credits back"（<https://www.reddit.com/r/lovable/comments/1orj7gc/>）。信用余额里存在 "Bonus credits from Lovable support" 类型——个案补偿通道的间接证据。

**2. 用户主动中止/取消？——四家中唯一有精确成文规则**
- 官方明文："**Stopped Build mode requests are charged based on the work completed so far. A request you stop before Lovable has made any change to your project is not charged.**"（中止按已完成工作量计费；未产生任何变更前停止则不计费。）来源：<https://docs.lovable.dev/introduction/credits-and-usage>
- 渐进式规则（按工作量比例结算＋零产出零收费），非一刀切。

**3. 「没产出的那次」怎么算？**
- Build 模式按工作量计费（非按结果质量）；配套两个缓冲机制：(a) **Try to fix 免费 10 次**（build error 修复与安全修复共享池，用完 24h 逐个回充，超出后按普通消息计费）——变相把「修复失败型消耗」部分免费化（<https://docs.lovable.dev/introduction/support-policy> FAQ「Does Try to fix cost credits?」）；(b) 长任务 **check-in 门槛（默认 20 credits）**，单消息超阈值自动暂停等你确认再继续——把超支矛盾前移（<https://docs.lovable.dev/introduction/credits-and-usage>）。

**4. 承诺或反例？**
- 无「失败免费」总承诺；反例＝上面 Reddit 回滚不退帖。但「10 次免费修复」是四家中最接近「失败补救免费」的显式机制。

## 三、Bolt.new（token 计量计费）

**1. 生成失败扣不扣？有无退款？**
- 扣（token 按 "reads, thinks about, and builds" 计量，与结果脱钩）。官方 help center 的 Tokens/Billing/Troubleshooting 三页**均无失败退款条款**。来源：<https://support.bolt.new/account/tokens>、<https://support.bolt.new/troubleshooting/issues>
- 订阅费明文不退："Bolt subscriptions are non-refundable, as stated in our Terms of Service."（<https://support.bolt.new/account/billing#refunds>）
- 退款实务＝完全人工裁量：第三方 Afterbuild Labs 评述其浪费 token 退款为 discretionary（不保证）；用户只能发 support@bolt.new 个案求助。

**2. 用户主动中止？**
- 官方文档无任何条款（查不到）。

**3. 「没产出的那次」怎么算？**
- 纯 token 计量：失败/跑偏的 prompt 消耗的 token 照扣，官方无补偿机制文案。唯一沾边的产品事实：Code view 手工改代码/跑命令不耗 token（免费逃生通道）。

**4. 承诺或反例？**
- 无承诺。反例最重：HN「Ask HN: Startup launch destroyed by Bolt.new's AI. 10M tokens」（<https://news.ycombinator.com/item?id=46321594>）；Reddit「Bolt saying it did something when it didn't」要求对假修复退 token（<https://www.reddit.com/r/boltnewbuilders/comments/1h5kcvv/>）；Trustpilot 多条「同一问题反复烧 token 无退款通道」差评（<https://www.trustpilot.com/review/bolt.new>）。

## 四、v0 / Vercel（credit 计量计费）

**1. 生成失败扣不扣？有无退款？**
- 扣。官方 FAQ（2026-03 更新，口径最新）原文名词条目："**Why do v0 agents keep failing and consuming my credits?** — The v0 agent is constantly improving. If you're experiencing repeated failures, please send us feedback so we can investigate."——官方承认失败会吃 credits，答案只给「持续改进＋反馈」，无退款承诺。来源：<https://v0.app/faq>（canonical <https://v0.app/docs/faqs>）
- 退款＝人工申请通道：Vercel 员工（Vercel Alum Pauline）在社区多次回复 "feel free to get a refund on credits at vercel.com/help"，且有用户实测确认成功（"It was very easy. I requested an amount roughly equal to the bad performance"）——个案、按用户申报金额、无成文政策。来源：<https://community.vercel.com/t/is-fix-with-v0-free/27771>
- 退款范围仅限 credits/未用额度层面，与订阅退款分开（v0.dev/agreement AI Product Terms）。

**2. 用户主动中止？**
- 官方文档无条款（查不到）。

**3. 「没产出的那次」怎么算？**
- credits 预付余额制：generations 从余额扣，余额耗尽生成暂停（<https://v0.dev/docs/pricing>）。失败运行不自动返还（第三方评测同样确认 "credits consumed by failed agent runs are typically not reimbursed"）。

**4. 承诺或反例？——有「免费修复」机制且翻过车**
- v0 有 "Fix with v0"（生成报错后免费修复）机制，但 v0 团队成员 Zeb Hermann 官方承认：因早期被滥用，加了**每日免费次数上限**（"99% of our users never hit that limit"），且 "the UI is not clear about when you're past that limit...We are fixing that shortly!"——承诺免费但 UI 不透上限，导致大量「Free 修复被扣费」投诉（用户贴出账单明细：应 FREE 的操作实扣 $0.54-$2.51）。同帖另一官方人员要求用户去 billing history 核查 free fixes "were not reimbursed"。来源：<https://community.vercel.com/t/is-fix-with-v0-free/27771>
- 其他反例：agent 卡重试循环 7-9 分钟烧 credits 且产出语法错误（同帖多条，含 message ID 级举证）；Reddit r/vercel「v0 is f\*cked」「My credits ran out in less than a day」。

## 对我方的启示（收敛模式）

**1. 「失败也扣减」是行业基线，四家无一例外。** 计费锚点全部是「已消耗的算力/token/工作量」，与结果好坏脱钩。Replit 把理由写成官方口径（算力已花、不可逆）；v0 甚至在 FAQ 里坦然承认 agent 失败照吃 credits。我方「失败也扣」与全行业一致，不构成竞争劣势。

**2. 但竞品无一「裸奔」，失败计费普遍配三件套缓冲（收敛的配套模式）：**
- **中止的渐进式结算**：Lovable 的「按已完成工作量计费、零变更零收费」是四家中唯一成文的中止规则，也是口碑最干净的写法——一刀切「中止也全额扣」在四家中反而找不到先例。
- **修复型免费额度**：Lovable「Try to fix 免费 10 次/24h 回充」、v0「Fix with v0 免费（带防滥用日限）」——两家都把「修复失败/修自己的错」这一最大抱怨点部分免费化。v0 的教训是：免费额度必须把上限和扣费时机做透明，否则「承诺免费却扣费」比「从不承诺免费」伤害更大。
- **消费控制前移**：Lovable check-in 默认 20 credits 暂停、多家有 usage limit/alerts；Replit 推荐 Plan mode/Economy mode。竞品把矛盾消化在「别让用户失控烧钱」，而不是事后退款。

**3. 退款通道收敛为两条清晰的线：**
- 模型能力不足/结果不好 → **一律不退**（四家一致，只给 "send feedback"）；
- 平台过错（计费算错、误扣）→ **人工个案通道**（Replit support 带支付凭证、Lovable billing errors、Bolt support@bolt.new、v0 vercel.com/help），唯一全自动退款先例是 Replit 2025-07-11 计费事故（平台自身错误→精准定位受影响用户→自动全额退＋全员 $10 补偿）。
- 我方分界线照抄即可：「平台判定为自身故障的扣减→返还；生成质量不达预期→不退」。

**4. 主要风险不是「失败扣费」，而是三个放大器**（四家社区骂声的共同根源）：(a) 让用户付费修平台/模型自己搞坏的东西（"business model" 级质疑，三家全中）；(b) 免费承诺与实际扣费不一致（v0 fix-free 上限不透明事故）；(c) 自动重试循环无人为上限地烧 credits（v0 retry loop 9.5 分钟）。我方落地「失败也扣减」时已配套：失败原因收尾卡透明标注（失败留账先例既有）、自动重试照扣但重试上限既有机制封顶。

## 与 #513 决议的对应

- D4「失败也扣、停止也扣」← 启示 1（行业基线）。
- D4「退款分界：平台故障→人工返还；质量不达预期→不退」← 启示 3（两条线）。
- D4 两条非机制约束（失败原因透明标注、重试上限封顶）← 启示 4（三个放大器的对策）。
- 启示 2 的三件缓冲（中止渐进结算/修复免费额/消费暂停线）→ 不进本期，随转化数据再议（地图雾区，见 #510）。
