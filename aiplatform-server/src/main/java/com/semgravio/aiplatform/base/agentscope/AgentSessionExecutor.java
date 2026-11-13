package com.semgravio.aiplatform.base.agentscope;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * 会话级任务执行器（#475 分池，ADR-0042；#477 生成道 FIFO 化＋错峰准入）：交互车道
 * （秒级轮——主智能体对话/判定/取名/预检等）与生成车道（小时级产出轨整场任务——
 * 生成/迭代复演/设计轨）两条独立车道，小时级任务结构性饿不死交互轮（2026-11-12
 * 生产事故根因：8 条 stripe 共池，生成 run 落 stripe 内 {@code blockLast} 阻塞 1–2h，
 * 同 stripe 的面试交互轮 FIFO 永不到头）。
 *
 * <p><b>交互车道＝stripe 池</b>（sessionId 固定落单线程 stripe，单线程 FIFO）：同一
 * (userId, sessionId) 状态槽位一次只跑一轮——新轮与续跑并发会互踩 AgentState；
 * 碰撞代价＝秒级排队，无害。跨会话并行度上界＝stripe 数（{@link #STRIPES}）。</p>
 *
 * <p><b>生成车道＝全局 FIFO＋固定 worker 池＋错峰准入（#477）</b>：宽度（worker 数）
 * ＝跨项目并发产出轨上界（配置面可调，{@code app.agentscope.generation-stripes}
 * ——单机容量为准）。不沿用 sessionId 哈希落位——哈希碰撞会让新项目生成任务排在
 * 无关小时任务后等数小时（#475 事故在池内的复刻形），FIFO 保证「有空闲 worker
 * 即起跑」；同会话串行不变量由取件规则保住（会话在途执行中，其排队任务保序留队
 * ——CodingRunTrack 单轨之外的第二道进程内保险）。<b>错峰准入</b>（{@code
 * app.agentscope.generation-admission-interval}，缺省 10s、0＝关）：生成任务起跑
 * 按间隔逐个拉开——发版重启的自愈续跑洪水不再瞬时齐发占死车道、打满单机容量
 * （11-12 实录：发版 15 秒内 7 场中断生成齐跑）；车道空闲（间隔窗已过）时任务
 * 即取即跑，稳态单任务零新增延迟。</p>
 *
 * <p>队列可观测（#475 第二恶劣点「零痕迹」的治）：生成车道逐任务留痕（入队/
 * 出队滞留 INFO，低频）；交互车道出队滞留超阈值 WARN（{@code
 * app.agentscope.session-queue-warn-threshold}）。<b>关停排空（#477）</b>：优雅
 * 关停宽限窗内排队任务不再出队执行（排空而非消费——宽限窗内拉起只会秒败烧账，
 * 11-12 19:03 实录）。任务异常吞掉记日志（REST 快返回的异步轨道——失败经 error
 * 事件表达，不炸调用方）；执行器是进程内状态，重启即清（会话状态在
 * cat_agent_state，按会话标识恢复）。</p>
 */
@Component
@Slf4j
public class AgentSessionExecutor implements DisposableBean {

    /** 交互车道 stripe 数 = 常驻线程数 = 秒级轮跨会话并行度上界。 */
    static final int STRIPES = 8;

    private final Lane interactive;
    private final GenerationLane generation;
    /** 提交通道（生产=null 走车道池；测试=直通同步）。 */
    private final Executor passthrough;

    @Autowired
    public AgentSessionExecutor(AgentscopeProperties properties) {
        this(properties.getGenerationStripes(), properties.getSessionQueueWarnThreshold(),
                properties.getGenerationAdmissionInterval());
    }

    /** 测试便利构造（缺省车道参数、错峰关，无生命周期注入——生产走 @Autowired 配置面）。 */
    AgentSessionExecutor() {
        this(null, null, Duration.ZERO);
    }

    AgentSessionExecutor(Integer generationStripes, Duration queueWarnThreshold) {
        this(generationStripes, queueWarnThreshold, Duration.ZERO);
    }

    /**
     * 车道参数化构造（测试缝：生成车道宽度＋滞留告警阈值＋错峰准入间隔可指钉）。
     * 显式置空的配置回落缺省——防 yml 空值把拆箱 NPE 埋进启动（阈值尤其阴：NPE
     * 在任务 run 之前被吞，交互任务会静默消失——事故形态的复活路径）。
     */
    AgentSessionExecutor(Integer generationStripes, Duration queueWarnThreshold,
            Duration admissionInterval) {
        int lanes = Math.max(1, generationStripes == null ? STRIPES : generationStripes);
        Duration threshold = queueWarnThreshold == null ? Duration.ofSeconds(5) : queueWarnThreshold;
        Duration interval = admissionInterval == null ? Duration.ZERO : admissionInterval;
        this.interactive = Lane.interactive(STRIPES, threshold);
        this.generation = new GenerationLane(lanes, interval);
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
        return dispatchInteractive(sessionId, task);
    }

    /**
     * 提交一轮生成车道会话任务（产出轨整场任务，#475 分池；#477 FIFO＋错峰准入）：
     * 小时级任务独占车道，宽度即并发准入（超宽排队可见、错峰起跑）；同会话串行
     * 不变量与交互车道同构（同项目生成/续跑/复演不在道内并发——CodingRunTrack
     * 的轨道互斥之外，进程内再保险）。{@link Future} {@code cancel(true)} 语义同
     * {@link #submit}（#359 停止面经 CodingRunTrack.registerTask 登记后中断模型流
     * 订阅；#477 删除级联取消同通道）。
     */
    public Future<?> submitGeneration(String sessionId, Runnable task) {
        return dispatchGeneration(sessionId, task);
    }

    /** 生成车道当前排队深度（已提交未起跑——#475 队列可观测；测试断言缝）。 */
    int generationQueuedCount() {
        return generation.queuedCount();
    }

    /** 车道统一提交面（交互 stripe 池与生成 FIFO 池同形——提交侧共用直通/包装逻辑）。 */
    private interface SessionLane {

        Future<?> dispatch(String sessionId, Runnable task);
    }

    private Future<?> dispatchInteractive(String sessionId, Runnable task) {
        return dispatch(interactive, sessionId, task);
    }

    private Future<?> dispatchGeneration(String sessionId, Runnable task) {
        return dispatch(generation, sessionId, task);
    }

    private Future<?> dispatch(SessionLane lane, String sessionId, Runnable task) {
        Runnable wrapped = wrap(sessionId, task);
        if (passthrough != null) {
            passthrough.execute(wrapped);
            return CompletableFuture.completedFuture(null);
        }
        return lane.dispatch(sessionId, wrapped);
    }

    /**
     * 任务包装（两车道共用）：关停排空（#477——宽限窗内不再消费队列）＋异常吞掉
     * 记日志（失败经 error 事件表达，不炸提交方）。
     */
    private static Runnable wrap(String sessionId, Runnable task) {
        return () -> {
            // 关停排空（#477）：宽限窗内不再出队执行（排空而非消费）——否则排队轮
            // 在关停宽限窗内继续被拉起、各烧一轮秒败（2026-11-12 19:03 实录）；
            // 跳过只留痕，不补跑（重启后由轨道层断点续跑收口）
            if (AgentShutdownGate.isShuttingDown()) {
                log.info("[agentscope] 平台关停中，车道任务跳过不执行：session={}", sessionId);
                return;
            }
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
     * （高频不刷屏）。
     */
    private static final class Lane implements SessionLane {

        /** 交互车道（秒级轮）：高频不逐任务留痕，滞留超阈值才 WARN。 */
        static Lane interactive(int stripeCount, Duration warnThreshold) {
            return new Lane(stripeCount, "agentscope-session", warnThreshold);
        }

        final ExecutorService[] stripes;
        final AtomicInteger queued = new AtomicInteger();
        final Duration warnThreshold;

        private Lane(int stripeCount, String threadPrefix, Duration warnThreshold) {
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
        }

        @Override
        public Future<?> dispatch(String sessionId, Runnable task) {
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
     * 生成执行车道（#477 FIFO 化）：全局 FIFO 队列＋固定 worker 池（宽度＝并发上界）
     * ＋错峰准入。取件规则保同会话串行：会话在途执行中，其排队任务保序留队
     * （跨会话任务可越过——不产生 #475 式哈希碰撞饿死）；会话收工唤醒后再取。
     * {@link GenerationTask#cancel(boolean) cancel(true)} 中断在途执行线程（#359
     * 停止面/#477 删除级联的通道），排队中取消的任务取件时丢弃不起跑。
     */
    static final class GenerationLane implements SessionLane {

        private final int width;
        private final ExecutorService workers;
        /** FIFO 队列（pickLock 守卫——取件扫描与入队同锁，保扫描原子）。 */
        private final Deque<GenerationEntry> queue = new ArrayDeque<>();
        /** 在途执行中的会话集（同会话串行的取件判据）。 */
        private final Set<String> executingSessions = ConcurrentHashMap.newKeySet();
        /** 已提交未起跑计数（入队递增、起跑递减——队列可观测断言缝）。 */
        private final AtomicInteger queued = new AtomicInteger();
        /** 错峰准入间隔（纳秒；≤0＝关）。 */
        private final long admissionIntervalNanos;
        /** 上次准入时刻（paceLock 守卫；初值负无穷＝车道空闲时即取即跑）。 */
        private long lastAdmitAt = Long.MIN_VALUE / 2;
        private final Object paceLock = new Object();
        private final Object pickLock = new Object();

        GenerationLane(int width, Duration admissionInterval) {
            this.width = width;
            this.admissionIntervalNanos =
                    admissionInterval == null ? 0 : admissionInterval.toNanos();
            AtomicInteger workerSeq = new AtomicInteger();
            this.workers = Executors.newFixedThreadPool(width, runnable -> {
                Thread thread = new Thread(runnable,
                        "agentscope-generation-" + workerSeq.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            });
            for (int i = 0; i < width; i++) {
                workers.execute(this::work);
            }
        }

        @Override
        public Future<?> dispatch(String sessionId, Runnable task) {
            GenerationEntry entry = new GenerationEntry(sessionId, task);
            int depth = queued.incrementAndGet();
            log.info("[agentscope] 生成任务入队：session={}, 排位={}（车道并发上界={}）",
                    sessionId, depth, width);
            synchronized (pickLock) {
                queue.addLast(entry);
                pickLock.notifyAll();
            }
            return entry.future;
        }

        int queuedCount() {
            return queued.get();
        }

        private void work() {
            while (!Thread.currentThread().isInterrupted()) {
                GenerationEntry entry;
                synchronized (pickLock) {
                    while ((entry = pickEligible()) == null) {
                        try {
                            pickLock.wait();
                        }
                        catch (InterruptedException e) {
                            return; // destroy：worker 退出（进程关停，队列弃置）
                        }
                    }
                    // 串行标记随取件落（pickLock 内）：取件后的错峰等待窗不再是同会话
                    // 第二任务的并发缝（另一 worker 在窗内取件会看见本会话在途）
                    executingSessions.add(entry.sessionId);
                }
                // 错峰准入（#477）：生成任务起跑按间隔逐个拉开——洪水齐发是「发版即
                // 满车道」的形态；放弃路径（destroy 中断/排队中取消）须释放串行标记
                if (!awaitAdmission()) {
                    releasePick(entry);
                    return;
                }
                long waitMillis = (System.nanoTime() - entry.enqueuedAt) / 1_000_000;
                queued.decrementAndGet();
                log.info("[agentscope] 生成任务出队执行：session={}, 队列滞留={}ms",
                        entry.sessionId, waitMillis);
                entry.future.runner = Thread.currentThread();
                if (entry.future.isCancelled()) {
                    releasePick(entry); // 排队中被取消——不起跑（cancel 语义：未开始即取消）
                    continue;
                }
                try {
                    entry.task.run();
                    entry.future.complete(null);
                }
                catch (RuntimeException e) {
                    log.warn("[agentscope] 会话任务异常：session={}, {}", entry.sessionId,
                            e.getMessage());
                    entry.future.completeExceptionally(e);
                }
                finally {
                    entry.future.runner = null;
                    Thread.interrupted(); // 清迟到 cancel 的残段中断——不毒害下一任务
                    releasePick(entry);
                }
            }
        }

        /** 释放取件占位（串行标记＋唤醒取件）——正常收口与放弃路径共口。 */
        private void releasePick(GenerationEntry entry) {
            executingSessions.remove(entry.sessionId);
            synchronized (pickLock) {
                pickLock.notifyAll(); // 同会话留队任务可入
            }
        }

        /**
         * 取件（pickLock 守卫下调用）：FIFO 扫描取首个可跑任务——已取消的出队丢弃，
         * 会话在途执行的保序留队（同会话串行），全部不可跑返回 null（等待收工唤醒）。
         */
        private GenerationEntry pickEligible() {
            for (Iterator<GenerationEntry> it = queue.iterator(); it.hasNext(); ) {
                GenerationEntry candidate = it.next();
                if (candidate.future.isCancelled()) {
                    it.remove();
                    queued.decrementAndGet(); // 已取消条目出队——排队计数同步回落（排队可见不虚高）
                    continue;
                }
                if (executingSessions.contains(candidate.sessionId)) {
                    continue;
                }
                it.remove();
                return candidate;
            }
            return null;
        }

        /**
         * 错峰准入等待（#477）：与上次起跑至少隔一个 admission-interval（车道空闲
         * ＝间隔窗已过即取即跑，稳态单任务零新增延迟）。destroy 中断＝false（放弃）。
         */
        private boolean awaitAdmission() {
            if (admissionIntervalNanos <= 0) {
                return true;
            }
            synchronized (paceLock) {
                while (true) {
                    long now = System.nanoTime();
                    long earliest = lastAdmitAt + admissionIntervalNanos;
                    if (earliest <= now) {
                        lastAdmitAt = now;
                        return true;
                    }
                    long waitNanos = earliest - now;
                    try {
                        paceLock.wait(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
    }

    /** 生成车道队列条目：会话标识（串行判据）＋任务＋入队时刻（滞留口径）。 */
    private static final class GenerationEntry {

        final String sessionId;
        final Runnable task;
        final long enqueuedAt = System.nanoTime();
        final GenerationTask future = new GenerationTask();

        GenerationEntry(String sessionId, Runnable task) {
            this.sessionId = sessionId;
            this.task = task;
        }
    }

    /**
     * 生成车道任务句柄：{@code cancel(true)} 中断在途执行线程（#359 停止面/#477
     * 删除级联取消的通道——打断模型流订阅即停烧 token）；排队中取消由取件丢弃。
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
     * 外复刻——生成车道已 FIFO 化，不再按会话落位）：尾随数字串（生产 sessionId
     * 均为前缀＋TSID）按数值低位取模——String.hashCode 对相邻 TSID 有碰撞聚集
     * （#475 哈希复算：事故两会话同落 stripe 0）；无尾随数字（测试习语 id）回落
     * hashCode，行为同旧。
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
            generation.workers.shutdownNow();
        }
    }
}
