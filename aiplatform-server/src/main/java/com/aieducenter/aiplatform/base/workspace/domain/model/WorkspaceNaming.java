package com.aieducenter.aiplatform.base.workspace.domain.model;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;

/**
 * 工作区确定性命名（CONTEXT.md「置备状态」配套）：containerName / networkName /
 * databaseName 是 workspaceId 的纯函数——记录创建（{@code registerPending}）与环境
 * 后端（docker 置备）同源派生，端口置备中置 0、docker 完成后回填。确定性命名是
 * 「记录先于副作用存在」与销毁级联的根基：容器/库名无需回读 docker 即可从
 * workspaceId 推导。
 */
public final class WorkspaceNaming {

    private WorkspaceNaming() {
    }

    /** 工作区容器名（exec / 预览 / 级联清理的锚点）。 */
    public static String containerName(WorkspaceId workspaceId, EnvKind kind) {
        return "ws-" + workspaceId.value() + suffixOf(kind);
    }

    /** 项目专属 docker network 名。 */
    public static String networkName(WorkspaceId workspaceId) {
        return "net-" + workspaceId.value();
    }

    /** 容器内应用库名（角色与库同名）：连接串与镜像自愈脚本（WORKSPACE_DB）共用。 */
    public static String databaseName(WorkspaceId workspaceId) {
        return "ws" + workspaceId.value();
    }

    /** 快照容器名（#92 查看会话）：主容器名「ws-{id}-dev」的快照变体「ws-{id}-snap-{viewId}」。 */
    public static String snapshotContainerName(WorkspaceId workspaceId, String viewId) {
        return "ws-" + workspaceId.value() + "-snap-" + viewId;
    }

    /** 快照容器名前缀（#92 销毁级联）：主容器销毁时按此前缀扫清在途查看会话。 */
    public static String snapshotContainerPrefix(WorkspaceId workspaceId) {
        return "ws-" + workspaceId.value() + "-snap-";
    }

    private static String suffixOf(EnvKind kind) {
        return switch (kind) {
            case DEV -> "-dev";
            case TEST -> "-test";
            case PROD -> "-prod";
        };
    }
}
