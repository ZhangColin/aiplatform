package com.semgravio.aiplatform.business.project.application;

import java.util.Map;

/**
 * 优雅关停中断的编码 run 收场信号（#477 关停旁路）：SIGTERM 宽限窗内的一切收场
 * （在飞模型调用被 {@code AgentShuttingDownException} 中断／环内查关停）按
 * <b>「中断非失败」</b>口径旁路——不判失败、不拉起后续排队轮；一路上抛轨道层的
 * 中断收口（区别于 {@link RunStoppedException} 的用户停止：关停不发终态事件——
 * 进程正死，SSE 与落库都在跟关停赛跑，留痕只日志；重启后由重跑入口恢复——
 * #604 起续跑机器退役，不再有断点事实源）。
 *
 * <p>{@code runId} = 被中断场次的用户面标识；{@code failure} =
 * 中断尝试的部分观察（#323 留账口径，随异常携出供轨道层日志观测——不落库）。</p>
 */
class RunShutdownException extends RuntimeException {

    private final String runId;

    private final Map<String, Object> failure;

    RunShutdownException(String runId, Map<String, Object> failure) {
        super("平台关停中断（runId=" + runId + "）——不判失败、不发终态事件");
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
