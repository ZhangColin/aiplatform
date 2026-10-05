package com.aieducenter.aiplatform.business.project.application.dto.command;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话区发言命令（#19 需求环①；#97 起携带圈注附件、#286 扩图片物料）：content 即
 * 用户在对话区输入的这句话——平台三分类派发（意见 / 咨询 / 兜底），意见与咨询都由
 * 主智能体以续同一 {@code main-{projectId}} 会话消化（催促收敛、PRD 修订意见也都
 * 从这进）。消息附件（预览上指认的圈注位置、上传的图片物料）随发言同句发送——
 * 附件是对话输入的增强不是替代。
 *
 * @param content     用户发言正文（非空；上限与需求描述同源 5000）
 * @param attachments 消息附件（可空——纯文字发言无附件；圈注与图片物料可叠加）
 */
public record PostMessageCommand(

        @NotBlank(message = "发言内容不能为空")
        @Size(max = 5000, message = "发言内容长度不能超过5000")
        String content,

        @Size(max = 20, message = "附件数量不能超过20")
        @Valid List<MessageAttachment> attachments
) {
}
