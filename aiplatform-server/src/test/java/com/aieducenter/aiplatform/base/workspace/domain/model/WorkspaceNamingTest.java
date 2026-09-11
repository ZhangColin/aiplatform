package com.aieducenter.aiplatform.base.workspace.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性命名纯函数：containerName / 预览 URL 是 workspaceId 的纯函数（与 docker
 * 后端同源派生，记录创建与置备不劈叉）。
 */
class WorkspaceNamingTest {

    @Test
    void given_workspace_id_when_container_name_then_bare_deterministic() {
        // #128 网关化：容器名裸 ws-{id}（无 kind 后缀）——网关按子域 {id}.localhost
        // → ws-{id}:8081 路由，名字必须与子域一一对应
        assertThat(WorkspaceNaming.containerName(WorkspaceId.of("42")))
                .isEqualTo("ws-42");
    }

    @Test
    void given_preview_network_when_queried_then_shared_previewnet() {
        // 所有工作区容器进同一共享网络（网关同网按容器名 DNS 路由）
        assertThat(WorkspaceNaming.PREVIEW_NETWORK).isEqualTo("previewnet");
    }

    @Test
    void given_workspace_id_when_database_name_then_deterministic() {
        // 容器内应用库名（角色与库同名）：连接串与镜像自愈脚本（WORKSPACE_DB）共用
        assertThat(WorkspaceNaming.databaseName(WorkspaceId.of("42"))).isEqualTo("ws42");
    }

    @Test
    void given_workspace_id_and_base_when_preview_url_then_subdomain() {
        // 预览 URL = scheme + 子域（开发 http://{id}.localhost；生产 https://{id}.preview.{domain}）
        assertThat(WorkspaceNaming.previewUrl(WorkspaceId.of("42"), "http", "localhost"))
                .isEqualTo("http://42.localhost/");
        assertThat(WorkspaceNaming.previewUrl(WorkspaceId.of("42"), "https", "preview.example.com"))
                .isEqualTo("https://42.preview.example.com/");
    }

    @Test
    void given_workspace_id_when_snapshot_name_then_deterministic() {
        // 快照容器名（#92）：主容器「ws-42」的快照变体「ws-42-snap-{viewId}」，
        // 前缀供销毁级联按名扫清在途查看会话
        assertThat(WorkspaceNaming.snapshotContainerName(WorkspaceId.of("42"), "view-7"))
                .isEqualTo("ws-42-snap-view-7");
        assertThat(WorkspaceNaming.snapshotContainerPrefix(WorkspaceId.of("42")))
                .isEqualTo("ws-42-snap-");
    }

    @Test
    void given_view_id_when_snapshot_alias_then_subdomain_label() {
        // #141 网关化：previewnet 内 DNS 别名 snap-{viewId} 与子域 label 一一对应——
        // 容器名带 ws-{id}- 前缀供级联清理（不改名），别名桥接「子域 → 容器」路由
        assertThat(WorkspaceNaming.snapshotNetworkAlias("483920104737"))
                .isEqualTo("snap-483920104737");
    }

    @Test
    void given_view_id_and_base_when_snapshot_preview_url_then_snap_subdomain() {
        // 快照预览 URL（#141）= scheme + snap-{viewId} 单层子域：开发
        // http://snap-{id}.localhost；生产 https://snap-{id}.preview.{domain}——单层
        // 落在主预览已有 *.preview.{domain} 通配 DNS/证书内（无需二层通配）
        assertThat(WorkspaceNaming.snapshotPreviewUrl("483920104737", "http", "localhost"))
                .isEqualTo("http://snap-483920104737.localhost/");
        assertThat(WorkspaceNaming.snapshotPreviewUrl("483920104737", "https", "preview.example.com"))
                .isEqualTo("https://snap-483920104737.preview.example.com/");
    }
}
