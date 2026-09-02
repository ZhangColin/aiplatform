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
 * 工作区文件树只读工具（#47 助理资产）：执行命令与文件模式端点同源
 * （{@code ProjectFiles.listCommand}，交付文件视图口径）；输出为解析后的清单行；
 * 容器故障如实回错误结果（模型可见可重试）；工具面 readOnly。
 */
class ListWorkspaceFilesToolTest {

    /** 执行缝替身：回放固定输出并捕获命令。 */
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

    private static ListWorkspaceFilesTool toolOf(DockerExecFilesystem.ExecOutput output) {
        return new ListWorkspaceFilesTool(new ScriptedExec(output));
    }

    private static ToolResultBlock call(ListWorkspaceFilesTool tool) {
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new io.agentscope.core.message.ToolUseBlock(
                        "tc-1", ListWorkspaceFilesTool.NAME, Map.of(), null))
                .input(Map.of())
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @Test
    void given_registration_shape_when_inspected_then_read_only_no_input() {
        assertThat(toolOf(ok("")).getName()).isEqualTo(ListWorkspaceFilesTool.NAME);
        assertThat(toolOf(ok("")).isReadOnly()).isTrue(); // 只读面：无任何写类事件的结构声明
        assertThat(toolOf(ok("")).getParameters()).containsKeys("type", "properties");
    }

    @Test
    void given_find_output_when_call_then_parsed_listing_from_same_command_as_endpoint() {
        ScriptedExec exec = new ScriptedExec(ok("12\tdocs/PRD.md\n2048\tREADME.md\n"));
        ListWorkspaceFilesTool tool = new ListWorkspaceFilesTool(exec);

        ToolResultBlock result = call(tool);

        assertThat(exec.command).isEqualTo(ProjectFiles.listCommand());
        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("docs/PRD.md（12 字节）")
                .contains("README.md（2048 字节）");
    }

    @Test
    void given_empty_workspace_when_call_then_stated_as_empty() {
        ToolResultBlock result = call(toolOf(ok("")));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("暂无交付文件");
    }

    @Test
    void given_container_failure_when_call_then_error_result() {
        ToolResultBlock result = call(toolOf(new DockerExecFilesystem.ExecOutput(125,
                new byte[0], "docker daemon 不在")));

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(String.valueOf(result.getOutput())).contains("docker daemon 不在");
    }

    private static DockerExecFilesystem.ExecOutput ok(String stdout) {
        return new DockerExecFilesystem.ExecOutput(0,
                stdout.getBytes(StandardCharsets.UTF_8), "");
    }
}
