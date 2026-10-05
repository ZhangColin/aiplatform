package com.aieducenter.aiplatform.business.project.application.dto.command;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.aieducenter.aiplatform.business.project.domain.enums.DesignScopeType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;

/**
 * 切换终点类型命令（#285 设置 tab 终点控件）：终点类型必填（Integer code，框架
 * 转 {@link ProjectEndpointType}）；设计范围作用域可选——系统→设计类切换（PRD
 * 已产出、功能清单在）必填（编排按项目态判 PRJ_040），scopePages 为功能清单条目
 * 标签原文（建议性锚——PRD 是模型独笔演进的正本，不作稳定标识）。
 *
 * @param endpointType 目标终点类型（code：1=设计 2=系统 3=系统＋设计）
 * @param scopeType    设计范围作用域（code：1=全部页面 2=勾选页面；可空）
 * @param scopePages   勾选页面标签（scopeType=勾选时必非空，编排判 PRJ_041）
 */
public record SwitchEndpointTypeCommand(

        @NotNull(message = "终点类型不能为空")
        ProjectEndpointType endpointType,

        DesignScopeType scopeType,

        @Size(max = 50, message = "设计范围页面数不能超过50")
        List<@Size(max = 200, message = "页面标签长度不能超过200") String> scopePages
) {
}
