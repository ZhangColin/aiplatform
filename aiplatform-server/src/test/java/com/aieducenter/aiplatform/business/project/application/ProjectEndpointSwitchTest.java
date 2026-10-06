package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.hutool.core.collection.CollUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.project.application.dto.command.SwitchEndpointTypeCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.DesignScopeType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 终点类型切换链路（#285，ADR-0024/0025）：设置 tab 终点控件→属性落库→主智能体
 * 切换重产轮（PRD 清单章随终点类型走形重产/访谈期转向通告）——验收面＝
 * <ol>
 * <li>切换后 PRD 已产出项目收到<b>重产指令</b>（清单章走形＋终点语义＋savePrd
 * 必传口径），访谈期项目收到<b>转向通告</b>；</li>
 * <li>设计范围：系统＋设计落库（作用域＋勾选标签），设计主线不落（防两处正本）
 * ——重产指令两者都携范围锚定；</li>
 * <li>守卫组：同目标幂等无操作、归档关闭、下单冻结（取消解冻）、挂起问答同步
 * 409、系统→设计类缺选范围 PRJ_040、勾选空集 PRJ_041；</li>
 * <li>生成轨道对设计类终点收口（PRJ_042 / 收口自动静默不派）；回到系统形经
 * 补产链自动起生成的轨内衔接不因切换断裂。</li>
 * </ol>
 */
@IntegrationTest
class ProjectEndpointSwitchTest {

    private static final long OWNER = 3897654321098765401L;

    @Autowired
    private ProjectLifecycleAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private GenerationAppService generationAppService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private EventsAppService eventsAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private KnowledgePort knowledgePort;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @BeforeEach
    void agentReplies() {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command != null) {
                converseCommands.add(command);
                return new AgentReply(command.runId(), "好的");
            }
            return null;
        });
    }

    /** 主智能体会话的 converse 命令流水（prompt 断言面；shift 轮总是首条）。 */
    private final List<AgentCommand> converseCommands = CollUtil.newArrayList();

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM prj_generation_segments");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_system_project_with_prd_when_switch_to_design_then_chapter_reproduction_directive() {
        Long projectId = persistedProject(/* prdProduced= */ true, /* generated= */ false);
        givenSessionExecutorRunsInline();

        ProjectDetailResponse detail = appService.switchEndpoint(projectId, new SwitchEndpointTypeCommand(
                ProjectEndpointType.DESIGN, DesignScopeType.SELECTED_PAGES,
                List.of("首页：展示产品与入口", "订单管理：下单与查看订单")));

        assertThat(detail.endpointType()).isEqualTo(ProjectEndpointType.DESIGN);
        assertThat(detail.designScope()).isNull(); // 设计主线范围由 PRD 设计物清单章承载
        assertThat(projectOf(projectId).getEndpointType()).isEqualTo(ProjectEndpointType.DESIGN);
        // 重产指令：清单章重写为设计物清单＋范围锚定＋savePrd 必传（summary 口径在技能/协议）
        assertThat(shiftPromptOf(projectId))
                .contains("切换为「设计」")
                .contains("设计物清单")
                .contains("用途、尺寸、风格要点与验收要点")
                .contains("首页：展示产品与入口")
                .contains("savePrd");
    }

    @Test
    void given_system_project_with_prd_when_switch_to_system_design_then_constraints_directive_and_scope_persisted() {
        Long projectId = persistedProject(true, false);
        givenSessionExecutorRunsInline();

        ProjectDetailResponse detail = appService.switchEndpoint(projectId, new SwitchEndpointTypeCommand(
                ProjectEndpointType.SYSTEM_DESIGN, DesignScopeType.ALL_PAGES, null));

        assertThat(detail.endpointType()).isEqualTo(ProjectEndpointType.SYSTEM_DESIGN);
        assertThat(detail.designScope()).isNotNull();
        assertThat(detail.designScope().type()).isEqualTo(DesignScopeType.ALL_PAGES);
        // 系统形不动：功能清单章保留、设计硬约束入关键约束、不另立设计物清单
        assertThat(shiftPromptOf(projectId))
                .contains("系统＋设计")
                .contains("功能清单章不动")
                .contains("关键约束")
                .contains("全部页面");
    }

    @Test
    void given_system_project_when_switch_to_system_design_selected_pages_then_scope_persisted() {
        // #289 修回归（#285 遗留缺陷）：系统＋设计·勾选页面的范围标签 jsonb 落库——
        // 原映射缺 @JdbcTypeCode(JSON)，勾选形切换落库即炸（既有测试只覆盖全部页面
        // 与设计主线的空范围，未触达此路径）
        Long projectId = persistedProject(true, false);
        givenSessionExecutorRunsInline();

        ProjectDetailResponse detail = appService.switchEndpoint(projectId, new SwitchEndpointTypeCommand(
                ProjectEndpointType.SYSTEM_DESIGN, DesignScopeType.SELECTED_PAGES,
                List.of("首页：展示产品与入口", "订单管理：下单与查看订单")));

        assertThat(detail.designScope().type()).isEqualTo(DesignScopeType.SELECTED_PAGES);
        assertThat(detail.designScope().pages()).containsExactly(
                "首页：展示产品与入口", "订单管理：下单与查看订单");
        // 回读库事实（jsonb 落库真通）
        assertThat(projectOf(projectId).designScope().pages()).containsExactly(
                "首页：展示产品与入口", "订单管理：下单与查看订单");
    }

    @Test
    void given_interview_project_when_switch_to_design_then_steer_notice_without_scope() {
        Long projectId = persistedProject(false, false); // 访谈期：无 PRD 无功能清单可勾
        givenSessionExecutorRunsInline();

        ProjectDetailResponse detail = appService.switchEndpoint(projectId,
                new SwitchEndpointTypeCommand(ProjectEndpointType.DESIGN, null, null));

        assertThat(detail.endpointType()).isEqualTo(ProjectEndpointType.DESIGN);
        assertThat(shiftPromptOf(projectId))
                .contains("终点类型已定为「设计」")
                .contains("设计资产包")
                .doesNotContain("savePrd"); // 访谈期无 PRD 可重产——转向通告非重产指令
    }

    @Test
    void given_design_project_with_prd_when_switch_back_to_system_then_system_form_directive() {
        Long projectId = persistedProject(true, false);
        projectRepository.save(switched(projectId, ProjectEndpointType.DESIGN));
        givenSessionExecutorRunsInline();

        appService.switchEndpoint(projectId,
                new SwitchEndpointTypeCommand(ProjectEndpointType.SYSTEM, null, null));

        // 转回系统形：清单章重写为功能清单；同首产序（savePrd＋切片计划→生成轨）
        assertThat(shiftPromptOf(projectId))
                .contains("由「设计」切换为「系统」")
                .contains("功能清单")
                .contains("saveBuildPlan");
    }

    @Test
    void given_system_project_with_prd_when_switch_without_scope_then_prj_040() {
        Long projectId = persistedProject(true, false);

        assertThatThrownBy(() -> appService.switchEndpoint(projectId,
                new SwitchEndpointTypeCommand(ProjectEndpointType.DESIGN, null, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.ENDPOINT_SCOPE_REQUIRED.message());
        assertThat(projectOf(projectId).getEndpointType()).isEqualTo(ProjectEndpointType.SYSTEM); // 拒绝即零副作用
        verify(agentClient, never()).converse(any(), any());
    }

    @Test
    void given_selected_scope_with_blank_pages_when_switch_then_prj_041() {
        Long projectId = persistedProject(true, false);

        assertThatThrownBy(() -> appService.switchEndpoint(projectId,
                new SwitchEndpointTypeCommand(ProjectEndpointType.SYSTEM_DESIGN,
                        DesignScopeType.SELECTED_PAGES, List.of(" ", ""))))
                .isInstanceOf(DomainException.class) // 聚合抛出（同改名 PRJ_005 口径）
                .hasMessageContaining(ProjectMessage.DESIGN_SCOPE_PAGES_REQUIRED.message());
        assertThat(projectOf(projectId).getEndpointType()).isEqualTo(ProjectEndpointType.SYSTEM);
    }

    @Test
    void given_same_target_when_switch_then_noop_without_turn() {
        Long projectId = persistedProject(true, false);
        givenSessionExecutorRunsInline();

        ProjectDetailResponse detail = appService.switchEndpoint(projectId,
                new SwitchEndpointTypeCommand(ProjectEndpointType.SYSTEM, null, null));

        assertThat(detail.endpointType()).isEqualTo(ProjectEndpointType.SYSTEM);
        verify(agentClient, never()).converse(any(), any()); // 同目标幂等：无操作不派轮
    }

    @Test
    void given_archived_project_when_switch_then_prj_013() {
        Long projectId = persistedProject(false, false);
        Project project = projectOf(projectId);
        project.archive();
        projectRepository.save(project);

        assertThatThrownBy(() -> appService.switchEndpoint(projectId,
                new SwitchEndpointTypeCommand(ProjectEndpointType.DESIGN, null, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
    }

    @Test
    void given_active_order_when_switch_then_ord_006_frozen() {
        Long projectId = persistedProject(true, false);
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 1, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9931L, projectId);

        assertThatThrownBy(() -> appService.switchEndpoint(projectId, new SwitchEndpointTypeCommand(
                ProjectEndpointType.SYSTEM_DESIGN, DesignScopeType.ALL_PAGES, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_FROZEN.message());
        assertThat(projectOf(projectId).getEndpointType()).isEqualTo(ProjectEndpointType.SYSTEM); // 下单即冻结
    }

    @Test
    void given_pending_question_when_switch_then_prj_024_before_mutation() {
        Long projectId = persistedProject(true, false);
        when(agentClient.hasAskingToolCall(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> appService.switchEndpoint(projectId, new SwitchEndpointTypeCommand(
                ProjectEndpointType.DESIGN, DesignScopeType.ALL_PAGES, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());
        assertThat(projectOf(projectId).getEndpointType()).isEqualTo(ProjectEndpointType.SYSTEM); // 拒绝即零副作用
    }

    @Test
    void given_design_project_with_prd_when_shift_turn_closes_then_no_generation_dispatched() {
        // 设计类终点收口（#285）：重产轮收口不自动派系统生成（设计过程编排归后续票）
        Long projectId = persistedProject(true, false);
        givenSessionExecutorRunsInline();

        appService.switchEndpoint(projectId, new SwitchEndpointTypeCommand(
                ProjectEndpointType.DESIGN, DesignScopeType.ALL_PAGES, null));

        // 只有一轮主智能体对话（切换重产）；无 coder 会话提交（生成轨道不派）
        verify(agentClient, never()).converse(argThat(command ->
                        command != null && command.sessionId().startsWith("coder-")), any());
    }

    @Test
    void given_design_project_when_generate_then_prj_042() {
        Long projectId = persistedProject(true, false);
        Project project = projectOf(projectId);
        project.switchEndpoint(ProjectEndpointType.DESIGN, null);
        projectRepository.save(project);

        assertThatThrownBy(() -> generationAppService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_DESIGN_ENDPOINT.message());
    }

    // ---------- 内部 ----------

    /** PRD 已产出（可未生成——生成中断态）形态的项目。 */
    private Long persistedProject(boolean prdProduced, boolean generated) {
        Project project = projectRepository.save(Project.create("终点切换项目", null,
                970900L + System.nanoTime() % 1000, OWNER));
        if (prdProduced) {
            project.markPrdProduced();
        }
        if (generated) {
            project.markGenerated();
        }
        return projectRepository.save(project).getId();
    }

    /** 已切到目标终点的库内项目（守卫用例的前置事实）。 */
    private Project switched(Long projectId, ProjectEndpointType target) {
        Project project = projectOf(projectId);
        project.switchEndpoint(target, null);
        return project;
    }

    private Project projectOf(Long projectId) {
        return projectRepository.findById(projectId).orElseThrow();
    }

    /** 切换重产轮的主智能体 prompt 断言面（main 会话首条 converse 命令的 prompt 腿
     * ——转系统形会经补产链再派计划轮，shift 轮总是首条）。 */
    private String shiftPromptOf(Long projectId) {
        AgentCommand command = converseCommands.stream()
                .filter(each -> each.sessionId()
                        .equals(MainAgentAppService.SESSION_PREFIX + projectId))
                .findFirst().orElseThrow();
        return command.prompt();
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
    }
}
