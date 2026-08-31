/**
 * Order Context（business.order）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>订单聚合（五态单向推进：待报价 → 已报价（=待支付）→ 已支付 → 已归档；
 *       已取消自任何未支付态可达，不设「支付中」）与下单快照冻结（PRD 全文入单，
 *       自含不依赖工作区存亡）</li>
 *   <li>同项目至多一个未终结订单（应用服务预检 + 库侧部分唯一索引兜底）</li>
 *   <li>价目留痕（append-only：首次报价与每次改价各一行，订单当前金额取最新行）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>交易载体 BC（#4 决议）：下单后的报价/改价/支付/归档编排。与 project 的
 * 唯一交叉 = 支付成功一事务内订单归档 + 项目归档；项目事实（存在性/PRD 快照源）
 * 经 {@code business.project} 应用层软引用（projectId 无 FK，同 workspace 口径）。
 * 表前缀 {@code ord_}，错误码前缀 {@code ORD_}。v1 支付为平台内 mock，
 * PaymentPort 是真实接入的切换边界（不建支付尝试表）。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain：Order 聚合（价目留痕随单）、OrderPriceEntry 实体、OrderStatus
 *       枚举、仓储接口、错误码</li>
 *   <li>application：OrderAppService（下单/详情/取消/报价与改价）+
 *       OrderQueryAppService（未终结订单读面——项目详情/列表嵌入与冻结守卫的
 *       供给方）+ BackofficeOrderAppService（后台三读端点：清单/详情/源码包；
 *       跨 BC 项目名/用户昵称/打包经 project、identity 应用层软引用）</li>
 *   <li>endpoints：用户面 REST（下单/订单详情/取消）；后台机机面
 *       {@code /api/backoffice/*}（cartisan-openapi 五头 HMAC 签名闸，#29）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Order", subDomain = SubDomain.CORE)
package com.aieducenter.aiplatform.business.order;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
