package com.aieducenter.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 指令区发言命令（#19 需求环①）：content 即用户在指令区输入的这句话——BA 以
 * 续同一 {@code ba-{projectId}} 会话消化（催促收敛、PRD 修订意见也都从这进）。
 *
 * @param content 用户发言正文（非空；上限与需求描述同源 5000）
 */
public record PostMessageCommand(

        @NotBlank(message = "发言内容不能为空")
        @Size(max = 5000, message = "发言内容长度不能超过5000")
        String content
) {
}
