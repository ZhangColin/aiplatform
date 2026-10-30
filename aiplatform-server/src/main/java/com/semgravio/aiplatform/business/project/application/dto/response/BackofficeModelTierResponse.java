package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.business.project.domain.model.ModelTier;

/**
 * 模型档位读面（#367）：GET/POST/PUT 回执同形。语义键→实际模型名＋所属通道＋
 * 显示名＋启用态；绑定面（三座/边缘点/路由强档）引用的即语义键，绑定即时校验
 * 放行值＝本读面 enabled=true 的行集。
 *
 * @param tierKey      语义键（引用腿）
 * @param providerKey  所属通道键（模型串前缀腿）
 * @param modelName    实际模型名（通道侧 Model ID）
 * @param displayName  运营显示名（null＝回落语义键展示）
 * @param enabled      启用态（停用＝既有绑定装配报错、新绑定被即时校验拒绝）
 * @param operatorId   最近写者 id（null＝种子/迁移写入）
 * @param operatorName 最近写者名（直读展示）
 * @param updatedAt    最近写入时刻
 */
public record BackofficeModelTierResponse(
        @Schema(description = "档位语义键（绑定面引用值）", example = "flash")
        String tierKey,
        @Schema(description = "所属通道键（模型串「前缀:实际模型名」的前缀腿）",
                example = "volcengine")
        String providerKey,
        @Schema(description = "实际模型名（通道侧 Model ID；版本升级改此处、全部引用自动跟上）",
                example = "deepseek-v4-flash-ga-260731")
        String modelName,
        @Schema(description = "运营显示名（后台绑定下拉的人话标签；null＝回落语义键）",
                example = "快模型档")
        String displayName,
        @Schema(description = "启用态（停用＝既有绑定装配报错 PRJ_061、新绑定被即时校验拒绝 PRJ_075）")
        boolean enabled,
        @Schema(description = "最近写者 id（admin 侧 TSID；null＝种子/迁移写入）", example = "700210")
        String operatorId,
        @Schema(description = "最近写者名（直读展示）", example = "运营·智能体管理员")
        String operatorName,
        @Schema(description = "最近写入时刻")
        LocalDateTime updatedAt) {

    /** 行 → 读面。 */
    public static BackofficeModelTierResponse of(ModelTier tier) {
        return new BackofficeModelTierResponse(tier.tierKey(), tier.providerKey(),
                tier.modelName(), tier.displayName(), tier.enabled(),
                tier.operatorId(), tier.operatorName(), tier.updatedAt());
    }
}
