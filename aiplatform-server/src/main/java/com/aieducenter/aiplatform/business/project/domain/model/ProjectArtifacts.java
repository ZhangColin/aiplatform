package com.aieducenter.aiplatform.business.project.domain.model;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 项目工作区产物路径（相对 {@code /workspace}）：PRD 读端点、主智能体的 savePrd
 * 落盘与后续生成注入共用此一事实，勿散落字面量。正本归工作区布局常量表
 * （{@link WorkspaceLayout}），此处只是业务侧引用面。
 */
public final class ProjectArtifacts {

    /** 主智能体写出的 PRD（单最新版 markdown，事实源在工作区文件）。 */
    public static final String PRD = WorkspaceLayout.PRD;

    private ProjectArtifacts() {
    }
}
