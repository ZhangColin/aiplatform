package com.semgravio.aiplatform.business.billing.application.dto.response;

import com.semgravio.aiplatform.business.billing.domain.enums.OperatorActionType;

/**
 * 后台账户运营动作回执视图（#535 计费⑤）：留痕行锚点＋流水行锚点＋池现值——
 * admin 对账三锚一次带回（balanceAfter 仅金额类动作带值）。
 *
 * @param actionId      动作留痕行 id（bil_operator_actions）
 * @param actionType    动作种类码（1=赠送 2=手动充值 3=余额调整 4=扣减源调整）
 * @param actionTypeName 动作种类名（直读展示）
 * @param entryId       流水行 id（bil_entries；源调整不动账为 null）
 * @param poolId        池 id（bil_pools；源调整不动池为 null）
 * @param balanceAfter  动作后池余额（分；源调整为 null）
 */
public record BackofficeActionView(Long actionId, Integer actionType, String actionTypeName,
        Long entryId, Long poolId, Long balanceAfter) {

    /** 源调整回执（不动账：流水/池/余额三列落空）。 */
    public static BackofficeActionView sourceOnly(Long actionId) {
        return new BackofficeActionView(actionId, OperatorActionType.SOURCE_RESELECT.getCode(),
                OperatorActionType.SOURCE_RESELECT.getName(), null, null, null);
    }
}
