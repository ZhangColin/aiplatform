package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * {@link AgentSessionExecutor}：同会话任务严格串行（同一 AgentState 槽位一次一轮）、
 * 跨会话并行（生产缝实测）、任务异常吞掉记日志不外溢。
 */
class AgentSessionExecutorTest {

    @Test
    void given_task_when_submit_then_executed() {
        List<String> ran = new ArrayList<>();
        AgentSessionExecutor executor = new AgentSessionExecutor((Executor) task -> {
            task.run();
            ran.add("executed");
        });

        executor.submit("s-1", () -> ran.add("task"));

        assertThat(ran).containsExactly("task", "executed");
    }

    @Test
    void given_task_throws_when_submit_then_swallowed_not_reraised() {
        // 异步轨道的失败表达归 error 帧——执行器吞掉任务异常，不外溢给提交方
        List<String> ran = new ArrayList<>();
        AgentSessionExecutor executor = new AgentSessionExecutor((Executor) task -> {
            task.run();
            ran.add("executed");
        });

        executor.submit("s-1", () -> {
            throw new IllegalStateException("boom");
        });

        assertThat(ran).containsExactly("executed");
    }

    @Test
    void given_same_session_tasks_when_submitted_then_run_serially() throws Exception {
        // 同会话任务严格串行（第二个须等第一个完成），否则同一会话两轮并发写同一
        // AgentState 槽位
        AgentSessionExecutor executor = new AgentSessionExecutor();
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            List<String> order = new ArrayList<>();

            executor.submit("s-1", () -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("first");
            });
            executor.submit("s-1", () -> {
                order.add("second");
                secondDone.countDown();
            });

            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200); // 留出「第二个违规抢跑」的窗口——它应仍在队里
            assertThat(order).isEmpty();
            releaseFirst.countDown();

            assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly("first", "second");
        }
        finally {
            executor.destroy();
        }
    }

    @Test
    void given_different_sessions_when_submitted_then_run_in_parallel() throws Exception {
        // 跨会话并行——B 会话任务在 A 会话任务阻塞期间完成，不再全局排队
        AgentSessionExecutor executor = new AgentSessionExecutor();
        try {
            String sessionA = "ses-parallel";
            String sessionB = peerOnOtherStripe(sessionA);
            CountDownLatch aStarted = new CountDownLatch(1);
            CountDownLatch releaseA = new CountDownLatch(1);
            CountDownLatch bDone = new CountDownLatch(1);

            executor.submit(sessionA, () -> {
                aStarted.countDown();
                try {
                    releaseA.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submit(sessionB, bDone::countDown);

            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(bDone.await(5, TimeUnit.SECONDS)).isTrue();
            releaseA.countDown();
        }
        finally {
            executor.destroy();
        }
    }

    /** 挑一个与 base 落不同 stripe 的对端会话 id（同 stripe 会串行——那测不出并行）。 */
    private static String peerOnOtherStripe(String base) {
        for (int i = 0; i < 1000; i++) {
            String candidate = base + "-peer-" + i;
            if (AgentSessionExecutor.stripeIndex(candidate) != AgentSessionExecutor.stripeIndex(base)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到不同 stripe 的对端会话 id");
    }
}
