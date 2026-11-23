package com.semgravio.aiplatform.base.agentscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 生成内核循环五条语义（#596，ADR-0054／#590 最终修订）：挂在内核装配上的
 * 中间件（结构性只挂内核形态，{@link AgentscopeHarnessAgentFactory} 内核分支），
 * 把实验脚本 {@code prototype/v0-firstrun2 v0-harness4.py} 的循环语义接到
 * 现有 agentscope 发动机上——会话状态存储、(userId,sessionId) 串行、内核重试
 * 关停、上下文压缩全部沿用现有装配（一行不改），本件只改造循环行为面。
 *
 * <p>五条语义与兑现位（中间件钩子在 ReActAgent 循环内的位置，2.0.5 源码核实）：</p>
 * <ol>
 *   <li><b>一轮不调工具即结束、不做做完后催促</b>（首建、迭代同一规则）：框架原生
 *       {@code isFinished}（无工具调用即收）兑现，本件不动；自然结束的收尾即模型
 *       原文——不挂任何催促/收尾句。</li>
 *   <li><b>输出截断＝轮次结束、收尾如实告知原因</b>：{@link #onModelCall} 探测
 *       {@code finishReason=length/max_tokens}（与框架
 *       {@code ReasoningContext.isTruncated} 同口径）——截断响应携带的工具调用
 *       照 DSH 丢弃不执行：随模型流末尾补发收尾文本增量（进
 *       {@code transformedText}，框架为 onModelCall 文本变换预留的通道），再发
 *       {@link RequestStopEvent} 停轮（跳过 acting 与框架的空回复回流）。</li>
 *   <li><b>空回复＝轮次结束</b>：2.0.5 框架对无文本无工具调用的响应会注入
 *       EMPTY_RESPONSE_REMINDER 回流重问（{@code isFinished} 只认可见文本）——
 *       内核语义照 DSH 改判轮次结束：{@link #onModelCall} 探测空回复（无可见
 *       文本且无工具调用，thinking-only 同判——DSH 无「整答案在思考通道」特例），
 *       补发空回复收尾句＋停轮。用户说「继续」以新调用进同一会话续跑。</li>
 *   <li><b>同一工具同样参数重复调用第 3／5／8 次只提醒、绝不拦截</b>：
 *       {@link #onActing} 按执行顺序重放连续重复计数（名字＋规范化参数
 *       [{@code Jackson 键排序}] 与上一调用同则续计、异则重置——跨轮不间断），
 *       本轮达到的最高阈值存为待发提醒；{@link #onReasoning} 在下一轮入口
 *       消费——提醒作为用户消息落在工具结果之后、模型再次调用之前（照 DSH
 *       「提醒出现在结果之后」），同时持久进会话上下文（会话续跑可见）；
 *       提醒只发一条（取最高次数）。调用本身永不拦截。</li>
 *   <li><b>到护栏上限不掐断：关工具、让模型如实汇报</b>：{@link #onReasoning}
 *       数轮次，越过 {@link #GUARD_ROUNDS} 的当轮改造为到顶轮——
 *       {@link KernelLoopPrompts#maxStepsPrompt()}（OpenCode 原文）作为用户消息
 *       注入上下文与该轮入参，该轮工具面清空（模型物理上无工具可调＝关工具），
 *       模型只能以文本如实汇报；该轮无工具调用→循环自然收。工厂把内核形态
 *       SDK maxIters 抬到护栏之上，保证 SDK summarizing（自带提示句）不抢先。
 *       护栏数值（300 轮／45 分钟／60M prompt）为运营兜底、非工作语义（ADR-0054），
 *       配置化接线属 #597。</li>
 * </ol>
 *
 * <p>会话态：重复计数/待发提醒按 {@link RuntimeContext} 实例（＝ReActAgent
 * 每调用的 call scope）分槽，同 (userId,sessionId) 串行、异槽并发不互染；
 * {@link #onAgent} 收尾清槽（上下文缺失的退化调用留空槽，幂等无害）。</p>
 */
public final class KernelLoopSemantics implements MiddlewareBase {

    /** 运营护栏轮次（ADR-0054：300 轮／45 分钟／60M prompt 为兜底数值，不重新标定）。 */
    static final int GUARD_ROUNDS = 300;

    /** 截断判据（与框架 ReasoningContext.isTruncated 同口径：OpenAI 系 length、Anthropic/Gemini 系 max_tokens）。 */
    private static final String FINISH_LENGTH = "length";
    private static final String FINISH_MAX_TOKENS = "max_tokens";

    /** 参数规范化序列化：键递归排序（照原型 json.dumps(sort_keys)）——同参判定的正本。 */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /**
     * 循环语义最外圈（大于平台观测中间件缺省 1）——提醒/到顶注入发生在其余
     * 中间件变换之后的最终入参上；事件补发也不受内圈截断影响。
     */
    @Override
    public int order() {
        return 10;
    }

    /**
     * 每调用循环态（分槽键＝RuntimeContext 实例身份；无 ctx 的退化调用共用哨兵槽——
     * 生产路径不出现：ReActAgent 每调用上下文恒非空，哨兵只兜直调中间件的非常规
     * 用法，不计数不清理、幂等无害）。
     */
    private static final Object UNKNOWN_SLOT = new Object();
    private final Map<Object, SlotState> slots = new ConcurrentHashMap<>();

    private static final class SlotState {

        /** 连续重复链：上一调用的工具名／规范化参数／连续次数（异参即重置）。 */
        private String chainName;
        private String chainSig;
        private int chainCount;

        /** 本轮达到最高阈值留下的待发提醒（下轮入口消费；null＝无）。 */
        private String pendingReminder;

        /** 已进行的推理轮数（到顶判据）。 */
        private int reasoningRounds;
    }

    private static SlotState fresh() {
        return new SlotState();
    }

    private SlotState slotOf(RuntimeContext ctx) {
        return slots.computeIfAbsent(ctx == null ? UNKNOWN_SLOT : ctx, ignored -> fresh());
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx == null) {
            // 退化调用（无 RuntimeContext）：哨兵槽不清理（见 slots 注释）
            return next.apply(input);
        }
        Object key = ctx;
        return next.apply(input).doFinally(signal -> slots.remove(key));
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        SlotState slot = slotOf(ctx);
        slot.reasoningRounds++;
        boolean capRound = slot.reasoningRounds > GUARD_ROUNDS;

        List<Msg> injected = new ArrayList<>();
        if (slot.pendingReminder != null) {
            // 上轮重复提醒：先落（照原型顺序——提醒在结果后、到顶提示前）
            injected.add(new UserMessage(slot.pendingReminder));
            slot.pendingReminder = null;
        }
        if (capRound) {
            injected.add(new UserMessage(KernelLoopPrompts.maxStepsPrompt()));
        }
        if (injected.isEmpty()) {
            return next.apply(input);
        }
        // 持久进会话上下文（会话续跑/「继续」接着跑时模型仍见事实），并并入本轮入参
        AgentState state = ctx == null ? null : ctx.getAgentState();
        if (state != null) {
            state.contextMutable().addAll(injected);
        }
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.addAll(injected);
        // 到顶轮工具面清空＝关工具；提醒轮工具面照旧
        List<ToolSchema> tools = capRound ? List.of() : input.tools();
        return next.apply(new ReasoningInput(messages, tools, input.options()));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        SlotState slot = slotOf(ctx);
        int reminded = 0;
        String remindedName = null;
        String remindedSig = null;
        for (ToolUseBlock call : input.toolCalls()) {
            String name = call.getName();
            String sig = canonicalArgs(call.getInput());
            if (name.equals(slot.chainName) && sig.equals(slot.chainSig)) {
                slot.chainCount++;
            }
            else {
                slot.chainName = name;
                slot.chainSig = sig;
                slot.chainCount = 1;
            }
            // 阈值单调递增——后达者覆盖即「本轮取最高次数」
            if (KernelLoopPrompts.REPEAT_THRESHOLDS.contains(slot.chainCount)) {
                reminded = slot.chainCount;
                remindedName = name;
                remindedSig = sig;
            }
        }
        if (reminded > 0) {
            slot.pendingReminder = KernelLoopPrompts.repeatReminder(reminded, remindedName, remindedSig);
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicBoolean visibleText = new AtomicBoolean();
        AtomicBoolean toolCalls = new AtomicBoolean();
        Model probingModel = probing(input.model(), finishReason, visibleText, toolCalls);
        ModelCallInput probingInput = new ModelCallInput(
                input.messages(), input.tools(), input.options(), probingModel);
        return next.apply(probingInput)
                .concatWith(Flux.defer(() -> closingEvents(finishReason.get(),
                        visibleText.get(), toolCalls.get())));
    }

    /**
     * 模型探针：委托原模型（供给链包装件原样——计量/重试/failover 不受影响），
     * 逐响应记录 finishReason 与「可见文本/工具调用」有无。
     */
    private static Model probing(Model delegate, AtomicReference<String> finishReason,
            AtomicBoolean visibleText, AtomicBoolean toolCalls) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages,
                    List<ToolSchema> tools, GenerateOptions options) {
                return delegate.stream(messages, tools, options)
                        .doOnNext(chunk -> probe(chunk, finishReason, visibleText, toolCalls));
            }

            @Override
            public String getModelName() {
                return delegate.getModelName();
            }

            @Override
            public boolean supportsNativeStructuredOutput() {
                return delegate.supportsNativeStructuredOutput();
            }

            @Override
            public boolean supportsNativeStructuredOutputWithTools() {
                return delegate.supportsNativeStructuredOutputWithTools();
            }

            @Override
            public boolean supportsToolChoiceSpecific() {
                return delegate.supportsToolChoiceSpecific();
            }

            @Override
            public int getContextWindowSize() {
                return delegate.getContextWindowSize();
            }
        };
    }

    private static void probe(ChatResponse chunk, AtomicReference<String> finishReason,
            AtomicBoolean visibleText, AtomicBoolean toolCalls) {
        if (chunk.getFinishReason() != null) {
            finishReason.set(chunk.getFinishReason());
        }
        for (ContentBlock block : chunk.getContent()) {
            if (block instanceof TextBlock text) {
                if (text.getText() != null && !text.getText().isBlank()) {
                    visibleText.set(true);
                }
            }
            else if (block instanceof ToolUseBlock) {
                toolCalls.set(true);
            }
        }
    }

    /**
     * 截断/空回复收尾：模型流末尾补发收尾文本增量（start→delta→end 三件套，块 id
     * {@code "text"} 同框架惯例）＋停轮事件。文本增量经框架 transformedText 通道
     * 进最终消息——截断即「部分文本＋原因句」、空回复即「原因句」。
     */
    private static Flux<AgentEvent> closingEvents(String finishReason, boolean visibleText,
            boolean toolCalls) {
        String closing = null;
        String stopReason = null;
        if (isTruncated(finishReason)) {
            closing = KernelLoopPrompts.truncationClosing();
            stopReason = "kernel-output-truncated";
        }
        else if (!visibleText && !toolCalls) {
            closing = KernelLoopPrompts.emptyReplyClosing();
            stopReason = "kernel-empty-reply";
        }
        if (closing == null) {
            return Flux.empty();
        }
        String replyId = UUID.randomUUID().toString().replace("-", "");
        return Flux.just(
                new TextBlockStartEvent(replyId, "text"),
                new TextBlockDeltaEvent(replyId, "text", closing),
                new TextBlockEndEvent(replyId, "text"),
                new RequestStopEvent(stopReason, GenerateReason.MIDDLEWARE_STOP_REQUESTED));
    }

    private static boolean isTruncated(String finishReason) {
        return finishReason != null
                && (FINISH_LENGTH.equalsIgnoreCase(finishReason)
                        || FINISH_MAX_TOKENS.equalsIgnoreCase(finishReason));
    }

    /** 同参判定的规范化序列：Jackson 键递归排序；序列化失败退原始 toString（判定一致性不受损）。 */
    private static String canonicalArgs(Map<String, Object> input) {
        Map<String, Object> safe = input == null ? Map.of() : input;
        try {
            return CANONICAL_MAPPER.writeValueAsString(safe);
        }
        catch (JsonProcessingException e) {
            return String.valueOf(safe);
        }
    }
}
