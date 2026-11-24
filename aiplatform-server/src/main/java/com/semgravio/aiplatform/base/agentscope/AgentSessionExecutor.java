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
 * 会话级任务执行器（#475 分池 ADR-0042／#477 FIFO＋错峰准入 ADR-0043 的生成车道
 * 形态已随 #603 整体退场——双池／错峰准入／优雅排空全拆，改记见两 ADR 文首与
 * ADR-0055 #580-4）：两条腿——
 *
 * <p><b>交互车道＝stripe 池</b>（sessionId 固定落单线程 stripe，单线程 FIFO）：
 * 承载秒级轮——主智能体对话/判定/取名等。同一 (userId, sessionId)
 * 状态槽位一次只跑一轮——新轮与续跑
 * 并发会互踩 AgentState；碰撞代价＝秒级排队，无害。跨会话并行度上界＝stripe 数
 * （{@link #STRIPES}）。出队滞留超 {@link #QUEUE_WARN_THRESHOLD} 即 WARN——
 * 「任务排在队列里无痕迹」是 2026-11-12 生产事故定位全靠 jstack 的根源，可观测
 * 面保留（原 session-queue-warn-threshold 配置键已随 #603 拆除、取值定死）。</p>
 *
 * <p><b>产出轨整场任务＝直接执行</b>（生成首跑/修正迭代，#603）：每任务一线程
 * 即交即跑——不领号排队、无错峰准入、无关停排空。车道排队层是切片时代老栈的
 * 配套保险（防小时级任务占死交互车道/发版洪水齐发），新内核形态单场 run
 * 10–17 分钟且并发资源面另有正身（项目内 FIFO＋休眠封存＋Docker 硬边界，
 * ADR-0055 #589——全局并发上限机制不进），排队层随旧栈整体退场。同项目并发
 * 不变量由 CodingRunTrack 单轨把住（派发前 begin 互斥——进程内在途事实的唯一
 * 正身，原车道取件规则只是第二道保险）。<b>停止面契约不变</b>（#359 用户停止/
 * #477 删除级联）：返回句柄 {@code cancel(true)} 中断在途执行线程（打断模型流
 * 订阅的通道），句柄照旧经 {@code CodingRunTrack.registerTask} 登记。</p>
 *
 * <p>任务异常吞掉记日志（REST 快返回的异步轨道——失败经 error 事件表达，不炸
 * 调用方）；执行器是进程内状态，重启即清（会话状态在 cat_agent_state，按会话
 * 标识恢复）。</p>
 */
@Component
@Slf4j
public class AgentSessionExecutor implements DisposableBean {

    /** 交互车道 stripe 数 = 常驻线程数 = 秒级轮跨会话并行度上界。 */
    static final int STRIPES = 8;

    /** 交互任务滞留告警阈值（#475 可观测面；取值依据见类注释——键已随 #603 拆除、定死）。 */
    static final Duration QUEUE_WARN_THRESHOLD = Duration.ofSeconds(5);

    /** 交互车道（stripe 池）。 */
    private final Lane interactive;

    /** 产出轨整场任务直接执行池（#603——生成车道退场后的即交即跑底座）。 */
    private final ExecutorService directWorkers;

    /** 提交通道（生产=null 走车道/直跑池；测试=直通同步）。 */
    private final Executor passthrough;

    /** 生产构造：无配置依赖（三个车道配置键已随 #603 拆除，属性面不再注入本件）。 */
    @Autowired
    public AgentSessionExecutor() {
        this(QUEUE_WARN_THRESHOLD);
    }

    /** 测试缝：滞留告警阈值可指钉（生产取定值 {@link #QUEUE_WARN_THRESHOLD}）。 */
    AgentSessionExecutor(Duration queueWarnThreshold) {
        this.interactive = new Lane(STRIPES, queueWarnThreshold);
        AtomicInteger workerSeq = new AtomicInteger();
        this.directWorkers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "agentscope-generation-" + workerSeq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        this.passthrough = null;
    }

    /** 测试便利构造（直通执行器，无生命周期）。 */
    public AgentSessionExecutor(Executor executor) {
        this.interactive = null;
        this.directWorkers = null;
        this.passthrough = executor;
    }

    /**
     * 提交一轮交互车道会话任务（新轮或续跑；同会话严格按提交序执行）。返回任务的
     * {@link Future}——{@code cancel(true)} 中断在途执行线程（#359 生成取消：
     * 停止请求经此打断阻塞中的模型流订阅；测试直通已同步跑完，返回已完成
     * future，cancel 是无害 no-op）。
     */
    public Future<?> submit(String sessionId, Runnable task) {
        Runnable wrapped = wrap(sessionId, task);
        Future<?> early = passthroughOrNull(wrapped);
        if (early != null) {
            return early;
        }
        return interactive.dispatch(sessionId, wrapped);
    }

    /**
     * 提交产出轨整场任务（生成首跑/修正迭代；#603 起<b>直接执行</b>——原生成
     * 车道的排队/错峰准入/优雅排空已随旧栈拆除）：即交即跑，每任务一线程。
     * {@link Future} {@code cancel(true)} 中断在途执行线程——#359 停止面/
     * #477 删除级联取消的通道不变，调用方照旧经 {@code CodingRunTrack#registerTask}
     * 登记句柄。取消窗语义：{@code execute} 已接手但任务尚未起跑的缝内取消＝
     * 不起跑（起跑前查取消位——旧车道「排队中取消取件丢弃」的同口径保留）。
     */
    public Future<?> submitGeneration(String sessionId, Runnable task) {
        Runnable wrapped = wrap(sessionId, task);
        Future<?> early = passthroughOrNull(wrapped);
        if (early != null) {
            return early;
        }
        GenerationTask handle = new GenerationTask();
        directWorkers.execute(() -> {
            handle.runner = Thread.currentThread();
            try {
                if (!handle.isCancelled()) {
                    wrapped.run();
                }
            }
            finally {
                handle.runner = null;
                Thread.interrupted(); // 清迟到 cancel 的残段中断——不毒害池线程复用
                handle.complete(null);
            }
        });
        return handle;
    }

    /** 测试直通通道：直通已代跑并返回已完成句柄；生产返回 null（走真实路径）。 */
    private Future<?> passthroughOrNull(Runnable wrapped) {
        if (passthrough != null) {
            passthrough.execute(wrapped);
            return CompletableFuture.completedFuture(null);
        }
        return null;
    }

    /** 任务包装（两腿共用）：异常吞掉记日志（失败经 error 事件表达，不炸提交方）。 */
    private static Runnable wrap(String sessionId, Runnable task) {
        return () -> {
            try {
                task.run();
            }
            catch (RuntimeException e) {
                log.warn("[agentscope] 会话任务异常：session={}, {}", sessionId, e.getMessage());
            }
        };
    }

    /**
     * 交互执行车道（stripe 池）：sessionId 固定落 stripe（单线程 FIFO）保同会话
     * 串行；跨会话并行度上界 = stripe 数。队列可观测：出队滞留超阈值 WARN
     * （高频不逐任务留痕）。
     */
    private static final class Lane {

        final ExecutorService[] stripes;
        final AtomicInteger queued = new AtomicInteger();
        final Duration warnThreshold;

        private Lane(int stripeCount, Duration warnThreshold) {
            this.stripes = new ExecutorService[stripeCount];
            for (int index = 0; index < stripeCount; index++) {
                int stripeIndex = index;
                this.stripes[index] = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "agentscope-session-" + stripeIndex);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            this.warnThreshold = warnThreshold;
        }

        Future<?> dispatch(String sessionId, Runnable task) {
            int depth = queued.incrementAndGet();
            long enqueuedAt = System.nanoTime();
            return stripes[stripeIndex(sessionId, stripes.length)].submit(() -> {
                long waitMillis = (System.nanoTime() - enqueuedAt) / 1_000_000;
                queued.decrementAndGet();
                if (waitMillis > warnThreshold.toMillis()) {
                    log.warn("[agentscope] 交互任务队列滞留 {}ms（阈值 {}ms）：session={}, 交互车道排队={}",
                            waitMillis, warnThreshold.toMillis(), sessionId, queued.get());
                }
                task.run();
            });
        }
    }

    /**
     * 产出轨任务句柄（#603 直接执行）：{@code cancel(true)} 中断在途执行线程
     * （#359 停止面/#477 删除级联取消的通道——打断模型流订阅即停烧 token）。
     */
    static final class GenerationTask extends CompletableFuture<Void> {

        volatile Thread runner;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (mayInterruptIfRunning) {
                Thread current = runner;
                if (current != null) {
                    current.interrupt();
                }
            }
            return super.cancel(mayInterruptIfRunning);
        }
    }

    /**
     * stripe 路由（交互车道；package-private 供测试挑不同 stripe 的会话对，勿在实现
     * 外复刻）：尾随数字串（生产 sessionId 均为前缀＋TSID）按数值低位取模——
     * String.hashCode 对相邻 TSID 有碰撞聚集（#475 哈希复算：事故两会话同落
     * stripe 0）；无尾随数字（测试习语 id）回落 hashCode，行为同旧。
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
        if (directWorkers != null) {
            directWorkers.shutdownNow();
        }
    }
}
