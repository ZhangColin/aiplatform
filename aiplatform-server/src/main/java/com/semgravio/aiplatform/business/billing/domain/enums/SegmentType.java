package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 结算段类型（CONTEXT「结算段」，ADR-0047 段间硬门）：主智能体一轮、一场生成
 * run、一场设计会话轮各为一段——段收口即结算一次；固定单价项（联网搜索等）
 * 不等段收口、按次直记（第 4 值专用于此）。段内绝不检查余额绝不中断，检查只
 * 在段边界（#537 消费面）。
 */
public enum SegmentType implements BaseEnum<SegmentType> {

    MAIN_TURN(1, "主智能体轮"),
    GENERATION_RUN(2, "run 场"),
    DESIGN_TURN(3, "设计会话轮"),
    FIXED_ITEM(4, "固定单价项");

    private final Integer code;
    private final String name;

    SegmentType(Integer code, String name) {
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

    /** 是否段收口型（三段型）——固定单价项直记不是段。 */
    public boolean isSegment() {
        return this != FIXED_ITEM;
    }

    /**
     * JPA Converter（框架自动应用，实体字段无需 @Convert）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<SegmentType> {
        public JpaConverter() {
            super(SegmentType.class);
        }
    }
}
