package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.semgravio.aiplatform.base.agentscope.DockerExecFilesystem.ExecCommand;
import com.semgravio.aiplatform.base.agentscope.DockerExecFilesystem.ExecOutput;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@link KernelTools} 内核工具四件（#594，沙箱面行为）：write_file 覆盖全量写＋
 * 自动建父目录；read_file 行号／分段／上限；edit_file 恰好一次替换＋三种错误文案；
 * bash 180 秒超时。替身 {@link ExecCommand} 录制命令形状（锚定／引号／超时秒数）。
 */
class KernelToolsTest {

    /** 录制式替身：按命令脚本化响应（DockerExecFilesystemTest.FakeExec 同款）。 */
    static class FakeExec implements ExecCommand {

        final List<String> commands = new ArrayList<>();
        final List<byte[]> stdins = new ArrayList<>();
        final List<Integer> timeouts = new ArrayList<>();
        Function<String, ExecOutput> responder = c -> new ExecOutput(1, new byte[0], "no script");

        @Override
        public ExecOutput run(String command, byte[] stdin) {
            commands.add(command);
            stdins.add(stdin);
            return responder.apply(command);
        }

        @Override
        public ExecOutput run(String command, byte[] stdin, int timeoutSeconds) {
            commands.add(command);
            stdins.add(stdin);
            timeouts.add(timeoutSeconds);
            return responder.apply(command);
        }
    }

    private final FakeExec exec = new FakeExec();
    private final List<AgentTool> tools = KernelTools.over(exec);

    private ToolResultBlock call(String name, Map<String, Object> input) {
        AgentTool tool = tools.stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tool not found: " + name));
        return tool.callAsync(ToolCallParam.builder().input(input).build()).block();
    }

    private static String textOf(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().get(0)).getText();
    }

    private static ExecOutput ok(String stdout) {
        return new ExecOutput(0, stdout.getBytes(StandardCharsets.UTF_8), "");
    }

    // ---------- write_file ----------

    @Test
    void given_write_file_when_invoked_then_full_overwrite_with_parent_dirs_created() {
        exec.responder = c -> ok("");

        ToolResultBlock block = call("write_file",
                Map.of("path", "/docs/v1/PRD.md", "content", "# PRD"));

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        // 覆盖全量写（非 create-only）＋自动建父目录＋工作区根锚定＋单引号包裹
        assertThat(exec.commands.get(0)).isEqualTo(
                "mkdir -p '/workspace/docs/v1' && cat > '/workspace/docs/v1/PRD.md'");
        assertThat(exec.stdins.get(0)).isEqualTo("# PRD".getBytes(StandardCharsets.UTF_8));
        assertThat(textOf(block)).isEqualTo("ok");
    }

    @Test
    void given_write_file_failure_when_invoked_then_error_block() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "no space left");

        ToolResultBlock block = call("write_file", Map.of("path", "/a.md", "content", "x"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).contains("no space left");
    }

    // ---------- read_file ----------

    @Test
    void given_read_file_when_invoked_then_line_numbered_with_end_footer() {
        exec.responder = c -> ok("alpha\nbeta\nalpha\ngamma\nalpha\n");

        ToolResultBlock block = call("read_file", Map.of("path", "/z.txt"));

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(exec.commands.get(0)).isEqualTo("cat -- '/workspace/z.txt'");
        assertThat(textOf(block)).isEqualTo("""
                <path>/z.txt</path>
                <type>file</type>
                <content>
                1: alpha
                2: beta
                3: alpha
                4: gamma
                5: alpha

                (End of file - total 5 lines)
                </content>""");
    }

    @Test
    void given_read_file_paged_when_offset_limit_then_segment_with_continue_footer() {
        exec.responder = c -> ok("alpha\nbeta\nalpha\ngamma\nalpha\n");

        ToolResultBlock block = call("read_file",
                Map.of("path", "/z.txt", "offset", 2, "limit", 2));

        assertThat(textOf(block)).isEqualTo("""
                <path>/z.txt</path>
                <type>file</type>
                <content>
                2: beta
                3: alpha

                (Showing lines 2-3 of 5. Use offset=4 to continue.)
                </content>""");
    }

    @Test
    void given_read_file_limit_over_cap_when_invoked_then_rejected() {
        ToolResultBlock block = call("read_file",
                Map.of("path", "/z.txt", "limit", 2001));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo(
                "Error: invalid arguments: limit must be less than or equal to 2000");
        assertThat(exec.commands).isEmpty();
    }

    @Test
    void given_read_file_offset_out_of_range_when_invoked_then_error() {
        exec.responder = c -> ok("l1\nl2\n");

        ToolResultBlock block = call("read_file", Map.of("path", "/z.txt", "offset", 99));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo(
                "Error: cannot read \"/z.txt\": offset 99 is out of range (2 lines)");
    }

    @Test
    void given_read_file_missing_when_invoked_then_not_found() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "No such file");

        ToolResultBlock block = call("read_file", Map.of("path", "/nope.txt"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo("Error: cannot read \"/nope.txt\": not found");
    }

    @Test
    void given_read_file_non_utf8_when_invoked_then_not_text() {
        exec.responder = c -> new ExecOutput(0, new byte[]{(byte) 0xFF, (byte) 0xFE}, "");

        ToolResultBlock block = call("read_file", Map.of("path", "/bin.dat"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block))
                .isEqualTo("Error: cannot read \"/bin.dat\" as text: file is not UTF-8");
    }

    @Test
    void given_read_file_long_line_when_invoked_then_line_truncated_to_cap() {
        String longLine = "x".repeat(2010);
        exec.responder = c -> ok(longLine + "\n");

        ToolResultBlock block = call("read_file", Map.of("path", "/z.txt"));

        assertThat(textOf(block)).contains("1: " + "x".repeat(2000)
                + "... (line truncated to 2000 chars)");
        assertThat(textOf(block)).contains("(End of file - total 1 lines)");
    }

    @Test
    void given_read_file_over_byte_cap_when_invoked_then_output_capped_footer() {
        // 30 行×2000 字符（行截断上限内）：25 行占 50024 字节、第 26 行超 51200 字节总上限
        // ——先逐行截断再计字节（照原型 render_read 顺序），capped footer 指路续读
        String line = "y".repeat(2000);
        exec.responder = c -> ok((line + "\n").repeat(30));

        ToolResultBlock block = call("read_file", Map.of("path", "/z.txt"));

        assertThat(textOf(block))
                .contains("(Output capped. Showing lines 1-25. Use offset=26 to continue.)");
    }

    // ---------- edit_file ----------

    @Test
    void given_edit_file_unique_when_invoked_then_replaced_and_written_back() {
        exec.responder = c -> c.startsWith("cat --")
                ? ok("hello\nworld\n")
                : ok("");

        ToolResultBlock block = call("edit_file",
                Map.of("path", "/dir/x.txt", "old_string", "world", "new_string", "世界"));

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(exec.commands.get(0)).isEqualTo("cat -- '/workspace/dir/x.txt'");
        // 读→改→覆盖写回（自动建父目录同 write_file 缝）
        assertThat(exec.commands.get(1)).isEqualTo(
                "mkdir -p '/workspace/dir' && cat > '/workspace/dir/x.txt'");
        assertThat(exec.stdins.get(1)).isEqualTo("hello\n世界\n".getBytes(StandardCharsets.UTF_8));
        assertThat(textOf(block)).isEqualTo("The file /dir/x.txt has been updated successfully.");
    }

    @Test
    void given_edit_file_not_found_text_when_invoked_then_verbatim_error() {
        exec.responder = c -> ok("hello\nworld\n");

        ToolResultBlock block = call("edit_file",
                Map.of("path", "/x.txt", "old_string", "nope", "new_string", "x"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo("Error: No replacement was performed, old_string `nope` "
                + "did not appear verbatim in /x.txt.");
    }

    @Test
    void given_edit_file_ambiguous_when_invoked_then_unique_error_with_lines() {
        exec.responder = c -> ok("a\na\n");

        ToolResultBlock block = call("edit_file",
                Map.of("path", "/y.txt", "old_string", "a", "new_string", "b"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo("Error: No replacement was performed. Multiple occurrences "
                + "of old_string `a` in lines [1, 2]. Please ensure it is unique");
    }

    @Test
    void given_edit_file_missing_file_when_invoked_then_cannot_read() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "No such file");

        ToolResultBlock block = call("edit_file",
                Map.of("path", "/nope.txt", "old_string", "a", "new_string", "b"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo("Error: cannot read \"/nope.txt\": not found");
    }

    @Test
    void given_edit_file_replace_all_when_invoked_then_all_replaced() {
        exec.responder = c -> c.startsWith("cat --")
                ? ok("alpha\nbeta\nalpha\n")
                : ok("");

        ToolResultBlock block = call("edit_file", Map.of(
                "path", "/f.txt", "old_string", "alpha", "new_string", "A", "replace_all", true));

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(exec.stdins.get(1)).isEqualTo("A\nbeta\nA\n".getBytes(StandardCharsets.UTF_8));
        assertThat(textOf(block)).isEqualTo(
                "The file /f.txt has been updated. All occurrences were successfully replaced.");
    }

    @Test
    void given_edit_file_empty_old_string_when_invoked_then_arg_error() {
        ToolResultBlock block = call("edit_file",
                Map.of("path", "/x.txt", "old_string", "", "new_string", "b"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo("Error: old_string must be a non-empty string");
        assertThat(exec.commands).isEmpty();
    }

    @Test
    void given_edit_file_non_utf8_when_invoked_then_script_original_message() {
        // 非 UTF-8 拒因照裁决脚本原文（v0-harness4.py VARIANT=3 自写中文句——与 read_file 形不同源）
        exec.responder = c -> new ExecOutput(0, new byte[]{(byte) 0xFF}, "");

        ToolResultBlock block = call("edit_file",
                Map.of("path", "/bin.dat", "old_string", "a", "new_string", "b"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo("Error: ERROR: /bin.dat 不是 UTF-8 文本文件。");
    }

    // ---------- bash ----------

    @Test
    void given_bash_when_invoked_then_command_executed_with_180s_timeout() {
        exec.responder = c -> ok("hello");

        ToolResultBlock block = call("bash", Map.of("command", "pnpm build"));

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        // 模型命令原样执行（sh -c 由 exec 前缀拼）、180 秒超时透传执行缝
        assertThat(exec.commands.get(0)).isEqualTo("pnpm build");
        assertThat(exec.timeouts.get(0)).isEqualTo(180);
        assertThat(textOf(block)).isEqualTo("hello");
    }

    @Test
    void given_bash_non_zero_exit_when_invoked_then_error_with_output() {
        exec.responder = c -> new ExecOutput(1, "build failed".getBytes(StandardCharsets.UTF_8),
                "err detail");

        ToolResultBlock block = call("bash", Map.of("command", "pnpm build"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo("Error: build failed\n--stderr--\nerr detail");
    }

    @Test
    void given_bash_timeout_when_invoked_then_timeout_reported() {
        // 执行缝超时＝退出码 124（DockerExecCommand 约定，同 DockerExecFilesystem 口径）
        exec.responder = c -> new ExecOutput(124, new byte[0], "docker exec 超时");

        ToolResultBlock block = call("bash", Map.of("command", "sleep 999"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).isEqualTo("Error: TIMEOUT after 180s");
    }

    @Test
    void given_bash_empty_output_when_invoked_then_no_output_marker() {
        exec.responder = c -> ok("");

        ToolResultBlock block = call("bash", Map.of("command", "true"));

        assertThat(textOf(block)).isEqualTo("(no output)");
    }

    @Test
    void given_bash_quota_guard_on_windows_host_when_double_quoted_then_rejected_without_exec() {
        List<AgentTool> windowsTools = KernelTools.over(exec, true);

        ToolResultBlock block = windowsTools.stream()
                .filter(t -> t.getName().equals("bash"))
                .findFirst()
                .orElseThrow()
                .callAsync(ToolCallParam.builder()
                        .input(Map.of("command", "grep \"x\" file"))
                        .build())
                .block();

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).contains("①族守卫");
        assertThat(exec.commands).isEmpty();
    }

    @Test
    void given_bash_same_command_on_linux_host_when_double_quoted_then_executed() {
        // 平台性守卫只条件化 Windows 宿主（Linux execve 原样传 argv 不启用，#440 口径）
        List<AgentTool> linuxTools = KernelTools.over(exec, false);

        exec.responder = c -> ok("hit");
        ToolResultBlock block = linuxTools.stream()
                .filter(t -> t.getName().equals("bash"))
                .findFirst()
                .orElseThrow()
                .callAsync(ToolCallParam.builder()
                        .input(Map.of("command", "grep \"x\" file"))
                        .build())
                .block();

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(exec.commands).containsExactly("grep \"x\" file");
    }
}
