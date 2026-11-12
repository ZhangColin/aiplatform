package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link AgentSessionExecutor}（#475 分池，ADR-0042）：交互／生成双车道——生成占满
 * 不饿死交互轮（事故复现）、两车道同会话任务严格串行（同一 AgentState 槽位一次
 * 一轮）、跨会话并行、生成宽度准入＋排队可见、交互滞留告警、生成逐任务留痕、
 * 任务异常吞掉记日志不外溢。
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

    @Test
    void given_generation_tasks_occupy_all_lanes_when_interactive_turn_submitted_then_executes_promptly()
            throws Exception {
        // #475 事故复现（2026-11-12 生产实录）：stripe 共池时代，小时级生成任务占死
        // 车道（jstack 实拍 40+ 分钟 parked），新项目的面试交互轮（秒级任务）排在其
        // 后永不出队——victim 落任一 stripe 都饿死。分池后生成车道再满，交互轮必须
        // 仍秒级执行（票面验收第一条）
        AgentSessionExecutor executor = new AgentSessionExecutor();
        try {
            CountDownLatch blockersRunning = new CountDownLatch(AgentSessionExecutor.STRIPES);
            CountDownLatch releaseBlockers = new CountDownLatch(1);
            for (String blockerSession : sessionsCoveringAllStripes()) {
                executor.submitGeneration(blockerSession, () -> {
                    blockersRunning.countDown();
                    try {
                        releaseBlockers.await(10, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(blockersRunning.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch victimDone = new CountDownLatch(1);
            executor.submit("main-379214346761251959", victimDone::countDown);

            assertThat(victimDone.await(2, TimeUnit.SECONDS))
                    .as("生成任务占满车道时，交互轮仍应及时执行（#475）")
                    .isTrue();
            releaseBlockers.countDown();
        }
        finally {
            executor.destroy();
        }
    }

    /** 挑互不同 stripe 的会话 id 集合（盖满全部 stripe——喂饱和场景用）。 */
    private static List<String> sessionsCoveringAllStripes() {
        Map<Integer, String> byStripe = new HashMap<>();
        for (long i = 0; byStripe.size() < AgentSessionExecutor.STRIPES && i < 100_000; i++) {
            String candidate = "coder-" + (379_000_000_000_000_000L + i);
            byStripe.putIfAbsent(AgentSessionExecutor.stripeIndex(candidate), candidate);
        }
        assertThat(byStripe).hasSize(AgentSessionExecutor.STRIPES);
        return List.copyOf(byStripe.values());
    }

    @Test
    void given_same_session_generation_tasks_when_submitted_then_run_serially() throws Exception {
        // 生成车道同会话串行不变量与交互车道同构：同项目生成/续跑/复演恒同 stripe
        // ——同会话两场产出轨并发会互踩工作区事实
        AgentSessionExecutor executor = new AgentSessionExecutor();
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            List<String> order = new ArrayList<>();

            executor.submitGeneration("coder-379000000000000042", () -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("first");
            });
            executor.submitGeneration("coder-379000000000000042", () -> {
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
    void given_generation_width_saturated_when_more_sessions_submitted_then_queue_is_visible()
            throws Exception {
        // 生成准入（#475）：宽度=2 时两会话并行占满，第三个会话排队——排队深度可查
        //（generationQueuedCount），释放后按 FIFO 出队执行
        AgentSessionExecutor executor = new AgentSessionExecutor(2, Duration.ofSeconds(5));
        try {
            String sessionA = generationSessionOnStripe(2, 0);
            String sessionB = generationSessionOnStripe(2, 1);
            String sessionC = generationSessionOnStripe(2, 0) + "0"; // 任意第三会话
            CountDownLatch bothStarted = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch cDone = new CountDownLatch(1);

            executor.submitGeneration(sessionA, () -> {
                bothStarted.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submitGeneration(sessionB, () -> {
                bothStarted.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();

            executor.submitGeneration(sessionC, cDone::countDown);
            assertThat(executor.generationQueuedCount())
                    .as("宽度饱和后第三会话应排队且深度可见")
                    .isEqualTo(1);

            release.countDown();
            assertThat(cDone.await(5, TimeUnit.SECONDS)).isTrue();
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
        // 交互车道滞留告警（票面验收第二条）：同会话前轮阻塞 300ms，后轮出队时
        // 滞留超阈值（此处钉 100ms）→ WARN 带 sessionId 与滞留时长
        AgentSessionExecutor executor = new AgentSessionExecutor(8, Duration.ofMillis(100));
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
    void given_generation_lane_when_tasks_dispatched_then_enqueue_and_dequeue_logged() throws Exception {
        // 生成车道逐任务留痕（#475「零可观测」教训）：入队（带排队深度）＋出队
        //（带滞留时长）——生成任务排在队里不再无痕迹，定位不靠 jstack
        AgentSessionExecutor executor = new AgentSessionExecutor(1, Duration.ofSeconds(5));
        Logger laneLogger = (Logger) LoggerFactory.getLogger(AgentSessionExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        laneLogger.addAppender(appender);
        try {
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            executor.submitGeneration("coder-traced-1", () -> {
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submitGeneration("coder-traced-2", secondDone::countDown);
            Thread.sleep(200); // 第二个任务滞留窗口
            releaseFirst.countDown();

            assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.INFO
                            && event.getFormattedMessage().contains("生成任务入队")
                            && event.getFormattedMessage().contains("coder-traced-2")))
                    .as("生成任务入队应 INFO 留痕")
                    .isTrue();
            assertThat(appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.INFO
                            && event.getFormattedMessage().contains("生成任务出队执行")
                            && event.getFormattedMessage().contains("coder-traced-2")))
                    .as("生成任务出队应 INFO 留痕（含滞留时长）")
                    .isTrue();
        }
        finally {
            laneLogger.detachAppender(appender);
            executor.destroy();
        }
    }

    /** 挑落指定生成车道 stripe 的会话 id（宽度参数化——测试缝）。 */
    private static String generationSessionOnStripe(int stripes, int target) {
        for (long i = 0; i < 100_000; i++) {
            String candidate = "coder-" + (379_100_000_000_000_000L + i);
            if (AgentSessionExecutor.stripeIndex(candidate, stripes) == target) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到落 stripe " + target + " 的会话 id");
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
