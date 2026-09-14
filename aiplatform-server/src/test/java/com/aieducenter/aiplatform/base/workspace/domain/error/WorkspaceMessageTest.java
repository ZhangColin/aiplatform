package com.aieducenter.aiplatform.base.workspace.domain.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码契约：WSP_ 前缀注册（ADR-0001）+ HTTP 语义状态 + 文案非空。
 */
class WorkspaceMessageTest {

    @Test
    void given_workspace_messages_when_inspect_then_codes_prefixed_and_statuses_aligned() {
        assertThat(WorkspaceMessage.WORKSPACE_NOT_FOUND.code()).isEqualTo("WSP_001");
        assertThat(WorkspaceMessage.WORKSPACE_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.code()).isEqualTo("WSP_002");
        assertThat(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.httpStatus()).isEqualTo(500);
        // WSP_003（宿主端口分配失败）已随 #141 快照网关化退役——全平台无宿主端口映射
        assertThat(WorkspaceMessage.WORKSPACE_ID_INVALID.code()).isEqualTo("WSP_004");
        assertThat(WorkspaceMessage.WORKSPACE_ID_INVALID.httpStatus()).isEqualTo(400);
        assertThat(WorkspaceMessage.WORKSPACE_FIELDS_INCOMPLETE.code()).isEqualTo("WSP_005");
        assertThat(WorkspaceMessage.WORKSPACE_FIELDS_INCOMPLETE.httpStatus()).isEqualTo(400);
        assertThat(WorkspaceMessage.RESOURCE_FIELDS_INCOMPLETE.code()).isEqualTo("WSP_006");
        assertThat(WorkspaceMessage.RESOURCE_FIELDS_INCOMPLETE.httpStatus()).isEqualTo(400);
        assertThat(WorkspaceMessage.ENVIRONMENT_KIND_NOT_SUPPORTED.code()).isEqualTo("WSP_007");
        assertThat(WorkspaceMessage.ENVIRONMENT_KIND_NOT_SUPPORTED.httpStatus()).isEqualTo(400);
        // #173 后台观测面：清单过滤参数绑定失败（非法期望态/实态 code、分页值）
        assertThat(WorkspaceMessage.WORKSPACE_FILTER_INVALID.code()).isEqualTo("WSP_014");
        assertThat(WorkspaceMessage.WORKSPACE_FILTER_INVALID.httpStatus()).isEqualTo(400);
        // #174 后台动作面：run 在途拒（409）／封存包不可取（404）／收敛任务在途拒（409）
        assertThat(WorkspaceMessage.WORKSPACE_ACTION_RUN_IN_FLIGHT.code()).isEqualTo("WSP_015");
        assertThat(WorkspaceMessage.WORKSPACE_ACTION_RUN_IN_FLIGHT.httpStatus()).isEqualTo(409);
        assertThat(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE.code()).isEqualTo("WSP_016");
        assertThat(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE.httpStatus()).isEqualTo(404);
        assertThat(WorkspaceMessage.WORKSPACE_ACTION_BUSY.code()).isEqualTo("WSP_017");
        assertThat(WorkspaceMessage.WORKSPACE_ACTION_BUSY.httpStatus()).isEqualTo(409);
    }

    @Test
    void given_workspace_messages_when_inspect_then_messages_not_blank() {
        for (WorkspaceMessage message : WorkspaceMessage.values()) {
            assertThat(message.message()).isNotBlank();
        }
    }
}
