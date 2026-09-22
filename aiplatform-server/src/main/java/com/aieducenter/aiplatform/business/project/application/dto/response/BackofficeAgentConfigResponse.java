package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.aieducenter.aiplatform.business.project.domain.model.AgentOperationalConfig;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

/**
 * 智能体配置读面（#251）：GET 与 PUT 回执同形。生效值（库值或枚举默认）＋
 * 覆盖标记（运营要看到「现在跑的是覆盖还是默认」）＋枚举默认预览（清空回落
 * 即落此值——回复默认前可先看落点）＋存储面工具开关（生效属 #252）＋最近
 * 写者。全库两座智能体（main/executor）各自寻址读写。
 *
 * @param agentKey                智能体稳定键（寻址腿）
 * @param agentName               展示名
 * @param systemPrompt            生效 systemPrompt（库覆盖值或枚举默认——装配实取值）
 * @param modelId                 生效模型档位裸名
 * @param systemPromptOverridden  true＝systemPrompt 为库覆盖值（false＝枚举默认）
 * @param modelIdOverridden       true＝模型档位为库覆盖值（false＝枚举默认）
 * @param defaultSystemPrompt     枚举默认 systemPrompt（预览：清空覆盖即落此值）
 * @param defaultModelId          枚举默认模型档位（同上）
 * @param webSearchEnabled        联网搜索开关存储态（生效属 #252；无行语境＝true）
 * @param fetchUrlEnabled         网页抓取开关存储态（同上）
 * @param operatorId              最近写者 id（admin 侧 TSID；null＝从未配置）
 * @param operatorName            最近写者名（直读展示）
 * @param updatedAt               最近写入时刻（null＝从未配置）
 */
public record BackofficeAgentConfigResponse(
        @Schema(description = "智能体稳定键（main=主智能体 / executor=run 执行体）",
                example = "main")
        String agentKey,
        @Schema(description = "展示名", example = "主智能体")
        String agentName,
        @Schema(description = "生效 systemPrompt（库覆盖值或枚举默认——装配实取值，"
                + "对照 systemPromptOverridden 知来源）")
        String systemPrompt,
        @Schema(description = "生效模型档位裸名（如 deepseek-v4-flash）", example = "deepseek-v4-flash")
        String modelId,
        @Schema(description = "systemPrompt 是否库覆盖值（true＝覆盖；false＝枚举默认）")
        boolean systemPromptOverridden,
        @Schema(description = "模型档位是否库覆盖值（true＝覆盖；false＝枚举默认）")
        boolean modelIdOverridden,
        @Schema(description = "枚举默认 systemPrompt（预览：清空覆盖即落此值——身份与"
                + "配置分治的缺省正本）")
        String defaultSystemPrompt,
        @Schema(description = "枚举默认模型档位（清空覆盖即落此值）", example = "deepseek-v4-flash")
        String defaultModelId,
        @Schema(description = "联网搜索开关存储态（生效属 #252；无覆盖行语境＝true 开）")
        boolean webSearchEnabled,
        @Schema(description = "网页抓取开关存储态（生效属 #252；无覆盖行语境＝true 开）")
        boolean fetchUrlEnabled,
        @Schema(description = "最近写者 id（admin 侧 TSID；null＝从未配置）", example = "700200")
        String operatorId,
        @Schema(description = "最近写者名（直读展示）", example = "运营·技能管理员")
        String operatorName,
        @Schema(description = "最近写入时刻（null＝从未配置）")
        LocalDateTime updatedAt) {

    /** 生效面合成单源：枚举缺省正本＋覆盖行（null 先归一缺省态）＋写者。 */
    public static BackofficeAgentConfigResponse of(AgentProfile profile, AgentOperationalConfig override) {
        AgentOperationalConfig config =
                override != null ? override : AgentOperationalConfig.defaults(profile.key());
        return new BackofficeAgentConfigResponse(
                profile.key(),
                profile.getName(),
                config.effectiveSystemPrompt(profile.systemPrompt()),
                config.effectiveModelId(profile.modelId()),
                config.systemPrompt() != null,
                config.modelId() != null,
                profile.systemPrompt(),
                profile.modelId(),
                config.webSearchEnabled(),
                config.fetchUrlEnabled(),
                config.operatorId(),
                config.operatorName(),
                config.updatedAt());
    }
}
