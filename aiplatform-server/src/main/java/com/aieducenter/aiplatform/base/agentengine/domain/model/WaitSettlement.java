package com.aieducenter.aiplatform.base.agentengine.domain.model;

import java.util.List;

/**
 * 等待点答复：两型封闭集合——问答回答 / 权限决策。
 */
public sealed interface WaitSettlement permits WaitSettlement.Answer,
        WaitSettlement.PermissionDecision {

    /** 等待点稳定标识（落库行的主键）。 */
    String waitId();

    /** 问答回答：answers 按问题顺序，每项 = 该问题选中的标签列表（custom 输入也作为标签）。 */
    record Answer(String waitId, List<List<String>> answers) implements WaitSettlement {
    }

    /** 权限决策：approve=true 批准（once）/ false 拒绝（reject）。 */
    record PermissionDecision(String waitId, boolean approve) implements WaitSettlement {
    }
}
