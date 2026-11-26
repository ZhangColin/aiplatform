package com.semgravio.aiplatform.business.project.domain.model;

import java.util.Locale;
import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;

/**
 * 边缘调用点（#366，spec #364-T2，ADR-0031 绑定层）：平台一次性轻调用——
 * {@link #NAMING 项目取名}、{@link #COMPACTION 上下文压缩}、{@link #CLASSIFICATION
 * 入口分类}、{@link #REFINEMENT 候选经验提炼}（#641，ADR-0058）——的身份与模型
 * 配置缺省正本。与智能体（{@link AgentProfile}）统一在智能体运营配置面
 * （{@code prj_agent_configs} 同表同机制：库值优先缺省回落、留痕回滚、改完即
 * 生效），但值面<b>仅模型配置</b>——一次性判定的提示词是编排内建，不属运营配置
 * （CONTEXT.md「智能体运营配置」）。技能预检边缘点随 #638 阶段六技能清场退场
 * （预检从未运行，配置行由 V91 迁移删除）。
 *
 * <p>缺省配置＝快模型档 flash（各点原 yml/代码缺省串的等位收编——轻调用不烧重档，
 * 与 {@link AgentProfile#MAIN} 同口径）。装配腿：{@code AgentConfigAppService
 * #effectiveEdgeModelConfigKey}（库值优先）→ {@code ModelConfigAppService#resolve}（配置键→
 * 模型串＋通道注入，ADR-0031 解析单点）——配置键解析产物随命令/装配透传，后台改配置
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

    /** 候选经验提炼（每出一个用户面版本的异步后台提炼，{@code ExperienceDistillationAppService}，#641）。 */
    REFINEMENT("refinement", 14, "候选经验提炼", "flash");

    private final String key;
    private final Integer code;
    private final String name;
    private final String modelConfigKey;

    AgentEdgePoint(String key, Integer code, String name, String modelConfigKey) {
        this.key = key;
        this.code = code;
        this.name = name;
        this.modelConfigKey = modelConfigKey;
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

    /** 缺省模型配置键（ADR-0031 语义键；库值优先的回落腿正本）。 */
    public String modelConfigKey() {
        return modelConfigKey;
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
