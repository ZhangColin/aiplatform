package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.business.project.domain.model.ModelProvider;

/**
 * 模型供应商通道读面（#367）：GET/POST/PUT 回执同形。<b>apiKey 一律掩码回显</b>
 * （{@code ModelProvider#maskApiKey} 尾 4 位单点——null＝未配置）；明文只在
 * 库内与装配链路上，接口回显面零明文 key。装配读腿动态查库，通道变更下一轮
 * 命令构建即生效（缓存指纹含 baseUrl/key——同模型串换 key 换地址＝新实例）。
 *
 * @param providerKey  键名＝协议路由前缀
 * @param baseUrl      通道 baseUrl
 * @param apiKeyMasked apiKey 掩码形（尾 4 位；null＝未配置）
 * @param enabled      启用态
 * @param operatorId   最近写者 id（null＝种子/迁移写入）
 * @param operatorName 最近写者名（直读展示）
 * @param updatedAt    最近写入时刻
 */
public record BackofficeModelProviderResponse(
        @Schema(description = "通道键名＝协议路由前缀", example = "volcengine")
        String providerKey,
        @Schema(description = "通道 baseUrl（创建上下文注入 SDK）",
                example = "https://ark.cn-beijing.volces.com/api/v3")
        String baseUrl,
        @Schema(description = "apiKey 掩码形（尾 4 位；null＝未配置——接口回显零明文 key）",
                example = "****ab12")
        String apiKeyMasked,
        @Schema(description = "启用态（停用＝引用该通道的装配明确报错，不静默换道）")
        boolean enabled,
        @Schema(description = "最近写者 id（admin 侧 TSID；null＝种子/迁移写入）", example = "700210")
        String operatorId,
        @Schema(description = "最近写者名（直读展示）", example = "运营·智能体管理员")
        String operatorName,
        @Schema(description = "最近写入时刻")
        LocalDateTime updatedAt) {

    /** 行 → 读面（掩码单点经此——全链无第二处掩码逻辑）。 */
    public static BackofficeModelProviderResponse of(ModelProvider provider) {
        return new BackofficeModelProviderResponse(provider.providerKey(), provider.baseUrl(),
                provider.maskedApiKey(), provider.enabled(),
                provider.operatorId(), provider.operatorName(), provider.updatedAt());
    }
}
