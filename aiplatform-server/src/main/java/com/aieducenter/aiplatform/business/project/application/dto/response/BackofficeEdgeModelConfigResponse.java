package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.aieducenter.aiplatform.business.project.domain.model.AgentEdgePoint;
import com.aieducenter.aiplatform.business.project.domain.model.AgentOperationalConfig;

/**
 * 边缘调用点档位读面（#366）：GET 与 PUT 回执同形。与三座读面
 * （{@link BackofficeAgentConfigResponse}）同机制不同值面——边缘点仅模型档位
 * （无 systemPrompt/工具开关/路由策略件），字段名对齐三座面（admin 侧同一渲染
 * 件可复用）。生效值（库值或代码缺省档位键）＋覆盖标记＋缺省预览＋最近写者。
 *
 * @param agentKey           边缘点稳定键（寻址腿，配置行 agent_key 同串）
 * @param agentName          展示名
 * @param modelId            生效模型档位键（库覆盖值或代码缺省——装配实取值）
 * @param modelIdOverridden  true＝档位为库覆盖值（false＝代码缺省）
 * @param defaultModelId     代码缺省档位键（预览：清空覆盖即落此值）
 * @param operatorId         最近写者 id（admin 侧 TSID；null＝从未配置）
 * @param operatorName       最近写者名（直读展示）
 * @param updatedAt          最近写入时刻（null＝从未配置）
 */
public record BackofficeEdgeModelConfigResponse(
        @Schema(description = "边缘点稳定键（naming=项目取名 / compaction=上下文压缩 / "
                + "classification=入口分类 / precheck=技能预检）", example = "naming")
        String agentKey,
        @Schema(description = "展示名", example = "项目取名")
        String agentName,
        @Schema(description = "生效模型档位键（ADR-0031 语义键，如 flash/pro）", example = "flash")
        String modelId,
        @Schema(description = "模型档位是否库覆盖值（true＝覆盖；false＝代码缺省 flash）")
        boolean modelIdOverridden,
        @Schema(description = "代码缺省模型档位（清空覆盖即落此值）", example = "flash")
        String defaultModelId,
        @Schema(description = "最近写者 id（admin 侧 TSID；null＝从未配置）", example = "700210")
        String operatorId,
        @Schema(description = "最近写者名（直读展示）", example = "运营·智能体管理员")
        String operatorName,
        @Schema(description = "最近写入时刻（null＝从未配置）")
        LocalDateTime updatedAt) {

    /** 生效面合成单源：代码缺省正本＋覆盖行（null 先归一缺省态）＋写者。 */
    public static BackofficeEdgeModelConfigResponse of(AgentEdgePoint point,
            AgentOperationalConfig override) {
        AgentOperationalConfig config =
                override != null ? override : AgentOperationalConfig.defaults(point.key());
        return new BackofficeEdgeModelConfigResponse(
                point.key(),
                point.getName(),
                config.effectiveModelId(point.modelId()),
                config.modelId() != null,
                point.modelId(),
                config.operatorId(),
                config.operatorName(),
                config.updatedAt());
    }
}
