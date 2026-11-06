package com.semgravio.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 片规模标签（#423 切片计划结构化输出扩展，#414 路由信号）：主智能体产计划时对
 * 每片自报的实现面积封闭集——小/中/大。封闭集即契约：saveBuildPlan 工具输出的
 * token 非集内值即拒（非法输出不落事实）；库交互以 code 为准（片行
 * {@code size_label}，NULL = 未设——存量计划与阶段 0 片行，消费面按「无信号」
 * 处理、存量不回补）。token 串形（小/中/大逗号接）另作模型路由升档映射配置列
 * （{@code routing_escalating_size_labels}）的存储形（#426，{@link #tokensOf}/
 * {@link #labelsOf} 编解码单源）。
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
     * 标签集 → 逗号接 token 串（路由策略配置列的存储形，#426 升档映射）：按 code
     * 定序（小,中,大）——集合入参无序，序列化形必须稳定（留痕比对与读回一致）。
     * null 透传（未设——落库 NULL 列）；空集序列化为空串（与 null＝未设分道：
     * 空串＝显式空映射）。
     */
    public static String tokensOf(java.util.Set<SliceSizeLabel> labels) {
        return labels == null ? null : String.join(",", namesOf(labels));
    }

    /**
     * 标签集 → 定序 token 列表（判定快照与配置读面的呈现形，序同 {@link #tokensOf}）。
     * 调用方保证非 null（读面与判定都以缺省映射兜底后才进）。
     */
    public static java.util.List<String> namesOf(java.util.Set<SliceSizeLabel> labels) {
        return java.util.Arrays.stream(values())
                .filter(labels::contains)
                .map(SliceSizeLabel::getName)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 逗号接 token 串 → 标签集（存储形读回，与 {@link #tokensOf} 对偶）：null＝未设
     * （null 透传，与空集分道）；空串/纯空白＝空集；未知 token 静默丢弃（读腿宽容
     * ——写腿已闭集校验，直改库的坏值不炸装配面，降级少一个标签）。
     */
    public static java.util.Set<SliceSizeLabel> labelsOf(String tokens) {
        if (tokens == null) {
            return null;
        }
        return java.util.Arrays.stream(tokens.split(","))
                .map(SliceSizeLabel::fromToken)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(
                        () -> java.util.EnumSet.noneOf(SliceSizeLabel.class)));
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
