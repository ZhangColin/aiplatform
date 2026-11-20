package com.semgravio.aiplatform.base.agentscope;

import java.time.Instant;

/**
 * 运行期切候选留痕端口（ADR-0053 ③，#557）：{@code FailoverModel} 每次链式切换的
 * append-only 留痕出口——「何时/从哪候选切到哪/触发错误/run 上下文」落
 * {@code prj_model_config_switch_traces}（备账号被烧运营可见）。知识在业务侧
 * （SQL 归宿 {@code ModelChannelStore}），消费在底座（base 不依赖 business）——
 * 反转口先例 {@link EdgeModelSupplier}/{@code AgentToolkitSupplier}，单体进程内
 * Spring 接线。
 *
 * <p>留痕是运营观察面不是正确性腿：{@link #record} 的失败由调用侧（{@code
 * FailoverModel}）兜底告警、不中断生成流（可用性优先——留痕库故障不应烧穿切换
 * 本身）；实现侧如实上抛即可，无需自行防御。</p>
 */
public interface ModelSwitchTraceSink {

    /** 追加一条切换留痕（append-only）。 */
    void record(Trace trace);

    /**
     * 一次候选切换的事实束。
     *
     * @param configKey         配置语义键（切换的归档腿）
     * @param fromProviderKey 切出候选通道键
     * @param fromModelName   切出候选实际模型名
     * @param toProviderKey   切入候选通道键
     * @param toModelName     切入候选实际模型名
     * @param triggerError    触发错误摘要（异常类签名＋文案，截断至库列上限）
     * @param runId           run 上下文（可空——不在计量订阅树上的装配面切换）
     * @param sessionId       会话上下文（可空，同上）
     * @param occurredAt      切换时刻
     */
    record Trace(String configKey, String fromProviderKey, String fromModelName,
            String toProviderKey, String toModelName, String triggerError,
            String runId, String sessionId, Instant occurredAt) {
    }
}
