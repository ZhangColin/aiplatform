package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 生成内核运营护栏三腿到顶语义（#597，ADR-0054：300 轮／45 分钟／60M prompt 为
 * 运营兜底、非工作语义、不重新标定）：任一到顶＝当轮关工具＋如实汇报提示注入
 * （循环第 2 条）——轮次腿照 OpenCode MAX_STEPS_PROMPT 原文（#596 既有语义），
 * 时长／prompt 量两腿为平台自写 guardCapPrompt（无成熟方案出处，同
 * {@link KernelLoopPrompts#truncationClosing()} 自写句先例——轮次文案照「step」
 * 如实报，不挪用）。
 *
 * <p>seam＝中间件对外契约（{@link KernelLoopSemantics} 实现框架 MiddlewareBase
 * 的钩子面）：直接驱动 onReasoning／onModelCall 观察「关工具＋提示注入＋计数累计」
 * 外部行为；时长腿用注入时钟（LongSupplier seam）确定性推进，不赌真实墙钟。
 * 装配层默认护栏（300 轮全链）由 KernelAgentLoopTest 既有用例钉守。</p>
 */
class KernelAgentGuardrailsTest {

    /** 本轮入参带的工具面（非空才能观察「关工具＝清空」）。 */
    private static final List<ToolSchema> ROUND_TOOLS = List.of(ToolSchema.builder()
            .name("write_file")
            .description("写文件")
            .build());

    /** 捕获 next 收到的入参（关工具＝tools 清空、提示＝末条用户消息注入的观察面）。 */
    private static final class Captured {

        final CopyOnWriteArrayList<ReasoningInput> reasonings = new CopyOnWriteArrayList<>();

        Function<ReasoningInput, Flux<AgentEvent>> nextReasoning() {
            return input -> {
                reasonings.add(input);
                return Flux.empty();
            };
        }
    }

    /** 回吐单个响应（可带用量）的替身模型：onModelCall 探针累计 inputTokens 的驱动面。 */
    private static Model usageModel(int inputTokens) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                    GenerateOptions options) {
                ChatResponse response = new ChatResponse("resp-guard",
                        List.of(TextBlock.builder().text("继续。").build()),
                        new ChatUsage(inputTokens, 1, 0.0), Map.of(), "stop");
                return Flux.just(response);
            }

            @Override
            public String getModelName() {
                return "usage-stub";
            }
        };
    }

    private static ModelCallInput modelCall(Model model) {
        return new ModelCallInput(List.of(new UserMessage("跑")), List.of(), null, model);
    }

    private static void runModelCall(KernelLoopSemantics semantics, Model model) {
        // next 须真正订阅探针模型——探针（用量累计/截断观测）在订阅链上生效；
        // 末块收口后无事件可发，返回空流
        semantics.onModelCall(null, null, modelCall(model), input -> {
            input.model().stream(input.messages(), input.tools(), input.options()).blockLast();
            return Flux.empty();
        }).blockLast();
    }

    private static ReasoningInput reasoning(KernelLoopSemantics semantics, Captured captured) {
        semantics.onReasoning(null, null,
                new ReasoningInput(List.of(), ROUND_TOOLS, null),
                captured.nextReasoning()).blockLast();
        return captured.reasonings.get(captured.reasonings.size() - 1);
    }

    private static boolean lastMessageContains(ReasoningInput input, String text) {
        List<Msg> messages = input.messages();
        if (messages.isEmpty()) {
            return false;
        }
        Msg last = messages.get(messages.size() - 1);
        return last.getContentBlocks(TextBlock.class).stream()
                .anyMatch(block -> block.getText() != null && block.getText().contains(text));
    }

    // ---------- 轮次腿：到顶轮关工具＋MAX_STEPS_PROMPT（OpenCode 原文） ----------

    @Test
    void given_rounds_guard_when_exceeded_then_tools_closed_and_max_steps_prompt() {
        KernelLoopSemantics semantics = new KernelLoopSemantics(2, null, 0L);
        Captured captured = new Captured();

        ReasoningInput first = reasoning(semantics, captured);   // 第 1 轮
        reasoning(semantics, captured);                          // 第 2 轮（恰在界内）
        ReasoningInput third = reasoning(semantics, captured);   // 第 3 轮＝越界

        assertThat(captured.reasonings).hasSize(3);
        assertThat(first.tools()).containsExactlyElementsOf(ROUND_TOOLS);
        assertThat(third.tools()).isEmpty();
        assertThat(lastMessageContains(third, "CRITICAL - MAXIMUM STEPS REACHED")).isTrue();
    }

    @Test
    void given_rounds_guard_when_within_limit_then_tools_kept_and_no_prompt() {
        KernelLoopSemantics semantics = new KernelLoopSemantics(2, null, 0L);

        ReasoningInput first = reasoning(semantics, new Captured());

        assertThat(first.tools()).containsExactlyElementsOf(ROUND_TOOLS);
        assertThat(first.messages()).isEmpty();
    }

    // ---------- 时长腿：越界轮关工具＋guardCapPrompt（平台自写句） ----------

    @Test
    void given_duration_guard_when_elapsed_exceeded_then_tools_closed_and_guard_cap_prompt() {
        AtomicLong clock = new AtomicLong();
        KernelLoopSemantics semantics = new KernelLoopSemantics(
                Integer.MAX_VALUE, Duration.ofMinutes(45), 0L, clock::get);
        Captured captured = new Captured();

        reasoning(semantics, captured);   // 第 1 轮：起手计时
        clock.addAndGet(Duration.ofMinutes(46).toNanos());
        ReasoningInput capped = reasoning(semantics, captured);   // 越界轮

        assertThat(capped.tools()).isEmpty();
        assertThat(lastMessageContains(capped, "CRITICAL - PLATFORM GUARD LIMIT REACHED"))
                .isTrue();
    }

    @Test
    void given_duration_guard_when_within_limit_then_round_uncapped() {
        AtomicLong clock = new AtomicLong();
        KernelLoopSemantics semantics = new KernelLoopSemantics(
                Integer.MAX_VALUE, Duration.ofMinutes(45), 0L, clock::get);
        Captured captured = new Captured();

        reasoning(semantics, captured);
        clock.addAndGet(Duration.ofMinutes(30).toNanos());
        ReasoningInput within = reasoning(semantics, captured);

        assertThat(within.tools()).containsExactlyElementsOf(ROUND_TOOLS);
        assertThat(within.messages()).isEmpty();
    }

    // ---------- prompt 量腿：累计越界轮关工具＋guardCapPrompt ----------

    @Test
    void given_prompt_tokens_guard_when_accumulated_exceeded_then_tools_closed_and_prompt() {
        KernelLoopSemantics semantics = new KernelLoopSemantics(
                Integer.MAX_VALUE, null, 100L);
        Captured captured = new Captured();

        runModelCall(semantics, usageModel(60));
        ReasoningInput within = reasoning(semantics, captured);   // 累计 60 ≤ 100：不顶
        runModelCall(semantics, usageModel(60));
        ReasoningInput capped = reasoning(semantics, captured);   // 累计 120 > 100

        assertThat(within.tools()).containsExactlyElementsOf(ROUND_TOOLS);
        assertThat(capped.tools()).isEmpty();
        assertThat(lastMessageContains(capped, "CRITICAL - PLATFORM GUARD LIMIT REACHED"))
                .isTrue();
    }

    @Test
    void given_prompt_tokens_guard_when_usage_absent_then_leg_not_triggered() {
        // 无用量回执的响应（inputTokens=0）不计入——腿不因缺失数据误触发
        KernelLoopSemantics semantics = new KernelLoopSemantics(
                Integer.MAX_VALUE, null, 1L);
        Captured captured = new Captured();

        runModelCall(semantics, usageModel(0));
        ReasoningInput after = reasoning(semantics, captured);

        assertThat(after.tools()).containsExactlyElementsOf(ROUND_TOOLS);
        assertThat(after.messages()).isEmpty();
    }

    // ---------- 腿间独立：各腿在界内时该轮不受任何腿影响 ----------

    @Test
    void given_all_guards_set_when_no_leg_exceeded_then_round_runs_uncapped() {
        AtomicLong clock = new AtomicLong();
        KernelLoopSemantics semantics = new KernelLoopSemantics(
                5, Duration.ofMinutes(45), 100L, clock::get);
        Captured captured = new Captured();

        runModelCall(semantics, usageModel(10));
        clock.addAndGet(Duration.ofMinutes(1).toNanos());
        ReasoningInput round = reasoning(semantics, captured);

        assertThat(round.tools()).containsExactlyElementsOf(ROUND_TOOLS);
        assertThat(round.messages()).isEmpty();
    }
}
