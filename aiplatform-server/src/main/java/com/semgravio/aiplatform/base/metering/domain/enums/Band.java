package com.semgravio.aiplatform.base.metering.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 时段带枚举（ADR-0044 峰谷维度）：供应商峰谷定价的归带结果——峰/谷两值。时段
 * 规则行（{@code met_time_band_rules}）按窗口行声明带：事件 ts 命中窗口→该行
 * 带；无窗口命中→补带（声明了峰窗→谷、只声明谷窗→峰——DeepSeek 峰窗显式声明、
 * 其余谷，谷不种行）；通道无生效规则行→不带（不看时段，存量语义）。单价表时段
 * 带列（本批姊妹票）匹配键成分同名同码。
 *
 * <p>落库与契约均为 Integer code（#34 收敛房规，BaseEnumConverter autoApply——
 * 落库值真库断言钉死防 ORDINAL 错位）。「谷」位不用「平」：ADR-0044 裁决词汇
 * 即峰/谷（DeepSeek peak/off-peak 对译），不引第三带。</p>
 */
public enum Band implements BaseEnum<Band> {

    PEAK(1, "峰"),
    OFF_PEAK(2, "谷");

    private final Integer code;
    private final String name;

    Band(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<Band> {
        public JpaConverter() {
            super(Band.class);
        }
    }
}
