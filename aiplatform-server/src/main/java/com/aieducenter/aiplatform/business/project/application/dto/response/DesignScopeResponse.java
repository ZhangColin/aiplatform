package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.util.List;

import com.aieducenter.aiplatform.business.project.domain.enums.DesignScopeType;

/**
 * 设计范围响应（#285）：系统＋设计项目的页面锚定范围（全部/勾选——勾选标签为
 * 功能清单条目原文）；null = 无页面锚定范围（非系统＋设计，或设计主线出身不由
 * 功能清单锚定）。
 *
 * @param type     作用域（code：1=全部页面 2=勾选页面）
 * @param typeName 作用域名
 * @param pages    勾选页面标签（全部页面形为空）
 */
public record DesignScopeResponse(
        DesignScopeType type,
        String typeName,
        List<String> pages
) {
}
