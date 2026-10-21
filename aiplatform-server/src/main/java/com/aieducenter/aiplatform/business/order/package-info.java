/**
 * Order Context（business.order）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>订单聚合（五态单向推进：待报价 → 已报价（=待支付）→ 已支付 → 已归档；
 *       已取消自任何未支付态可达，不设「支付中」）与下单冻结（已确认 PRD 版本全文
 *       冗余拷贝＋双版本引用入单，订单自含不依赖版本表存亡）</li>
 *   <li>同项目至多一个未终结订单（应用服务预检 + 库侧部分唯一索引兜底）</li>
 *   <li>价目留痕（append-only：首次报价与每次改价各一行，订单当前金额取最新行）</li>
 *   <li>合同域（#336 六态人工推进留痕＋范围快照冻结集；确认范围并准备合同＝
 *       合同创建口）与付款域（#337 付款计划随合同冻结同刻创建＋节点五态凭证
 *       核验路径：用户上传凭证→后台核验到账/打回、留痕 append-only）</li>
 *   <li>开工判定（#338 双门禁＝合同签署完成 && 开工门节点全部到账：两触发点
 *       共用判定器，满足即同事务订单转「已支付」〔新语义＝开工条件满足〕＋
 *       创建生产承接〔跨 BC 写 project〕；取消边界收窄＋归档取版对齐同票）</li>
 *   <li>归档（重试归档补卡单＝已归档人工终局的唯一触发：订单＋项目一事务、
 *       提交后知识沉淀〔取合同冻结 PRD 版本，无合同回退最新确认版〕＋订单态
 *       变化通知；PaymentPort = 真实渠道接入切换边界，#337 起维持现状不加宽）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>交易载体 BC（#4 决议）：下单后的报价/改价/合同/付款/归档编排。与 project 的
 * 写交叉 = 归档时订单归档 + 项目归档、开工判定时订单转已支付 + 生产承接创建
 * （ADR-0002 改写）；项目事实（存在性/PRD 快照源）
 * 经 {@code business.project} 应用层软引用（projectId 无 FK，同 workspace 口径）。
 * 表前缀 {@code ord_}，错误码前缀 {@code ORD_}。v1 支付走付款节点凭证核验路径
 * （线下转账），PaymentPort 是真实渠道接入的切换边界（维持现状不加宽——渠道
 * 扩展位由付款节点上的渠道类型枚举＋流水号字段承载）。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain：Order 聚合（价目留痕随单）、OrderPriceEntry 实体（操作者两列
 *       落痕，#155）、Contract 聚合＋ContractTransition 留痕（#336）、PaymentPlan
 *       聚合＋PaymentNode/PaymentVerification（#337）、PaymentVoucher/PaymentVoucherFile
 *       （凭证行＋字节伴行）、Operator 值对象（admin 侧操作者，落空口径）、
 *       状态/渠道/动作枚举、仓储接口、PaymentPort（真实支付切换边界）、错误码</li>
 *   <li>application：OrderAppService（下单/详情/取消/报价与改价；运营取消
 *       cancelByBackoffice 同守卫带必填原因＋操作者留痕，#157；#338 取消收窄
 *       ——已签关门＋活跃合同联动作废；重试归档 retryArchive 卡单补归档同守卫
 *       带操作者留痕＋沉淀跟随归档成功，#158）+
 *       ContractAppService（#336 六态人工推进＋草稿重录；#337 起合同与付款计划
 *       同事务创建；#338 推进签署完成触发开工判定）+ PaymentPlanAppService
 *       （#337 节点录入/凭证上传/到账核验/打回＋两面读面；#338 核验落库触发
 *       开工判定）+ KickoffAppService（#338 开工判定器）+ DeliveryStatusAppService
 *       （#338 前台六态纯推导读面——项目维度四源投影）+
 *       OrderQueryAppService（未终结订单读面——项目详情/列表嵌入与冻结守卫的
 *       供给方 + 最近订单嵌入）+ BackofficeOrderAppService（后台三读端点：清单/
 *       详情/源码包；跨 BC 项目名/用户昵称/打包经 project、identity 应用层软引用）
 *       + OrderEventTypes（平台通知事件名册常量）</li>
 *   <li>infrastructure：MockPaymentAdapter（#337 起 mock 支付闭环退役、无调用方
 *       ——端口留作渠道真接缝）</li>
 *   <li>endpoints：用户面 REST（下单/订单详情/取消/合同确认与预览/付款计划与
 *       凭证上传）；后台机机面 {@code /api/backoffice/*}（cartisan-openapi
 *       五头 HMAC 签名闸，#29——订单/合同/付款三面）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Order", subDomain = SubDomain.CORE)
package com.aieducenter.aiplatform.business.order;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
