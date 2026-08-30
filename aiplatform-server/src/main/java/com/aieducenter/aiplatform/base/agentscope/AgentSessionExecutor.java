package com.aieducenter.aiplatform.base.agentscope;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话级任务执行器：同一 (userId, sessionId) 状态槽位一次只跑一轮——新轮与续跑
 * 并发会互踩 AgentState，故 sessionId 哈希固定落一个 stripe（单线程 FIFO）严格
 * 排队；跨会话多数落不同 stripe 并行执行（单线程全局排队曾是「创建→对话」分钟级
 * 等待的第二来源），哈希碰撞时退化为同 stripe 排队——不劣于串行。并行度上界 =
 * stripe 数（{@value #STRIPES}，daemon）。
 *
 * <p>任务异常吞掉记日志（REST 快返回的异步轨道——失败经 error 帧表达，不炸调用方）；
 * 执行器是进程内状态，重启即清（会话状态在 cat_agent_state，按会话标识恢复）。</p>
 */
@Component
@Slf4j
public class AgentSessionExecutor implements DisposableBean {

    /** stripe 数 = 常驻线程数 = 跨会话并行度上界。 */
    static final int STRIPES = 8;

    private final ExecutorService[] stripes;
    /** 提交通道（生产=null 走 stripe 池；测试=直通同步）。 */
    private final Executor passthrough;

    public AgentSessionExecutor() {
        this.stripes = new ExecutorService[STRIPES];
        for (int index = 0; index < STRIPES; index++) {
            final int stripeIndex = index;
            this.stripes[index] = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "agentscope-session-" + stripeIndex);
                thread.setDaemon(true);
                return thread;
            });
        }
        this.passthrough = null;
    }

    /** 测试便利构造（直通执行器，无生命周期）。 */
    public AgentSessionExecutor(Executor executor) {
        this.stripes = null;
        this.passthrough = executor;
    }

    /** 提交一轮会话任务（新轮或续跑）；同会话严格按提交序执行。 */
    public void submit(String sessionId, Runnable task) {
        dispatch(sessionId, () -> {
            try {
                task.run();
            }
            catch (RuntimeException e) {
                log.warn("[agentscope] 会话任务异常：session={}, {}", sessionId, e.getMessage());
            }
        });
    }

    /** 生产：落 sessionId 哈希对应的 stripe（同会话恒同 stripe → 串行）；测试：直通。 */
    private void dispatch(String sessionId, Runnable wrapped) {
        if (passthrough != null) {
            passthrough.execute(wrapped);
            return;
        }
        stripes[stripeIndex(sessionId)].execute(wrapped);
    }

    /** stripe 路由（package-private 供测试挑不同 stripe 的会话对，勿在实现外复刻）。 */
    static int stripeIndex(String sessionId) {
        return Math.floorMod(sessionId.hashCode(), STRIPES);
    }

    @Override
    public void destroy() {
        if (stripes != null) {
            for (ExecutorService stripe : stripes) {
                stripe.shutdownNow();
            }
        }
    }
}
