package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.business.project.domain.decisionhint.TrajectorySnapshot;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;

/**
 * 轨迹快照提取缝（#437）：agentscope 消息列 → 判定缝框架中立形。分组口径：
 * assistant 消息开一个迭代（文本＋工具调用）；TOOL 消息的 ToolResultBlock 按
 * id 归位到调用；真实用户消息是轮界（其后才计注入键）；合成消息（agentscope
 * synthetic / 决策提示）不进迭代也不当轮界。
 */
class TrajectorySnapshotsTest {

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Msg assistant(String text, ToolUseBlock... calls) {
        List<ContentBlock> blocks = new java.util.ArrayList<>();
        if (!text.isEmpty()) {
            blocks.add(TextBlock.builder().text(text).build());
        }
        blocks.addAll(List.of(calls));
        return Msg.builder().role(MsgRole.ASSISTANT).content(blocks).build();
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, Map.of("path", "a.ts"));
    }

    private static Msg toolResult(ToolUseBlock call, ToolResultState state, String output) {
        return Msg.builder().role(MsgRole.TOOL)
                .content(new ToolResultBlock(call.getId(), call.getName(),
                        TextBlock.builder().text(output).build()).withState(state))
                .metadata(Map.of())
                .build();
    }

    private static Msg syntheticHint(String kind) {
        return Msg.builder().role(MsgRole.USER).name("decision_hint")
                .content(TextBlock.builder().text("【平台提示】换策略").build())
                .metadata(Map.of(
                        "agentscope_synthetic", true,
                        "decision_hint_kind", kind))
                .build();
    }

    @Test
    void given_assistant_and_tool_messages_when_extract_then_iterations_grouped() {
        ToolUseBlock read = call("c1", "read_file");
        ToolUseBlock run = call("c2", "execute");
        List<Msg> messages = List.of(
                user("做订单页"),
                assistant("先读文件", read),
                toolResult(read, ToolResultState.SUCCESS, "文件内容"),
                assistant("跑一下", run),
                toolResult(run, ToolResultState.ERROR, "Error: boom"));

        TrajectorySnapshot snapshot = TrajectorySnapshots.from(messages);

        assertThat(snapshot.iterations()).hasSize(2);
        assertThat(snapshot.iterations().get(0).assistantText()).isEqualTo("先读文件");
        assertThat(snapshot.iterations().get(0).calls()).hasSize(1);
        assertThat(snapshot.iterations().get(0).calls().get(0).failed()).isFalse();
        assertThat(snapshot.iterations().get(0).calls().get(0).resultChars())
                .isEqualTo("文件内容".length());
        assertThat(snapshot.iterations().get(1).calls().get(0).failed()).isTrue();
        assertThat(snapshot.iterations().get(1).calls().get(0).outputText())
                .isEqualTo("Error: boom");
    }

    @Test
    void given_parallel_calls_in_one_response_when_extract_then_same_iteration() {
        ToolUseBlock a = call("c1", "read_file");
        ToolUseBlock b = call("c2", "read_file");
        List<Msg> messages = List.of(
                user("开工"),
                assistant("", a, b),
                toolResult(a, ToolResultState.SUCCESS, "A"),
                toolResult(b, ToolResultState.SUCCESS, "BB"));

        TrajectorySnapshot snapshot = TrajectorySnapshots.from(messages);

        assertThat(snapshot.iterations()).hasSize(1);
        assertThat(snapshot.iterations().get(0).calls()).hasSize(2);
    }

    @Test
    void given_text_only_assistant_when_extract_then_iteration_with_no_calls() {
        // 纯文本迭代（解说）也是迭代：并行度信号把它算作「非单调用」断点
        ToolUseBlock list = call("c1", "list_files");
        List<Msg> messages = List.of(
                user("开工"),
                assistant("我先看看目录", list),
                toolResult(list, ToolResultState.SUCCESS, "x"),
                assistant("准备开始写代码"));

        TrajectorySnapshot snapshot = TrajectorySnapshots.from(messages);

        assertThat(snapshot.iterations()).hasSize(2);
        assertThat(snapshot.iterations().get(1).calls()).isEmpty();
        assertThat(snapshot.iterations().get(1).assistantText()).isEqualTo("准备开始写代码");
    }

    @Test
    void given_hints_across_turn_boundary_when_extract_then_only_current_turn_keys() {
        // 轮界＝最近一条真实用户消息：上一轮的注入键不进本轮快照
        List<Msg> messages = List.of(
                user("第一段任务"),
                assistant("做", call("c1", "write_file")),
                toolResult(call("c1", "write_file"), ToolResultState.SUCCESS, "ok"),
                syntheticHint("failure_stop_loss"),
                user("第二段任务"),
                syntheticHint("parallel_tools"),
                assistant("继续", call("c2", "write_file")),
                toolResult(call("c2", "write_file"), ToolResultState.SUCCESS, "ok"));

        TrajectorySnapshot snapshot = TrajectorySnapshots.from(messages);

        assertThat(snapshot.injectedHintKeys()).containsExactly("parallel_tools");
    }

    @Test
    void given_framework_synthetic_reminder_when_extract_then_not_a_boundary_nor_iteration() {
        // 框架 TaskReminder 的合成消息（agentscope_synthetic 无 decision_hint_kind）：
        // 不当轮界、不进注入键、不进迭代
        Msg reminder = Msg.builder().role(MsgRole.USER).name("system")
                .content(TextBlock.builder().text("todo 提醒").build())
                .metadata(Map.of("agentscope_synthetic", true,
                        "agentscope_reminder_kind", "todo_state"))
                .build();
        ToolUseBlock call = call("c1", "write_file");
        List<Msg> messages = List.of(
                user("任务"),
                reminder,
                assistant("做", call),
                toolResult(call, ToolResultState.SUCCESS, "ok"));

        TrajectorySnapshot snapshot = TrajectorySnapshots.from(messages);

        assertThat(snapshot.iterations()).hasSize(1);
        assertThat(snapshot.injectedHintKeys()).isEmpty();
    }
}
