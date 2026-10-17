package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.DeliveryState;
import com.aieducenter.aiplatform.business.project.application.ProductionHandoffAppService;

/**
 * 交付状态读面（#338 前台六态·项目维度投影）：订单＋合同＋付款＋承接四源
 * 一次读齐的纯推导视图（不存储——每次现算）。{@code state} null＝六态不覆盖
 * （待报价——订单面「待确认报价」占位态照旧）；{@code handoff} null＝承接未
 * 建（开工判定未触发/mock 时代遗留单）。「需用户补充」以 {@code state=6}＋
 * {@code needsUserSupplement=true} 双呈现（盖帽态与标志同报，卡片双行呈现）。
 */
public record DeliveryStatusResponse(
        String orderId,
        Integer orderStatus,
        String orderStatusName,
        DeliveryState state,
        String stateName,
        boolean needsUserSupplement,
        HandoffView handoff) {

    public static DeliveryStatusResponse of(Order order, DeliveryState state,
            boolean needsUserSupplement, ProductionHandoffAppService.ProductionHandoffFacts handoff) {
        return new DeliveryStatusResponse(
                Long.toString(order.getId()),
                order.getStatus().getCode(),
                order.getStatus().getName(),
                state,
                state == null ? null : state.getName(),
                needsUserSupplement,
                handoff == null ? null : new HandoffView(handoff.status(), handoff.statusName(),
                        handoff.contact(), handoff.nextStep(), handoff.progressNote(),
                        handoff.estimatedStartAt()));
    }

    /**
     * 承接字段视图（人工措辞面）：可空段如实 null——呈现层「仅真实值显示」
     * （预计开始时间 null＝待安排，不虚构日期）。
     */
    public record HandoffView(
            Integer status,
            String statusName,
            String contact,
            String nextStep,
            String progressNote,
            LocalDateTime estimatedStartAt) {
    }
}
