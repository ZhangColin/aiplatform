package com.semgravio.aiplatform.base.agentscope;

import java.util.List;

/**
 * 一轮流失败（converse / resume 流段异常的上抛形，#323 失败留账）：失败流段的
 * <b>部分观察面</b>随异常携带上浮，不再清零弃置——①已提交的文件变更事实
 * （{@link FileChangeFacts} 工具结果成功即提交的口径——失败流段里已成功落盘的写
 * 是真实工作区事实，重试在已有成果上继续修就该被看见）；②已闭合区间的阶段耗时
 * 事实（{@link StageDurationFacts} 只计配对闭合区间的口径——中段崩断时在途的
 * 未闭合区间仍不计，其墙钟由尝试环的尝试墙钟承载）。外层尝试环据此把失败尝试
 * 的观察面与耗时账计入本场累计。非本异常的失败（前段失败 / 收口判据回调抛错）
 * 无流段观察可携，维持零桶口径。
 */
public final class AgentTurnFailure extends IllegalStateException {

    private final List<FileChange> changes;
    private final StageDurations durations;

    /**
     * @param message   失败叙事（与既有失败消息同形：智能体调用/续跑失败（runId、
     *                  model）＋原因消息）
     * @param cause     流段原始异常
     * @param changes   失败流段已提交的文件变更（部分观察）
     * @param durations 失败流段已闭合区间的阶段耗时（部分观察）
     */
    public AgentTurnFailure(String message, Throwable cause, List<FileChange> changes,
            StageDurations durations) {
        super(message, cause);
        this.changes = List.copyOf(changes);
        this.durations = durations;
    }

    /** 失败流段已提交的文件变更（部分观察——工具结果成功即提交口径）。 */
    public List<FileChange> changes() {
        return changes;
    }

    /** 失败流段已闭合区间的阶段耗时（部分观察——未闭合区间不计口径）。 */
    public StageDurations durations() {
        return durations;
    }
}
