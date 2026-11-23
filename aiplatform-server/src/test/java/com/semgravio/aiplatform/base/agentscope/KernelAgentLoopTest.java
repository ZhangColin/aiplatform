package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 生成内核循环五条语义（#596，ADR-0054／#590 最终修订）：装配层 seam（#593 Testing
 * Decisions 唯一新 seam）替身模型注入响应序列，只测外部可观察行为——模型调用序列、
 * 循环结束时机、提醒出现时机、收尾内容。五条：①一轮不调工具即结束（不做做完后
 * 催促）；②截断响应→轮次结束、收尾文本含原因；③空回复→轮次结束；④同一工具同样
 * 参数重复调用第 3／5／8 次只提醒、绝不拦截；⑤到护栏上限→工具被关、如实汇报提示
 * 出现（MAX_STEPS_PROMPT）。
 *
 * <p>工具执行不触 docker：重复/自然结束用例的工具参数取非法路径（{@code /../..}），
 * {@link KernelTools} 在校验期即返回「路径非法」错误（DockerExecFilesystem 同口径），
 * fast lane 无环境依赖；fast lane 纪律见 docs/agents/live-test-lane.md。</p>
 */
class KernelAgentLoopTest {

    /** 重复用例的非法路径参数：write_file 校验期拒绝（不触 docker），结果确定性。 */
    private static final Map<String, Object> ILLEGAL_WRITE = Map.of(
            "path", "/../loop.txt", "content", "x");
    private static final Map<String, Object> OTHER_ILLEGAL_WRITE = Map.of(
            "path", "/../other.txt", "content", "y");

    /**
     * 脚本替身模型（装配层 seam）：按脚本回吐响应（主模型脚本耗尽即报错——把「调用
     * 序列恰为脚本长度」钉成断言：循环早退/多调都会以脚本耗尽失败），记录模型可见的
     * 消息序列与工具 schema 序列（提醒时机/到顶关工具的外部观察面）。
     *
     * <p>压缩档（{@code :compaction-stub}）与主模型共享注册前缀，装配时各解析一
     * 实例——长用例（护栏 300 轮，会话 600+ 消息）触发既有装配的 50 消息压缩
     * （CompactionConfig triggerMessages 缺省 50，底座行为非本件改动）：压缩档给
     * 永不耗尽的摘要脚本（主模型脚本不被压缩调用偷吃），本例断言只认主模型实例。</p>
     */
    private static final class ScriptedKernelModel implements Model {

        private static final List<ScriptedKernelModel> PROBES = new CopyOnWriteArrayList<>();
        private static volatile Queue<ChatResponse> nextScript;

        private final String modelId;
        private final boolean infinite;
        private final List<List<Msg>> seenMessages = new CopyOnWriteArrayList<>();
        private final List<List<ToolSchema>> seenToolSchemas = new CopyOnWriteArrayList<>();
        private final Queue<ChatResponse> script;

        ScriptedKernelModel(String modelId, Queue<ChatResponse> script, boolean infinite) {
            this.modelId = modelId;
            this.script = script;
            this.infinite = infinite;
            PROBES.add(this);
        }

        /** 取本例主模型替身（按模型 id 前缀＋「记录到调用」认领，同装配测试先例）。 */
        static ScriptedKernelModel called() {
            List<ScriptedKernelModel> called = PROBES.stream()
                    .filter(candidate -> candidate.modelId.endsWith(":loop")
                            && !candidate.seenMessages.isEmpty())
                    .toList();
            assertThat(called).hasSize(1);
            return called.get(0);
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                GenerateOptions options) {
            seenMessages.add(List.copyOf(messages));
            seenToolSchemas.add(tools == null ? List.of() : List.copyOf(tools));
            ChatResponse next = script.poll();
            if (next == null && infinite) {
                // 压缩档替身：摘要调用永不耗尽（用例断言不数压缩调用次数）
                next = new ChatResponse("resp-compact",
                        List.of(TextBlock.builder().text("压缩摘要。").build()),
                        null, Map.of(), "stop");
            }
            return next == null
                    ? Flux.error(new IllegalStateException(
                            "scripted-kernel 脚本耗尽（第 " + (seenMessages.size()) + " 次调用无脚本）"))
                    : Flux.just(next);
        }

        @Override
        public String getModelName() {
            return "scripted-kernel";
        }
    }

    /** 不触网不依赖环境 key 的模型工厂注册（probe557／装配测试先例）：脚本逐例注入。 */
    @BeforeAll
    static void registerScriptedKernelModelFactory() {
        ModelRegistry.registerFactory("scripted-kernel:.+", (modelId, context) -> {
            boolean compaction = modelId.endsWith(":compaction-stub");
            // 压缩档持独立空队列（永不耗尽走兜底摘要）——不偷吃主模型脚本
            return new ScriptedKernelModel(modelId,
                    compaction ? new ConcurrentLinkedQueue<>() : ScriptedKernelModel.nextScript,
                    compaction);
        });
    }

    /** 每例开局：清探针列表＋注入本例脚本（探针按「记录到调用」认领，防跨例残留）。 */
    private static void beginScript(Queue<ChatResponse> script) {
        ScriptedKernelModel.PROBES.clear();
        ScriptedKernelModel.nextScript = script;
    }

    private static Queue<ChatResponse> script(ChatResponse... responses) {
        return new ConcurrentLinkedQueue<>(List.of(responses));
    }

    private static ChatResponse textReply(String text) {
        return new ChatResponse("resp-text", List.of(TextBlock.builder().text(text).build()),
                null, Map.of(), "stop");
    }

    private static ChatResponse toolReply(String callId, Map<String, Object> input) {
        return new ChatResponse("resp-" + callId,
                List.of(new ToolUseBlock(callId, "write_file", input)), null, Map.of(), "stop");
    }

    private static AgentscopeHarnessAgentFactory realFactory() {
        return new AgentscopeHarnessAgentFactory(new InMemoryAgentStateStore(),
                (agentKey, workspace, toolSpec) -> new Toolkit(),
                (agentKey, workspace) -> List.of(), (agentKey, workspace) -> List.of(),
                (agentKey, workspace) -> List.of(), edgeModels(),
                new AgentscopeProperties(), null);
    }

    private static EdgeModelSupplier edgeModels() {
        EdgeModelSupplier.EdgeModel edge = new EdgeModelSupplier.EdgeModel(
                "scripted-kernel:compaction-stub", null);
        return new EdgeModelSupplier() {
            @Override
            public EdgeModel compactionModel() {
                return edge;
            }

            @Override
            public EdgeModel precheckModel() {
                return edge;
            }

            @Override
            public EdgeModel subagentModel() {
                return null;
            }
        };
    }

    private static AgentWorkspace.ProjectKernel kernelWorkspace() {
        return new AgentWorkspace.ProjectKernel("42", "ws-42-loop");
    }

    /** 跑一次生成：注入用户消息，收集模型文本增量（收尾内容观察面），返回拼接文本。 */
    private static String runGeneration(HarnessAgent agent) {
        List<String> texts = new ArrayList<>();
        agent.streamEvents(List.of(new UserMessage("开始")), RuntimeContext.empty())
                .doOnNext(event -> {
                    if (event instanceof TextBlockDeltaEvent delta) {
                        texts.add(delta.getDelta());
                    }
                })
                .blockLast(Duration.ofSeconds(120));
        return String.join("", texts);
    }

    private static String textOf(Msg message) {
        StringBuilder text = new StringBuilder();
        for (TextBlock block : message.getContentBlocks(TextBlock.class)) {
            text.append(block.getText());
        }
        // 工具结果消息：文本块在 ToolResultBlock 输出内（顶层无 TextBlock）
        for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
            for (ContentBlock out : result.getOutput()) {
                if (out instanceof TextBlock block) {
                    text.append(block.getText());
                }
            }
        }
        return text.toString();
    }

    /** 任意一条消息的文本含给定片段（模型可见面断言）。 */
    private static boolean anyMessageContains(List<Msg> messages, String fragment) {
        return messages.stream().anyMatch(m -> textOf(m).contains(fragment));
    }

    /** 片段首次出现在第几次模型调用的可见消息里（提醒时机的观察面；-1＝未出现）。 */
    private static int firstIndexContaining(List<List<Msg>> calls, String fragment) {
        for (int i = 0; i < calls.size(); i++) {
            if (anyMessageContains(calls.get(i), fragment)) {
                return i;
            }
        }
        return -1;
    }

    /** 给定调用可见消息里，含任一段提醒文本的用户消息条数（「本轮一条」观察面）。 */
    private static long reminderMessageCount(List<Msg> messages) {
        return messages.stream()
                .filter(m -> textOf(m).contains(
                        "You are repeating the exact same tool call with identical arguments")
                        || textOf(m).contains("Repeated tool call detected:"))
                .count();
    }

    // ---------- ① 自然结束：一轮不调工具即结束（不做做完后催促） ----------

    @Test
    void given_tool_round_then_plain_text_round_when_run_then_ends_naturally_without_nudge() {
        beginScript(script(
                toolReply("c1", ILLEGAL_WRITE), textReply("做完了。")));
        try (HarnessAgent agent = realFactory().obtainKernel("scripted-kernel:loop", null, null,
                kernelWorkspace(), null)) {
            String finalText = runGeneration(agent);
            ScriptedKernelModel stub = ScriptedKernelModel.called();

            // 连续一轮纯文本无工具调用→生成结束：恰两轮模型调用（工具轮＋文本轮），
            // 收尾即模型原文——不催续、不挂任何收尾句
            assertThat(stub.seenMessages).hasSize(2);
            assertThat(finalText).isEqualTo("做完了。");
            // 工具轮确实执行了（非法路径校验期拒绝，错误结果回模型——执行发生的外部证明）
            assertThat(anyMessageContains(stub.seenMessages.get(1), "路径非法")).isTrue();
        } finally {
            ScriptedKernelModel.nextScript = null;
        }
    }

    // ---------- ② 截断响应→轮次结束、收尾文本含原因 ----------

    @Test
    void given_truncated_response_when_run_then_round_ends_and_closing_states_reason() {
        ChatResponse truncated = new ChatResponse("resp-trunc",
                List.of(TextBlock.builder().text("写了一半").build()), null, Map.of(), "length");
        beginScript(script(truncated));
        try (HarnessAgent agent = realFactory().obtainKernel("scripted-kernel:loop", null, null,
                kernelWorkspace(), null)) {
            String finalText = runGeneration(agent);
            ScriptedKernelModel stub = ScriptedKernelModel.called();

            // 截断即轮次结束：只有一次模型调用（若继续循环，脚本耗尽即报错）；
            // 收尾文本＝截断片段＋截断原因句
            assertThat(stub.seenMessages).hasSize(1);
            assertThat(finalText).startsWith("写了一半");
            assertThat(finalText).contains("截断");
            assertThat(finalText).contains("继续");
        } finally {
            ScriptedKernelModel.nextScript = null;
        }
    }

    @Test
    void given_truncated_response_with_tool_call_when_run_then_tool_not_executed() {
        // 截断响应携带工具调用：工具调用被丢弃（照 DSH 截断即轮次结束），不执行不续轮
        ChatResponse truncatedWithTool = new ChatResponse("resp-trunc-tool",
                List.of(TextBlock.builder().text("写到一半").build(),
                        new ToolUseBlock("c9", "write_file", ILLEGAL_WRITE)),
                null, Map.of(), "length");
        beginScript(script(truncatedWithTool));
        try (HarnessAgent agent = realFactory().obtainKernel("scripted-kernel:loop", null, null,
                kernelWorkspace(), null)) {
            String finalText = runGeneration(agent);
            ScriptedKernelModel stub = ScriptedKernelModel.called();

            assertThat(stub.seenMessages).hasSize(1);
            assertThat(finalText).contains("截断");
            // 工具不执行的直接断言：全程零工具结果（截断携带的 c9 调用被丢弃，
            // 若执行过必见「路径非法」校验期拒绝结果）
            assertThat(stub.seenMessages.stream()
                    .flatMap(List::stream)
                    .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()))
                    .isEmpty();
        } finally {
            ScriptedKernelModel.nextScript = null;
        }
    }

    // ---------- ③ 空回复→轮次结束、收尾文本含原因 ----------

    @Test
    void given_empty_reply_when_run_then_round_ends_and_closing_states_reason() {
        // 空回复（无文本无工具调用，finish_reason=stop）：2.0.5 缺省会回流重问——
        // 内核语义照 DSH：空回复即轮次结束；若回流则第二次调用脚本耗尽即报错
        ChatResponse empty = new ChatResponse("resp-empty", List.of(), null, Map.of(), "stop");
        beginScript(script(empty));
        try (HarnessAgent agent = realFactory().obtainKernel("scripted-kernel:loop", null, null,
                kernelWorkspace(), null)) {
            String finalText = runGeneration(agent);
            ScriptedKernelModel stub = ScriptedKernelModel.called();

            assertThat(stub.seenMessages).hasSize(1);
            assertThat(finalText).contains("空回复");
            assertThat(finalText).contains("继续");
        } finally {
            ScriptedKernelModel.nextScript = null;
        }
    }

    // ---------- ④ 同一工具同样参数重复调用：第 3／5／8 次提醒、绝不拦截 ----------

    @Test
    void given_same_tool_same_args_repeats_when_run_then_remind_at_3_5_8_and_never_block() {
        List<ChatResponse> responses = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            responses.add(toolReply("same-" + i, ILLEGAL_WRITE));
        }
        responses.add(toolReply("other-1", OTHER_ILLEGAL_WRITE));
        responses.add(textReply("做完了。"));
        beginScript(new ConcurrentLinkedQueue<>(responses));
        try (HarnessAgent agent = realFactory().obtainKernel("scripted-kernel:loop", null, null,
                kernelWorkspace(), null)) {
            String finalText = runGeneration(agent);
            ScriptedKernelModel stub = ScriptedKernelModel.called();

            // 绝不拦截：8 次重复＋1 次换参＋1 次文本，10 轮模型调用全走通（脚本恰耗尽）
            assertThat(stub.seenMessages).hasSize(10);
            assertThat(finalText).isEqualTo("做完了。");
            // 9 次工具调用全部执行（非法路径校验期拒绝——执行发生的外部证明）
            int executed = stub.seenMessages.get(9).stream()
                    .mapToInt(m -> m.getContentBlocks(ToolResultBlock.class).size())
                    .sum();
            assertThat(executed).isEqualTo(9);

            // 提醒时机（提醒入上下文后随历史带往后轮——断「首次出现」轮次）：
            // 第 3 次短提醒在第 4 轮模型首次可见（结果之后、下一轮之前注入）；
            // 第 5／8 次详细模板取达到的最高次数
            assertThat(firstIndexContaining(stub.seenMessages,
                    "You are repeating the exact same tool call with identical arguments"))
                    .isEqualTo(3);
            assertThat(firstIndexContaining(stub.seenMessages, "- consecutive_calls: 5"))
                    .isEqualTo(5);
            assertThat(firstIndexContaining(stub.seenMessages, "- consecutive_calls: 8"))
                    .isEqualTo(8);
            // 第 3／5／8 次提醒各只出现一条（本轮一条、取最高次数）——末轮上下文里
            // 恰三条提醒用户消息；换参后无新提醒
            assertThat(reminderMessageCount(stub.seenMessages.get(9))).isEqualTo(3);
        } finally {
            ScriptedKernelModel.nextScript = null;
        }
    }

    @Test
    void given_same_tool_different_args_when_run_then_repeat_chain_resets_without_reminder() {
        // 「同样参数」腿：同工具换参即重新计数——两轮换参穿插，全程无提醒
        beginScript(script(
                toolReply("x1", ILLEGAL_WRITE), toolReply("x2", ILLEGAL_WRITE),
                toolReply("y1", OTHER_ILLEGAL_WRITE), toolReply("y2", OTHER_ILLEGAL_WRITE),
                toolReply("x3", ILLEGAL_WRITE), textReply("做完了。")));
        try (HarnessAgent agent = realFactory().obtainKernel("scripted-kernel:loop", null, null,
                kernelWorkspace(), null)) {
            String finalText = runGeneration(agent);
            ScriptedKernelModel stub = ScriptedKernelModel.called();

            assertThat(stub.seenMessages).hasSize(6);
            assertThat(finalText).isEqualTo("做完了。");
            // 同工具换参即重新计数：末轮上下文里一条提醒都没有
            assertThat(reminderMessageCount(stub.seenMessages.get(5))).isZero();
        } finally {
            ScriptedKernelModel.nextScript = null;
        }
    }

    @Test
    void given_two_same_calls_in_one_round_when_run_then_count_continues_across_rounds() {
        // 连续计数跨轮不间断：一轮内两个相同调用（计数 1、2），次轮同参调用达第 3 次
        ChatResponse doubleCall = new ChatResponse("resp-double",
                List.of(new ToolUseBlock("d1", "write_file", ILLEGAL_WRITE),
                        new ToolUseBlock("d2", "write_file", ILLEGAL_WRITE)),
                null, Map.of(), "stop");
        beginScript(script(doubleCall, toolReply("d3", ILLEGAL_WRITE),
                textReply("做完了。")));
        try (HarnessAgent agent = realFactory().obtainKernel("scripted-kernel:loop", null, null,
                kernelWorkspace(), null)) {
            String finalText = runGeneration(agent);
            ScriptedKernelModel stub = ScriptedKernelModel.called();

            assertThat(stub.seenMessages).hasSize(3);
            assertThat(finalText).isEqualTo("做完了。");
            // 一轮两个相同调用均执行（次轮模型可见 2 条工具结果）
            int executedBeforeThird = stub.seenMessages.get(1).stream()
                    .mapToInt(m -> m.getContentBlocks(ToolResultBlock.class).size())
                    .sum();
            assertThat(executedBeforeThird).isEqualTo(2);
            // 第 3 次落在次轮（一轮内计 1、2，跨轮续 3）→ 提醒在第三轮首次可见；
            // 全程恰一条提醒
            assertThat(firstIndexContaining(stub.seenMessages,
                    "You are repeating the exact same tool call with identical arguments"))
                    .isEqualTo(2);
            assertThat(reminderMessageCount(stub.seenMessages.get(2))).isEqualTo(1);
        } finally {
            ScriptedKernelModel.nextScript = null;
        }
    }

    // ---------- ⑤ 到护栏上限：工具被关、如实汇报提示出现 ----------

    @Test
    void given_guard_rounds_reached_when_run_then_tools_closed_and_max_steps_prompt_sent() {
        // 护栏 300 轮（KernelLoopSemantics.GUARD_ROUNDS）：前 300 轮同参重复（工具执行
        // 走校验期拒绝、不触 docker），第 301 轮到顶——关工具＋MAX_STEPS_PROMPT 注入
        List<ChatResponse> responses = new ArrayList<>();
        for (int i = 1; i <= KernelLoopSemantics.GUARD_ROUNDS; i++) {
            responses.add(toolReply("g" + i, ILLEGAL_WRITE));
        }
        responses.add(textReply("【收尾汇报】已做一部分，剩余未完成。"));
        beginScript(new ConcurrentLinkedQueue<>(responses));
        try (HarnessAgent agent = realFactory().obtainKernel("scripted-kernel:loop", null, null,
                kernelWorkspace(), null)) {
            String finalText = runGeneration(agent);
            ScriptedKernelModel stub = ScriptedKernelModel.called();

            // 到顶轮＝第 301 次模型调用（前 300 轮正常 + 1 轮到顶汇报；若提前到顶或漏顶，
            // 脚本长度断言即红）
            assertThat(stub.seenMessages).hasSize(KernelLoopSemantics.GUARD_ROUNDS + 1);
            // 工具被关：到顶轮模型入参工具 schema 为空
            assertThat(stub.seenToolSchemas.get(KernelLoopSemantics.GUARD_ROUNDS)).isEmpty();
            // 如实汇报提示照 OpenCode 原文注入（到顶轮模型可见）
            assertThat(anyMessageContains(stub.seenMessages.get(KernelLoopSemantics.GUARD_ROUNDS),
                    "CRITICAL - MAXIMUM STEPS REACHED")).isTrue();
            // 到顶语义由内核循环兑现（非 SDK summarizing 自写句）
            assertThat(stub.seenMessages.stream()
                    .anyMatch(msgs -> anyMessageContains(msgs,
                            "You have failed to generate response within")))
                    .isFalse();
            // 收尾＝模型如实汇报文本
            assertThat(finalText).isEqualTo("【收尾汇报】已做一部分，剩余未完成。");
        } finally {
            ScriptedKernelModel.nextScript = null;
        }
    }
}
