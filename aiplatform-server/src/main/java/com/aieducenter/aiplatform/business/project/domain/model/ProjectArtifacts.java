package com.aieducenter.aiplatform.business.project.domain.model;

/**
 * 项目工作区产物路径正本（相对 {@code /workspace}）：PRD 读端点、BA 的 savePrd
 * 落盘与后续生成注入共用此一事实，勿散落字面量。布局根与目录约定的收拢归
 * workspace 布局常量表（#15）。
 */
public final class ProjectArtifacts {

    /** BA 写出的 PRD（单最新版 markdown，事实源在工作区文件）。 */
    public static final String PRD = "docs/PRD.md";

    private ProjectArtifacts() {
    }
}
