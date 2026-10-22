package com.aieducenter.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 模型供应商＝接入通道（ADR-0031，{@code prj_model_providers} 一行）：键名、
 * baseUrl、apiKey 与启用态。键名同时是协议路由前缀——对话轨道模型串
 * 「前缀:实际模型名」的前缀即 {@link #providerKey()}（OpenAI 兼容通道由平台
 * 代码注册模型工厂，SDK 内置前缀走原生 provider）。
 *
 * <p>apiKey 落库为<b>唯一正本</b>（无环境变量回落——空即装配明确报错）；换通道、
 * 换 key、换 baseUrl 皆不发版不重启，下一轮命令构建即生效（进行中 run 不定格）。
 * <b>接口回显一律掩码</b>（{@link #maskedApiKey()} 单点，尾 4 位——#367 后台
 * 维护面起读写面都只出掩码形，回显面零明文 key）；明文只在库内与装配链路上。</p>
 *
 * @param providerKey  键名＝协议路由前缀（volcengine/deepseek）
 * @param baseUrl      通道 baseUrl（创建上下文注入 SDK，覆盖依赖库默认地址）
 * @param apiKey       凭证唯一正本；null/空＝未配置
 * @param enabled      启用态；停用＝引用该通道的装配明确报错（不静默换道）
 * @param operatorId   最近写者 id（admin 侧 TSID；null＝种子/迁移写入）
 * @param operatorName 最近写者名（直读展示）
 * @param updatedAt    最近写入时刻（读模型列——回读填充；写路径构造时 null，
 *                     落库时刻由库定，同 {@code AgentOperationalConfig} 房规）
 */
public record ModelProvider(String providerKey, String baseUrl, String apiKey, boolean enabled,
        String operatorId, String operatorName, LocalDateTime updatedAt) {

    /** 掩码保留的尾长（尾 4 位——短于 8 的键全掩，防短键被掩码泄露大半）。 */
    private static final int MASK_TAIL_LENGTH = 4;

    private static final int MASK_MIN_PLAINTEXT_LENGTH = 8;

    /** 本行 apiKey 的掩码形（接口回显唯一形；null/空键透传 null＝未配置）。 */
    public String maskedApiKey() {
        return maskApiKey(apiKey);
    }

    /**
     * apiKey 掩码单点（尾 4 位）：键长不足 8 全掩（尾 4 即泄大半，不留「半裸」
     * 回显面）。供应商读面与留痕读面一律经此——全链无明文 key 回显。
     */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return apiKey.length() < MASK_MIN_PLAINTEXT_LENGTH
                ? "****"
                : "****" + apiKey.substring(apiKey.length() - MASK_TAIL_LENGTH);
    }
}
