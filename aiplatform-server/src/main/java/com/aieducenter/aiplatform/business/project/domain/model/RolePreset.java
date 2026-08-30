package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.Locale;
import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 业务角色卡 preset（代码配置不落库）：v1 资产里只有 BA 一个入口智能体
 * （#8：入口与内部分派是智能体资产问题，不是平台代码问题；编码智能体的资产
 * 随生成环（#22）落位）。systemPrompt 即访谈协议，modelId 是对话内核模型档位。
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）；SSE payload 用枚举名（稳定键），
 * {@link #byName} 是两者间解析口。</p>
 */
public enum RolePreset implements BaseEnum<RolePreset> {

    BA(1, "需求分析师", "deepseek-v4-flash",
            // systemPrompt 即访谈协议：多轮澄清（ask_user）→ 判定明确停止提问 →
            // 催促即收敛；产出协议（判定明确/催促 → savePrd 落 PRD 全文）
            "你是平台的需求分析师（BA），以访谈方式帮用户把一句话想法梳理成明确需求。工作协议：\n"
                    + "1. 开场：简要回应用户的初始想法（欢迎 + 你的初步理解），随即调用 ask_user 工具提出第一个澄清问题。\n"
                    + "2. 每轮只问一个问题：围绕目标用户、核心场景、范围边界、关键约束等对需求影响最大的缺口；"
                    + "question 填问题文本、header 填主题短标签、options 给 2-4 个候选（开放问题可不填选项）。\n"
                    + "3. 收到答复后消化信息，仍有必要缺口就继续追问；一次只解决一个缺口。\n"
                    + "4. 判定明确的标准：目标用户、核心场景、范围边界、关键约束四方面都有用户确认的信息，"
                    + "且没有必须追问的缺口；缺任一方面就继续追问，宁可多问一轮，不得替用户假设后收敛。\n"
                    + "5. 判定明确即停止提问（不再调用 ask_user），立即调用 savePrd 工具保存 PRD："
                    + "content 传完整 PRD markdown 全文（含需求背景、目标用户、核心场景、范围边界、关键约束、待定项）。"
                    + "保存成功后向用户输出简短总结（PRD 已产出 + 核心要点 + 待定项）。\n"
                    + "6. 催促收敛（优先级高于第 4 条）：用户表达催促（如「直接出 PRD」「别问了」「不要再问了」——"
                    + "无论在答复还是补充消息里说的）时，即使四方面仍有缺口也立即停止提问（不再调用 ask_user），"
                    + "基于已有信息调用 savePrd 保存 PRD（缺口列入待定项），随后输出简短总结。\n"
                    + "7. PRD 修订：用户对已产出的 PRD 提出修改意见时，消化意见后再次调用 savePrd 保存修订后的"
                    + "完整 PRD 全文（覆盖旧版），随后告知用户 PRD 已更新。\n"
                    + "全程使用中文；除 savePrd 外不写文件、不写业务代码。");

    /** 缺省 BA 访谈的开场提示（建项目未附需求描述时的对话展开起点）。 */
    public static final String DEFAULT_KICKOFF_PROMPT =
            "请开始梳理本项目需求：向用户确认项目目标、范围与关键诉求。";

    private final Integer code;
    private final String name;
    private final String modelId;
    private final String systemPrompt;

    RolePreset(Integer code, String name, String modelId, String systemPrompt) {
        this.code = code;
        this.name = name;
        this.modelId = modelId;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /** 该角色的模型档位（对话内核模型条目名）。 */
    public String modelId() {
        return modelId;
    }

    /**
     * 对话轨道模型串（AgentScope {@code provider:modelId} 形）：provider 与
     * {@code ModelRef} 白名单一致（当前仅 deepseek，加白时同步）。
     */
    public String chatModelString() {
        return "deepseek:" + modelId;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 按枚举名解析角色卡（SSE payload / 计量 dims 的稳定键；大小写不敏感）：
     * 空名/未知名返回空。
     */
    public static Optional<RolePreset> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(RolePreset.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * JPA Converter（框架自动应用——preset 代码配置不落库，仅 REST 编解码用）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<RolePreset> {
        public JpaConverter() {
            super(RolePreset.class);
        }
    }
}
