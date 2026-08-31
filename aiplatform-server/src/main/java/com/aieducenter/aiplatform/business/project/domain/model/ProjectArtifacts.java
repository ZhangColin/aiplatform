package com.aieducenter.aiplatform.business.project.domain.model;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 项目工作区产物路径（相对 {@code /workspace}）：PRD 读端点、BA 的 savePrd
 * 落盘与后续生成注入共用此一事实，勿散落字面量。正本归工作区布局常量表
 * （{@link WorkspaceLayout}），此处只是业务侧引用面。
 */
public final class ProjectArtifacts {

    /** BA 写出的 PRD（单最新版 markdown，事实源在工作区文件）。 */
    public static final String PRD = WorkspaceLayout.PRD;

    /**
     * PRD 固定七章节依序清单（#20）：访谈协议（BA systemPrompt）与 savePrd
     * 工具描述共用的单点事实——改章节只动这里。
     */
    public static final String PRD_SECTIONS =
            "需求背景、目标用户、核心场景、范围边界、关键约束、功能清单、待定项";

    private ProjectArtifacts() {
    }
}
