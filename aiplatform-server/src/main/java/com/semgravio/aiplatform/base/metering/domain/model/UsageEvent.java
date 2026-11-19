package com.semgravio.aiplatform.base.metering.domain.model;

import java.time.Instant;
import java.util.Map;

import com.cartisan.core.exception.DomainException;

import com.semgravio.aiplatform.base.metering.domain.error.MeteringMessage;

/**
 * 用量事件协议（A1 §2.2，研究稿 F4.2 照收）：一次上报 = 一条 run 级计量记录。
 *
 * <p>字段语义：{@code eventId} 调用方生成的幂等键（重复上报 first-write-wins）；
 * {@code subject} 不透明归属 id（业务层传 projectId，底座不解释）；{@code runId}/
 * {@code sessionId} 运行与会话寻址（可空——非 run 级来源可不带）；{@code provider}
 * 账号维度（通道行键——按账号统计的归属腿）；{@code vendor} 供应商维度（归一
 * 供应商——按模型统计〔vendor+model 跨账号合并〕与单价表/峰谷规则的匹配键，
 * ADR-0053 ②；与 provider 同值＝供应商即账号本体〔图片/搜索等供数方、历史
 * 回填行〕，不同值＝同供应商多账号〔deepseek/deepseek-2 同填 deepseek〕）；
 * {@code model} 实际模型名；
 * {@code dims} 业务维度透传（终态口径 projectId + agentKind(ba/coder) + sessionId，
 * 底座不解释，可空）；{@code tokens} 五档互斥分解（见 {@link TokenUsage}）；
 * {@code images} 按张用量（#288 图片生成与模型调用并列的计量事件——按张事件
 * token 五档为零、token 事件张数为零，两量并列不混算）；{@code units} 按件用量
 * （#531 固定单价项族——联网搜索按次等，按件事件 token 五档与张数均为零，
 * 计量仍只记用量与件数、零商业概念）。run 级一条：
 * step-finish 增量求和是 adapter 内部实现，不进协议。</p>
 */
public record UsageEvent(
        String eventId,
        Instant ts,
        String subject,
        String runId,
        String sessionId,
        String provider,
        String vendor,
        String model,
        Map<String, String> dims,
        TokenUsage tokens,
        long images,
        long units) {

    /**
     * 维度键：智能体种类（dims 协议词表之一——写侧终态口径成分，读侧分智能体
     * 分桶按此过滤；键名非用户输入，内联常量不占位）。
     */
    public static final String DIM_KEY_AGENT_KIND = "agentKind";

    public UsageEvent {
        if (images < 0) {
            throw new DomainException(MeteringMessage.USAGE_IMAGES_NEGATIVE);
        }
        if (units < 0) {
            throw new DomainException(MeteringMessage.USAGE_UNITS_NEGATIVE);
        }
    }

    /**
     * token 事件便捷构造（按张/按件用量 = 0；vendor 归一 provider 同值——供应商
     * 即账号本体的供数方〔图片/搜索等〕与既有模型调用面计量口径不变）。
     */
    public UsageEvent(
            String eventId, Instant ts, String subject, String runId, String sessionId,
            String provider, String model, Map<String, String> dims, TokenUsage tokens) {
        this(eventId, ts, subject, runId, sessionId, provider, provider, model, dims, tokens, 0, 0);
    }

    /** 按张事件便捷构造（token 五档为零、按件用量 = 0；vendor 同值口径同上，#288）。 */
    public UsageEvent(
            String eventId, Instant ts, String subject, String runId, String sessionId,
            String provider, String model, Map<String, String> dims, TokenUsage tokens,
            long images) {
        this(eventId, ts, subject, runId, sessionId, provider, provider, model, dims, tokens,
                images, 0);
    }
}
