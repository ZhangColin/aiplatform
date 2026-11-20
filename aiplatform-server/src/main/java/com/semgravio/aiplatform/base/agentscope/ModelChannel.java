package com.semgravio.aiplatform.base.agentscope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 模型通道注入件（ADR-0031 装配链的底座半边）：业务侧从模型配置/供应商通道
 * 解析（{@code ModelConfigAppService}，库为唯一正本）后随命令透传——baseUrl/apiKey
 * 经创建上下文注入 SDK（覆盖依赖库默认地址与环境变量），enableThinking 显式
 * 表态（值随配置行走——#370 运营配置，缺省关＝#369 活体结论；装配不留给
 * 模型默认）。
 *
 * <p>可为 null＝无通道语境（测试形——主链全部调用面〔三座与边缘四点，#366 收编〕
 * 一律经配置解析携通道注入；null 时 SDK 侧回落内置 provider 的环境变量/默认地址）。
 * {@code vendor}（ADR-0053 ②）＝通道行的归一供应商维度（deepseek/deepseek-2 同填
 * deepseek）：计量事件按模型统计与单价表/峰谷规则匹配键的腿，随候选行走（failover
 * 切候选后 vendor 随实际命中通道行归因）；可空＝无通道语境，模型边界回落 provider
 * 前缀同值（{@code MeteredModel}，V79 历史回填同款语义）。</p>
 *
 * <p>{@link #fingerprint()} 是<b>生效配置指纹</b>：进智能体实例缓存键
 * （{@code AgentscopeHarnessAgentFactory#obtain}）——同模型串下换 key/换地址
 * ＝指纹变＝新实例，守「改配置即生效、进行中 run 不定格」；vendor 同入指纹
 * （改 vendor＝新实例——缓存实例的计量归因不滞留旧值）。apiKey 只以 SHA-256
 * 摘要入指纹（不进明文键——防日志/堆转储泄漏，镜像 {@code ModelRegistry}
 * 缓存指纹口径）。</p>
 *
 * @param baseUrl         通道 baseUrl（创建上下文注入）
 * @param apiKey          通道凭证（创建上下文注入；业务侧保证非空——空在解析层报错）
 * @param thinkingEnabled 深度思考开关（显式值，装配不留给模型默认）
 * @param vendor          归一供应商（计量双维度腿；可空＝无通道语境，同上回落）
 */
public record ModelChannel(String baseUrl, String apiKey, boolean thinkingEnabled, String vendor) {

    /** 兼容构造（无通道语境的测试形：vendor 空）。 */
    public ModelChannel(String baseUrl, String apiKey, boolean thinkingEnabled) {
        this(baseUrl, apiKey, thinkingEnabled, null);
    }

    /**
     * vendor 归一单点（V79 同值语义，ADR-0053 ②）：行值非空用行值（deepseek/
     * deepseek-2 同填 deepseek 的归一腿在行值上——写面带受控值随 #559）；空
     * （pre-T5 写面建的行/无通道语境）回落通道键（provider 前缀）同值——供应商
     * 即账号本体的同值映射，解析与模型边界计量归一共用本口。
     */
    public static String normalizedVendor(String providerKey, String vendor) {
        return vendor == null || vendor.isBlank() ? providerKey : vendor;
    }

    /** 生效配置指纹（缓存键腿）：apiKey 摘要＋baseUrl＋thinking 开关＋vendor。 */
    public String fingerprint() {
        return "key=" + sha256Hex(apiKey) + "|url=" + baseUrl + "|thinking=" + thinkingEnabled
                + "|vendor=" + (vendor == null ? "" : vendor);
    }

    private static String sha256Hex(String value) {
        if (value == null) {
            return "<null>";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
