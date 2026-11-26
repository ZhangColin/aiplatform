package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link AgentSessionExecutor}（#603 起的新形态）：交互车道（stripe 池——主智能体
 * 对话与设计线）＋产出轨整场任务直接执行（生成/修正——原生成车道的排队/错峰准入/
 * 优雅排空已随 ADR-0042/0043 改记整体退场）。行为契约：同会话交互轮严格串行、
 * 跨会话并行、滞留超阈值 WARN、任务异常吞掉记日志不外溢；产出轨任务即交即跑
 * （不领号排队）、停止面（#359）cancel(true) 中断在途执行线程的契约不变。
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
        // 异步轨道的失败表达归 error 事件——执行器吞掉任务异常，不外溢给提交方
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
        // 跨会话并行——B 会话任务在 A 会话任务阻塞期间完成，不全局排队
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

    @Test
    void given_incident_sessions_when_stripe_placement_then_distinct_stripes() {
        // #475 哈希复算：事故两会话 String.hashCode % 8 同落 stripe 0（碰撞聚集）；
        // TSID 低位取模后 605%8=5、959%8=7——分道（字面期望值独立于实现算式）
        assertThat(AgentSessionExecutor.stripeIndex("main-379213937602474605")).isEqualTo(5);
        assertThat(AgentSessionExecutor.stripeIndex("main-379214346761251959")).isEqualTo(7);
        // 无尾随数字（测试习语 id）回落 hashCode——行为同旧（「s-1」尾随数字 1 取模）
        assertThat(AgentSessionExecutor.stripeIndex("s-1")).isEqualTo(1);
    }

    @Test
    void given_interactive_wait_exceeds_threshold_when_dispatched_then_warn_logged() throws Exception {
        // 交互车道滞留告警（#475「零痕迹」的可观测面；配置键已随 #603 拆除、阈值
        // 定死——测试缝指钉小阈值）：同会话前轮阻塞 300ms，后轮出队时滞留超阈值
        // → WARN 带 sessionId 与滞留时长
        AgentSessionExecutor executor = new AgentSessionExecutor(Duration.ofMillis(100));
        Logger laneLogger = (Logger) LoggerFactory.getLogger(AgentSessionExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        laneLogger.addAppender(appender);
        try {
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            executor.submit("main-slow-wait", () -> {
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submit("main-slow-wait", secondDone::countDown);
            Thread.sleep(300); // 后轮滞留窗口
            releaseFirst.countDown();

            assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("main-slow-wait")
                            && event.getFormattedMessage().contains("交互任务队列滞留")))
                    .as("交互任务滞留超阈值应 WARN 留痕")
                    .isTrue();
        }
        finally {
            laneLogger.detachAppender(appender);
            executor.destroy();
        }
    }

    @Test
    void given_generation_task_held_when_another_submitted_then_runs_promptly_without_queueing()
            throws Exception {
        // #603 直接执行：产出轨任务即交即跑——不领号排队、无宽度上限、无错峰准入
        // （原生成车道的排队层已随 ADR-0042/0043 改记整体退场）；首任务持住期间，
        // 第二个任务照常立即起跑（旧车道形态下宽度/排队会拦住它——行为锚）
        AgentSessionExecutor executor = new AgentSessionExecutor();
        try {
            CountDownLatch firstRunning = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            executor.submitGeneration("coder-379000000000000042", () -> {
                firstRunning.countDown();
                try {
                    releaseFirst.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(firstRunning.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch secondDone = new CountDownLatch(1);
            executor.submitGeneration("coder-379100000000000099", secondDone::countDown);

            assertThat(secondDone.await(2, TimeUnit.SECONDS))
                    .as("产出轨任务即交即跑——不再经车道排队（#603）")
                    .isTrue();
            releaseFirst.countDown();
        }
        finally {
            executor.destroy();
        }
    }

    @Test
    void given_generation_task_when_cancelled_then_running_thread_interrupted() throws Exception {
        // #359/#603 停止面契约不变：直接执行句柄 cancel(true) 中断在途执行线程
        // （打断模型流订阅的通道——车道形态退场后契约不得漂移）
        AgentSessionExecutor executor = new AgentSessionExecutor();
        try {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            AtomicReference<java.util.concurrent.Future<?>> handle = new AtomicReference<>();
            handle.set(executor.submitGeneration("coder-cancel-interrupt", () -> {
                running.countDown();
                try {
                    Thread.sleep(10_000);
                }
                catch (InterruptedException e) {
                    interrupted.countDown();
                }
            }));
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

            handle.get().cancel(/* mayInterruptIfRunning= */ true);

            assertThat(interrupted.await(2, TimeUnit.SECONDS))
                    .as("cancel(true) 应中断产出轨任务的在途执行线程")
                    .isTrue();
        }
        finally {
            executor.destroy();
        }
    }

    @Test
    void given_generation_task_throws_when_run_then_swallowed_and_pool_thread_survives()
            throws Exception {
        // 直接执行路径的任务异常吞掉记日志（失败经 error 事件表达，不炸提交方），
        // 池线程不被异常打死——后续任务照常执行
        AgentSessionExecutor executor = new AgentSessionExecutor();
        Logger laneLogger = (Logger) LoggerFactory.getLogger(AgentSessionExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        laneLogger.addAppender(appender);
        try {
            CountDownLatch boomDone = new CountDownLatch(1);
            executor.submitGeneration("coder-boom", () -> {
                boomDone.countDown();
                throw new IllegalStateException("boom");
            });
            assertThat(boomDone.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch afterDone = new CountDownLatch(1);
            executor.submitGeneration("coder-after-boom", afterDone::countDown);
            assertThat(afterDone.await(5, TimeUnit.SECONDS))
                    .as("异常任务之后池线程照常可用")
                    .isTrue();
            assertThat(appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("coder-boom")
                            && event.getFormattedMessage().contains("boom")))
                    .as("任务异常应 WARN 留痕")
                    .isTrue();
        }
        finally {
            laneLogger.detachAppender(appender);
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
