package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;

import org.junit.jupiter.api.Test;

/**
 * 工具写动作 → 文件变更事实（#88 收口扩载的观察面）：write_file / edit_file 的
 * 参数增量累积 → 调用落定解析 path 与行数 → 结果成功才提交（失败/被拒的写不是
 * 变更）；command 与读类工具不收（非文件面）；行数口径 write = 新文件行数、
 * edit = old/new 行数。事实源 = 平台可观测的工具调用（判定不由模型自报）。
 */
class FileChangeFactsTest {

    private static ToolCallDeltaEvent delta(String toolCallId, String toolName, String json) {
        return new ToolCallDeltaEvent("reply-1", toolCallId, toolName, json);
    }

    private static ToolCallEndEvent callEnd(String toolCallId, String toolName) {
        return new ToolCallEndEvent("reply-1", toolCallId, toolName);
    }

    private static ToolResultEndEvent resultEnd(String toolCallId, String toolName,
            ToolResultState state) {
        return new ToolResultEndEvent("reply-1", toolCallId, toolName, state);
    }

    @Test
    void given_write_file_success_when_settled_then_change_is_new_file_lines() {
        FileChangeFacts facts = new FileChangeFacts();
        facts.onDelta(delta("tc-1", "write_file",
                "{\"path\":\"/src/pages/Orders.jsx\",\"content\":\"a\\nb\\nc\"}"));
        facts.onCallEnd("write_file", "tc-1");
        facts.onResultEnd("tc-1", true);

        assertThat(facts.changes()).containsExactly(new FileChange("/src/pages/Orders.jsx", 3, 0));
    }

    @Test
    void given_edit_file_success_when_settled_then_change_counts_old_and_new_lines() {
        FileChangeFacts facts = new FileChangeFacts();
        facts.onDelta(delta("tc-2", "edit_file",
                "{\"path\":\"/src/App.jsx\",\"old_string\":\"a\\nb\",\"new_string\":\"x\"}"));
        facts.onCallEnd("edit_file", "tc-2");
        facts.onResultEnd("tc-2", true);

        assertThat(facts.changes()).containsExactly(new FileChange("/src/App.jsx", 1, 2));
    }

    @Test
    void given_failed_tool_result_when_settled_then_not_a_change() {
        FileChangeFacts facts = new FileChangeFacts();
        facts.onDelta(delta("tc-3", "write_file", "{\"path\":\"/x.js\",\"content\":\"a\"}"));
        facts.onCallEnd("write_file", "tc-3");
        facts.onResultEnd("tc-3", false);

        assertThat(facts.changes()).isEmpty();
    }

    @Test
    void given_command_or_read_tools_when_streamed_then_not_collected() {
        FileChangeFacts facts = new FileChangeFacts();
        facts.onDelta(delta("tc-4", "command", "{\"command\":\"npm install\"}"));
        facts.onCallEnd("command", "tc-4");
        facts.onResultEnd("tc-4", true);
        facts.onDelta(delta("tc-5", "read_file", "{\"path\":\"/a.js\"}"));
        facts.onCallEnd("read_file", "tc-5");
        facts.onResultEnd("tc-5", true);

        assertThat(facts.changes()).isEmpty();
    }

    @Test
    void given_incomplete_or_unparsable_args_when_settled_then_no_change() {
        FileChangeFacts facts = new FileChangeFacts();
        // 参数流不完整（无结果落定）与非法 JSON：不是可观测的文件变更
        facts.onDelta(delta("tc-6", "write_file", "{\"path\":\"/y.js\""));
        facts.onCallEnd("write_file", "tc-6");
        facts.onResultEnd("tc-6", true);

        assertThat(facts.changes()).isEmpty();
    }

    @Test
    void given_same_path_multiple_calls_when_settled_then_all_kept_in_order() {
        FileChangeFacts facts = new FileChangeFacts();
        facts.onDelta(delta("tc-7", "write_file", "{\"path\":\"/a.js\",\"content\":\"a\"}"));
        facts.onCallEnd("write_file", "tc-7");
        facts.onResultEnd("tc-7", true);
        facts.onDelta(delta("tc-8", "edit_file",
                "{\"path\":\"/a.js\",\"old_string\":\"a\",\"new_string\":\"a\\nb\"}"));
        facts.onCallEnd("edit_file", "tc-8");
        facts.onResultEnd("tc-8", true);

        // 逐次保留（合并归收口扩载拼装层——同路径跨尝试合并在业务侧单点）
        assertThat(facts.changes()).containsExactly(
                new FileChange("/a.js", 1, 0),
                new FileChange("/a.js", 2, 1));
    }
}
