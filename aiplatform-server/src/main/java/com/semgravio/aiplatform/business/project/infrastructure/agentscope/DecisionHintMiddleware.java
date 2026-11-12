package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.IterationOverheadFacts;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintConfig;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintPolicyProvider;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHints;
import com.semgravio.aiplatform.business.project.domain.decisionhint.HintDecision;
import com.semgravio.aiplatform.business.project.domain.decisionhint.TrajectorySnapshot;

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
 * 结果后」的决策点位），命中判定则追加一条合成微指令消息（{@code
 * agentscope_synthetic}＋{@code decision_hint_kind} 元数据——镜像框架
 * TaskReminder 注入先例），本轮推理即见。<b>不进用户面事件流</b>（2026-11-12
 * 定档：微指令是模型面指引，用户面呈现只添噪——part-hint 事件已退役，观测走
 * INFO 日志）。
 *
 * <p><b>防重灌载体（2026-11-12 修）</b>：原设计「已注入键不重灌」靠消息列回读
 * （injectedHintKeys），但框架不把中间件追加的 ReasoningInput 消息写回
 * AgentState——下一迭代输入列由 state 重建、恒不含注入消息，回读恒空 → 生产
 * 实案同一提示每迭代重注刷屏。现改双源：①RuntimeContext 属性袋里的本 run 注入
 * 集（{@value #INJECTED_KINDS_CONTEXT_KEY}，条目＝agentKey|sessionId|kind——
 * ctx 跨迭代同实例（#453 IterationOverheadFacts 先例）、跨 run 新实例，天然
 * 「每 run 每会话每键一次」）；②消息列回读保留（框架若未来持久化合成消息，
 * 重放语义仍成立）。两源并集进 {@link DecisionHints#select} 的既有堆叠裁决。</p>
 *
 * <p>配置口径：策略经 {@link DecisionHintPolicyProvider} 每迭代读取（装配腿经
 * 5s TTL 进程内缓存降频——#453-2；运营面改档下一个 TTL 窗口即生效、不发版、
 * 进行中 run 不定格）——<b>因此装配不进 agent 实例缓存键</b>（改配置无需重建
 * 实例，与 #366 边缘点同逻辑）；总开关关/无信号/策略读失败一律原样透传
 * （fail-open，注入是增益件绝不阻断推理链）。</p>
 *
 * <p>成本口径（#437 报告依据）：分类器为确定性规则判定（见 {@link DecisionHints}
 * 类注），零模型调用、零 token。#453-2 起策略读腿经 5s TTL 进程内缓存降频
 * （每迭代查库降为窗口内零查库）；#453-3 起判定前过廉价判定门（
 * {@link TrajectorySnapshots#mayProduceSignal}——门断四条规则都不可能命中即
 * 跳过重评估，全量快照提取是本专项残差首嫌；门跳过 ⇔ 判定必空，注入行为
 * 不变）——健康生成段绝大多数迭代只付有界预检，观测面为
 * {@link IterationOverheadFacts} 的 configMs/evalMs/gateSkipped/gateEvaluated
 * 桶（随收尾卡 durationBreakdown.overhead 留档）＋每条注入的 INFO 日志。</p>
 */
@Slf4j
public class DecisionHintMiddleware implements MiddlewareBase {

    /** 注入消息名（合成消息标识——同框架 reminder 的 name=system 先例的Platform 版）。 */
    static final String HINT_MESSAGE_NAME = "decision_hint";

    /**
     * 本 run 注入集的属性袋键（2026-11-12 防重灌载体）：值为 {@code Set<String>}，
     * 条目＝{@code agentKey|sessionId|kind}——run 生命周期内同一会话同一键至多
     * 注入一次（跨段不重注：微指令是一次性提醒，复读只烧 token 且无增益）。
     */
    static final String INJECTED_KINDS_CONTEXT_KEY = "decisionHintInjectedKinds";

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
            TrajectorySnapshot snapshot = TrajectorySnapshots.from(input.messages());
            decision = DecisionHints.select(
                    DecisionHints.classify(snapshot, config.policy()),
                    injectedKinds(ctx, snapshot), config.policy());
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
        noteInjected(ctx, hint.kind().key());
        log.info("[decision-hint] 注入 run={} kind={} reason={} instructionChars={}",
                runIdOf(ctx), hint.kind().key(), hint.reason(), hint.instruction().length());
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(hintMessage(hint));
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    /**
     * 防重灌注入集（双源并集）：①属性袋本 run 已注入条目（见类注——真实流的
     * 主载体）；②消息列回读（框架若持久化合成消息则非空——保留原设计语义，
     * 兼容未来框架行为）。ctx 缺席（一次性调用面）只回读消息列。
     */
    private List<String> injectedKinds(RuntimeContext ctx, TrajectorySnapshot snapshot) {
        List<String> injected = new ArrayList<>(snapshot.injectedHintKeys());
        Object store = ctx == null ? null : ctx.get(INJECTED_KINDS_CONTEXT_KEY);
        if (store instanceof Set<?> set) {
            String scope = agentKey + "|" + (ctx.getSessionId() == null ? "" : ctx.getSessionId()) + "|";
            for (Object entry : set) {
                if (entry instanceof String key && key.startsWith(scope)) {
                    injected.add(key.substring(scope.length()));
                }
            }
        }
        return injected;
    }

    /**
     * 注入记账：写属性袋集（首写建集）。并发口径——同 ctx 上多 agent 并发首写
     * 存在 get-put 竞态（后写覆盖先写丢条目），最坏后果＝某提示多注一次，不
     * 损正确性主线（ReAct 循环内同 agent 串行，正常路径无竞态）。
     */
    @SuppressWarnings("unchecked")
    private void noteInjected(RuntimeContext ctx, String kindKey) {
        if (ctx == null) {
            return;
        }
        Object existing = ctx.get(INJECTED_KINDS_CONTEXT_KEY);
        if (!(existing instanceof Set)) {
            existing = java.util.concurrent.ConcurrentHashMap.newKeySet();
            ctx.put(INJECTED_KINDS_CONTEXT_KEY, existing);
        }
        ((Set<String>) existing)
                .add(agentKey + "|" + (ctx.getSessionId() == null ? "" : ctx.getSessionId())
                        + "|" + kindKey);
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

    private static String runIdOf(RuntimeContext ctx) {
        Object runId = ctx == null ? null : ctx.get(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY);
        return runId == null ? "" : String.valueOf(runId);
    }
}
