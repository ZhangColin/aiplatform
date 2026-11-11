package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.IterationOverheadFacts;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintConfig;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintPolicyProvider;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHints;
import com.semgravio.aiplatform.business.project.domain.decisionhint.HintDecision;

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
 * 决策时微指令注入中间件（#437，Replit decision-time guidance 杠杆的落地件）：
 * 挂在执行体 ReAct 循环的 {@code onReasoning}（每迭代的模型调用前——即「工具
 * 结果后」的决策点位），命中判定则
 * <ol>
 * <li>模型面：追加一条合成微指令消息（{@code agentscope_synthetic}＋
 * {@code decision_hint_kind} 元数据——镜像框架 TaskReminder 注入先例），本轮
 * 推理即见，不进用户面事件流（合成消息无 SSE 呈现——过程呈现不倒退）；</li>
 * <li>用户面：经 {@link AgentscopeAgentClient#PLATFORM_EVENT_SINK_CONTEXT_KEY}
 * 属性袋取本轮平台事件汇，推 {@code part-hint} 可见事实（同 emitSelfCheck 的
 * 平台侧部件先例，runId 归锚在调用方投影内）——注入内容对用户可见。</li>
 * </ol>
 *
 * <p>配置口径：策略经 {@link DecisionHintPolicyProvider} 每迭代读取（装配腿经
 * 5s TTL 进程内缓存降频——#453-2；运营面改档下一个 TTL 窗口即生效、不发版、
 * 进行中 run 不定格）——<b>因此装配不进 agent 实例缓存键</b>（改配置无需重建
 * 实例，与 #366 边缘点同逻辑）；总开关关/无信号/策略读失败一律原样透传
 * （fail-open，注入是增益件绝不阻断推理链）。堆叠语义（≤3、不重灌、单注入位）
 * 全由消息列回读承载（见 {@link DecisionHints}），中间件无状态、多 agent 共享
 * 安全。</p>
 *
 * <p>成本口径（#437 报告依据）：分类器为确定性规则判定（见 {@link DecisionHints}
 * 类注），零模型调用、零 token。#453-2 起策略读腿经 5s TTL 进程内缓存降频
 * （每迭代查库降为窗口内零查库）；#453-3 起判定前过廉价判定门（
 * {@link TrajectorySnapshots#mayProduceSignal}——门断四条规则都不可能命中即
 * 跳过重评估，全量快照提取是本专项残差首嫌；门跳过 ⇔ 判定必空，注入行为
 * 不变）——健康生成段绝大多数迭代只付有界预检，观测面为
 * {@link IterationOverheadFacts} 的 configMs/evalMs/gateSkipped/gateEvaluated
 * 桶（随收尾卡 durationBreakdown.overhead 留档）＋每条注入的 INFO 日志与
 * part-hint 事件流。</p>
 */
@Slf4j
public class DecisionHintMiddleware implements MiddlewareBase {

    /** 注入消息名（合成消息标识——同框架 reminder 的 name=system 先例的Platform 版）。 */
    static final String HINT_MESSAGE_NAME = "decision_hint";

    private final String agentKey;
    private final DecisionHintPolicyProvider policyProvider;

    public DecisionHintMiddleware(String agentKey, DecisionHintPolicyProvider policyProvider) {
        this.agentKey = agentKey;
        this.policyProvider = policyProvider;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (input == null || input.messages() == null || input.messages().isEmpty()) {
            return next.apply(input);
        }
        IterationOverheadFacts overhead = overheadOf(ctx);
        DecisionHintConfig config;
        try {
            // 掐表（#453 观测面）：策略读腿墙钟——TTL 缓存命中（#453-2）近零、miss 如实计
            long readStarted = System.nanoTime();
            config = policyProvider.configFor(agentKey);
            if (overhead != null) {
                overhead.addConfigNanos(System.nanoTime() - readStarted);
            }
        }
        catch (RuntimeException e) {
            // fail-open：策略读失败绝不阻断推理链（增益件的 best-effort 口径）
            log.warn("[decision-hint] 策略读取失败（降级透传）：{}", e.toString());
            return next.apply(input);
        }
        if (config == null || !config.enabled()) {
            return next.apply(input);
        }
        Optional<HintDecision> decision;
        try {
            // 判定门（#453-3 降频件）：廉价预检——门断必空即跳过重评估（全量快照
            // 提取＋判定是 O(本轮消息 × 结果字节) 的重活，门跳过 ⇔ decide 必返空，
            // 注入行为与门无关）；门与判定同表（判定失败经同一 catch fail-open）
            long gateStarted = System.nanoTime();
            boolean mayProduce = TrajectorySnapshots.mayProduceSignal(
                    input.messages(), config.policy());
            if (!mayProduce) {
                if (overhead != null) {
                    overhead.addEvalNanos(System.nanoTime() - gateStarted);
                    overhead.noteGateSkipped();
                }
                return next.apply(input);
            }
            decision = DecisionHints.decide(
                    TrajectorySnapshots.from(input.messages()), config.policy());
            if (overhead != null) {
                // 门成本并入 evalMs（门与判定同表——一次决策点位的完整判定墙钟）
                overhead.addEvalNanos(System.nanoTime() - gateStarted);
                overhead.noteGateEvaluated();
            }
        }
        catch (RuntimeException e) {
            // fail-open：判定失败绝不阻断推理链（增益件的 best-effort 口径）
            log.warn("[decision-hint] 判定失败（降级透传）：{}", e.toString());
            return next.apply(input);
        }
        if (decision.isEmpty()) {
            return next.apply(input);
        }
        HintDecision hint = decision.get();
        emitNotice(ctx, hint);
        log.info("[decision-hint] 注入 run={} kind={} reason={} instructionChars={}",
                runIdOf(ctx), hint.kind().key(), hint.reason(), hint.instruction().length());
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(hintMessage(hint));
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    /** 每迭代平台开销观察面（#453）：经属性袋取 facts，缺席（一次性调用面）跳过观测。 */
    private static IterationOverheadFacts overheadOf(RuntimeContext ctx) {
        Object facts = ctx == null ? null : ctx.get(AgentscopeAgentClient.ITERATION_OVERHEAD_CONTEXT_KEY);
        return facts instanceof IterationOverheadFacts overhead ? overhead : null;
    }

    /** 合成微指令消息：框架合成口径（agentscope_synthetic），元数据带库键供回读。 */
    private static Msg hintMessage(HintDecision hint) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name(HINT_MESSAGE_NAME)
                .content(TextBlock.builder().text(hint.instruction()).build())
                .metadata(Map.of(
                        Msg.METADATA_SYNTHETIC, true,
                        TrajectorySnapshots.HINT_KIND_METADATA_KEY, hint.kind().key()))
                .build();
    }

    /** 用户面可见事实：part-hint 经本轮平台事件汇推出（无汇的一次性调用面跳过）。 */
    @SuppressWarnings("unchecked")
    private static void emitNotice(RuntimeContext ctx, HintDecision hint) {
        if (ctx == null) {
            return;
        }
        Object sink = ctx.get(AgentscopeAgentClient.PLATFORM_EVENT_SINK_CONTEXT_KEY);
        if (!(sink instanceof Consumer)) {
            return;
        }
        // 平台事件类型与框架 AgentEvent 同名——装配单点在 AgentscopeAgentClient
        // （decisionHintEvent），此处仅留转换一行
        ((Consumer<com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent>) sink)
                .accept(AgentscopeAgentClient.decisionHintEvent(runIdOf(ctx), ctx.getSessionId(),
                        hint.kind().key(), hint.instruction()));
    }

    private static String runIdOf(RuntimeContext ctx) {
        Object runId = ctx == null ? null : ctx.get(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY);
        return runId == null ? "" : String.valueOf(runId);
    }
}
