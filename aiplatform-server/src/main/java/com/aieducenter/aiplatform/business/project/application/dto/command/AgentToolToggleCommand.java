package com.aieducenter.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 工具开关命令（#252，PUT 窄幅写）：目标态必须显式（无缺省翻转语义——幂等写面，
 * 同值重写零写入不落痕）；仅增强工具（web_search/fetch_url）接受本命令，骨架与
 * harness 内建在应用层结构性拒绝（PRJ_036）。
 */
public record AgentToolToggleCommand(
        @Schema(description = "开关目标态（必填、无缺省：true=挂载回归装配面 / "
                + "false=退出槽位装配面——变更走配置留痕，与智能体配置同机制）",
                example = "false")
        Boolean enabled) {
}
