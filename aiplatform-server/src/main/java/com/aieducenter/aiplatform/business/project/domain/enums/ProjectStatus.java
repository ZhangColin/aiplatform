package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 派生项目状态：归档是真实动作（archived_at），优先于派生；未归档即进行中。
 * REST 以 Integer code 传递（BaseEnum 约定），展示名随附 statusName。
 */
public enum ProjectStatus implements BaseEnum<ProjectStatus> {

    IN_PROGRESS(1, "进行中"),

    // code 2 曾是「已交付」（有无 OPEN 期的派生，随期/主链概念删除注销），码位不复用

    ARCHIVED(3, "已归档");

    private final Integer code;
    private final String name;

    ProjectStatus(Integer code, String name) {
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
}
