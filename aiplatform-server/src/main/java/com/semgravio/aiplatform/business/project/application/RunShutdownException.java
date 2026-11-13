package com.semgravio.aiplatform.business.project.application;

import java.util.Map;

/**
 * 优雅关停中断的编码 run 收场信号（#477 关停旁路）：SIGTERM 宽限窗内的一切收场
 * （在飞模型调用被 {@code AgentShuttingDownException} 中断／环内查关停）按
 * <b>「中断可续跑」</b>口径旁路——不走重试环（不烧尝试预算、不升档）、不触发
 * 段回滚、不拉起后续片/排队轮；一路上抛轨道层的中断收口（区别于
 * {@link RunStoppedException} 的用户停止：关停不发终态事件——进程正死，SSE 与
 * 落库都在跟关停赛跑，留痕只日志；片保持待跑＝「继续生成」断点续跑事实源）。
 *
 * <p>{@code runId} = 被中断场次的用户面标识（首试 runId）；{@code failure} =
 * 中断尝试的部分观察（#323 留账口径，随异常携出供轨道层日志观测——不落库）。</p>
 */
class RunShutdownException extends RuntimeException {

    private final String runId;

    private final Map<String, Object> failure;

    RunShutdownException(String runId, Map<String, Object> failure) {
        super("平台关停中断（runId=" + runId + "）——不烧尝试预算，落中断可续跑态");
        this.runId = runId;
        this.failure = failure;
    }

    /** 被中断场次的用户面 run 标识（日志观测锚）。 */
    String runId() {
        return runId;
    }

    /** 中断尝试的失败面（#418 面形状——平台观测面日志，不落库不发事件）。 */
    Map<String, Object> failure() {
        return failure;
    }
}
