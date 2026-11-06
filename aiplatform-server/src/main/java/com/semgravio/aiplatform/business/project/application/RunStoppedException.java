package com.semgravio.aiplatform.business.project.application;

import java.util.Map;

/**
 * 用户停止的编码 run 收场信号（#359 生成取消）：在途 run 被用户叫停（停止请求
 * 在场时的一切收场——中断打断的流失败/环内查停），尝试环与轨道层以此旁路——
 * <b>不走重试环、不触发段回滚</b>（用户停止＝中断口径非判死，ADR-0020 断点脏续；
 * #353 回滚只属自动重试耗尽的失败段），一路上抛到轨道层的停止收口（run-failed
 * 载荷 stopped=true，失败账口径）。{@code runId} = 被停场次的用户面标识（首试
 * runId——终态收口事件的锚）。
 *
 * <p><b>停止的失败面随异常携出（#418 失败片落库面）</b>：{@code failure} =
 * 中断尝试的部分观察（逐尝试墙钟/档位＋本场累计——#323 留账口径，stopped 口径
 * 无失败原因）；轨道级停止（并行波停止——无逐尝试账在场）携最小面
 * （{@code { stopped: true }}）。轨道层收口经事件桥与 run-failed SSE 扩载同载荷
 * 落库。</p>
 */
class RunStoppedException extends RuntimeException {

    private final String runId;

    private final Map<String, Object> failure;

    RunStoppedException(String runId, Map<String, Object> failure) {
        super("用户停止（runId=" + runId + "）");
        this.runId = runId;
        this.failure = failure;
    }

    /** 被停场次的用户面 run 标识（终态收口事件的锚）。 */
    String runId() {
        return runId;
    }

    /** 停止的失败面（#418 落库面——环内携逐尝试账，轨道级携最小面）。 */
    Map<String, Object> failure() {
        return failure;
    }
}
