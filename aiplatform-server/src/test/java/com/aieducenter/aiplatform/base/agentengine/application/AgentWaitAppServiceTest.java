package com.aieducenter.aiplatform.base.agentengine.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.SettleResult;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentWait;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;

import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.model.WaitSettlement;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.port.WaitResponder;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentWaitRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 等待点用例：raise 幂等与稳定 waitId、settle 两型（校验链 404/409、引擎先送达
 * 后落库、deny cap 判定回报）、run 终态联动 EXPIRED。
 */
@ExtendWith(MockitoExtension.class)
class AgentWaitAppServiceTest {

    private static final long WORKSPACE_ID = 4242L;
    private static final String WORKSPACE = Long.toString(WORKSPACE_ID);
    private static final Instant NOW = Instant.parse("2026-08-22T08:00:00Z");

    @Mock
    private AgentWaitRepository waitRepository;
    @Mock
    private AgentSessionRepository sessionRepository;

    private final FakeWorkspaceHandleClient handleClient = new FakeWorkspaceHandleClient();
    private final RecordingResponder responder = new RecordingResponder();
    private AgentWaitAppService appService;

    @BeforeEach
    void setUp() {
        appService = new AgentWaitAppService(waitRepository, sessionRepository,
                new WaitResponderDirectory(List.of(responder)), handleClient, 3,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------- raise ----------

    @Test
    void given_new_wait_when_raise_then_persisted_with_stable_wait_id() {
        when(waitRepository.findBySessionIdAndEngineRefAndStatus(
                "ses_1", "que_1", WaitStatus.PENDING)).thenReturn(Optional.empty());
        when(waitRepository.save(any(AgentWait.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WaitPointResponse response = appService.raise(WORKSPACE_ID, "ses_1", "run-1",
                WaitKind.QUESTION, "que_1", "用哪个框架?", Map.of("id", "que_1"));

        assertThat(response.waitId()).isNotBlank();
        assertThat(response.status()).isEqualTo(WaitStatus.PENDING);
        assertThat(response.kind()).isEqualTo(WaitKind.QUESTION);
        assertThat(response.raisedAt()).isEqualTo(NOW);
        // 稳定标识：登记即定，后续任何读面同值
        ArgumentCaptor<AgentWait> saved = ArgumentCaptor.forClass(AgentWait.class);
        verify(waitRepository).save(saved.capture());
        assertThat(saved.getValue().getWaitId()).isEqualTo(response.waitId());
    }

    @Test
    void given_pending_row_of_same_ref_when_raise_then_idempotent_return_existing() {
        AgentWait existing = AgentWait.raise(WORKSPACE_ID, "ses_1", "run-1",
                WaitKind.QUESTION, "que_1", null, null, NOW);
        when(waitRepository.findBySessionIdAndEngineRefAndStatus(
                "ses_1", "que_1", WaitStatus.PENDING)).thenReturn(Optional.of(existing));

        WaitPointResponse response = appService.raise(WORKSPACE_ID, "ses_1", "run-9",
                WaitKind.QUESTION, "que_1", null, null);

        // 同挂起重复上报（watcher 重连/轮询重叠）收敛到既有 PENDING 行
        assertThat(response.waitId()).isEqualTo(existing.getWaitId());
        verify(waitRepository, never()).save(any());
    }

    @Test
    void given_terminal_row_of_same_ref_when_raise_then_new_pending_row_registered() {
        // 引擎侧挂起在终态联动后仍活着（超时误伤后引擎重检到）：终态行不挡路——
        // 登记新 PENDING 行（「重启后看得见答不了」对策）
        AgentWait expired = AgentWait.raise(WORKSPACE_ID, "ses_1", "run-1",
                WaitKind.QUESTION, "que_1", null, null, NOW);
        expired.expire(NOW);
        when(waitRepository.findBySessionIdAndEngineRefAndStatus(
                "ses_1", "que_1", WaitStatus.PENDING)).thenReturn(Optional.empty());
        when(waitRepository.save(any(AgentWait.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WaitPointResponse response = appService.raise(WORKSPACE_ID, "ses_1", "run-2",
                WaitKind.QUESTION, "que_1", null, null);

        assertThat(response.waitId()).isNotEqualTo(expired.getWaitId());
        assertThat(response.status()).isEqualTo(WaitStatus.PENDING);
        assertThat(response.runId()).isEqualTo("run-2");
        verify(waitRepository).save(any(AgentWait.class));
    }

    @Test
    void given_wait_raised_stream_event_when_raiseFromEvent_then_mapped_and_persisted() {
        when(waitRepository.findBySessionIdAndEngineRefAndStatus(
                "ses_1", "per_1", WaitStatus.PENDING)).thenReturn(Optional.empty());
        when(waitRepository.save(any(AgentWait.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WaitPointResponse response = appService.raiseFromEvent(WORKSPACE_ID, Map.of(
                "runId", "run-1",
                "sessionId", "ses_1",
                "kind", "PERMISSION",
                "summary", "执行 rm -rf /tmp",
                "engineRef", "per_1",
                "data", Map.of("id", "per_1", "title", "执行 rm -rf /tmp")));

        assertThat(response.kind()).isEqualTo(WaitKind.PERMISSION);
        assertThat(response.summary()).isEqualTo("执行 rm -rf /tmp");
        assertThat(response.engineRef()).isEqualTo("per_1");
        assertThat(response.body()).containsEntry("id", "per_1");
    }

    // ---------- 聚合查询 ----------

    @Test
    void given_wait_id_when_wait_then_addressable_globally() {
        when(waitRepository.findById("wait_x")).thenReturn(Optional.of(
                AgentWait.raise(4242L, "ses_1", "run-1", WaitKind.QUESTION, "que_1",
                        null, null, NOW)));

        assertThat(appService.wait("wait_x")).isPresent();
        assertThat(appService.wait("wait_none")).isEmpty();
    }

    // ---------- settle：校验链 ----------

    @Test
    void given_answer_when_settle_then_engine_replied_first_then_settled() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "agentscope",
                        "ses_1", "run-1")));

        appService.settle(WORKSPACE, new WaitSettlement.Answer(wait.getWaitId(),
                List.of(List.of("Vue3"))));

        // 引擎派发键 = engineRef（que_*），答案原样
        assertThat(responder.repliedQuestions).containsExactly(
                new ReplyRecord("ses_1", "que_1", List.of(List.of("Vue3"))));
        // 落库关闭：引擎送达成功才 SETTLED（answer 语义）
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.SETTLED);
        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.ANSWERED);
        verify(waitRepository).save(wait);
    }

    @Test
    void given_unknown_wait_when_settle_then_404() {
        when(waitRepository.findById("wait_none")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appService.settle(WORKSPACE,
                new WaitSettlement.Answer("wait_none", List.of(List.of("A")))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_NOT_FOUND.message());
    }

    @Test
    void given_stale_wait_when_settle_then_409_conflict() {
        AgentWait settled = raisedQuestion();
        settled.settle(WaitOutcome.ANSWERED, NOW);
        when(waitRepository.findById(settled.getWaitId())).thenReturn(Optional.of(settled));

        assertThatThrownBy(() -> appService.settle(WORKSPACE,
                new WaitSettlement.Answer(settled.getWaitId(), List.of(List.of("A")))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_CONFLICT.message());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void given_session_gone_when_settle_then_409_not_resumable() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appService.settle(WORKSPACE,
                new WaitSettlement.Answer(wait.getWaitId(), List.of(List.of("A")))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_CONFLICT.message());
        // 未达引擎：会话不可续跑时不派发
        assertThat(responder.repliedQuestions).isEmpty();
    }

    @Test
    void given_foreign_workspace_when_settle_then_409() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));

        assertThatThrownBy(() -> appService.settle(Long.toString(9999L),
                new WaitSettlement.Answer(wait.getWaitId(), List.of(List.of("A")))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_CONFLICT.message());
    }

    @Test
    void given_engine_failure_when_settle_answer_then_502_and_stays_pending() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "agentscope",
                        "ses_1", "run-1")));
        responder.failReplies = true;

        assertThatThrownBy(() -> appService.settle(WORKSPACE,
                new WaitSettlement.Answer(wait.getWaitId(), List.of(List.of("A")))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.ENGINE_REQUEST_FAILED.message());

        // 引擎未送达：保持 PENDING 可重试，不落库
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.PENDING);
        verify(waitRepository, never()).save(any());
    }

    // ---------- settle：权限与 deny cap ----------

    @Test
    void given_permission_approve_when_settle_then_approved() {
        AgentWait wait = raisedPermission();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "agentscope",
                        "ses_1", "run-1")));

        appService.settle(WORKSPACE, new WaitSettlement.PermissionDecision(
                wait.getWaitId(), true));

        assertThat(responder.repliedPermissions).containsExactly(
                new PermissionRecord("ses_1", "per_1", true));
        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.APPROVED);
    }

    @Test
    void given_denies_below_cap_when_settle_deny_then_no_termination_signalled() {
        assertDenyCapping(2, 3, false);
    }

    @Test
    void given_denies_reach_cap_when_settle_deny_then_termination_signalled_to_caller() {
        // settle 只判不定——denyCapped=true 回报（含派发键 engine），接续动作归调用方
        assertDenyCapping(3, 3, true);
    }

    private void assertDenyCapping(long totalDenies, int cap, boolean expectCapped) {
        AgentWaitAppService service = new AgentWaitAppService(waitRepository,
                sessionRepository,
                new WaitResponderDirectory(List.of(responder)), handleClient,
                cap, Clock.fixed(NOW, ZoneOffset.UTC));
        AgentWait wait = raisedPermission();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "agentscope",
                        "ses_1", "run-1")));
        // 计数语义：本行 deny 已落库后的同 run deny 总数（含本行）
        when(waitRepository.countByRunIdAndStatusAndSettleOutcome(
                eq("run-1"), eq(WaitStatus.SETTLED), eq(WaitOutcome.DENIED)))
                .thenReturn(totalDenies);

        SettleResult result = service.settle(WORKSPACE,
                new WaitSettlement.PermissionDecision(wait.getWaitId(), false));

        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.DENIED);
        assertThat(result.settled().waitId()).isEqualTo(wait.getWaitId());
        assertThat(result.engine()).isEqualTo("agentscope"); // 派发键随结果回报
        assertThat(result.denyCapped()).isEqualTo(expectCapped);
    }

    // ---------- settle：命令形态 ----------

    @Test
    void given_settle_command_when_settle_then_mapped_to_typed_settlement() {
        AgentWait wait = raisedPermission();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "agentscope",
                        "ses_1", "run-1")));

        appService.settle(WORKSPACE, wait.getWaitId(),
                new WaitSettleCommand(WaitSettleCommand.TYPE_PERMISSION, null, false, null));

        assertThat(responder.repliedPermissions).containsExactly(
                new PermissionRecord("ses_1", "per_1", false));
        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.DENIED);
    }

    @Test
    void given_answer_command_without_answers_when_settle_then_rejected() {
        // 400 面：BaseCodeMessage.BAD_REQUEST（detail 参数进 errors 数组，不进 message）
        assertThatThrownBy(() -> appService.settle(WORKSPACE, "wait_x",
                new WaitSettleCommand(WaitSettleCommand.TYPE_ANSWER, null, null, null)))
                .isInstanceOf(com.cartisan.core.exception.ApplicationException.class)
                .hasMessageContaining(
                        com.cartisan.core.exception.BaseCodeMessage.BAD_REQUEST.message());
        assertThatThrownBy(() -> appService.settle(WORKSPACE, "wait_x",
                new WaitSettleCommand("bogus", null, null, null)))
                .isInstanceOf(com.cartisan.core.exception.ApplicationException.class)
                .hasMessageContaining(
                        com.cartisan.core.exception.BaseCodeMessage.BAD_REQUEST.message());
        assertThatThrownBy(() -> appService.settle(WORKSPACE, "wait_x",
                new WaitSettleCommand("deferred", null, null, "转任务")))
                .isInstanceOf(com.cartisan.core.exception.ApplicationException.class)
                .hasMessageContaining(
                        com.cartisan.core.exception.BaseCodeMessage.BAD_REQUEST.message());
    }

    // ---------- 终态联动 ----------

    @Test
    void given_run_terminal_when_expireRun_then_pending_waits_expired() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findByRunIdAndStatus("run-1", WaitStatus.PENDING))
                .thenReturn(List.of(wait));
        when(waitRepository.transitionIfStatus(wait.getWaitId(), WaitStatus.PENDING,
                WaitStatus.EXPIRED, NOW)).thenReturn(1);

        int closed = appService.expireRun("run-1");

        assertThat(closed).isEqualTo(1);
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.EXPIRED);
        assertThat(wait.getSettleOutcome()).isNull();
        verify(waitRepository).transitionIfStatus(wait.getWaitId(), WaitStatus.PENDING,
                WaitStatus.EXPIRED, NOW);
    }

    @Test
    void given_no_pending_when_expireRun_then_noop() {
        when(waitRepository.findByRunIdAndStatus("run-x", WaitStatus.PENDING))
                .thenReturn(List.of());

        assertThat(appService.expireRun("run-x")).isZero();
        verify(waitRepository, never()).transitionIfStatus(anyString(), any(),
                any(), any());
    }

    @Test
    void given_guard_missed_row_when_expireRun_then_skipped() {
        // 联动快照后行已被 settle 迁出（守卫 UPDATE 命中 0）——静默跳过（后写不得胜出）
        AgentWait wait = raisedQuestion();
        when(waitRepository.findByRunIdAndStatus("run-1", WaitStatus.PENDING))
                .thenReturn(List.of(wait));
        when(waitRepository.transitionIfStatus(wait.getWaitId(), WaitStatus.PENDING,
                WaitStatus.EXPIRED, NOW)).thenReturn(0);

        assertThat(appService.expireRun("run-1")).isZero();
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.PENDING); // 内存实体不动
        verify(waitRepository, never()).save(any());
    }

    // ---------- 替身与工具 ----------

    private AgentWait raisedQuestion() {
        return AgentWait.raise(WORKSPACE_ID, "ses_1", "run-1", WaitKind.QUESTION,
                "que_1", "用哪个框架?", Map.of("id", "que_1"), NOW);
    }

    private AgentWait raisedPermission() {
        return AgentWait.raise(WORKSPACE_ID, "ses_1", "run-1", WaitKind.PERMISSION,
                "per_1", "执行 rm -rf", Map.of("id", "per_1"), NOW);
    }

    private static final class FakeWorkspaceHandleClient implements WorkspaceHandleClient {

        @Override
        public WorkspaceHandle handleOf(String workspaceId) {
            // 按入参解析（工作区不匹配路径的判定依据）
            return WorkspaceHandle.dev(new WorkspaceId(Long.parseLong(workspaceId)),
                    "ws-1", "net-1", 4096, 0);
        }
    }

    private record ReplyRecord(String sessionId, String requestId,
                               List<List<String>> answers) {
    }

    private record PermissionRecord(String sessionId, String permissionId, boolean approve) {
    }

    /** 答复通道替身：记录答复派发，可注入失败。 */
    private static class RecordingResponder implements WaitResponder {

        final List<ReplyRecord> repliedQuestions = new CopyOnWriteArrayList<>();
        final List<PermissionRecord> repliedPermissions = new CopyOnWriteArrayList<>();
        volatile boolean failReplies;

        @Override
        public String engine() {
            return "agentscope";
        }

        @Override
        public void replyQuestions(WorkspaceHandle handle, String sessionId,
                                   String requestId, List<List<String>> answers) {
            if (failReplies) {
                throw new IllegalStateException("引擎不可达（测试注入）");
            }
            repliedQuestions.add(new ReplyRecord(sessionId, requestId, answers));
        }

        @Override
        public void replyPermission(WorkspaceHandle handle, String sessionId,
                                    String permissionId, boolean approve) {
            if (failReplies) {
                throw new IllegalStateException("引擎不可达（测试注入）");
            }
            repliedPermissions.add(new PermissionRecord(sessionId, permissionId, approve));
        }

        @Override
        public boolean abort(WorkspaceHandle handle, String sessionId) {
            return false; // deny cap 终止接续归调用方，本替身不涉
        }
    }
}
