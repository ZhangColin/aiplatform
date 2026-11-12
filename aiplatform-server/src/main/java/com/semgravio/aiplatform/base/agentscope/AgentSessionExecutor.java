package com.semgravio.aiplatform.base.agentscope;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话级任务执行器（#475 分池，ADR-0042）：交互车道（秒级轮——主智能体对话/
 * 判定/取名/预检等）与生成车道（小时级产出轨整场任务——生成/迭代复演/设计轨）
 * 两条独立车道，小时级任务结构性饿不死交互轮（2026-11-12 生产事故根因：8 条
 * stripe 共池，生成 run 落 stripe 内 {@code blockLast} 阻塞 1–2h，同 stripe 的
 * 面试交互轮 FIFO 永不到头）。车道内沿用 stripe 设计：同一 (userId, sessionId)
 * 状态槽位一次只跑一轮——新轮与续跑并发会互踩 AgentState，sessionId 固定落一个
 * stripe（单线程 FIFO）严格排队；跨会话多数落不同 stripe 并行。生成车道宽度
 * （stripe 数）＝跨项目并发产出轨上界，配置面可调（单机容量为准，{@code
 * app.agentscope.generation-stripes}）。
 *
 * <p>队列可观测（#475 第二恶劣点「零痕迹」的治）：生成车道逐任务留痕（入队/
 * 出队滞留 INFO，低频）；交互车道出队滞留超阈值 WARN（{@code
 * app.agentscope.session-queue-warn-threshold}）。任务异常吞掉记日志（REST 快
 * 返回的异步轨道——失败经 error 事件表达，不炸调用方）；执行器是进程内状态，
 * 重启即清（会话状态在 cat_agent_state，按会话标识恢复）。</p>
 */
@Component
@Slf4j
public class AgentSessionExecutor implements DisposableBean {

    /** 交互车道 stripe 数 = 常驻线程数 = 秒级轮跨会话并行度上界。 */
    static final int STRIPES = 8;

    private final Lane interactive;
    private final Lane generation;
    /** 提交通道（生产=null 走车道 stripe 池；测试=直通同步）。 */
    private final Executor passthrough;

    @Autowired
    public AgentSessionExecutor(AgentscopeProperties properties) {
        this(properties.getGenerationStripes(), properties.getSessionQueueWarnThreshold());
    }

    /** 测试便利构造（缺省车道参数，无生命周期注入）。 */
    AgentSessionExecutor() {
        this(new AgentscopeProperties());
    }

    /**
     * 车道参数化构造（测试缝：生成车道宽度＋滞留告警阈值可指钉）。显式置空的
     * 配置回落缺省——防 yml 空值把拆箱 NPE 埋进启动（阈值尤其阴：NPE 在任务
     * run 之前被吞，交互任务会静默消失——事故形态的复活路径）。
     */
    AgentSessionExecutor(Integer generationStripes, Duration queueWarnThreshold) {
        int lanes = Math.max(1, generationStripes == null ? STRIPES : generationStripes);
        Duration threshold = queueWarnThreshold == null ? Duration.ofSeconds(5) : queueWarnThreshold;
        this.interactive = Lane.interactive(STRIPES, threshold);
        this.generation = Lane.generation(lanes, threshold);
        this.passthrough = null;
    }

    /** 测试便利构造（直通执行器，无生命周期）。 */
    public AgentSessionExecutor(Executor executor) {
        this.interactive = null;
        this.generation = null;
        this.passthrough = executor;
    }

    /**
     * 提交一轮交互车道会话任务（新轮或续跑；同会话严格按提交序执行）。返回任务的
     * {@link Future}——{@code cancel(true)} 中断在途执行线程（#359 生成取消：
     * 停止请求经此打断阻塞中的模型流订阅；测试直通已同步跑完，返回已完成
     * future，cancel 是无害 no-op）。
     */
    public Future<?> submit(String sessionId, Runnable task) {
        return dispatch(interactive, sessionId, task);
    }

    /**
     * 提交一轮生成车道会话任务（产出轨整场任务，#475 分池）：小时级任务独占车道，
     * 宽度即并发准入（超宽排队可见）；同会话串行不变量与交互车道同构（同项目
     * 生成/续跑/复演恒同 stripe——CodingRunTrack 的轨道互斥之外，进程内再保险）。
     * {@link Future} {@code cancel(true)} 语义同 {@link #submit}（#359 停止面
     * 经 CodingRunTrack.registerTask 登记后中断模型流订阅）。
     */
    public Future<?> submitGeneration(String sessionId, Runnable task) {
        return dispatch(generation, sessionId, task);
    }

    /** 生成车道当前排队深度（已提交未起跑——#475 队列可观测；测试断言缝）。 */
    int generationQueuedCount() {
        return generation.queued.get();
    }

    private Future<?> dispatch(Lane lane, String sessionId, Runnable task) {
        Runnable wrapped = () -> {
            try {
                task.run();
            }
            catch (RuntimeException e) {
                log.warn("[agentscope] 会话任务异常：session={}, {}", sessionId, e.getMessage());
            }
        };
        if (passthrough != null) {
            passthrough.execute(wrapped);
            return CompletableFuture.completedFuture(null);
        }
        return lane.dispatch(sessionId, wrapped);
    }

    /**
     * 一条执行车道：sessionId 固定落 stripe（单线程 FIFO）保同会话串行；跨会话
     * 并行度上界 = stripe 数。队列可观测：排队深度计数＋出队滞留时长——生成
     * 车道逐任务 INFO 留痕（低频），交互车道超阈值 WARN（高频不刷屏）。
     */
    private static final class Lane {

        /** 交互车道（秒级轮）：高频不逐任务留痕，滞留超阈值才 WARN。 */
        static Lane interactive(int stripeCount, Duration warnThreshold) {
            return new Lane(stripeCount, "agentscope-session", warnThreshold, /* traceDispatch= */ false);
        }

        /** 生成车道（小时级产出轨）：派发低频，逐任务入队/出队 INFO 留痕。 */
        static Lane generation(int stripeCount, Duration warnThreshold) {
            return new Lane(stripeCount, "agentscope-generation", warnThreshold, /* traceDispatch= */ true);
        }

        final ExecutorService[] stripes;
        final AtomicInteger queued = new AtomicInteger();
        final Duration warnThreshold;
        final boolean traceDispatch;

        private Lane(int stripeCount, String threadPrefix, Duration warnThreshold, boolean traceDispatch) {
            this.stripes = new ExecutorService[stripeCount];
            for (int index = 0; index < stripeCount; index++) {
                int stripeIndex = index;
                this.stripes[index] = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, threadPrefix + "-" + stripeIndex);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            this.warnThreshold = warnThreshold;
            this.traceDispatch = traceDispatch;
        }

        Future<?> dispatch(String sessionId, Runnable task) {
            int depth = queued.incrementAndGet();
            long enqueuedAt = System.nanoTime();
            if (traceDispatch) {
                log.info("[agentscope] 生成任务入队：session={}, 排位={}（车道并发上界={}）",
                        sessionId, depth, stripes.length);
            }
            return stripes[stripeIndex(sessionId, stripes.length)].submit(() -> {
                long waitMillis = (System.nanoTime() - enqueuedAt) / 1_000_000;
                queued.decrementAndGet();
                if (traceDispatch) {
                    log.info("[agentscope] 生成任务出队执行：session={}, 队列滞留={}ms", sessionId, waitMillis);
                }
                else if (waitMillis > warnThreshold.toMillis()) {
                    log.warn("[agentscope] 交互任务队列滞留 {}ms（阈值 {}ms）：session={}, 交互车道排队={}",
                            waitMillis, warnThreshold.toMillis(), sessionId, queued.get());
                }
                task.run();
            });
        }
    }

    /**
     * stripe 路由（package-private 供测试挑不同 stripe 的会话对，勿在实现外复刻）：
     * 尾随数字串（生产 sessionId 均为前缀＋TSID）按数值低位取模——String.hashCode
     * 对相邻 TSID 有碰撞聚集（#475 哈希复算：事故两会话同落 stripe 0）；无尾随
     * 数字（测试习语 id）回落 hashCode，行为同旧。
     */
    static int stripeIndex(String sessionId, int stripes) {
        long tail = trailingDigits(sessionId);
        if (tail >= 0) {
            return (int) (tail % stripes);
        }
        return Math.floorMod(sessionId.hashCode(), stripes);
    }

    /** 交互车道宽度的 stripe 路由（测试缝同上）。 */
    static int stripeIndex(String sessionId) {
        return stripeIndex(sessionId, STRIPES);
    }

    /** 尾随数字串的数值（无尾随数字或超 long 位宽返回 -1）。 */
    private static long trailingDigits(String sessionId) {
        int end = sessionId.length();
        while (end > 0 && Character.isDigit(sessionId.charAt(end - 1))) {
            end--;
        }
        if (end == sessionId.length()) {
            return -1;
        }
        try {
            return Long.parseLong(sessionId, end, sessionId.length(), 10);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public void destroy() {
        if (interactive != null) {
            for (ExecutorService stripe : interactive.stripes) {
                stripe.shutdownNow();
            }
        }
        if (generation != null) {
            for (ExecutorService stripe : generation.stripes) {
                stripe.shutdownNow();
            }
        }
    }
}
