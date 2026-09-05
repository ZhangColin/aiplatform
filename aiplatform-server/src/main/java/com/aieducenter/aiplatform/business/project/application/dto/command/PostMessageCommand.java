package com.aieducenter.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话区发言命令（#19 需求环①）：content 即用户在对话区输入的这句话——平台
 * 三分类派发（意见 / 咨询 / 兜底），意见与咨询都由主智能体以续同一
 * {@code main-{projectId}} 会话消化（催促收敛、PRD 修订意见也都从这进）。
 *
 * @param content 用户发言正文（非空；上限与需求描述同源 5000）
 */
public record PostMessageCommand(

        @NotBlank(message = "发言内容不能为空")
        @Size(max = 5000, message = "发言内容长度不能超过5000")
        String content
) {
}
