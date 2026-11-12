package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Flux;

/**
 * verify 自决开关中间件缝（#442，#441-1）：挂在执行体 ReAct 循环的 onReasoning
 * （每迭代模型调用前）——运营面开关关（自决关闭）时注入一条合成指令消息（每轮
 * 〔派段/对话窗〕一次：ctx 属性袋防重灌＋消息列回读兼容源——#481 修，
 * #480 ①b 同构），告知模型生成过程中不要自发委派 verify；开关开／读失败 →
 * 原样透传（fail-open，绝不阻断推理链）。
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
        // 一次性调用面（ctx 缺席只回读消息列）：已注入过 → 不重复注入（防振荡）
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

    @Test
    void given_consecutive_iterations_built_from_state_when_on_reasoning_then_injected_once() {
        // #481 生产缺陷回归（#480 ①b 同族）：真框架流里下一迭代的输入消息列由
        // AgentState 重建——中间件追加进 ReasoningInput 的合成指令消息不落 state
        // （agentscope 2.0.1），消息列回读恒空 → 自决关闭态下关闭指令每迭代重注。
        // 修后防重灌主载体＝ctx 属性袋已注入会话集（同 ctx 跨迭代——#480/#453
        // 先例）。本测按真框架口径驱动两个连续迭代（第二轮消息列＝第一轮 state
        // 视图＋一次新交互，不含合成指令），断言同一 run 同一会话全链只注入一次。
        VerifySelfDecisionMiddleware mw = middleware(() -> false);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("coder-1").userId("u1")
                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, "run-9").build();

        // 迭代 1：关闭态 → 注入关闭指令（消息列＋1）
        List<Msg> first = plainMessages();
        InputCaptor firstCaptor = new InputCaptor();
        mw.onReasoning(null, ctx, new ReasoningInput(first, List.of(), null), firstCaptor);
        assertThat(firstCaptor.captured.messages()).hasSize(first.size() + 1);

        // 迭代 2：state 重建口径——消息列含迭代 1 后的真实交互（assistant 回复＋
        // 新任务消息），不含合成指令（不落 state，消息列回读恒空）→ ctx 注入集
        // 拦截，不再注入
        List<Msg> second = new ArrayList<>(first);
        second.add(Msg.builder().role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("订单页已完成").build()).build());
        second.add(user("继续做结算页"));
        InputCaptor secondCaptor = new InputCaptor();
        mw.onReasoning(null, ctx, new ReasoningInput(second, List.of(), null), secondCaptor);

        assertThat(secondCaptor.captured.messages())
                .as("同一 run 同一会话不重复注入（真框架 state 重建口径）")
                .hasSize(second.size());
    }

    @Test
    void given_new_turn_fresh_ctx_same_session_when_on_reasoning_then_injected_again() {
        // 载体作用域＝ctx 生命周期＝一轮命令（AgentscopeAgentClient.prepareTurn 逐轮
        // 构建：一 run 多派段则每段新 ctx、段内跨迭代同实例）：新派段（#434 每派段
        // 新对话、恒定 sessionId 复用状态槽位）首迭代再注入一次——指令在新对话窗
        // 须在场；防重灌不跨段全局记账（不饿死后续段的指令可见性）
        VerifySelfDecisionMiddleware mw = middleware(() -> false);
        RuntimeContext turn1 = RuntimeContext.builder().sessionId("coder-1").userId("u1")
                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, "run-9").build();
        InputCaptor first = new InputCaptor();
        mw.onReasoning(null, turn1, new ReasoningInput(plainMessages(), List.of(), null), first);
        assertThat(first.captured.messages()).hasSize(plainMessages().size() + 1);

        // 新派段：新 ctx（同 run 同 sessionId）、消息列由 state 重建（新对话窗，
        // 不含任何合成指令）→ 再注入一次
        RuntimeContext turn2 = RuntimeContext.builder().sessionId("coder-1").userId("u1")
                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, "run-9").build();
        InputCaptor second = new InputCaptor();
        mw.onReasoning(null, turn2, new ReasoningInput(plainMessages(), List.of(), null), second);
        assertThat(second.captured.messages())
                .as("新派段首迭代再注入（作用域＝ctx 生命周期，非跨段全局）")
                .hasSize(plainMessages().size() + 1);
    }

    @Test
    void given_notice_in_history_and_ctx_present_when_on_reasoning_then_not_reinjected() {
        // 兼容源独立生效：ctx 在场但袋未记（框架若未来把合成消息写回 state——重放
        // 流消息列含注入标记）→ 消息列回读命中即不重注（双源并集，任一命中即拦）
        VerifySelfDecisionMiddleware mw = middleware(() -> false);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("coder-1").userId("u1")
                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, "run-9").build();
        List<Msg> messages = new ArrayList<>(plainMessages());
        messages.add(Msg.builder().role(MsgRole.USER).name("verify_self_decision_notice")
                .content(TextBlock.builder().text("verify 自决已关闭").build())
                .metadata(Map.of("agentscope_synthetic", true,
                        VerifySelfDecisionMiddleware.NOTICE_METADATA_KEY, true))
                .build());
        InputCaptor captor = new InputCaptor();

        mw.onReasoning(null, ctx, new ReasoningInput(messages, List.of(), null), captor);

        assertThat(captor.captured.messages()).hasSize(messages.size());
    }
}
