package com.semgravio.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 项目终点类型（#285，ADR-0024「项目不分型、终点是属性」；<b>#612 收窄为系统
 * 单值</b>：设计线整条退役，项目只剩系统一种终点——{@code DESIGN}／
 * {@code SYSTEM_DESIGN} 退场，{@code prj_projects.endpoint_type} 列拆除）。
 *
 * <p>码位保留 2=系统（与 {@code OrderDeliverableType} 的历史码表对齐；终点概念
 * 收窄后所有项目恒为系统）。</p>
 */
public enum ProjectEndpointType implements BaseEnum<ProjectEndpointType> {

    SYSTEM(2, "系统");

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
