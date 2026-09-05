package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 对话史条目类型（#89 对话面全量落库）：对话面 = 用户可回看的全部对话内容；
 * 过程明细（解说段 / 动作卡流水）不在此列——收尾卡已是凝聚物。
 */
public enum ConversationEntryKind implements BaseEnum<ConversationEntryKind> {

    /** 用户发言（意见 / 咨询 / 建项目开场需求——提交守卫全过后同步落）。 */
    USER(1, "用户发言"),

    /** 智能体回复（主智能体话语——轮收口或问答挂起时按段落库，段序即对话序）。 */
    AGENT(2, "智能体回复"),

    /** 问答卡（ask_user 挂起——载荷原样落库：刷新 / 回访后问答卡可重建可作答）。 */
    QUESTION(3, "问答卡"),

    /** 问答作答（作答通道回执——落库时同步置位对应问答卡的 answered）。 */
    ANSWER(4, "问答作答"),

    /** 收尾卡（编码 run 真收口的 closing 权威事实，#88 同载荷——版本锚定复用）。 */
    CLOSING(5, "收尾卡"),

    /** 平台轻引导（兜底分支的定型文案——平台自己说话，呈现自带「平台」署名）。 */
    GUIDE(6, "平台轻引导");

    private final Integer code;
    private final String name;

    ConversationEntryKind(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * JPA Converter（框架自动应用，实体字段无需 @Convert）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<ConversationEntryKind> {
        public JpaConverter() {
            super(ConversationEntryKind.class);
        }
    }
}
