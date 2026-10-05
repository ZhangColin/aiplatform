package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 设计范围作用域（#285，ADR-0025 切换受理时选）：系统→设计类切换时从功能清单
 * 锚定的页面集口径——全部页面，或勾选子集（页面级锚定从功能清单来）。仅系统＋
 * 设计项目落库（设计主线的计划对应物是 PRD 设计物清单章，不另存范围）。
 */
public enum DesignScopeType implements BaseEnum<DesignScopeType> {

    ALL_PAGES(1, "全部页面"),

    SELECTED_PAGES(2, "勾选页面");

    private final Integer code;
    private final String name;

    DesignScopeType(Integer code, String name) {
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
     * JPA Converter - 必须声明为 public static class。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<DesignScopeType> {
        public JpaConverter() {
            super(DesignScopeType.class);
        }
    }
}
