package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentscope.DockerExecFilesystem;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 工作区文件内容只读工具（#47 助理资产）：与文件模式「点看」端点同口径——
 * isViewable 判定先行（非交付物/机密/逃逸路径拒绝且不触容器）、退出码语义
 * （1 不存在 / 2 超限 / 0 首行字节大小 + 余文正文）；模型上下文护栏（超长截断
 * 明示）；工具面 readOnly。
 */
class ReadWorkspaceFileToolTest {

    /** 执行缝替身：按命令回放退出码语义。 */
    private static final class ScriptedExec implements DockerExecFilesystem.ExecCommand {

        private final DockerExecFilesystem.ExecOutput output;
        private String command;

        private ScriptedExec(DockerExecFilesystem.ExecOutput output) {
            this.output = output;
        }

        @Override
        public DockerExecFilesystem.ExecOutput run(String command, byte[] stdin) {
            this.command = command;
            return output;
        }
    }

    private static ToolResultBlock call(ReadWorkspaceFileTool tool, String path) {
        Map<String, Object> input = path != null ? Map.of("path", path) : Map.of();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new io.agentscope.core.message.ToolUseBlock(
                        "tc-1", ReadWorkspaceFileTool.NAME, input, null))
                .input(input)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @Test
    void given_text_file_when_call_then_content_after_size_line() {
        ScriptedExec exec = new ScriptedExec(new DockerExecFilesystem.ExecOutput(0,
                "18\nadmin / 123456\n".getBytes(StandardCharsets.UTF_8), ""));
        ReadWorkspaceFileTool tool = new ReadWorkspaceFileTool(exec);

        ToolResultBlock result = call(tool, "README.md");

        // 命令与端点同源（isViewable 已过 → contentCommand）
        assertThat(exec.command).isEqualTo(ProjectFiles.contentCommand("README.md"));
        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).isEqualTo("admin / 123456\n");
    }

    @Test
    void given_non_viewable_path_when_call_then_rejected_without_touching_container() {
        // 机密（根级 .env）/ 逃逸路径：判定层拒绝，容器不被触达（命令未构造）
        ScriptedExec exec = new ScriptedExec(new DockerExecFilesystem.ExecOutput(0,
                "0\n".getBytes(StandardCharsets.UTF_8), ""));
        ReadWorkspaceFileTool tool = new ReadWorkspaceFileTool(exec);

        assertThat(call(tool, ".env").getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(call(tool, "../etc/passwd").getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(exec.command).isNull();
    }

    @Test
    void given_missing_or_oversized_or_broken_when_call_then_error_semantics() {
        assertThat(call(toolOf(1, ""), "README.md").getState())
                .isEqualTo(ToolResultState.ERROR); // 不存在
        assertThat(call(toolOf(2, ""), "README.md").getState())
                .isEqualTo(ToolResultState.ERROR); // 超 1 MiB
        assertThat(call(toolOf(125, ""), "README.md").getState())
                .isEqualTo(ToolResultState.ERROR); // 容器故障
        assertThat(call(toolOf(0, "nosizeline-without-newline"), "README.md").getState())
                .isEqualTo(ToolResultState.ERROR); // 首行缺失（防御位）
        assertThat(call(toolOf(0, "1\nx"), null).getState())
                .isEqualTo(ToolResultState.ERROR); // 缺 path 入参
    }

    @Test
    void given_overlong_content_when_call_then_truncated_with_marker() {
        String big = "长".repeat(40_000);
        ReadWorkspaceFileTool tool = toolOf(0, String.valueOf(big.length()) + "\n" + big);

        ToolResultBlock result = call(tool, "docs/big.md");

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("已截断").hasSizeLessThan(40_000);
    }

    private static ReadWorkspaceFileTool toolOf(int exitCode, String stdout) {
        return new ReadWorkspaceFileTool(new ScriptedExec(new DockerExecFilesystem.ExecOutput(
                exitCode, stdout.getBytes(StandardCharsets.UTF_8), "")));
    }
}
