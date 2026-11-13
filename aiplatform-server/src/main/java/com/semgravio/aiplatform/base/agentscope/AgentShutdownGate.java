package com.semgravio.aiplatform.base.agentscope;

import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.ShutdownState;

/**
 * 优雅关停闸（#477 关停旁路）：内核 {@link GracefulShutdownManager} 单例状态的
 * 平台单点读口——SIGTERM 后 JVM 钩子（{@code AgentScopeJvmShutdownHook}）置
 * {@code SHUTTING_DOWN} 并中断在飞模型调用（{@link AgentShuttingDownException}），
 * 随后在宽限窗内等待在途请求收口。<b>宽限窗内的一切编码 run 收场都按「中断」
 * 处置</b>（不重试、不烧尝试预算、不升档、不触发下一轮派发），否则关停错误被
 * 重试环当普通失败消费——2026-11-12 19:03 生产实录：升档秒败×3 烧满转终态、
 * 排队轮在宽限窗内继续拉起各烧 3 次，6m50s 进度报废（本应落「中断可续跑」态）。
 *
 * <p>业务层（business.project.application）经本类读关停态/判关停因，不直触
 * {@code io.agentscope.core.shutdown}（分层同 {@link AgentSessionExecutor} 等
 * 薄包惯例——base.agentscope 是内核的唯一接线面）。</p>
 */
public final class AgentShutdownGate {

    private AgentShutdownGate() {
    }

    /** 平台是否已进入优雅关停（RUNNING 之外皆真——SHUTTING_DOWN/TERMINATED）。 */
    public static boolean isShuttingDown() {
        return GracefulShutdownManager.getInstance().getState() != ShutdownState.RUNNING;
    }

    /**
     * 失败是否由关停中断引起：异常链上出现 {@link AgentShuttingDownException}
     * （内核在模型流/请求入口掷出、经 {@link AgentTurnFailure} 包装上浮）即真。
     * 与 {@link #isShuttingDown()} 互补双保险——链判据权威于「这次失败」，
     * 状态判据兜住非典型异常形（中断时点上游异常面可能变形）。真实链形不可在
     * RUNNING 态构造（内核掷此异常前置位关停态），测试经 {@link #enterShutdownForTesting()}
     * 以状态判据驱动同一旁路。
     */
    public static boolean causedByShutdown(Throwable failure) {
        for (Throwable current = failure; current != null;
                current = current.getCause() == current ? null : current.getCause()) {
            if (current instanceof AgentShuttingDownException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 测试缝：进入关停态（内核单例 {@code performGracefulShutdown} 的平台接线）
     * ——业务层测试模拟「SIGTEM 宽限窗」一律经本缝，不直触内核单例（base.agentscope
     * 是内核的唯一接线面）。须以 {@link #exitShutdownForTesting()} 收尾复位。
     */
    public static void enterShutdownForTesting() {
        GracefulShutdownManager.getInstance().performGracefulShutdown();
    }

    /** 测试缝：退出关停态复位（内核 {@code resetForTesting} 的平台接线）。 */
    public static void exitShutdownForTesting() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }
}
