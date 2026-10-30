package com.semgravio.aiplatform.business.project.application;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 切片并行执行器（#351）：并行波内各 fork 切片各跑一线程。独立于
 * {@link com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor}——后者的
 * stripe 模型按 sessionId 哈希串行同会话，轨道任务（跑在 {@code coder-{projectId}}
 * stripe 上）若等待同 stripe 排队的 fork 片任务会死锁（单线程 stripe 上轨道任务
 * 阻塞不 yield、fork 片排在后面永不到达）。本执行器用独立固定小池（daemon），
 * 并发度上界 = 波内切片数（受池大小约束）。
 */
@Component
public class SliceParallelExecutor implements DisposableBean {

    /** 池大小 = 并行度上界（波内切片数一般 ≤ 此值；超出则排队退化，不劣于串行）。 */
    private static final int THREADS = 8;

    private final ExecutorService pool;

    public SliceParallelExecutor() {
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(THREADS, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "slice-parallel-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** 并行任务投递面（{@link java.util.concurrent.CompletableFuture#supplyAsync} 的池）。 */
    public ExecutorService pool() {
        return pool;
    }

    /**
     * 并行任务提交（#359 生成取消起波任务走本口）：返回池的 {@link Future}——
     * {@code cancel(true)} 中断执行中的 fork 片线程（打断阻塞中的模型流订阅）。
     * {@link java.util.concurrent.CompletableFuture#cancel} 不传中断（对已起跑任务
     * 无效），停止通道必须走本口。
     */
    public <T> Future<T> submit(Callable<T> task) {
        return pool.submit(task);
    }

    @Override
    public void destroy() {
        pool.shutdownNow();
    }
}
