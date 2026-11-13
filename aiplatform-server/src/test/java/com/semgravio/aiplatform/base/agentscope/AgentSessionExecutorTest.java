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
import io.agentscope.core.shutdown.GracefulShutdownManager;
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

    @Test
    void given_admission_interval_when_generation_flood_then_starts_staggered() throws Exception {
        // #477 错峰准入（「发版即满车道」的治——重启自愈洪水 N 场齐发把车道瞬时占死、
        // 单机容量被瞬时打满）：生成任务起跑按 admission-interval 逐个拉开——首个
        // 立即入场，后续按间隔错峰；间隔 0＝关闭错峰（既有测试构造缺省）
        AgentSessionExecutor executor = new AgentSessionExecutor(4, Duration.ofSeconds(5),
                Duration.ofMillis(250));
        try {
            List<Long> startAt = new ArrayList<>();
            CountDownLatch allStarted = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) {
                executor.submitGeneration("coder-stagger-" + i, () -> {
                    synchronized (startAt) {
                        startAt.add(System.nanoTime());
                    }
                    allStarted.countDown();
                });
            }
            assertThat(allStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // 相邻起跑间隔 ≥ 250ms−容差 60ms（CI 抖动）：洪水不再齐发
            long floor = TimeUnit.MILLISECONDS.toNanos(190);
            for (int i = 1; i < startAt.size(); i++) {
                assertThat(startAt.get(i) - startAt.get(i - 1))
                        .as("第 %d 个生成任务起跑应与前一任务错峰 ≥190ms", i)
                        .isGreaterThanOrEqualTo(floor);
            }
        }
        finally {
            executor.destroy();
        }
    }

    @Test
    void given_free_worker_when_new_generation_session_then_runs_without_hash_collision_wait()
            throws Exception {
        // #477 FIFO 公平（#475 事故在生成道内的复刻防：sessionId 哈希落位＝新项目
        // 生成任务可能落在被小时级任务占死的 stripe 后面等数小时）：生成道为全局
        // FIFO＋固定 worker 池——有空闲 worker 即起跑，落位碰撞不产生跨任务等待；
        // 同会话串行不变量仍保（CodingRunTrack 之外的第二道进程内保险）
        AgentSessionExecutor executor = new AgentSessionExecutor(2, Duration.ofSeconds(5),
                Duration.ZERO);
        try {
            // 宽度 2：blocker 占 1；victim 挑与 blocker 同 stripe 的会话（尾数补 0
            // 保奇偶不变——同落 stripe 0；旧实现下同 stripe FIFO 必等 blocker，正是
            // 事故形态），错峰关（隔离 FIFO 变量）
            String blocker = generationSessionOnStripe(2, 0);
            String victim = blocker + "0";
            CountDownLatch blockerRunning = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch victimDone = new CountDownLatch(1);
            executor.submitGeneration(blocker, () -> {
                blockerRunning.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(blockerRunning.await(5, TimeUnit.SECONDS)).isTrue();

            executor.submitGeneration(victim, victimDone::countDown);

            assertThat(victimDone.await(2, TimeUnit.SECONDS))
                    .as("空闲 worker 在场时，新会话生成任务不应等在无关任务后（无哈希碰撞饿死）")
                    .isTrue();
            release.countDown();
        }
        finally {
            executor.destroy();
        }
    }

    @Test
    void given_generation_task_when_cancelled_then_running_thread_interrupted() throws Exception {
        // #477/#359 停止与删除级联的执行器契约：generation Future cancel(true) 中断
        // 在途执行线程（打断模型流订阅的通道——FIFO 化后契约不得漂移）
        AgentSessionExecutor executor = new AgentSessionExecutor(2, Duration.ofSeconds(5),
                Duration.ZERO);
        try {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> handle =
                    new java.util.concurrent.atomic.AtomicReference<>();
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
                    .as("cancel(true) 应中断生成任务的在途执行线程")
                    .isTrue();
        }
        finally {
            executor.destroy();
        }
    }

    @Test
    void given_stagger_window_when_same_session_tasks_picked_then_strictly_serial() throws Exception {
        // #477 评审修件（Spec c②）：同会话串行标记须随取件落（pickLock 内）——取件后
        // 的错峰等待窗不得成为同会话第二任务的并发缝（错峰窗内两个 worker 各取走
        // 同会话两任务即互踩 AgentState 槽位）
        AgentSessionExecutor executor = new AgentSessionExecutor(2, Duration.ofSeconds(5),
                Duration.ofMillis(300));
        try {
            // 首任务即时收口（占一次准入时刻），随后同会话两任务<b>背靠背</b>入队
            //（同落下一个错峰窗——竞态窗的构造要件）：第二任务起跑后持住，第三任务
            // 必须等它收工才可起跑
            CountDownLatch firstRoundDone = new CountDownLatch(1);
            executor.submitGeneration("coder-serial-gap", firstRoundDone::countDown);
            assertThat(firstRoundDone.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch secondRunning = new CountDownLatch(1);
            CountDownLatch releaseSecond = new CountDownLatch(1);
            CountDownLatch thirdDone = new CountDownLatch(1);
            executor.submitGeneration("coder-serial-gap", () -> {
                secondRunning.countDown();
                try {
                    releaseSecond.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submitGeneration("coder-serial-gap", thirdDone::countDown); // 背靠背

            // 第二任务起跑并持住（错峰等待 + 运行中）；错峰窗全过后第三任务仍不得
            // 起跑——同会话串行不因错峰等待降级（若标记滞后于取件，第二 worker 会
            // 在窗内取走第三任务、错峰放行后与持住中的第二任务并发）
            assertThat(secondRunning.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(800); // > 错峰间隔 300ms——若串行有缝，此处已违规起跑
            assertThat(thirdDone.getCount())
                    .as("同会话第二任务在跑期间，第三任务不得并发起跑")
                    .isEqualTo(1L);
            releaseSecond.countDown();
            assertThat(thirdDone.await(5, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            executor.destroy();
        }
    }

    @Test
    void given_queued_task_cancelled_when_swept_then_queue_count_returns_to_zero() throws Exception {
        // #477 评审修件（Spec c①）：排队中被取消的任务条目在取件清扫丢弃时应回落
        // 排队计数——否则删除级联/停止取消排队任务后 generationQueuedCount 与「入队
        // 排位」日志永久虚高，污染「排队可见」
        AgentSessionExecutor executor = new AgentSessionExecutor(1, Duration.ofSeconds(5),
                Duration.ZERO);
        try {
            CountDownLatch blockerRunning = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitGeneration("coder-queued-cancel-blocker", () -> {
                blockerRunning.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(blockerRunning.await(5, TimeUnit.SECONDS)).isTrue();

            java.util.concurrent.Future<?> victim =
                    executor.submitGeneration("coder-queued-cancel-victim", () -> { });
            assertThat(executor.generationQueuedCount()).isEqualTo(1); // 排队中
            victim.cancel(false); // 排队中取消（未起跑）

            release.countDown();
            // worker 空闲后清扫丢弃已取消条目——计数回落 0（旧口径：丢弃不减计数，
            // 永久虚高 1）
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executor.generationQueuedCount() > 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(executor.generationQueuedCount())
                    .as("已取消的排队条目被清扫后，排队计数应回落")
                    .isZero();
        }
        finally {
            executor.destroy();
        }
    }

    @Test
    void given_graceful_shutdown_when_tasks_submitted_then_queued_tasks_drain_not_consume()
            throws Exception {
        // #477 关停排空（2026-11-12 19:03 实录「排队中的后续轮在关停宽限窗内继续被
        // 拉起」的治）：宽限窗内提交/排队的车道任务不再出队执行（跳过留痕）——
        // 排空而非消费；恢复 RUNNING 后新任务照常执行，被跳过任务不补跑
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        assertThat(manager.performGracefulShutdown()).isTrue();
        try {
            List<String> ran = new ArrayList<>();
            AgentSessionExecutor executor = new AgentSessionExecutor();
            try {
                executor.submit("main-shutdown-drain", () -> ran.add("interactive"));
                executor.submitGeneration("coder-shutdown-drain", () -> ran.add("generation"));
                Thread.sleep(300); // 留出「违规出队执行」的窗口
                assertThat(ran)
                        .as("关停宽限窗内排队任务不应出队执行")
                        .isEmpty();

                manager.resetForTesting(); // 恢复 RUNNING——新任务照常执行
                CountDownLatch resumed = new CountDownLatch(1);
                executor.submit("main-after-reset", () -> {
                    ran.add("after-reset");
                    resumed.countDown();
                });
                assertThat(resumed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(ran).containsExactly("after-reset");
            }
            finally {
                executor.destroy();
            }
        }
        finally {
            manager.resetForTesting();
        }
    }

    @Test
    void given_blank_lane_config_when_constructed_then_falls_back_to_defaults_and_tasks_run() throws Exception {
        // yml 显式置空（`generation-stripes:` 空值）→ 属性注入 null：拆箱 NPE 会埋进
        // 启动（宽度）或更阴地炸在任务 run 之前被吞（阈值——交互任务静默消失，
        // 事故形态的复活路径）。回落缺省，任务照常执行
        AgentSessionExecutor executor = new AgentSessionExecutor(null, null);
        try {
            CountDownLatch interactiveDone = new CountDownLatch(1);
            CountDownLatch generationDone = new CountDownLatch(1);

            executor.submit("main-blank-config", interactiveDone::countDown);
            executor.submitGeneration("coder-blank-config", generationDone::countDown);

            assertThat(interactiveDone.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(generationDone.await(2, TimeUnit.SECONDS)).isTrue();
        }
        finally {
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
