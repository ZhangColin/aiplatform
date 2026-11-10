package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * verify 自决开关中间件（#442，#441-1——#439 终裁 verify 窗 20.9min 的最大单一
 * 可收窄面）：运营面 {@code verify_self_decision_enabled} 开关关（自决关闭）时，
 * 在执行体 ReAct 循环的 {@code onReasoning}（每迭代模型调用前）注入一条合成指令
 * 消息（{@code agentscope_synthetic}＋{@value #NOTICE_METADATA_KEY} 元数据——镜像
 * #437 决策提示注入先例），告知模型<b>生成过程中不要自发把验证委派给 verify 子
 * 智能体</b>（#435 自决化的提示层收窄腿——工作协议第 9 条的运营面覆盖件）：
 * 收口唯一硬判据仍是 8081 探活三件套；例外＝平台在任务说明或拒绝回执中明确要求的
 * 验证（验证在场闸/空转产出证明闸/整轨终验的 gate 回执与终验编排任务说明）照常
 * 执行——<b>本开关只约束自发性委派，不动平台驱动验证腿</b>。
 *
 * <p>注入口径：每会话（对话）一次——消息列回读 {@value #NOTICE_METADATA_KEY} 防
 * 重灌；开关开 → 零注入零扰动（缺省态＝现状维持）；开关读失败 → 原样透传
 * （fail-open，增益件绝不阻断推理链）。配置经 {@link BooleanSupplier} 每迭代
 * 动态查库（运营面改档下一次迭代即生效、不发版、进行中 run 不定格）——<b>装配
 * 不进 agent 实例缓存键</b>（改配置无需重建实例，与 #437 同逻辑）。粘性语义：
 * 注入过的指令随消息列留在本会话——开关中途切回开，已注入会话的指令仍生效至
 * 该会话结束（同「进行中 run 不定格」对偶口径）。中间件无状态、多 agent 共享
 * 安全。</p>
 */
@Slf4j
public class VerifySelfDecisionMiddleware implements MiddlewareBase {

    /** 注入消息的元数据键（注入时写入、回读判重——每会话一次语义的载体）。 */
    static final String NOTICE_METADATA_KEY = "verify_self_decision_notice";

    /** 注入消息名（同框架 reminder 的 name=system 先例的 Platform 版——与判重键分持）。 */
    static final String NOTICE_MESSAGE_NAME = "verify_self_decision_notice";

    /** 开关读腿（true＝自决开＝缺省现状；false＝注入关闭指令）。 */
    private final BooleanSupplier selfDecisionEnabled;

    public VerifySelfDecisionMiddleware(BooleanSupplier selfDecisionEnabled) {
        this.selfDecisionEnabled = selfDecisionEnabled;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (input == null || input.messages() == null || input.messages().isEmpty()) {
            return next.apply(input);
        }
        boolean enabled;
        try {
            enabled = selfDecisionEnabled.getAsBoolean();
        }
        catch (RuntimeException e) {
            // fail-open：开关读失败绝不阻断推理链（增益件的 best-effort 口径）
            log.warn("[verify-self-decision] 开关读取失败（降级透传）：{}", e.toString());
            return next.apply(input);
        }
        if (enabled || alreadyNoticed(input.messages())) {
            return next.apply(input);
        }
        log.info("[verify-self-decision] 自决关闭态注入指令 run={}", runIdOf(ctx));
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(noticeMessage());
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    /** 合成指令消息：框架合成口径（agentscope_synthetic），元数据带判重键。 */
    private static Msg noticeMessage() {
        return Msg.builder()
                .role(MsgRole.USER)
                .name(NOTICE_MESSAGE_NAME)
                .content(TextBlock.builder().text(NOTICE_TEXT).build())
                .metadata(Map.of(
                        Msg.METADATA_SYNTHETIC, true,
                        NOTICE_METADATA_KEY, true))
                .build();
    }

    /** 消息列回读判重：本会话已注入过指令即不再注入。 */
    private static boolean alreadyNoticed(List<Msg> messages) {
        for (Msg msg : messages) {
            if (msg != null && msg.getMetadata() != null
                    && msg.getMetadata().containsKey(NOTICE_METADATA_KEY)) {
                return true;
            }
        }
        return false;
    }

    private static String runIdOf(RuntimeContext ctx) {
        Object runId = ctx == null ? null : ctx.get(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY);
        return runId == null ? "" : String.valueOf(runId);
    }

    /** 关闭指令正文（提示层覆盖工作协议第 9 条——只约束自发性委派，平台驱动验证例外）。 */
    private static final String NOTICE_TEXT = "〔平台运营面通知〕verify 自决已关闭：生成过程中"
            + "不要自发把验证委派给 verify 子智能体（不主动 agent_spawn verify、不发起"
            + "修复—复验循环）——验证登记不是收口要件，收口唯一硬判据仍是 8081 探活"
            + "三件套。例外：平台在任务说明或拒绝回执中明确要求的验证（验证在场闸／"
            + "空转产出证明闸／整轨终验等的回执与终验编排任务说明）照常执行。";
}
