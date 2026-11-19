package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cartisan.data.jpa.id.TsidGenerator;
import com.semgravio.aiplatform.base.agentscope.ModelSwitchTraceSink;
import com.semgravio.aiplatform.business.project.domain.model.ModelTierSwitchTrace;
import com.semgravio.aiplatform.business.project.domain.repository.ModelChannelStore;

/**
 * 运行期切候选留痕落库适配器（#557，ADR-0053 ③）：base 层 {@link ModelSwitchTraceSink}
 * 端口的业务实现——FailoverModel 每次链式切换的事实束转 {@link ModelTierSwitchTrace}
 * 经 {@link ModelChannelStore} 落 {@code prj_model_tier_switch_traces}
 * （append-only，id＝TSID 写痕时生成——照 V45 两留痕表机制）。
 *
 * <p><b>边界知识在此归位</b>：触发错误摘要在 base 层产全量（异常类签名＋文案），
 * 本层截断至库列宽（{@code trigger_error VARCHAR(500)}——列宽是业务侧事实，base
 * 不感知）。记录失败如实上抛，由调用侧（FailoverModel）兜底告警不中断生成流
 * （观察面不烧穿可用性面——单点防御）。无事务：单行 append-only insert，库自持
 * 原子性。</p>
 */
@Component
public class PersistingModelSwitchTraceSink implements ModelSwitchTraceSink {

    private static final Logger logger =
            LoggerFactory.getLogger(PersistingModelSwitchTraceSink.class);

    /** 触发错误摘要的库列上限（prj_model_tier_switch_traces.trigger_error）。 */
    private static final int TRIGGER_ERROR_MAX = 500;

    private final ModelChannelStore channelStore;

    public PersistingModelSwitchTraceSink(ModelChannelStore channelStore) {
        this.channelStore = channelStore;
    }

    @Override
    public void record(Trace trace) {
        channelStore.insertTierSwitchTrace(new ModelTierSwitchTrace(
                TsidGenerator.newInstance().generate(),
                trace.tierKey(),
                trace.fromProviderKey(), trace.fromModelName(),
                trace.toProviderKey(), trace.toModelName(),
                truncate(trace.triggerError()),
                trace.runId(), trace.sessionId(),
                LocalDateTime.ofInstant(trace.occurredAt(), ZoneId.systemDefault())));
    }

    private static String truncate(String summary) {
        if (summary == null || summary.length() <= TRIGGER_ERROR_MAX) {
            return summary;
        }
        logger.info("切换留痕触发错误超列宽截断（{}→{} 字符）", summary.length(), TRIGGER_ERROR_MAX);
        return summary.substring(0, TRIGGER_ERROR_MAX);
    }
}
