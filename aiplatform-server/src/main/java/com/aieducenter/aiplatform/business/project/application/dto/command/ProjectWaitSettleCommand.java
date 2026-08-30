package com.aieducenter.aiplatform.business.project.application.dto.command;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 答复等待点命令（与底座 wait 端点同构，A1 §1.1 型封闭；type 字面量的唯一
 * 真值是底座 {@code WaitSettleCommand.TYPE_*} 常量）。
 *
 * @param type    答复型：answer / permission
 * @param answers 问答选项（type=answer 必填，选项 label）
 * @param approve 批准与否（type=permission 必填）
 * @param note    备注（可带）
 */
public record ProjectWaitSettleCommand(

        @NotBlank(message = "type 不能为空")
        String type,

        List<List<String>> answers,

        Boolean approve,

        @Size(max = 1000, message = "备注不能超过1000字")
        String note
) {
}
