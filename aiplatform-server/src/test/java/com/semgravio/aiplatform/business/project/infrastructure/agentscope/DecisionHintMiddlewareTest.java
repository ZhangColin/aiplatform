package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
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
 * 模型调用前）——判定命中则追加一条合成微指令消息（模型面注入）并向平台事件
 * 汇推 part-hint（用户面可见）；未命中/总开关关/策略读失败 → 原样透传（fail-open，
 * 绝不阻断推理链）。
 */
class DecisionHintMiddlewareTest {

    private final List<AgentEvent> notices = new CopyOnWriteArrayList<>();

    /** 平台事件汇替身：断言 part-hint 通知（用户可见面）。 */
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
        assertThat(appended.getTextContent())
                .contains(DecisionHintKind.FAILURE_STOP_LOSS.instructionTemplate()
                        .substring(0, 10));
        // 用户面可见：part-hint 通知经平台事件汇推出（携带 runId 与正文）
        assertThat(notices).hasSize(1);
        AgentEvent notice = notices.get(0);
        assertThat(notice.type()).isEqualTo(AgentEventTypes.PART_HINT);
        assertThat(notice.payload())
                .containsEntry(AgentEventTypes.RUN_FIELD, "run-9")
                .containsEntry(AgentEventTypes.PART_HINT_KIND_FIELD,
                        DecisionHintKind.FAILURE_STOP_LOSS.key())
                .containsKey(AgentEventTypes.PART_HINT_TEXT_FIELD);
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
}
