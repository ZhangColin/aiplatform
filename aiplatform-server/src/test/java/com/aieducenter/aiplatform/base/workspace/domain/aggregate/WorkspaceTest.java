package com.aieducenter.aiplatform.base.workspace.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.base.workspace.domain.entity.MiddlewareResource;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.ProvisionedResource;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作区聚合：注册不变量、资源登记幂等、句柄重建（重启接回的域内前提）。
 * 预览网关化（#128）后无宿主端口——容器名裸 ws-{id}、进共享预览网络 previewnet。
 */
class WorkspaceTest {

    private static final WorkspaceId ID = WorkspaceId.of("42");

    @Test
    void given_valid_input_when_register_dev_then_workspace_created() {
        Workspace workspace = Workspace.register(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet")));

        assertThat(workspace.getKind()).isEqualTo(EnvKind.DEV);
        assertThat(workspace.getId()).isEqualTo(42L);
        assertThat(workspace.workspaceId()).isEqualTo(ID);
        assertThat(workspace.getContainerName()).isEqualTo("ws-42");
        assertThat(workspace.getNetworkName()).isEqualTo("previewnet");
    }

    @Test
    void given_runtime_kind_when_register_then_no_preview_network() {
        Workspace workspace = Workspace.register(WorkspaceProvision.of(
                WorkspaceHandle.runtime(ID, EnvKind.TEST, "ws-42", "previewnet")));

        assertThat(workspace.getKind()).isEqualTo(EnvKind.TEST);
        assertThat(workspace.getContainerName()).isEqualTo("ws-42");
    }

    @Test
    void given_blank_fields_when_register_then_rejected() {
        assertThatThrownBy(() -> Workspace.register(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, " ", "previewnet"))))
                .isInstanceOf(DomainException.class);
        // 构造不变量各分支逐一（WSP_005）：空标识 / 空容器名 / 空网络名
        assertThatThrownBy(() -> Workspace.dev(null, "ws-42", "previewnet"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("工作区字段不完整");
        assertThatThrownBy(() -> Workspace.dev(ID, "ws-42", " "))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Workspace.dev(ID, "ws-42", null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_dev_workspace_when_dev_factory_then_created() {
        // dev 显式工厂（与 register(dev 供给) 等价的直接路径）
        Workspace workspace = Workspace.dev(ID, "ws-42", "previewnet");

        assertThat(workspace.getKind()).isEqualTo(EnvKind.DEV);
    }

    @Test
    void given_dev_kind_when_runtime_factory_then_rejected() {
        assertThatThrownBy(() -> Workspace.runtime(ID, EnvKind.DEV, "c", "n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_same_kind_resource_when_register_twice_then_only_latest_kept() {
        Workspace workspace = Workspace.dev(ID, "ws-42", "previewnet");

        workspace.registerResource(new MiddlewareResource(42L, MiddlewareKind.POSTGRESQL,
                "pg-old", 5432, "postgresql://old"));
        workspace.registerResource(new MiddlewareResource(42L, MiddlewareKind.POSTGRESQL,
                "pg-new", 5433, "postgresql://new"));

        assertThat(workspace.getResources()).hasSize(1);
        assertThat(workspace.getResources().iterator().next().getContainerName()).isEqualTo("pg-new");
    }

    @Test
    void given_provision_with_resources_when_register_then_resources_attached() {
        WorkspaceProvision provision = WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet"),
                new ProvisionedResource(MiddlewareKind.POSTGRESQL, "pg-42", 35432, "postgresql://pg"),
                new ProvisionedResource(MiddlewareKind.REDIS, "rd-42", 36379, "redis://rd"));

        Workspace workspace = Workspace.register(provision);

        assertThat(workspace.getResources()).hasSize(2);
        assertThat(workspace.getResources())
                .extracting(MiddlewareResource::getInternalUrl)
                .containsExactlyInAnyOrder("postgresql://pg", "redis://rd");
    }

    @Test
    void given_registered_workspace_when_to_handle_then_round_trip() {
        Workspace workspace = Workspace.register(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet")));

        WorkspaceHandle handle = workspace.toHandle();

        // 重启接回：记录 → 句柄无损重建（exec/销毁的寻址锚点）
        assertThat(handle.workspaceId()).isEqualTo(ID);
        assertThat(handle.kind()).isEqualTo(EnvKind.DEV);
        assertThat(handle.containerName()).isEqualTo("ws-42");
        assertThat(handle.networkName()).isEqualTo("previewnet");
    }

    // ---------- 置备状态机（#60：registerPending / complete / markFailed 不变量） ----------

    @Test
    void given_pending_registration_when_created_then_provisioning_with_deterministic_names() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV);

        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(workspace.getContainerName()).isEqualTo("ws-42");
        assertThat(workspace.getNetworkName()).isEqualTo("previewnet");
    }

    @Test
    void given_runtime_pending_when_register_then_bare_naming() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.TEST);

        // #128：容器名裸 ws-{id}（无 kind 后缀），网络统一预览网络
        assertThat(workspace.getContainerName()).isEqualTo("ws-42");
        assertThat(workspace.getNetworkName()).isEqualTo("previewnet");
    }

    @Test
    void given_pending_workspace_when_complete_then_ready_with_resources_backfilled() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV);
        WorkspaceProvision provision = WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet"),
                new ProvisionedResource(MiddlewareKind.POSTGRESQL, "pg-42", 35432, "postgresql://pg"),
                new ProvisionedResource(MiddlewareKind.REDIS, "rd-42", 36379, "redis://rd"));

        workspace.complete(provision);

        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(workspace.getResources()).hasSize(2);
    }

    @Test
    void given_mismatched_provision_when_complete_then_rejected() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV);
        WorkspaceProvision provision = WorkspaceProvision.of(
                WorkspaceHandle.dev(WorkspaceId.of("99"), "ws-99", "previewnet"));

        assertThatThrownBy(() -> workspace.complete(provision))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("置备状态不合法");
    }

    @Test
    void given_ready_workspace_when_complete_again_then_rejected() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV);
        workspace.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet")));

        assertThatThrownBy(() -> workspace.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_pending_workspace_when_mark_failed_then_failed_with_reason() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV);

        workspace.markFailed("WSP_002：环境后端操作失败");

        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(workspace.getProvisionError()).isEqualTo("WSP_002：环境后端操作失败");
    }

    @Test
    void given_failed_workspace_when_complete_then_rejected() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV)
                .markFailed("WSP_002：环境后端操作失败");

        assertThatThrownBy(() -> workspace.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_ready_workspace_when_mark_failed_then_rejected() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV);
        workspace.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet")));

        assertThatThrownBy(() -> workspace.markFailed("WSP_002：环境后端操作失败"))
                .isInstanceOf(DomainException.class);
    }

    // ---------- 置备失败重试（#63：FAILED → PROVISIONING 回置备中） ----------

    @Test
    void given_failed_workspace_when_retry_then_back_to_provisioning_and_error_cleared() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV)
                .markFailed("WSP_002：环境后端操作失败");

        workspace.retry();

        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(workspace.getProvisionError()).isNull();
        assertThat(workspace.getResources()).isEmpty();
    }

    @Test
    void given_retried_workspace_when_complete_then_ready() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV)
                .markFailed("WSP_002：环境后端操作失败").retry();

        workspace.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet")));

        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(workspace.getProvisionError()).isNull();
    }

    @Test
    void given_pending_workspace_when_retry_then_rejected() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV);

        assertThatThrownBy(workspace::retry)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("置备状态不合法");
    }

    @Test
    void given_ready_workspace_when_retry_then_rejected() {
        Workspace workspace = Workspace.registerPending(ID, EnvKind.DEV);
        workspace.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet")));

        assertThatThrownBy(workspace::retry)
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_registered_workspace_when_status_then_ready() {
        Workspace workspace = Workspace.register(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet")));

        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.READY);
    }
}
