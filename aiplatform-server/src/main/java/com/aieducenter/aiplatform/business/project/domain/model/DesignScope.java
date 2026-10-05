package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.List;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.project.domain.enums.DesignScopeType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 设计范围（#285，ADR-0025）：系统→设计类切换受理时选的作用域——全部页面或
 * 从功能清单勾选的页面子集（标签为功能清单条目原文，供 PRD 重产指令锚定与设计
 * 过程圈定设计物；PRD 是模型独笔演进的正本，标签是建议性锚不是稳定标识）。
 * 勾选形必须非空（空集无意义——那是不做设计，不是做部分设计）。
 */
public record DesignScope(DesignScopeType type, List<String> pages) {

    /** 全部页面形（无标签集）。 */
    public static DesignScope allPages() {
        return new DesignScope(DesignScopeType.ALL_PAGES, List.of());
    }

    /** 勾选页面形（至少一页，空白条目剔除后为空即拒绝）。 */
    public static DesignScope selected(List<String> pages) {
        List<String> cleaned = pages == null ? List.of()
                : pages.stream().filter(page -> page != null && !page.isBlank()).toList();
        if (cleaned.isEmpty()) {
            throw new DomainException(ProjectMessage.DESIGN_SCOPE_PAGES_REQUIRED);
        }
        return new DesignScope(DesignScopeType.SELECTED_PAGES, cleaned);
    }

    /** 范围句（PRD 重产指令与事实清单的共用读面）：全部页面 / 勾选 N 页逐项列举。 */
    public String describe() {
        if (type == DesignScopeType.ALL_PAGES) {
            return "全部页面";
        }
        return "勾选页面（功能清单）：" + String.join("、", pages);
    }
}
