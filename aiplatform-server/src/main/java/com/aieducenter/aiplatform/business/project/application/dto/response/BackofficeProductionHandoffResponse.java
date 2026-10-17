package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.business.project.domain.aggregate.ProductionHandoff;
import com.aieducenter.aiplatform.business.project.domain.entity.ProductionHandoffTransition;
import com.aieducenter.aiplatform.business.project.domain.enums.ProductionHandoffAction;
import com.aieducenter.aiplatform.business.project.domain.enums.ProductionHandoffStatus;

/**
 * 后台生产承接详情（#338 承接全量面）：承接字段（交付联系人/下一步说明/进展
 * 更新/预计开始时间——可空段如实 null，呈现层「仅真实值显示」）＋两态＋动作
 * 留痕时间线（新 → 旧，append-only 全量——动作/两侧状态/依据/操作者/时间；
 * 创建行＝开工判定系统操作者）。
 */
public record BackofficeProductionHandoffResponse(
        String id,
        String projectId,
        String orderId,
        ProductionHandoffStatus status,
        String statusName,
        String contact,
        String nextStep,
        String progressNote,
        LocalDateTime estimatedStartAt,
        LocalDateTime createdAt,
        List<TransitionView> transitions) {

    public static BackofficeProductionHandoffResponse of(ProductionHandoff handoff) {
        return new BackofficeProductionHandoffResponse(
                Long.toString(handoff.getId()),
                Long.toString(handoff.getProjectId()),
                Long.toString(handoff.getOrderId()),
                handoff.getStatus(),
                handoff.getStatus().getName(),
                handoff.getContact(),
                handoff.getNextStep(),
                handoff.getProgressNote(),
                handoff.getEstimatedStartAt(),
                handoff.getCreatedAt(),
                handoff.transitionHistoryNewestFirst().stream().map(TransitionView::of).toList());
    }

    /** 留痕行视图（新 → 旧单行）：动作/两侧状态/依据/操作者/时间。 */
    public record TransitionView(
            ProductionHandoffAction action,
            String actionName,
            ProductionHandoffStatus fromStatus,
            String fromStatusName,
            ProductionHandoffStatus toStatus,
            String toStatusName,
            String basis,
            String operatorId,
            String operatorName,
            LocalDateTime createdAt) {

        static TransitionView of(ProductionHandoffTransition transition) {
            return new TransitionView(
                    transition.getAction(),
                    transition.getAction().getName(),
                    transition.getFromStatus(),
                    transition.getFromStatus().getName(),
                    transition.getToStatus(),
                    transition.getToStatus().getName(),
                    transition.getBasis(),
                    transition.getOperatorId(),
                    transition.getOperatorName(),
                    transition.getCreatedAt());
        }
    }
}
