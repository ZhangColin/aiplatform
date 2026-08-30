package com.aieducenter.aiplatform.base.agentengine.application.dto.command;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.aieducenter.aiplatform.base.agentengine.domain.model.WaitSettlement;

/**
 * 等待点答复命令：type 二选一（answer / permission），各型必填字段——answer 要
 * answers、permission 要 approve。域内映射 {@link WaitSettlement}。
 */
public record WaitSettleCommand(

        @NotBlank(message = "type 不能为空")
        String type,

        /** 问答回答（type=answer 必填）：按问题顺序，每项 = 选中标签列表。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<List<String>> answers,

        /** 权限决策（type=permission 必填）：true 批准 / false 拒绝。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean approve,

        /** 备注（可选）：业务侧留痕，底座不解释。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String note) {

    /** 答复型字面量（域内封闭集合的 REST 名）。 */
    public static final String TYPE_ANSWER = "answer";
    public static final String TYPE_PERMISSION = "permission";
}
