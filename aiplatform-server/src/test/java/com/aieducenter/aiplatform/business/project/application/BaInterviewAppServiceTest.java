package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * BA 访谈编排：对话命令的会话寻址（projectId → ba-{projectId} 稳定绑定，每轮
 * 都续此会话）、计量归属（role=BA 维度）、role-assigned 帧序（engine=agentscope）。
 */
@SpringBootTest
class BaInterviewAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private BaInterviewAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ChatAgentAppService chatAgentAppService;

    @MockitoBean
    private AgentStreamAppService streamAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_project_when_interview_turn_then_command_bound_to_ba_session() {
        Long projectId = persistedProject("9700");
        when(chatAgentAppService.converseAsync(any())).thenReturn(true);

        BaInterviewAppService.InterviewRun run = appService.runInterviewTurn(projectId, "做一个官网");

        // 对话命令全要素：BA 会话稳定绑定 + owner 寻址 + 角色卡模型/人格 + 计量归属 + 项目工作区 + 流关联
        ArgumentCaptor<ChatAgentCommand> command = ArgumentCaptor.forClass(ChatAgentCommand.class);
        verify(chatAgentAppService).converseAsync(command.capture());
        ChatAgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo("做一个官网");
        assertThat(value.sessionId()).isEqualTo("ba-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).contains("ask_user");
        assertThat(value.modelString()).isEqualTo(RolePreset.BA.chatModelString());
        assertThat(value.workspaceId()).isEqualTo("9700");
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).containsEntry("role", "BA");
        assertThat(run.accepted()).isTrue();
    }

    @Test
    void given_turn_when_run_then_role_assigned_first_then_converse() {
        Long projectId = persistedProject("9701");
        when(chatAgentAppService.converseAsync(any())).thenReturn(true);

        appService.runInterviewTurn(projectId, "梳理需求");

        // 帧序 role-assigned（engine=agentscope）→ task-start（converseAsync 内）
        InOrder order = inOrder(streamAppService, chatAgentAppService);
        order.verify(streamAppService).publish(org.mockito.ArgumentMatchers.eq(
                        AgentEventTypes.ROLE_ASSIGNED),
                org.mockito.ArgumentMatchers.anyMap());
        order.verify(chatAgentAppService).converseAsync(any());
    }

    @Test
    void given_submission_rejected_when_turn_then_not_accepted() {
        Long projectId = persistedProject("9702");
        when(chatAgentAppService.converseAsync(any())).thenReturn(false);

        BaInterviewAppService.InterviewRun run = appService.runInterviewTurn(projectId, "梳理需求");

        // 提交被拒（复活后与关闸竞态的极端窗口）accepted=false——「被拒的 run 没有
        // 创建事实」
        assertThat(run.accepted()).isFalse();
    }

    @Test
    void given_missing_project_when_turn_then_prj_001() {
        assertThatThrownBy(() -> appService.runInterviewTurn(-1L, "梳理需求"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 测试数据 ----------

    private Long persistedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("访谈项目", null, "agentscope",
                Long.parseLong(workspaceId), OWNER));
        return project.getId();
    }
}
