package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;

/**
 * 事件桥终态失败原因映射（#355 机制检查——run-failed 终态零原因补齐）。
 */
class AgentEventBridgeTest {

    /** 人话定型映射：常见内部错误形态各归一句可理解原因。 */
    @Test
    void given_known_error_shapes_when_map_then_user_facing_reasons() {
        assertThat(AgentEventBridge.userFacingRunFailure(
                "活超时判死：PT10M 内无事件/工具动作进展（纯静默超限）"))
                .isEqualTo("生成长时间没有进展，已自动停止");
        assertThat(AgentEventBridge.userFacingRunFailure(
                "单条模型流总时长超上限（limit=PT15M）——按失败处置，不再无限等"))
                .isEqualTo("模型响应超时，已自动停止");
        assertThat(AgentEventBridge.userFacingRunFailure("8081 不可达（curl 退出码 7）"))
                .isEqualTo("应用服务未能启动");
        assertThat(AgentEventBridge.userFacingRunFailure(
                "入口文件残缺（src/app/page.tsx 含「其余代码略」式截断占位，未一次写全）"))
                .isEqualTo("预览入口页面未写完整");
        assertThat(AgentEventBridge.userFacingRunFailure("ModelHttpException: invalid api key"))
                .isEqualTo("模型服务凭证异常，请稍后再试");
    }

    /** 未命中形态截首行定宽 120（#229 同款截断口径）；空值缺省不映射。 */
    @Test
    void given_unknown_or_blank_error_when_map_then_truncated_or_absent() {
        String longLine = "x".repeat(200);
        assertThat(AgentEventBridge.userFacingRunFailure(longLine)).hasSize(120);
        assertThat(AgentEventBridge.userFacingRunFailure(longLine)).endsWith("…");
        assertThat(AgentEventBridge.userFacingRunFailure("   ")).isNull();
        assertThat(AgentEventBridge.userFacingRunFailure(null)).isNull();
    }

    /** 桥只消费归属三件事实，测试桩聚合即可（id 由持久化层分配）。 */
    private static Project projectStub(long id, Long ownerAccountId, Long ownerGuestId) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getOwnerAccountId()).thenReturn(ownerAccountId);
        when(project.getOwnerGuestId()).thenReturn(ownerGuestId);
        return project;
    }

    /** run-failed 载荷随事件携 message（映射命中），失败原因不缺席。 */
    @Test
    void given_mapped_error_when_emit_run_failed_then_payload_carries_message() {
        EventsAppService events = mock(EventsAppService.class);
        AgentEventBridge bridge = new AgentEventBridge(events);

        bridge.emitRunFailed(projectStub(42L, 7L, null), "run-1", "8081 不可达（curl 退出码 7）");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), payload.capture());
        assertThat(payload.getValue()).containsEntry("runId", "run-1");
        assertThat(payload.getValue()).containsEntry("message", "应用服务未能启动");
    }

    /** 空错误现场缺省不携带 message（载荷形态不回退）。 */
    @Test
    void given_blank_error_when_emit_run_failed_then_message_absent() {
        EventsAppService events = mock(EventsAppService.class);
        AgentEventBridge bridge = new AgentEventBridge(events);

        bridge.emitRunFailed(projectStub(42L, null, 9L), "run-2", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), payload.capture());
        assertThat(payload.getValue()).doesNotContainKey("message");
    }

    /** 用户停止的终态收口（#359）：run-failed 载荷带 stopped=true＋一句人话——前端失败档分岔「已停止」文案的判定锚。 */
    @Test
    void given_user_stop_when_emit_run_stopped_then_payload_carries_stopped_and_message() {
        EventsAppService events = mock(EventsAppService.class);
        AgentEventBridge bridge = new AgentEventBridge(events);

        bridge.emitRunStopped(projectStub(42L, 7L, null), "run-3");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), payload.capture());
        assertThat(payload.getValue()).containsEntry("runId", "run-3");
        assertThat(payload.getValue()).containsEntry("stopped", true);
        assertThat(payload.getValue()).containsEntry("message", "已按你的要求停止，已完成的进度都保留");
    }
}
