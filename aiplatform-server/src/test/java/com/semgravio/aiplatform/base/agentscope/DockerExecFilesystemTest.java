package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.semgravio.aiplatform.base.agentscope.DockerExecFilesystem.ExecCommand;
import com.semgravio.aiplatform.base.agentscope.DockerExecFilesystem.ExecOutput;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@link DockerExecFilesystem}（fake ExecCommand）：命令形状（根目录
 * 锚定 / 单引号转义 / noclobber create-only）、结果解析（ls/read/grep/glob）、
 * 语义对齐本地文件系统（幂等删除、遍历阻断、二进制 base64）。
 */
class DockerExecFilesystemTest {

    /** 录制式替身：按命令前缀脚本化响应。 */
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
            // 2 参通道不经此（超时缺省断言只看 shell 面），双通道各自录制
            commands.add(command);
            stdins.add(stdin);
            timeouts.add(timeoutSeconds);
            return responder.apply(command);
        }

        boolean ran(String fragment) {
            return commands.stream().anyMatch(c -> c.contains(fragment));
        }
    }

    private final FakeExec exec = new FakeExec();
    private final DockerExecFilesystem fs = new DockerExecFilesystem(exec);

    private static ExecOutput ok(String stdout) {
        return new ExecOutput(0, stdout.getBytes(StandardCharsets.UTF_8), "");
    }

    @Test
    void given_write_when_invoked_then_noclobber_create_only_anchored_and_quoted() {
        exec.responder = c -> ok("");

        WriteResult result = fs.write(RuntimeContext.empty(), "/docs/PRD.md", "# PRD");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.path()).isEqualTo("/docs/PRD.md");
        // create-only 原子性（set -C）+ 父目录创建 + 工作区根锚定 + 单引号包裹
        assertThat(exec.ran("set -C")).isTrue();
        assertThat(exec.ran("mkdir -p '/workspace/docs'")).isTrue();
        assertThat(exec.ran("cat > '/workspace/docs/PRD.md'")).isTrue();
        assertThat(exec.stdins.get(0)).isEqualTo("# PRD".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void given_noclobber_failure_when_write_then_rejected_as_existing() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "cannot create");

        WriteResult result = fs.write(RuntimeContext.empty(), "/docs/PRD.md", "x");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("already exists");
    }

    @Test
    void given_read_text_when_invoked_then_anchored_and_sliced() {
        exec.responder = c -> ok("l1\nl2\nl3");

        ReadResult full = fs.read(RuntimeContext.empty(), "/docs/a.txt", 0, 0);
        ReadResult paged = fs.read(RuntimeContext.empty(), "/docs/a.txt", 1, 1);

        assertThat(exec.ran("test -f '/workspace/docs/a.txt' && cat '/workspace/docs/a.txt'")).isTrue();
        assertThat(full.fileData().content()).isEqualTo("l1\nl2\nl3");
        assertThat(full.fileData().encoding()).isEqualTo("utf-8");
        assertThat(paged.fileData().content()).isEqualTo("l2");
    }

    @Test
    void given_read_missing_file_when_invoked_then_not_found() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "No such file");

        ReadResult result = fs.read(RuntimeContext.empty(), "/docs/none.txt", 0, 0);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    void given_container_unreachable_when_read_then_operational_error_distinguished() {
        // docker exec 自身失败（容器不在 = 退出码 125）与文件不存在（1）必须可辨
        exec.responder = c -> new ExecOutput(125, new byte[0],
                "Error response from daemon: No such container");

        ReadResult result = fs.read(RuntimeContext.empty(), "/docs/a.txt", 0, 0);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error())
                .contains("容器不可达")
                .contains("No such container");
    }

    @Test
    void given_read_binary_extension_when_invoked_then_base64() {
        exec.responder = c -> new ExecOutput(0, new byte[]{1, 2, 3}, "");

        ReadResult result = fs.read(RuntimeContext.empty(), "/assets/logo.png", 0, 0);

        assertThat(result.fileData().encoding()).isEqualTo("base64");
        assertThat(result.fileData().content())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
    }

    @Test
    void given_ls_when_invoked_then_entries_parsed_with_dir_slash() {
        exec.responder = c -> ok("d\t0\t1756100000.123\tdocs\n-\t12\t1756200000.456\tPRD.md\n");

        LsResult result = fs.ls(RuntimeContext.empty(), "/");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.entries()).extracting(io.agentscope.harness.agent.filesystem.model.FileInfo::path)
                .containsExactly("/PRD.md", "/docs/")   // 按路径排序（大写在前）
                .doesNotContainNull();
        var dir = result.entries().stream()
                .filter(e -> e.path().equals("/docs/")).findFirst().orElseThrow();
        var file = result.entries().stream()
                .filter(e -> e.path().equals("/PRD.md")).findFirst().orElseThrow();
        assertThat(dir.isDirectory()).isTrue();
        assertThat(file.isDirectory()).isFalse();
        assertThat(file.size()).isEqualTo(12);
    }

    @Test
    void given_ls_missing_dir_when_invoked_then_empty_success() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "No such file");

        LsResult result = fs.ls(RuntimeContext.empty(), "/none");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void given_ls_fragments_from_newline_filename_when_parsed_then_skipped_not_thrown() {
        // #467：文件名含换行+制表符（write_file 可造，validatePath 只拦空/../..）时
        // find -printf 会产出「碎行」——大小段非数字的行必须跳过，不得 NFE 穿透杀 run
        exec.responder = c -> ok("-\t12\t1756100000.0\tREADME.md\n8\tfoo\t3\t名\n");

        LsResult result = fs.ls(RuntimeContext.empty(), "/");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.entries()).extracting(io.agentscope.harness.agent.filesystem.model.FileInfo::path)
                .containsExactly("/README.md");
    }

    @Test
    void given_edit_unique_match_when_invoked_then_replaced_and_written_back() {
        exec.responder = c -> c.startsWith("test -f") ? ok("你好，世界") : ok("");

        EditResult result = fs.edit(RuntimeContext.empty(), "/docs/a.txt", "世界", "AgentScope", false);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.occurrences()).isEqualTo(1);
        assertThat(new String(exec.stdins.get(exec.stdins.size() - 1), StandardCharsets.UTF_8))
                .isEqualTo("你好，AgentScope");
    }

    @Test
    void given_edit_no_match_when_invoked_then_failed() {
        exec.responder = c -> c.startsWith("test -f") ? ok("内容") : ok("");

        EditResult result = fs.edit(RuntimeContext.empty(), "/docs/a.txt", "不存在", "x", false);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    void given_edit_ambiguous_without_replace_all_when_invoked_then_failed() {
        exec.responder = c -> c.startsWith("test -f") ? ok("ab\nab") : ok("");

        EditResult result = fs.edit(RuntimeContext.empty(), "/docs/a.txt", "ab", "x", false);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("not unique");
    }

    @Test
    void given_grep_when_invoked_then_literal_search_with_input_space_paths() {
        exec.responder = c -> ok("/workspace/docs/PRD.md:3:这里有 PRD\n/workspace/README.md:7:PRD 说明");

        GrepResult result = fs.grep(RuntimeContext.empty(), "PRD", "/docs", null);

        assertThat(exec.ran("grep -rFIn")).isTrue();
        assertThat(exec.ran("-- 'PRD' '/workspace/docs'")).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches().get(0).path()).isEqualTo("/docs/PRD.md");
        assertThat(result.matches().get(0).line()).isEqualTo(3);
        assertThat(result.matches().get(0).text()).isEqualTo("这里有 PRD");
    }

    @Test
    void given_grep_no_match_when_exit_1_then_success_empty() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "");

        GrepResult result = fs.grep(RuntimeContext.empty(), "PRD", "/", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void given_grep_fragments_from_colon_filename_when_parsed_then_skipped_not_thrown() {
        // #467：文件名含冒号（write_file 可造）会让「路径:行号:文本」错位——
        // 行号段非数字的行跳过，不 NFE 穿透杀 run（ls/glob 碎行同口径）
        exec.responder = c -> ok("/workspace/docs/PRD.md:3:这里有 PRD\n"
                + "/workspace/evil:名:bar:碎行");

        GrepResult result = fs.grep(RuntimeContext.empty(), "PRD", "/docs", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).line()).isEqualTo(3);
    }

    @Test
    void given_glob_when_invoked_then_files_filtered_by_pattern() {
        exec.responder = c -> ok("7\t1756100000.0\t/workspace/docs/PRD.md\0"
                + "3\t1756100000.0\t/workspace/index.html\0");

        var result = fs.glob(RuntimeContext.empty(), "**/*.md", "/");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).path()).isEqualTo("/docs/PRD.md");
        assertThat(result.matches().get(0).size()).isEqualTo(7);
    }

    @Test
    void given_glob_fragments_from_newline_filename_when_parsed_then_skipped_not_thrown() {
        // #467：同 ls 碎行口径——大小/时间戳段非数字的记录跳过，不 NFE 穿透杀 run
        exec.responder = c -> ok("7\t1756100000.0\t/workspace/docs/PRD.md\0"
                + "碎\t行\t/workspace/x\ny.md\0");

        var result = fs.glob(RuntimeContext.empty(), "**/*.md", "/");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.matches()).extracting(io.agentscope.harness.agent.filesystem.model.FileInfo::path)
                .containsExactly("/docs/PRD.md");
    }

    @Test
    void given_invalid_glob_pattern_when_invoked_then_fail_result_with_corrective_text() {
        // #467：glob 语法是模型入参——非法语法（未闭合 [）须 fail 结果带纠偏指引，
        // 不抛 IAE 丢整次结果（getPathMatcher 在碎行 try 之外的同族缺口；
        // matcher 构造在 exec.run 之后，须先放行 exec 才达该行）
        exec.responder = c -> ok("");

        var result = fs.glob(RuntimeContext.empty(), "docs[", "/");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("glob 语法非法");
    }

    @Test
    void given_exists_when_exit_code_reflects_presence_then_boolean() {
        exec.responder = c -> c.endsWith("'/workspace/docs/PRD.md'") ? ok("") : new ExecOutput(1, new byte[0], "");

        assertThat(fs.exists(RuntimeContext.empty(), "/docs/PRD.md")).isTrue();
        assertThat(fs.exists(RuntimeContext.empty(), "/docs/none.md")).isFalse();
    }

    @Test
    void given_delete_when_invoked_then_recursive_rm_idempotent() {
        exec.responder = c -> ok("");

        WriteResult result = fs.delete(RuntimeContext.empty(), "/docs");

        assertThat(exec.ran("rm -rf -- '/workspace/docs'")).isTrue();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void given_move_when_invoked_then_source_checked_parent_created() {
        exec.responder = c -> ok("");

        WriteResult result = fs.move(RuntimeContext.empty(), "/a.md", "/docs/b.md");

        assertThat(exec.ran("test -e '/workspace/a.md'")).isTrue();
        assertThat(exec.ran("mkdir -p '/workspace/docs'")).isTrue();
        assertThat(exec.ran("mv -- '/workspace/a.md' '/workspace/docs/b.md'")).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.path()).isEqualTo("/docs/b.md");
    }

    @Test
    void given_upload_when_invoked_then_bytes_written_overwrite() {
        exec.responder = c -> ok("");

        var responses = fs.uploadFiles(RuntimeContext.empty(),
                List.of(Map.entry("/assets/x.bin", new byte[]{9, 9})));

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).isSuccess()).isTrue();
        assertThat(exec.ran("cat > '/workspace/assets/x.bin'")).isTrue();
        assertThat(exec.stdins.get(0)).isEqualTo(new byte[]{9, 9});
    }

    @Test
    void given_download_when_invoked_then_bytes_passthrough() {
        exec.responder = c -> c.contains("cat '/workspace/a.bin'") && c.startsWith("test -f")
                ? new ExecOutput(1, new byte[0], "No such")
                : new ExecOutput(0, new byte[]{5, 5}, "");

        var responses = fs.downloadFiles(RuntimeContext.empty(), List.of("/a.bin"));

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).isSuccess()).isTrue();
        assertThat(responses.get(0).content()).isEqualTo(new byte[]{5, 5});
    }

    // ---------- 路径安全 ----------

    @Test
    void given_traversal_path_when_invoked_then_blocked_not_reaching_container() {
        assertThat(fs.exists(RuntimeContext.empty(), "/../etc/passwd")).isFalse();
        assertThat(fs.ls(RuntimeContext.empty(), "/a/../b").error()).contains("路径非法");
        // #467：校验异常必须转模型可见错误（FilesystemTool 框架壳无 catch，throw
        // 即穿透杀 run——与 ls/read 同为 fail 形态，模型可纠偏重试）
        WriteResult write = fs.write(RuntimeContext.empty(), "/../escape.md", "x");
        assertThat(write.isSuccess()).isFalse();
        assertThat(write.error()).contains("路径非法");
        var upload = fs.uploadFiles(RuntimeContext.empty(),
                List.of(Map.entry("a/../escape.bin", new byte[]{1})));
        assertThat(upload.get(0).isSuccess()).isFalse();
        // 未产生任何容器命令
        assertThat(exec.commands).isEmpty();
    }

    @Test
    void given_quote_in_filename_when_invoked_then_shell_escaped() {
        exec.responder = c -> ok("");

        fs.write(RuntimeContext.empty(), "/docs/it's.md", "内容");

        assertThat(exec.ran("cat > '/workspace/docs/it'\\''s.md'")).isTrue();
    }

    // ---------- 框架内部状态重映射（#107 / ADR-0012） ----------

    @Test
    void given_memory_flush_path_when_written_then_remapped_into_platform_state() {
        exec.responder = c -> ok("");

        fs.write(RuntimeContext.empty(), "memory/2026-09-08.md", "记忆条目");

        assertThat(exec.ran("cat > '/workspace/.platform/agentscope-state/memory/2026-09-08.md'")).isTrue();
        assertThat(exec.ran("/workspace/memory")).isFalse();
    }

    @Test
    void given_offload_session_path_when_uploaded_then_remapped_into_platform_state() {
        exec.responder = c -> ok("");

        fs.uploadFiles(RuntimeContext.empty(),
                List.of(Map.entry("agents/main/sessions/s1.jsonl", new byte[]{1})));

        assertThat(exec.ran("cat > '/workspace/.platform/agentscope-state/agents/main/sessions/s1.jsonl'")).isTrue();
        assertThat(exec.ran("/workspace/agents/main/sessions")).isFalse();
    }

    @Test
    void given_eviction_path_when_written_then_remapped_into_platform_state() {
        exec.responder = c -> ok("");

        fs.write(RuntimeContext.empty(), "large_tool_results/main/call_1-hash", "超大输出");

        assertThat(exec.ran("cat > '/workspace/.platform/agentscope-state/large_tool_results/main/call_1-hash'")).isTrue();
        assertThat(exec.ran("/workspace/large_tool_results")).isFalse();
    }

    @Test
    void given_subagent_isolated_workspace_when_written_then_stays_in_workspace() {
        exec.responder = c -> ok("");

        fs.uploadFiles(RuntimeContext.empty(),
                List.of(Map.entry("agents/verify/workspace/app.js", new byte[]{2})));

        assertThat(exec.ran("cat > '/workspace/agents/verify/workspace/app.js'")).isTrue();
        assertThat(exec.ran("/workspace/.platform/agentscope-state/agents")).isFalse();
    }

    @Test
    void given_state_path_read_when_invoked_then_same_remapped_root() {
        exec.responder = c -> ok("已有记忆");

        fs.read(RuntimeContext.empty(), "memory/2026-09-08.md", 0, 0);

        assertThat(exec.ran("test -f '/workspace/.platform/agentscope-state/memory/2026-09-08.md'")).isTrue();
    }

    // ---------- shell 执行面（sandbox 接口，run 执行体命令通道） ----------

    @Test
    void given_shell_command_when_executed_then_passthrough_with_merged_streams() {
        exec.responder = c -> new ExecOutput(3, "已安装 3 个包".getBytes(StandardCharsets.UTF_8), "npm warn");

        var resp = fs.execute(RuntimeContext.empty(), "cd 'src' && npm install", 60);

        assertThat(exec.commands).containsExactly("cd 'src' && npm install");
        assertThat(resp.exitCode()).isEqualTo(3);
        assertThat(resp.output()).isEqualTo("已安装 3 个包\nnpm warn");
        assertThat(resp.truncated()).isFalse();
    }

    @Test
    void given_shell_timeout_when_executed_then_seconds_flow_to_seam_and_default_applies() {
        exec.responder = c -> ok("");

        fs.execute(RuntimeContext.empty(), "npm install", 300);
        fs.execute(RuntimeContext.empty(), "ls", null);

        assertThat(exec.timeouts).containsExactly(300, 120);
    }

    @Test
    void given_oversized_shell_output_when_executed_then_truncated() {
        exec.responder = c -> ok("x".repeat(150_000));

        var resp = fs.execute(RuntimeContext.empty(), "cat big.log", 10);

        assertThat(resp.output()).hasSize(100_000);
        assertThat(resp.truncated()).isTrue();
    }

    // ---------- ①族守卫（#440：智能体自产命令 Windows argv fail-fast） ----------

    @Test
    void given_windows_host_when_shell_command_has_embedded_quote_then_rejected_fail_fast_with_rewrite_guidance() {
        DockerExecFilesystem windowsFs = new DockerExecFilesystem(exec, true);

        // #434 排练实测原形的挂死命令：内嵌双引号（\.env|AGENTS|DESIGN 正则族）
        var resp = windowsFs.execute(RuntimeContext.empty(),
                "grep -E \"\\.env|AGENTS\" '/workspace/docs/PRD.md'", 30);

        assertThat(resp.exitCode())
                .isEqualTo(DockerExecFilesystem.SHELL_GUARD_REJECTED_EXIT_CODE);
        assertThat(resp.output())
                .contains("#440")
                .contains("单引号")
                .contains("未执行");
        assertThat(resp.truncated()).isFalse();
        // fail-fast：容器命令缝一次都没碰（不会 spawn、不会挂死）
        assertThat(exec.commands).isEmpty();
    }

    @Test
    void given_windows_host_when_shell_command_has_command_substitution_then_rejected() {
        DockerExecFilesystem windowsFs = new DockerExecFilesystem(exec, true);

        var resp = windowsFs.execute(RuntimeContext.empty(), "s=$(stat -c %s 'f')", 30);

        assertThat(resp.exitCode())
                .isEqualTo(DockerExecFilesystem.SHELL_GUARD_REJECTED_EXIT_CODE);
        assertThat(resp.output())
                .contains("$(")
                .contains("#440");
        assertThat(exec.commands).isEmpty();
    }

    @Test
    void given_windows_host_when_single_quote_command_then_passthrough() {
        DockerExecFilesystem windowsFs = new DockerExecFilesystem(exec, true);
        exec.responder = c -> new ExecOutput(0, "ok".getBytes(StandardCharsets.UTF_8), "");

        var resp = windowsFs.execute(RuntimeContext.empty(), "grep -E '\\.env' '/workspace/docs/PRD.md'", 30);

        // 单引号纪律形态不拦：改写重试后的成功路径照常到达容器缝
        assertThat(exec.commands).containsExactly("grep -E '\\.env' '/workspace/docs/PRD.md'");
        assertThat(resp.exitCode()).isEqualTo(0);
        assertThat(resp.output()).isEqualTo("ok");
    }

    @Test
    void given_non_windows_host_when_same_violating_command_then_passthrough_unchanged() {
        DockerExecFilesystem linuxFs = new DockerExecFilesystem(exec, false);
        exec.responder = c -> new ExecOutput(0, "ok".getBytes(StandardCharsets.UTF_8), "");

        var resp = linuxFs.execute(RuntimeContext.empty(), "grep -E \"\\.env\" f", 30);

        // Linux execve 原样传 argv、双引号合法——守卫必须 os.name 条件化，不得一刀切
        assertThat(exec.commands).containsExactly("grep -E \"\\.env\" f");
        assertThat(resp.exitCode()).isEqualTo(0);
    }
}
