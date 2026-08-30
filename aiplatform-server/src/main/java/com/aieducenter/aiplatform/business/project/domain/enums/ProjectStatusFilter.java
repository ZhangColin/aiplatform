package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 项目列表状态过滤桶：跨枚举的派生谓词，非 {@link ProjectStatus} 的直接值域。
 * REST query param 以 Integer code 传递（框架 converter 按 code 绑定），不合法
 * 取值 400 PRJ_014。
 */
public enum ProjectStatusFilter implements BaseEnum<ProjectStatusFilter> {

    ACTIVE(1, "进行中"),

    // code 2 曾是「存在待办」（期门就绪 ∨ 等待点待处理的派生，随门/等待点待办
    // 概念删除注销），码位不复用；交易环（#21）起四态过滤重建

    ARCHIVED(3, "已归档");

    private final Integer code;
    private final String name;

    ProjectStatusFilter(Integer code, String name) {
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
