package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 项目终点类型（#285，ADR-0024「项目不分型、终点是属性」）：项目下单前可变的
 * 交付物去向——设计（设计资产包交付）/ 系统 / 系统＋设计（先设计、系统从定稿
 * 设计稿长出）。入口两档显式选择定初值（做设计 = {@link #DESIGN}，缺省 {@link #SYSTEM}），
 * 下单即冻结；对齐订单交付物类型（#297 接线）。
 *
 * <p>PRD 清单章形态跟本枚举走（技能 prd-writing 双形态的选择键）：仅
 * {@link #DESIGN} 用设计形（设计物清单），{@link #SYSTEM} 与
 * {@link #SYSTEM_DESIGN} 用系统形（功能清单——后者设计硬约束入关键约束、
 * 设计范围＝功能清单页面集，不另立清单正本）。</p>
 */
public enum ProjectEndpointType implements BaseEnum<ProjectEndpointType> {

    DESIGN(1, "设计"),

    SYSTEM(2, "系统"),

    SYSTEM_DESIGN(3, "系统＋设计");

    private final Integer code;
    private final String name;

    ProjectEndpointType(Integer code, String name) {
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

    /** 终点带设计交付（设计主线或系统＋设计）——设计过程的作用域判据。 */
    public boolean designInvolved() {
        return this == DESIGN || this == SYSTEM_DESIGN;
    }

    /**
     * JPA Converter - 必须声明为 public static class。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<ProjectEndpointType> {
        public JpaConverter() {
            super(ProjectEndpointType.class);
        }
    }
}
