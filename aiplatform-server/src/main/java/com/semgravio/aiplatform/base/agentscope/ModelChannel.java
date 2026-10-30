package com.semgravio.aiplatform.base.agentscope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 模型通道注入件（ADR-0031 装配链的底座半边）：业务侧从模型档位/供应商通道
 * 解析（{@code ModelTierAppService}，库为唯一正本）后随命令透传——baseUrl/apiKey
 * 经创建上下文注入 SDK（覆盖依赖库默认地址与环境变量），enableThinking 显式关
 * （方舟 DeepSeek V4 标深度思考，不传参默认开/关未确证，冒烟实测后定终值）。
 *
 * <p>可为 null＝无通道语境（测试形——主链全部调用面〔三座与边缘四点，#366 收编〕
 * 一律经档位解析携通道注入；null 时 SDK 侧回落内置 provider 的环境变量/默认地址）。</p>
 *
 * <p>{@link #fingerprint()} 是<b>生效配置指纹</b>：进智能体实例缓存键
 * （{@code AgentscopeHarnessAgentFactory#obtain}）——同模型串下换 key/换地址
 * ＝指纹变＝新实例，守「改配置即生效、进行中 run 不定格」；apiKey 只以 SHA-256
 * 摘要入指纹（不进明文键——防日志/堆转储泄漏，镜像 {@code ModelRegistry}
 * 缓存指纹口径）。</p>
 *
 * @param baseUrl         通道 baseUrl（创建上下文注入）
 * @param apiKey          通道凭证（创建上下文注入；业务侧保证非空——空在解析层报错）
 * @param thinkingEnabled 深度思考开关（显式值，装配不留给模型默认）
 */
public record ModelChannel(String baseUrl, String apiKey, boolean thinkingEnabled) {

    /** 生效配置指纹（缓存键腿）：apiKey 摘要＋baseUrl＋thinking 开关。 */
    public String fingerprint() {
        return "key=" + sha256Hex(apiKey) + "|url=" + baseUrl + "|thinking=" + thinkingEnabled;
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
