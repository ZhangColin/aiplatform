package com.semgravio.aiplatform.business.project.application;

/**
 * 用户停止的编码 run 收场信号（#359 生成取消）：在途 run 被用户叫停（停止请求
 * 在场时的一切收场——中断打断的流失败/环内查停），尝试环与轨道层以此旁路——
 * <b>不走重试环、不触发段回滚</b>（用户停止＝中断口径非判死，ADR-0020 断点脏续；
 * #353 回滚只属自动重试耗尽的失败段），一路上抛到轨道层的停止收口（run-failed
 * 载荷 stopped=true，失败账口径）。{@code runId} = 被停场次的用户面标识（首试
 * runId——终态收口事件的锚）。
 */
class RunStoppedException extends RuntimeException {

    private final String runId;

    RunStoppedException(String runId) {
        super("用户停止（runId=" + runId + "）");
        this.runId = runId;
    }

    /** 被停场次的用户面 run 标识（终态收口事件的锚）。 */
    String runId() {
        return runId;
    }
}
