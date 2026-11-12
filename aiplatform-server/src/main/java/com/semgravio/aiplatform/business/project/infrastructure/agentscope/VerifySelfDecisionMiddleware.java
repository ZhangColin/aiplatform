package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.IterationOverheadFacts;

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
 * <p>注入口径：每轮（派段/对话窗）一次（载体见下段）；开关开 → 零注入
 * 零扰动（缺省态＝现状维持）；开关读失败 → 原样透传（fail-open，增益件绝不阻断
 * 推理链）。配置经 {@link BooleanSupplier} 每迭代动态查库（运营面改档下一次迭代
 * 即生效、不发版、进行中 run 不定格）——<b>装配不进 agent 实例缓存键</b>（改配置
 * 无需重建实例，与 #437 同逻辑）。中间件无状态、多 agent 共享安全。</p>
 *
 * <p><b>防重灌载体（#481 修，#480 ①b 同构缺陷）</b>：原设计「每会话一次」靠
 * 消息列回读 {@value #NOTICE_METADATA_KEY}，但框架不把中间件追加进
 * ReasoningInput 的合成消息写回 AgentState——下一迭代输入列由 state 重建、恒
 * 不含注入消息，回读恒空 → 自决关闭态下关闭指令每迭代重注（#480 生产实案同
 * 形态）。现改双源：①RuntimeContext 属性袋里的已注入会话集（
 * {@value #NOTICED_SESSIONS_CONTEXT_KEY}，条目＝sessionId——ctx 逐轮命令构建
 * （AgentscopeAgentClient.prepareTurn：一 run 多派段则每段新实例）、段内跨迭代
 * 同实例（#453 facts 同载体先例），故作用域＝ctx 生命周期＝一轮（派段/对话窗）
 * ——本中间件只挂执行体装配面且指令单一，条目不带 #480 的 agentKey|kind 前缀，
 * 载体机制同构、维度按本件收窄）；②消息列回读保留（框架若未来持久化合成消息，
 * 重放语义仍成立）。<b>口径换位须明说</b>：原「粘性：注入过的指令随消息列留在
 * 本会话——开关中途切回开，已注入会话的指令仍生效至该会话结束」在真实流从未
 * 成立——合成消息不落 state、不随消息列留存，注入仅当次模型调用可见（防重灌
 * 与指令可见性是两件事，复读只烧 token 且无增益——#480 同判）。换位后语义：
 * 段内迭代间不重注（段内开关切回开再关也不重注——袋已留痕）；新派段新 ctx
 * 首迭代再注入一次，指令在新对话窗在场——对齐原「每会话（对话＝派段）一次」
 * 意图，真正修掉的是段内每迭代重注。</p>
 */
@Slf4j
public class VerifySelfDecisionMiddleware implements MiddlewareBase {

    /** 注入消息的元数据键（注入时写入、消息列回读判重——兼容源载体，主载体见下键）。 */
    static final String NOTICE_METADATA_KEY = "verify_self_decision_notice";

    /** 注入消息名（同框架 reminder 的 name=system 先例的 Platform 版——与判重键分持）。 */
    static final String NOTICE_MESSAGE_NAME = "verify_self_decision_notice";

    /**
     * 已注入会话集的属性袋键（#481 防重灌载体——#480 ①b 同构修）：值为
     * {@code Set<String>}，条目＝sessionId——ctx 生命周期（一轮命令/派段）内
     * 同一会话至多注入一次（复读只烧 token 且无增益）。只挂执行体装配面且指令
     * 单一（无 kind 维度），条目不带 #480 DecisionHintMiddleware 的
     * agentKey|kind 前缀——载体机制同构、维度按本件装配面收窄。
     */
    static final String NOTICED_SESSIONS_CONTEXT_KEY = "verifySelfDecisionNoticedSessions";

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
        IterationOverheadFacts overhead = overheadOf(ctx);
        boolean enabled;
        try {
            // 掐表（#453 观测面）：开关读腿墙钟——TTL 缓存命中时近零、miss 时如实计
            long readStarted = System.nanoTime();
            enabled = selfDecisionEnabled.getAsBoolean();
            if (overhead != null) {
                overhead.addConfigNanos(System.nanoTime() - readStarted);
            }
        }
        catch (RuntimeException e) {
            // fail-open：开关读失败绝不阻断推理链（增益件的 best-effort 口径）
            log.warn("[verify-self-decision] 开关读取失败（降级透传）：{}", e.toString());
            return next.apply(input);
        }
        if (enabled) {
            return next.apply(input);
        }
        // 掐表（#453 观测面）：判重回读扫描墙钟（ctx 属性袋＋消息列元数据双源——纯计算面）
        long scanStarted = System.nanoTime();
        boolean noticed = alreadyNoticed(ctx, input.messages());
        if (overhead != null) {
            overhead.addEvalNanos(System.nanoTime() - scanStarted);
        }
        if (noticed) {
            return next.apply(input);
        }
        noteNoticed(ctx);
        log.info("[verify-self-decision] 自决关闭态注入指令 run={}", runIdOf(ctx));
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(noticeMessage());
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    /** 每迭代平台开销观察面（#453）：经属性袋取 facts，缺席（一次性调用面）跳过观测。 */
    private static IterationOverheadFacts overheadOf(RuntimeContext ctx) {
        Object facts = ctx == null ? null : ctx.get(AgentscopeAgentClient.ITERATION_OVERHEAD_CONTEXT_KEY);
        return facts instanceof IterationOverheadFacts overhead ? overhead : null;
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

    /**
     * 防重灌判重（双源并集，#480 ①b 同构）：①属性袋已注入会话集（真实流的主
     * 载体——agentscope 2.0.1 不把 onReasoning 追加消息写回 AgentState，下一迭代
     * 输入列由 state 重建、恒不含注入消息，消息列回读恒空）；②消息列回读（框架
     * 若持久化合成消息则非空——保留原设计语义，兼容未来框架行为）。ctx 缺席
     * （一次性调用面）只回读消息列。
     */
    private static boolean alreadyNoticed(RuntimeContext ctx, List<Msg> messages) {
        if (ctx != null) {
            Object store = ctx.get(NOTICED_SESSIONS_CONTEXT_KEY);
            if (store instanceof Set<?> set && set.contains(sessionKeyOf(ctx))) {
                return true;
            }
        }
        for (Msg msg : messages) {
            if (msg != null && msg.getMetadata() != null
                    && msg.getMetadata().containsKey(NOTICE_METADATA_KEY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 注入记账：写属性袋集（首写建集）。并发口径——同 ctx 上并发首写存在
     * get-put 竞态（后写覆盖先写丢条目），最坏后果＝指令多注一次，不损正确性
     * 主线（ReAct 循环内串行，正常路径无竞态）——#480 noteInjected 同判。
     */
    @SuppressWarnings("unchecked")
    private static void noteNoticed(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        Object existing = ctx.get(NOTICED_SESSIONS_CONTEXT_KEY);
        if (!(existing instanceof Set)) {
            existing = ConcurrentHashMap.newKeySet();
            ctx.put(NOTICED_SESSIONS_CONTEXT_KEY, existing);
        }
        ((Set<String>) existing).add(sessionKeyOf(ctx));
    }

    /** 属性袋条目键＝sessionId（缺席记空串——与 #480 条目前缀的空串回退同口径）。 */
    private static String sessionKeyOf(RuntimeContext ctx) {
        return ctx.getSessionId() == null ? "" : String.valueOf(ctx.getSessionId());
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
