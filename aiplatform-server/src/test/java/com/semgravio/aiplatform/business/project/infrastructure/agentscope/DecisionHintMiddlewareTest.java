package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.IterationOverheadFacts;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintConfig;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintKind;
import com.semgravio.aiplatform.business.project.domain.decisionhint.HintPolicy;

import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

/**
 * 决策时注入中间件缝（#437）：挂在执行体 ReAct 循环的 onReasoning（每迭代
 * 模型调用前）——判定命中则追加一条合成微指令消息（模型面注入，2026-11-12 起
 * 不再推用户面 part-hint——事件汇恒空是退役钉）；未命中/总开关关/策略读失败 →
 * 原样透传（fail-open，绝不阻断推理链）。
 */
class DecisionHintMiddlewareTest {

    private final List<AgentEvent> notices = new CopyOnWriteArrayList<>();

    /** 平台事件汇替身：断言用户面恒无 part-hint（事件退役钉——微指令不进用户面）。 */
    private final java.util.function.Consumer<AgentEvent> noticeSink = notices::add;

    private DecisionHintMiddleware middleware(DecisionHintConfig config) {
        return new DecisionHintMiddleware("executor", agentKey -> config);
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build())
                .build();
    }

    /** 三连失败轨迹：失败止损必命中（缺省阈值 3）。 */
    private static List<Msg> failingMessages() {
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做订单页"));
        for (int i = 0; i < 3; i++) {
            ToolUseBlock call = new ToolUseBlock("c" + i, "execute", Map.of("cmd", "pnpm build"));
            messages.add(Msg.builder().role(MsgRole.ASSISTANT).content(call).build());
            messages.add(Msg.builder().role(MsgRole.TOOL)
                    .content(new ToolResultBlock(call.getId(), call.getName(),
                            TextBlock.builder().text("Error: build failed").build())
                            .withState(ToolResultState.ERROR))
                    .build());
        }
        return messages;
    }

    private io.agentscope.core.agent.RuntimeContext ctxWithSink() {
        io.agentscope.core.agent.RuntimeContext ctx = io.agentscope.core.agent.RuntimeContext
                .builder().sessionId("coder-1-single").userId("u1")
                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, "run-9")
                .put(AgentscopeAgentClient.PLATFORM_EVENT_SINK_CONTEXT_KEY, noticeSink)
                .build();
        return ctx;
    }

    /** 捕获 next 收到的 input（透传/注入的行为断言面）。 */
    private static class InputCaptor implements Function<ReasoningInput, Flux<io.agentscope.core.event.AgentEvent>> {
        ReasoningInput captured;

        @Override
        public Flux<io.agentscope.core.event.AgentEvent> apply(ReasoningInput input) {
            captured = input;
            return Flux.empty();
        }
    }

    @Test
    void given_decision_hit_when_on_reasoning_then_hint_message_appended_and_notice_emitted() {
        ReasoningInput input = new ReasoningInput(failingMessages(), List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(DecisionHintConfig.enabled(HintPolicy.defaults()))
                .onReasoning(null, ctxWithSink(), input, captor);

        assertThat(captor.captured).isNotNull();
        assertThat(captor.captured.messages()).hasSize(input.messages().size() + 1);
        Msg appended = captor.captured.messages().get(captor.captured.messages().size() - 1);
        assertThat(appended.getMetadata()).containsEntry("decision_hint_kind",
                DecisionHintKind.FAILURE_STOP_LOSS.key());
        assertThat(appended.getMetadata()).containsEntry("agentscope_synthetic", true);
        // 渲染契约（2026-11-12 修）：{0} 承接理由整句——成品读成通顺句子，无嵌套复读
        assertThat(appended.getTextContent())
                .startsWith("【平台提示】同类错误连续失败 3 次（error: build failed）。");
        // 用户面退役（2026-11-12 定档）：微指令不进用户事件流——事件汇恒空
        assertThat(notices).isEmpty();
    }

    @Test
    void given_disabled_when_on_reasoning_then_passthrough() {
        ReasoningInput input = new ReasoningInput(failingMessages(), List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(DecisionHintConfig.disabled())
                .onReasoning(null, ctxWithSink(), input, captor);

        assertThat(captor.captured.messages()).hasSize(input.messages().size());
        assertThat(notices).isEmpty();
    }

    @Test
    void given_no_signal_when_on_reasoning_then_passthrough() {
        List<Msg> clean = List.of(user("做订单页"),
                Msg.builder().role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("好的").build()).build());
        ReasoningInput input = new ReasoningInput(clean, List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(DecisionHintConfig.enabled(HintPolicy.defaults()))
                .onReasoning(null, ctxWithSink(), input, captor);

        assertThat(captor.captured.messages()).hasSize(clean.size());
        assertThat(notices).isEmpty();
    }

    @Test
    void given_policy_read_fails_when_on_reasoning_then_passthrough_fail_open() {
        ReasoningInput input = new ReasoningInput(failingMessages(), List.of(), null);
        InputCaptor captor = new InputCaptor();
        DecisionHintMiddleware failing = new DecisionHintMiddleware("executor", agentKey -> {
            throw new IllegalStateException("db down");
        });

        failing.onReasoning(null, ctxWithSink(), input, captor);

        assertThat(captor.captured.messages()).hasSize(input.messages().size());
        assertThat(notices).isEmpty();
    }

    @Test
    void given_null_ctx_when_on_reasoning_then_injected_without_notice() {
        // 无上下文（一次性调用面）：注入照常（模型面），通知跳过——不 NPE
        ReasoningInput input = new ReasoningInput(failingMessages(), List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(DecisionHintConfig.enabled(HintPolicy.defaults()))
                .onReasoning(null, null, input, captor);

        assertThat(captor.captured.messages()).hasSize(input.messages().size() + 1);
        assertThat(notices).isEmpty();
    }

    @Test
    void given_second_hit_same_kind_when_on_reasoning_then_not_reinjected() {
        // 本会话已注入过该键（消息列回读）→ 不重复注入（防振荡）
        List<Msg> messages = new ArrayList<>(failingMessages());
        messages.add(Msg.builder().role(MsgRole.USER).name("decision_hint")
                .content(TextBlock.builder().text("提示").build())
                .metadata(Map.of("agentscope_synthetic", true,
                        "decision_hint_kind", DecisionHintKind.FAILURE_STOP_LOSS.key()))
                .build());
        ReasoningInput input = new ReasoningInput(messages, List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(DecisionHintConfig.enabled(HintPolicy.defaults()))
                .onReasoning(null, ctxWithSink(), input, captor);

        assertThat(captor.captured.messages()).hasSize(messages.size());
        assertThat(notices).isEmpty();
    }

    @Test
    void given_healthy_trajectory_when_on_reasoning_then_gate_skipped_without_eval() {
        // #453-3 判定门：健康轨迹（无信号可能）→ 跳过重评估，观测面记 gateSkipped
        List<Msg> clean = new ArrayList<>();
        clean.add(user("做订单页"));
        for (int i = 0; i < 6; i++) {
            ToolUseBlock a = new ToolUseBlock("h" + i + "a", "write_file", Map.of("p", i + "a"));
            ToolUseBlock b = new ToolUseBlock("h" + i + "b", "write_file", Map.of("p", i + "b"));
            clean.add(Msg.builder().role(MsgRole.ASSISTANT).content(List.of(a, b)).build());
            clean.add(Msg.builder().role(MsgRole.TOOL)
                    .content(List.of(
                            new ToolResultBlock(a.getId(), a.getName(),
                                    TextBlock.builder().text("ok").build())
                                    .withState(ToolResultState.SUCCESS),
                            new ToolResultBlock(b.getId(), b.getName(),
                                    TextBlock.builder().text("ok").build())
                                    .withState(ToolResultState.SUCCESS)))
                    .build());
        }
        ReasoningInput input = new ReasoningInput(clean, List.of(), null);
        InputCaptor captor = new InputCaptor();
        IterationOverheadFacts facts = new IterationOverheadFacts();
        io.agentscope.core.agent.RuntimeContext ctx = io.agentscope.core.agent.RuntimeContext
                .builder().sessionId("coder-1").userId("u1")
                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, "run-9")
                .put(AgentscopeAgentClient.PLATFORM_EVENT_SINK_CONTEXT_KEY, noticeSink)
                .put(AgentscopeAgentClient.ITERATION_OVERHEAD_CONTEXT_KEY, facts)
                .build();

        middleware(DecisionHintConfig.enabled(HintPolicy.defaults()))
                .onReasoning(null, ctx, input, captor);

        // 行为面：透传（无注入无通知）；观测面：门跳过一回、全量评估零回
        assertThat(captor.captured.messages()).hasSize(clean.size());
        assertThat(notices).isEmpty();
        assertThat(facts.snapshot(0))
                .containsEntry(IterationOverheadFacts.GATE_SKIPPED, 1L)
                .containsEntry(IterationOverheadFacts.GATE_EVALUATED, 0L);
    }

    @Test
    void given_signal_trajectory_when_on_reasoning_then_gate_evaluated_and_injected() {
        // #453-3 判定门放行面：失败条带轨迹走全量评估，注入行为与门无关（同既有断言）
        ReasoningInput input = new ReasoningInput(failingMessages(), List.of(), null);
        InputCaptor captor = new InputCaptor();
        IterationOverheadFacts facts = new IterationOverheadFacts();
        io.agentscope.core.agent.RuntimeContext ctx = io.agentscope.core.agent.RuntimeContext
                .builder().sessionId("coder-1").userId("u1")
                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, "run-9")
                .put(AgentscopeAgentClient.PLATFORM_EVENT_SINK_CONTEXT_KEY, noticeSink)
                .put(AgentscopeAgentClient.ITERATION_OVERHEAD_CONTEXT_KEY, facts)
                .build();

        middleware(DecisionHintConfig.enabled(HintPolicy.defaults()))
                .onReasoning(null, ctx, input, captor);

        assertThat(captor.captured.messages()).hasSize(input.messages().size() + 1);
        assertThat(notices).isEmpty();
        assertThat(facts.snapshot(0))
                .containsEntry(IterationOverheadFacts.GATE_SKIPPED, 0L)
                .containsEntry(IterationOverheadFacts.GATE_EVALUATED, 1L);
    }

    @Test
    void given_consecutive_iterations_built_from_state_when_on_reasoning_then_injected_once() {
        // 生产实案回归（2026-11-12 用户报：【平台提示】在会话里不断复读）：真框架流
        // 里下一迭代的输入消息列由 AgentState.contextMutable 重建——中间件追加进
        // ReasoningInput 的合成提示消息<b>不落 state</b>（框架 TaskReminder 先例同判：
        // 其重渲判据在 TaskContextState 而非消息列），故「消息列回读 injectedHintKeys」
        // 在真实流里恒空、防重灌失效，同一提示每迭代重注。修后防重灌主载体＝ctx
        // 属性袋注入集（同 ctx 跨迭代——#453 facts 先例）。本测按真框架口径驱动两
        // 个连续迭代（第二轮消息列＝第一轮 state 视图＋一次新失败迭代，不含合成
        // 提示），断言同一键全链只注入一次。
        List<Msg> base = new ArrayList<>(failingMessages());
        DecisionHintMiddleware mw = middleware(
                DecisionHintConfig.enabled(HintPolicy.defaults()));
        io.agentscope.core.agent.RuntimeContext ctx = ctxWithSink();

        // 迭代 1：三连失败 → 注入止损提示（消息列＋1）
        InputCaptor first = new InputCaptor();
        mw.onReasoning(null, ctx, new ReasoningInput(base, List.of(), null), first);
        assertThat(first.captured.messages()).hasSize(base.size() + 1);

        // 迭代 2：框架从 state 重建消息列（assistant 回复＋工具结果已入列；合成提示
        // 不在列——消息列回读恒空）＋又一轮同签名失败（双调用迭代——不引入
        // parallel_tools 新键，专测 failure_stop_loss 防重灌）→ ctx 注入集拦截，不再注入
        List<Msg> second = new ArrayList<>(base);
        ToolUseBlock callA = new ToolUseBlock("c3", "execute", Map.of("cmd", "pnpm build"));
        ToolUseBlock callB = new ToolUseBlock("c4", "execute", Map.of("cmd", "pnpm build"));
        second.add(Msg.builder().role(MsgRole.ASSISTANT).content(List.of(callA, callB)).build());
        second.add(Msg.builder().role(MsgRole.TOOL)
                .content(List.of(
                        new ToolResultBlock(callA.getId(), callA.getName(),
                                TextBlock.builder().text("Error: build failed").build())
                                .withState(ToolResultState.ERROR),
                        new ToolResultBlock(callB.getId(), callB.getName(),
                                TextBlock.builder().text("Error: build failed").build())
                                .withState(ToolResultState.ERROR)))
                .build());
        InputCaptor secondCaptor = new InputCaptor();
        mw.onReasoning(null, ctx, new ReasoningInput(second, List.of(), null), secondCaptor);

        assertThat(secondCaptor.captured.messages())
                .as("同一会话内同一键不重复注入（真框架 state 口径）")
                .hasSize(second.size());
        // 用户面退役恒成立（双保险断言）
        assertThat(notices).isEmpty();
    }
}
