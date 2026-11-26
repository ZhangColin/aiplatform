package com.semgravio.aiplatform.base.workspace.domain.model;

/**
 * 工作区确定性命名（CONTEXT.md「置备状态」配套）：containerName / 预览网络 / 预览 URL /
 * databaseName 是 workspaceId 的纯函数——记录创建（{@code registerPending}）与环境
 * 后端（docker 置备）同源派生。确定性命名是「记录先于副作用存在」与销毁级联的根基：
 * 容器/库名无需回读 docker 即可从 workspaceId 推导。
 *
 * <p>预览网关化（#128）后：容器名去 kind 后缀为裸 {@code ws-{id}}（网关按子域
 * {@code {id}.localhost} → {@code ws-{id}:8081} 路由，名字必须与子域一一对应）；所有
 * 工作区容器进同一共享网络 {@link #PREVIEW_NETWORK}（不再有 per-workspace 专属网络、
 * 不再随机映射宿主端口）；预览 URL 是 workspaceId + 预览基域名的纯函数。</p>
 */
public final class WorkspaceNaming {

    /** 平台预览网关共享网络（#128）：网关与全部工作区容器同网，按容器名 DNS 路由。 */
    public static final String PREVIEW_NETWORK = "previewnet";

    /**
     * 预览网关在 previewnet 的稳定寻址别名（#397 网络别名契约）：dev 网关
     * （aiplatform-gateway）与生产共享 nginx（semgravio-nginx）都在本网络挂同一
     * 别名——收口探针从工作区容器内经 {@code http://<本别名>/}＋Host 头（选
     * {@code {id}.{previewBase}} 路由）打到网关，观察点与用户浏览器一致。别名
     * 缺席＝网关不可达，收口判据如实为红（WSP_012 待期），不误报绿。
     */
    public static final String PREVIEW_GATEWAY_ALIAS = "preview-gateway";

    private WorkspaceNaming() {
    }

    /** 工作区容器名（exec / 预览 / 级联清理的锚点；裸 {@code ws-{id}}，无 kind 后缀）。 */
    public static String containerName(WorkspaceId workspaceId) {
        return "ws-" + workspaceId.value();
    }

    /** 容器内应用库名（角色与库同名）：连接串与镜像自愈脚本（WORKSPACE_DB）共用。 */
    public static String databaseName(WorkspaceId workspaceId) {
        return "ws" + workspaceId.value();
    }

    /**
     * 预览 URL（#128 网关化、#129 生产化）：workspaceId 子域 + scheme + 预览基域名的
     * 纯函数——开发 {@code http://{id}.localhost/}（关 TLS），生产
     * {@code https://{id}.preview.{domain}/}（wildcard 证书 TLS 终止）。scheme 随
     * profile 供给（{@code app.workspace.preview-scheme}），非写死 http。
     */
    public static String previewUrl(WorkspaceId workspaceId, String scheme, String previewBase) {
        return scheme + "://" + workspaceId.value() + "." + previewBase + "/";
    }
}
