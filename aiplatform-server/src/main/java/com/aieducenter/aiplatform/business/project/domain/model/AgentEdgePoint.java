package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.Locale;
import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;

/**
 * 边缘调用点（#366，spec #364-T2，ADR-0031 绑定层）：平台四件一次性轻调用——
 * {@link #NAMING 项目取名}、{@link #COMPACTION 上下文压缩}、{@link #CLASSIFICATION
 * 入口分类}、{@link #PRECHECK 技能预检}——的身份与模型档位缺省正本。与三座智能体
 * （{@link AgentProfile}）统一在智能体运营配置面（{@code prj_agent_configs} 同表
 * 同机制：库值优先缺省回落、留痕回滚、改完即生效），但值面<b>仅模型档位</b>——
 * 一次性判定的提示词是编排内建，不属运营配置（CONTEXT.md「智能体运营配置」）。
 *
 * <p>缺省档位＝快模型档 flash（各点原 yml/代码缺省串的等位收编——轻调用不烧重档，
 * 与 {@link AgentProfile#MAIN} 同口径）。装配腿：{@code AgentConfigAppService
 * #effectiveEdgeModelId}（库值优先）→ {@code ModelTierAppService#resolve}（档位键→
 * 模型串＋通道注入，ADR-0031 解析单点）——档位键解析产物随命令/装配透传，后台改档
 * 下一轮命令构建即生效。{@link #key()} 与配置行 {@code agent_key} 同串寻址（后台
 * 边缘点端点 {@code /agent-configs/edge/{edgeKey}}）。</p>
 */
public enum AgentEdgePoint implements BaseEnum<AgentEdgePoint> {

    /** 项目取名（创建后异步轻调用，{@code ProjectNamingAppService}）。 */
    NAMING("naming", 11, "项目取名", "flash"),

    /** 上下文压缩（会话上下文摘要，装配经底座边缘模型供数反转读，#108/ADR-0012）。 */
    COMPACTION("compaction", 12, "上下文压缩", "flash"),

    /** 入口分类（对话区消息三分类，{@code DispatchAppService}，#51）。 */
    CLASSIFICATION("classification", 13, "入口分类", "flash"),

    /** 技能预检（指派双头重叠判定，{@code BackofficeSkillPrecheckAppService}，#255）。 */
    PRECHECK("precheck", 14, "技能预检", "flash");

    private final String key;
    private final Integer code;
    private final String name;
    private final String modelId;

    AgentEdgePoint(String key, Integer code, String name, String modelId) {
        this.key = key;
        this.code = code;
        this.name = name;
        this.modelId = modelId;
    }

    /** 框架约定码（domain 枚举实现 BaseEnum 的自动转换面；本配置不落库不经 REST）。 */
    @Override
    public Integer getCode() {
        return code;
    }

    /** 寻址稳定键（配置行 agent_key／后台边缘点端点路径段共用）。 */
    public String key() {
        return key;
    }

    /** 展示名（后台配置页直读）。 */
    public String getName() {
        return name;
    }

    /** 缺省模型档位键（ADR-0031 语义键；库值优先的回落腿正本）。 */
    public String modelId() {
        return modelId;
    }

    /**
     * 按稳定键解析（大小写不敏感，同 {@link AgentProfile#byKey} 形制）：
     * 空键/未知键返回空（调用方定 404 语义）。
     */
    public static Optional<AgentEdgePoint> byKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(AgentEdgePoint.valueOf(key.trim().toUpperCase(Locale.ROOT)));
        }
        catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
