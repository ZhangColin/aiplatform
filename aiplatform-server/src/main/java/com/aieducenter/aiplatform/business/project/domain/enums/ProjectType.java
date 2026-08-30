package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 项目类型（业务分类字段；v1 单模板服务端缺省，分类仅作展示）。
 */
public enum ProjectType implements BaseEnum<ProjectType> {

    WEBSITE(1, "官网"),
    ECOMMERCE(2, "电商");

    private final Integer code;
    private final String name;

    ProjectType(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 缺省类型（创建不填时）：官网。
     */
    public static ProjectType orDefault(ProjectType type) {
        return type == null ? WEBSITE : type;
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
    public static class JpaConverter extends BaseEnumConverter<ProjectType> {
        public JpaConverter() {
            super(ProjectType.class);
        }
    }
}
