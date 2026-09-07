package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * {@link RunPermissionAppService}（#83 权限作答通道）：作答受理（permission-resolved
 * 先发再唤醒）、会合键校验（engineRef 未知/runId 不符 → PRJ_027 过期指路）、
 * 轨道驻留唤醒（await 阻塞至作答落定）。
 */
@ExtendWith(MockitoExtension.class)
class RunPermissionAppServiceTest {

    private static final long PROJECT_ID = 42L;

    @Mock
    private AgentEventBridge eventBridge;

    @Mock
    private ProjectRepository projectRepository;

    /** 可拨动时钟（#112 测试缝——超时断言快进 10 分钟，不真等）。 */
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-07T00:00:00Z"));

    private RunPermissionAppService appService;

    @BeforeEach
    void initService() {
        appService = new RunPermissionAppService(eventBridge, projectRepository, clock);
    }

    private void givenProjectExists() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(Mockito.mock(Project.class)));
    }

    @Test
    void given_unknown_engine_ref_when_answer_then_stale_rejected() {
        givenProjectExists();

        assertThatThrownBy(() -> appService.answer(PROJECT_ID, "run-1", "reply-x", true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PERMISSION_ANSWER_STALE.message());
    }

    /** 等会合点登记到位（作答先于登记会误判过期——先例见 isAwaiting 探针注释）。 */
    private void awaitRegistered(String engineRef) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!appService.isAwaiting(engineRef)) {
            assertThat(System.nanoTime() < deadline).as("会合点登记超时").isTrue();
            Thread.sleep(10);
        }
    }

    @Test
    void given_run_id_mismatch_when_answer_then_stale_rejected_and_wait_kept() throws Exception {
        givenProjectExists();
        Thread parked = new Thread(() -> appService.await("reply-1", "run-1"));
        parked.start();
        awaitRegistered("reply-1");

        assertThatThrownBy(() -> appService.answer(PROJECT_ID, "run-OTHER", "reply-1", true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PERMISSION_ANSWER_STALE.message());
        // 校验拒绝不发射事件、不消耗会合点
        verify(eventBridge, Mockito.never())
                .emitPermissionResolved(any(), any(), any(), anyBoolean());

        // 正确 runId 仍可作答（会合点完好——串卡校验只拦错卡，不误伤）
        appService.answer(PROJECT_ID, "run-1", "reply-1", true);
        parked.join(5000);
        assertThat(parked.isAlive()).isFalse();
    }

    @Test
    void given_project_missing_when_answer_then_not_found() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appService.answer(PROJECT_ID, "run-1", "reply-x", true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_awaiting_run_when_answer_then_resolved_event_then_waiter_wakes_with_decision() throws Exception {
        givenProjectExists();
        AtomicReference<RunPermissionAppService.Decision> wokeWith = new AtomicReference<>();
        Thread parked = new Thread(() -> wokeWith.set(appService.await("reply-9", "run-7")));
        parked.start();
        awaitRegistered("reply-9");

        appService.answer(PROJECT_ID, "run-7", "reply-9", false);

        parked.join(5000);
        assertThat(wokeWith.get()).isEqualTo(RunPermissionAppService.Decision.DENIED);
        // 先发落定事件（确认卡转终态），事件带批准位与批复锚
        ArgumentCaptor<String> runId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> engineRef = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> approved = ArgumentCaptor.forClass(Boolean.class);
        verify(eventBridge).emitPermissionResolved(eq(PROJECT_ID), runId.capture(),
                engineRef.capture(), approved.capture());
        assertThat(runId.getValue()).isEqualTo("run-7");
        assertThat(engineRef.getValue()).isEqualTo("reply-9");
        assertThat(approved.getValue()).isFalse();
    }

    @Test
    void given_answered_ref_when_answer_again_then_stale_rejected() throws Exception {
        givenProjectExists();
        Thread parked = new Thread(() -> appService.await("reply-2", "run-2"));
        parked.start();
        awaitRegistered("reply-2");

        appService.answer(PROJECT_ID, "run-2", "reply-2", true);
        parked.join(5000);

        // 会合点已消耗：重复作答（双击/双端）拒绝
        assertThatThrownBy(() -> appService.answer(PROJECT_ID, "run-2", "reply-2", true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PERMISSION_ANSWER_STALE.message());
    }

    @Test
    void given_concurrent_double_answer_then_exactly_once_resolved() throws Exception {
        // 并发双答（双击/双端竞态）：落定恰一次（事件恰发一条）+ 另一发 PRJ_027
        givenProjectExists();
        Thread parked = new Thread(() -> appService.await("reply-3", "run-3"));
        parked.start();
        awaitRegistered("reply-3");

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> answerers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    appService.answer(PROJECT_ID, "run-3", "reply-3", true);
                }
                catch (ApplicationException | InterruptedException ignored) {
                    // 竞态败方：PRJ_027（已落定）——恰一次语义在事件计数断言
                }
            });
            answerers.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : answerers) {
            t.join(5000);
        }
        parked.join(5000);

        // 恰一次：落定事件只发一条（permission-resolved 双发会让确认卡终态漂移）
        verify(eventBridge, Mockito.times(1))
                .emitPermissionResolved(eq(PROJECT_ID), eq("run-3"), eq("reply-3"), eq(true));
    }

    @Test
    void given_waiting_run_when_timeout_then_default_deny_and_card_frozen() throws Exception {
        // #112 超时默认拒绝：等作答越 10 分钟上限 → 三态落定 TIMED_OUT（只断言外部
        // 行为——拒绝结果与确认卡定格，不断言锁/线程实现）；超时不发 permission-
        // resolved（非作答）；会合点随超时清——作答侧再查即过期（确认卡定格不可再点）
        givenProjectExists();
        AtomicReference<RunPermissionAppService.Decision> wokeWith = new AtomicReference<>();
        Thread parked = new Thread(() -> wokeWith.set(appService.await("reply-t", "run-t")));
        parked.start();
        awaitRegistered("reply-t");

        clock.advance(Duration.ofMinutes(11));
        parked.join(5000);

        assertThat(parked.isAlive()).isFalse();
        assertThat(wokeWith.get()).isEqualTo(RunPermissionAppService.Decision.TIMED_OUT);
        verify(eventBridge, Mockito.never())
                .emitPermissionResolved(any(), any(), any(), anyBoolean());
        assertThatThrownBy(() -> appService.answer(PROJECT_ID, "run-t", "reply-t", true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PERMISSION_ANSWER_STALE.message());
    }
}
