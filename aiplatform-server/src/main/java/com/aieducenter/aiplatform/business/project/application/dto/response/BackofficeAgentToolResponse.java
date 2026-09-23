package com.aieducenter.aiplatform.business.project.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.aieducenter.aiplatform.business.project.domain.model.AgentToolKind;

/**
 * 工具面清单行（#252）：一件工具的呈现单元——注册名（模型可见名＝按名寻址键）、
 * 类别（{@link AgentToolKind}，开关判定正本：仅 ENHANCEMENT 可开关）、挂载态
 * （骨架/harness 内建恒 true；增强按运营配置生效态——关＝该工具退出装配面，
 * 清单呈现「在册但未挂载」供排障对照）与一句描述。
 */
public record BackofficeAgentToolResponse(
        @Schema(description = "工具注册名（模型可见名＝工具面按名寻址键；清单读面即全集——"
                + "PUT 开关与骨架锁死判定都按本名）", example = "web_search")
        String name,
        @Schema(description = "工具面类别（开关判定正本）：SKELETON=骨架（编排链路＋项目事实"
                + "只读件，结构性锁死不开放关停——ADR-0021 编排权不下放配置）；ENHANCEMENT="
                + "增强（联网搜索/网页抓取，窄幅可开关：关即退出槽位装配面、开即回归）；"
                + "HARNESS_BUILTIN=harness 内建编码工具（框架自带，呈现口径，不可开关）",
                example = "ENHANCEMENT")
        String kind,
        @Schema(description = "挂载态：骨架/harness 内建恒 true；增强按运营配置生效态"
                + "（false＝在册但退出装配面——模型不可见）")
        boolean enabled,
        @Schema(description = "一句职能描述（清单即审核/排障面）")
        String description) {

    /** 平台工具行（挂载态由调用方按类别与生效开关给定）。 */
    public static BackofficeAgentToolResponse of(String name, AgentToolKind kind,
            boolean enabled, String description) {
        return new BackofficeAgentToolResponse(name, kind.name(), enabled, description);
    }
}
