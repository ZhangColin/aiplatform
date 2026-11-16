/**
 * Billing Context（business.billing）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>充值余额计费域（ADR-0047，#531 底座）：双池（账户余额/项目余额）单源
 *       扣减——扣减源一次性选定（后台向项目池入账即选择），负余额落源池（软模型，
 *       上界≈一段实耗由段间硬门保证，#537 消费面）</li>
 *   <li>计价结算：结算段三段型（主智能体轮/run 场/设计会话轮）段收口按段汇总
 *       用量×成本单价×全局折算率落扣减行；固定单价项（联网搜索等计件族）按次
 *       直记不乘折算率；扣减行带金额＋用量＋单价快照（历史不随调参漂移）；
 *       试用项目一切智能体调用零扣减行</li>
 *   <li>付费状态（项目级、不可逆、计费边界＝翻转时间点）：两门翻转（门①岔路口/
 *       门②拦截卡——载体卡在后续票，本 BC 收翻转事实）；<b>两轨判定单读点</b>
 *       （扣减源未设＋未翻转＝试用；源选定＋翻转＝付费）供拦截闸、派发门、协议
 *       分轨、体验提示四消费面同源取用</li>
 *   <li>流水 append-only（bil_entries）：入账/扣减/调整各一行带行后余额快照＋
 *       操作者留痕；账本弱引用 account/project 不外键（可分离——未来独立账户
 *       服务的缝）</li>
 *   <li>试用拦截面（#533 门②拦截卡，ADR-0047「平台结构闸」）：入口三分类后、
 *       受理前两道拦（意见/兜底，咨询放行、下单意图旧轨）——被拦输入随卡留存
 *       （bil_intercepts 待决行：确认扣减立即受理原文〔不重输〕，充值引导/中途
 *       关闭卡可回）；确认＝随卡首次选源（源未定）＋翻转（门②）同事务</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>充值余额计费 BC（商业化一期 #526 切片①）：平台内人民币元记账（Long 分、
 * v1 恒 CNY），真支付对接出界（模拟收银台/后台入账承载，后续票）。与 project/
 * identity 的交叉全走软引用（projectId/accountId 无 FK）；计价素材（用量×时点
 * 生效单价）经 {@code UsagePricingPort} 向 base.metering 取（单价表私有边界不破
 * ——端口只出换算结果）。表前缀 {@code bil_}，错误码前缀 {@code BILL_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain：BillingPool 聚合（双池）、ProjectBilling 聚合（扣减源＋付费状态
 *       ＝两轨判定单读点）、BillingEntry 聚合（append-only 流水）、BillingParam
 *       聚合（运营参数——全局折算率）、扣减源/翻转门/段型/流水种类/池种类枚举、
 *       Operator 值对象（admin 侧操作者，落空口径）、UsagePricingPort（计量
 *       计价读面）、PricedLine/PiecePrice 换算素材模型、仓储接口、错误码</li>
 *   <li>application：BillingAppService（两轨判定读点/源选定/翻转门①②/参数）、
 *       PoolTopupAppService（双池入账——项目池入账即选源）、SegmentSettlementAppService
 *       （段收口结算＋固定单价项按次直记）、TrialInterceptAppService（拦截留存/
 *       确认扣减＝门②——编排归 project 侧 DispatchAppService）</li>
 *   <li>infrastructure：MeteringPricingAdapter（UsagePricingPort 进程内适配器）</li>
 *   <li>endpoints：模拟收银台与账单读面（#534 计费④——准入码校验/账户上下文
 *       充值/我的账户视图走本 BC 控制器；项目账单读面与项目上下文充值挂
 *       /api/projects/{id}/billing 复用 project owner 守卫）；拦截卡读面/确认
 *       挂 project 控制器（#533 编排归 DispatchAppService）；后台运营端点随
 *       #535 落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Billing", subDomain = SubDomain.CORE)
package com.semgravio.aiplatform.business.billing;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
