package com.aieducenter.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 权限确认卡作答命令（#83 作答通道分家）：批准/拒绝二选一——与问答作答
 * （{@link AnswerQuestionCommand}，答复文本）通道分离、互不串扰。恢复私货
 * （待确认工具清单/会话寻址）不回传：挂起事实在平台侧进程内会合点
 * （run 无表口径同款），runId 仅作串卡校验。
 *
 * <p>路径 {@code /permissions/{ref}/answer} 的 ref = 挂起事件 {@code engineRef}
 * （引擎侧请求 id，续跑批复的锚）。</p>
 *
 * @param runId    挂起轮的运行标识（串卡校验——与挂起登记不符即 409）
 * @param approved 批准位（true = 批准 / false = 拒绝）
 */
public record PermissionAnswerCommand(

        @NotBlank(message = "runId 不能为空")
        String runId,

        @NotNull(message = "批准位不能为空")
        Boolean approved
        ) {
}
