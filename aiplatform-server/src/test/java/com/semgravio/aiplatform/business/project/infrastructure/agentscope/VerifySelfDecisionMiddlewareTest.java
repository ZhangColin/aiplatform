package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Flux;

/**
 * verify 自决开关中间件缝（#442，#441-1）：挂在执行体 ReAct 循环的 onReasoning
 * （每迭代模型调用前）——运营面开关关（自决关闭）时注入一条合成指令消息（每会话
 * 一次、消息列回读防重灌），告知模型生成过程中不要自发委派 verify；开关开／读失败
 * → 原样透传（fail-open，绝不阻断推理链）。
 */
class VerifySelfDecisionMiddlewareTest {

    private VerifySelfDecisionMiddleware middleware(BooleanSupplier toggle) {
        return new VerifySelfDecisionMiddleware(toggle);
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build())
                .build();
    }

    private static List<Msg> plainMessages() {
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做订单页"));
        messages.add(Msg.builder().role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("好的").build()).build());
        return messages;
    }

    /** 捕获 next 收到的 input（透传/注入的行为断言面）。 */
    private static class InputCaptor
            implements Function<ReasoningInput, Flux<io.agentscope.core.event.AgentEvent>> {
        ReasoningInput captured;

        @Override
        public Flux<io.agentscope.core.event.AgentEvent> apply(ReasoningInput input) {
            captured = input;
            return Flux.empty();
        }
    }

    @Test
    void given_self_decision_disabled_when_on_reasoning_then_notice_appended_once() {
        ReasoningInput input = new ReasoningInput(plainMessages(), List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(() -> false).onReasoning(null, null, input, captor);

        assertThat(captor.captured).isNotNull();
        assertThat(captor.captured.messages()).hasSize(input.messages().size() + 1);
        Msg appended = captor.captured.messages().get(captor.captured.messages().size() - 1);
        assertThat(appended.getMetadata())
                .containsEntry("agentscope_synthetic", true)
                .containsKey(VerifySelfDecisionMiddleware.NOTICE_METADATA_KEY);
        assertThat(appended.getTextContent())
                .contains("verify 自决已关闭")
                .contains("8081 探活");
    }

    @Test
    void given_notice_already_in_history_when_on_reasoning_then_not_reinjected() {
        // 本会话已注入过（消息列回读）→ 不重复注入（防振荡）
        List<Msg> messages = new ArrayList<>(plainMessages());
        messages.add(Msg.builder().role(MsgRole.USER).name("verify_self_decision_notice")
                .content(TextBlock.builder().text("verify 自决已关闭").build())
                .metadata(Map.of("agentscope_synthetic", true,
                        VerifySelfDecisionMiddleware.NOTICE_METADATA_KEY, true))
                .build());
        ReasoningInput input = new ReasoningInput(messages, List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(() -> false).onReasoning(null, null, input, captor);

        assertThat(captor.captured.messages()).hasSize(messages.size());
    }

    @Test
    void given_self_decision_enabled_when_on_reasoning_then_passthrough() {
        // 缺省态（开关开＝自决维持现状）：零注入零扰动
        ReasoningInput input = new ReasoningInput(plainMessages(), List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(() -> true).onReasoning(null, null, input, captor);

        assertThat(captor.captured.messages()).hasSize(input.messages().size());
    }

    @Test
    void given_toggle_read_fails_when_on_reasoning_then_passthrough_fail_open() {
        ReasoningInput input = new ReasoningInput(plainMessages(), List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(() -> {
            throw new IllegalStateException("db down");
        }).onReasoning(null, null, input, captor);

        assertThat(captor.captured.messages()).hasSize(input.messages().size());
    }

    @Test
    void given_null_messages_when_on_reasoning_then_passthrough() {
        ReasoningInput input = new ReasoningInput(null, List.of(), null);
        InputCaptor captor = new InputCaptor();

        middleware(() -> false).onReasoning(null, null, input, captor);

        assertThat(captor.captured).isNotNull();
    }
}
