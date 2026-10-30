package com.semgravio.aiplatform.business.order.application;

import java.util.Map;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.order.domain.aggregate.Order;

/**
 * 平台通知事件名册常量（#30 交易环③，ADR-0001：代码侧每 BC 一个 EventTypes
 * 常量类，禁止字符串字面量散落；正本见 docs/spec/SSE事件清单.md·平台通知族）。订单
 * 上下文在状态变化副作用真实落定后发射（base 不发 SSE）；前端消费 = toast
 * （点击直达项目页）+ 失效订单/项目/对话史域重查（#203 报价卡经对话史重查入流）。
 * 两事件的 payload 同形，构造单点＝{@link #notificationPayload}（#338 收敛）。
 */
public final class OrderEventTypes {

    /**
     * 订单状态已变化：下单（待报价）/首次报价（已报价）/取消/支付完成（已支付）/
     * 归档（已归档）各发一次（#37/#39：支付与归档分两发，归档失败只发「已支付」）
     * ——改价不换状态、不发本事件（改价信号 = {@link #ORDER_REPRICED}）。payload 带
     * orderId + status（Integer code）。
     */
    public static final String ORDER_STATUS_CHANGED = "order-status-changed";

    /**
     * 订单已改价（#204 改价入流，推翻「改价不换状态不发」的静默）：已报价态改价
     * 落定后发射。发射时序 = 先落「报价已更新」对话卡后发（信号触发前端重查时卡
     * 须已在库，#203 同款）。payload 与 {@link #ORDER_STATUS_CHANGED} 同字段同量级
     * （status 恒已报价），不含金额与备注。
     */
    public static final String ORDER_REPRICED = "order-repriced";

    // ---------- payload 契约键（SSE事件清单·平台通知族） ----------

    /** 关联字段（通知通道 streamId 同值）。 */
    public static final String PROJECT_ID_FIELD = "projectId";

    /** 订单标识（TSID 十进制字符串）。 */
    public static final String ORDER_ID_FIELD = "orderId";

    /** 订单状态（OrderStatus 的 Integer code：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消）。 */
    public static final String STATUS_FIELD = "status";

    /** 状态名（用户面文案兜底）。 */
    public static final String STATUS_NAME_FIELD = "statusName";

    /**
     * 订单域平台通知 payload（{@link #ORDER_STATUS_CHANGED} 与 {@link
     * #ORDER_REPRICED} 两事件同形的构造单点，#338 自 OrderAppService/
     * KickoffAppService 双份逐字重复收敛）：关联字段＋状态面＋归属路由键双键
     * （ownerAccountId/ownerGuestId——取自订单聚合而非操作者上下文，含后台触发
     * 路径，ADR-0018；#326 访客名下订单走访客键）。不含金额（#203 视镜语义）。
     */
    public static Map<String, Object> notificationPayload(Order order) {
        return Map.of(
                PROJECT_ID_FIELD, order.getProjectId().toString(),
                ORDER_ID_FIELD, order.getId().toString(),
                STATUS_FIELD, order.getStatus().getCode(),
                STATUS_NAME_FIELD, order.getStatus().getName(),
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(order.getOwnerAccountId()),
                EventsAppService.OWNER_GUEST_FIELD, EventsAppService.ownerPayload(order.getOwnerGuestId()));
    }

    private OrderEventTypes() {
    }
}
