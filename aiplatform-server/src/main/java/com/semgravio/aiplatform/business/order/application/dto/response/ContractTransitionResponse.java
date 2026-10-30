package com.semgravio.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.semgravio.aiplatform.business.order.domain.entity.ContractTransition;
import com.semgravio.aiplatform.business.order.domain.enums.ContractStatus;
import com.semgravio.aiplatform.business.order.domain.enums.ContractTransitionAction;

/**
 * 合同动作留痕行（#336 后台详情时间线条目）：动作/两侧状态/依据（必填自由
 * 文本）/操作者两列/时间。非迁移动作（签署标记/草稿重录）from = to。
 *
 * @param action        动作（code）：1=推进 2=撤回 3=作废 4=签署标记 5=草稿重录 6=失败或过期
 * @param actionName    动作名
 * @param fromStatus    动作前状态（code，六态）
 * @param fromStatusName 动作前状态名
 * @param toStatus      动作后状态（code）
 * @param toStatusName  动作后状态名
 * @param basis         依据（必填自由文本）
 * @param operatorId    操作者标识（无头落空口径 null）
 * @param operatorName  操作者名（直读展示；null 呈现为空）
 * @param createdAt     动作时间
 */
public record ContractTransitionResponse(
        ContractTransitionAction action,
        String actionName,
        ContractStatus fromStatus,
        String fromStatusName,
        ContractStatus toStatus,
        String toStatusName,
        String basis,
        String operatorId,
        String operatorName,
        LocalDateTime createdAt
) {

    /** 留痕行 → 响应。 */
    public static ContractTransitionResponse of(ContractTransition transition) {
        return new ContractTransitionResponse(
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
