package com.aieducenter.aiplatform.business.project.application;

/**
 * 平台通知事件名册常量（ADR-0001：代码侧每 BC 一个 EventTypes 常量类，禁止字符串
 * 字面量散落；正本见 docs/spec/SSE事件清单.md·通道一）。本上下文编排层在副作用
 * 真实落定后发射（base 不发 SSE——底座零业务概念的自然推论）。
 */
public final class ProjectEventTypes {

    /** 工作区记录已建（容器后台置备中，建项目副作用落定后；非「容器就绪」）。 */
    public static final String WORKSPACE_CREATED = "workspace-created";

    /** 预览已就绪（端口真实暴露后的 SSE 呈现）。 */
    public static final String PREVIEW_READY = "preview-ready";

    /** 工作区已销毁（删除级联清理落定后）。 */
    public static final String WORKSPACE_DESTROYED = "workspace-destroyed";

    /**
     * 文档已更新：工作区文档产物写出/修订落定后广播（v1 唯一写入方 = BA 的
     * savePrd 工具，落盘成功经 PrdArtifactAdapter 发射，每次执行必发）。前端按
     * 失效为主模式消费——invalidate 文档域后重拉 REST，不携带内容增量。
     */
    public static final String DOCUMENT_UPDATED = "document-updated";

    /**
     * 项目已改名（触达补口）：异步取名落库成功（顶替占位名）后发射——载荷
     * projectId + projectName，前端失效 projects 域重拉（停留中的页面名字静默
     * 浮现，ChatGPT 式）。守卫不覆写（用户已改名/取名已完成）与取名失败保占位
     * 均不发。
     */
    public static final String PROJECT_RENAMED = "project-renamed";

    // ---------- payload 契约键（SSE事件清单·通道一） ----------

    /** 关联字段（通知通道 streamId 同值）。 */
    public static final String PROJECT_ID_FIELD = "projectId";

    /** 项目名。 */
    public static final String PROJECT_NAME_FIELD = "projectName";

    /** dev 容器名。 */
    public static final String CONTAINER_FIELD = "container";

    /** 项目类型（枚举名）。 */
    public static final String PROJECT_TYPE_FIELD = "projectType";

    /** 智能体栈名（单栈常量）。 */
    public static final String ENGINE_FIELD = "engine";

    /** 预览 URL（preview-ready）。 */
    public static final String URL_FIELD = "url";

    /** 文档类型（document-updated；v1 仅 PRD）。 */
    public static final String DOCUMENT_TYPE_FIELD = "documentType";

    /** 文档类型值：PRD（docs/PRD.md 写出/修订）。 */
    public static final String DOCUMENT_TYPE_PRD = "PRD";

    private ProjectEventTypes() {
    }
}
