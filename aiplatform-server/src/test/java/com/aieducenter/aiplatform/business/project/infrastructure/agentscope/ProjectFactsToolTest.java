package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 项目事实查询工具（#47 助理资产）：事实清单拼装——系统访问地址以预览端口映射
 * 为准（「我后台的地址」的正答）、未产出/未生成如实呈现（不装样子）；工作区
 * 句柄解析失败如实报「暂不可知」（不编造地址）；工具面 readOnly。
 */
class ProjectFactsToolTest {

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService =
            mock(WorkspaceLifecycleAppService.class);

    @Test
    void given_registration_shape_when_inspected_then_read_only_no_input() {
        assertThat(tool().getName()).isEqualTo(ProjectFactsTool.NAME);
        assertThat(tool().isReadOnly()).isTrue();
        assertThat(tool().getParameters()).containsKeys("type", "properties");
    }

    @Test
    void given_generated_project_when_call_then_fact_sheet_with_preview_url() {
        Project project = Project.create("品牌官网", null, 42L, 77L);
        project.markPrdProduced();
        project.markGenerated();
        when(projectRepository.findByWorkspaceId(42L)).thenReturn(Optional.of(project));
        when(workspaceLifecycleAppService.handleOf("42")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 32168));

        ToolResultBlock result = call(tool());

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        String facts = resultText(result);
        assertThat(facts).contains("品牌官网").contains("进行中")
                .contains("http://localhost:32168/")
                .contains("系统首次生成时间");
    }

    @Test
    void given_fresh_project_when_call_then_not_yet_states_stated_as_such() {
        // 未产出/未生成如实呈现（「查不到就答不知，不编造」的事实面）
        when(projectRepository.findByWorkspaceId(43L)).thenReturn(Optional.of(
                Project.create("新项目", null, 43L, 77L)));
        when(workspaceLifecycleAppService.handleOf("43")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("43"), "ws-43-dev", "net-43", 32169));

        String facts = resultText(call(toolOf("43")));

        assertThat(facts).contains("尚未产出").contains("尚未生成");
    }

    @Test
    void given_handle_failure_when_call_then_url_stated_unknown_not_fabricated() {
        when(projectRepository.findByWorkspaceId(44L)).thenReturn(Optional.of(
                Project.create("环境异常项目", null, 44L, 77L)));
        when(workspaceLifecycleAppService.handleOf("44"))
                .thenThrow(new IllegalStateException("工作区不在"));

        String facts = resultText(call(toolOf("44")));

        assertThat(facts).contains("暂不可知");
    }

    @Test
    void given_no_project_under_workspace_when_call_then_error_result() {
        when(projectRepository.findByWorkspaceId(45L)).thenReturn(Optional.empty());

        assertThat(call(toolOf("45")).getState()).isEqualTo(ToolResultState.ERROR);
    }

    // ---------- 内部 ----------

    private ProjectFactsTool tool() {
        return toolOf("42");
    }

    private ProjectFactsTool toolOf(String workspaceId) {
        return new ProjectFactsTool(workspaceId, projectRepository,
                workspaceLifecycleAppService);
    }

    private static ToolResultBlock call(ProjectFactsTool tool) {
        Map<String, Object> input = Map.of();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new io.agentscope.core.message.ToolUseBlock(
                        "tc-1", ProjectFactsTool.NAME, input, null))
                .input(input)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
