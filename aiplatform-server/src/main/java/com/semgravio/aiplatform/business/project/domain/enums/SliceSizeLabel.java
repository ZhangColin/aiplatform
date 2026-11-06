package com.semgravio.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 片规模标签（#423 切片计划结构化输出扩展，#414 路由信号）：主智能体产计划时对
 * 每片自报的实现面积封闭集——小/中/大。封闭集即契约：saveBuildPlan 工具输出的
 * token 非集内值即拒（非法输出不落事实）；库交互以 code 为准（片行
 * {@code size_label}，NULL = 未设——存量计划与阶段 0 片行，消费面按「无信号」
 * 处理、存量不回补）。
 */
public enum SliceSizeLabel implements BaseEnum<SliceSizeLabel> {

    /** 小：单点小改。 */
    SMALL(1, "小"),

    /** 中：常规功能片。 */
    MEDIUM(2, "中"),

    /** 大：多页面多接口的重功能片。 */
    LARGE(3, "大");

    private final Integer code;
    private final String name;

    SliceSizeLabel(Integer code, String name) {
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
     * 工具契约 token（「小」「中」「大」）→ 标签：非集内值/空返回 null
     * （调用方口径：null = 非法不入事实）。
     */
    public static SliceSizeLabel fromToken(String token) {
        if (token == null) {
            return null;
        }
        for (SliceSizeLabel size : values()) {
            if (size.name.equals(token.trim())) {
                return size;
            }
        }
        return null;
    }

    /**
     * JPA Converter（框架自动应用，实体字段无需 @Convert）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<SliceSizeLabel> {
        public JpaConverter() {
            super(SliceSizeLabel.class);
        }
    }
}
