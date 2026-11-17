package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.billing.application.PoolTopupAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.project.domain.aggregate.GenerationSegment;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ForkState;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.GenerationSegmentRepository;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 岔路口卡与派发门（#542 访谈②，ADR-0048 切片②＋ADR-0049 并入）：
 * 岔路口状态位（试用态自然收口置 PENDING、持久可回——关页回来卡仍在）、
 * 派发门读面（岔路口卡＝试用态∧PENDING∧首建未点火；PRD 确认按钮＝付费态∧PRD
 * 在∧首建未点火）与付费轨 PRD 确认（原动作形态恢复——确认即派发首个构建、
 * 点击幂等〔重复确认 PRJ_017〕、确认不写版本行〔版本表构建基准序零改动——
 * 记行归起跑单点〕）。快道不走本缝（MainAgent 收口观测直派，不出卡不确认）。
 */
@IntegrationTest
class BuildGateAppServiceTest {

    private static final long OWNER = 3897654321098765401L;

    @Autowired
    private BuildGateAppService gateService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private GenerationSegmentRepository generationSegments;

    @Autowired
    private BillingAppService billingAppService;

    @Autowired
    private PoolTopupAppService topupService;

    @Autowired
    private CodingRunTrack codingRunTrack;

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
    void seed() {
        ModelTierSeed.ensureChannelKey(jdbcTemplate);
        // 主智能体轮与编码 run 全链默认成功脚本（确认即派发的全链走真轨道）
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return command != null ? new AgentReply(command.runId(), "完成") : null;
        });
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(sessionExecutor).submitGeneration(any(), any());
        // exec 全成功脚本（资产就位/收口探针/git 成版）；PRD 读（起跑记行快照源）回
        // 「stat epoch 首行 + cat 正文」形（READ_PRD_COMMAND 契约）——记行可落
        // （版本零改动回归的断言面）
        when(workspaceLifecycleAppService.exec(any(), any())).thenAnswer(invocation -> {
            WorkspaceExecCommand command = invocation.getArgument(1);
            return command != null && command.command().contains("PRD.md")
                    ? new ExecResultResponse("1731820000\n# PRD 岔路口确认门回归", "", 0)
                    : new ExecResultResponse("", "", 0);
        });
        when(workspaceLifecycleAppService.exposePreview(any()))
                .thenReturn(java.net.URI.create("http://localhost:30080"));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_generation_segments");
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_demo_versions");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
        jdbcTemplate.update("DELETE FROM bil_balance_holds");
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
    }

    /** 试用态 PRD 已产出的系统终点项目（岔路口前置形态）。 */
    private Long persistedTrialPrdProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("岔路口项目", null,
                ProjectEndpointType.SYSTEM, Long.parseLong(workspaceId), OWNER, null));
        project.markPrdProduced();
        return projectRepository.save(project).getId();
    }

    /** 付费态 PRD 已产出的系统终点项目（项目池入账即选源＋门①翻转）。 */
    private Long persistedPaidPrdProject(String workspaceId) {
        Long projectId = persistedTrialPrdProject(workspaceId);
        topupService.topup(null, projectId, 10_000, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.FORK);
        return projectId;
    }

    /** 轨道表现行计划种子（确认派发读表内计划——不走补产链）。 */
    private void seedPlan(Long projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        LocalDateTime anchor = project.getPrdProducedAt();
        generationSegments.save(GenerationSegment.pending(projectId, 0,
                GenerationAppService.STAGE0_TITLE, anchor));
        generationSegments.save(GenerationSegment.pending(projectId, 1,
                "用户能注册登录", anchor));
    }

    // ---------- 岔路口状态位（自然收口置位、持久可回） ----------

    @Test
    void given_trial_natural_close_when_ensure_fork_pending_then_card_visible_and_persistent() {
        // 灵魂例（AC①）：试用态自然收口→岔路口卡在场；关页回来（重新读库）卡仍在
        Long projectId = persistedTrialPrdProject("9901");

        boolean marked = gateService.ensureForkPending(projectId);

        assertThat(marked).isTrue();
        BuildGateAppService.BuildGateView gate = gateService.gateOf(projectId);
        assertThat(gate.forkCardPending()).isTrue();
        assertThat(gate.prdConfirmable()).isFalse(); // 试用态无确认门（岔路口即点火动作）
        // 持久可回：落库事实独立可查（fork_state＝PENDING code 1——Integer code 落库）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT fork_state, generation_requested_at FROM prj_projects WHERE id = ?",
                projectId);
        assertThat(row.get("fork_state")).isEqualTo(ForkState.PENDING.getCode());
        assertThat(row.get("generation_requested_at")).isNull();
        // 幂等：意见修订轮再收口照旧在场（不拒绝不漂移）
        assertThat(gateService.ensureForkPending(projectId)).isFalse();
        assertThat(gateService.gateOf(projectId).forkCardPending()).isTrue();
    }

    @Test
    void given_paid_project_when_ensure_fork_pending_then_no_card() {
        // 岔路口是试用态三岔（ADR-0049）：付费态不出卡——确认门读面在场
        Long projectId = persistedPaidPrdProject("9902");

        assertThat(gateService.ensureForkPending(projectId)).isFalse();

        BuildGateAppService.BuildGateView gate = gateService.gateOf(projectId);
        assertThat(gate.forkCardPending()).isFalse();
        assertThat(gate.prdConfirmable()).isTrue(); // 付费态∧PRD 在∧首建未点火
    }

    @Test
    void given_no_prd_when_ensure_fork_pending_then_no_card() {
        // PRD 未产出＝访谈未收口——无卡无门
        Project project = projectRepository.save(Project.create("访谈中项目", null,
                ProjectEndpointType.SYSTEM, 9903L, OWNER, null));

        assertThat(gateService.ensureForkPending(project.getId())).isFalse();
        BuildGateAppService.BuildGateView gate = gateService.gateOf(project.getId());
        assertThat(gate.forkCardPending()).isFalse();
        assertThat(gate.prdConfirmable()).isFalse();
    }

    @Test
    void given_design_endpoint_when_ensure_fork_pending_then_no_card() {
        // 设计类终点不走岔路口（设计轨触发律不改：PRD 收口起设计过程、全部定稿起构建）
        Project project = projectRepository.save(Project.create("设计项目", null,
                ProjectEndpointType.SYSTEM_DESIGN, 9904L, OWNER, null));
        project.markPrdProduced();
        Long projectId = projectRepository.save(project).getId();

        assertThat(gateService.ensureForkPending(projectId)).isFalse();
        assertThat(gateService.gateOf(projectId).forkCardPending()).isFalse();
        assertThat(gateService.gateOf(projectId).prdConfirmable()).isFalse(); // 设计轨首建由定稿触发
    }

    @Test
    void given_first_build_requested_when_gate_then_neither_card_nor_button() {
        // 首建点火后：卡不复现（失败重试走「继续生成」）、确认门不复现（确认不重复）
        Long trialId = persistedTrialPrdProject("9905");
        gateService.ensureForkPending(trialId);
        Long paidId = persistedPaidPrdProject("9906");

        projectRepository.findById(trialId).ifPresent(p -> {
            p.markFirstBuildRequested();
            projectRepository.save(p);
        });
        projectRepository.findById(paidId).ifPresent(p -> {
            p.markFirstBuildRequested();
            projectRepository.save(p);
        });

        assertThat(gateService.gateOf(trialId).forkCardPending()).isFalse();
        assertThat(gateService.gateOf(paidId).prdConfirmable()).isFalse();
    }

    // ---------- 付费轨 PRD 确认（原动作形态恢复：确认即派发首建） ----------

    @Test
    void given_paid_prd_unrequested_when_confirm_then_first_build_dispatched_once() {
        // 灵魂例（AC②）：确认即派发首建（点火时点落位）＋版本机制零改动——确认
        // 不写版本行，起跑记行单点照旧（v1＝构建基准序首行）
        Long projectId = persistedPaidPrdProject("9907");
        seedPlan(projectId);

        GenerationAppService.GenerationRun run = gateService.confirmFirstBuild(projectId);

        assertThat(run).isNotNull();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT generation_requested_at, fork_state FROM prj_projects WHERE id = ?",
                projectId);
        assertThat(row.get("generation_requested_at")).isNotNull(); // 点火事实
        // 版本表零改动回归：仅起跑记行单行（确认动作本身不写版本行——确认即成版不复活）
        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
                "SELECT version_no FROM prj_prd_versions WHERE project_id = ?", projectId);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("version_no")).isEqualTo(1);
        // 幂等（AC②重复点不重复派发）：已点火即拒绝 PRJ_017——不重复确认
        assertThatThrownBy(() -> gateService.confirmFirstBuild(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
        assertThat(jdbcTemplate.queryForList(
                "SELECT version_no FROM prj_prd_versions WHERE project_id = ?", projectId))
                .hasSize(1); // 拒绝路径不落第二行
    }

    @Test
    void given_trial_when_confirm_then_prj_084() {
        // 确认门属付费轨首建——试用轨点火动作是岔路口「立即生成」，不走确认端点
        Long projectId = persistedTrialPrdProject("9908");

        assertThatThrownBy(() -> gateService.confirmFirstBuild(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PRD_CONFIRMATION_NOT_PAID.message());
        assertThat(jdbcTemplate.queryForMap(
                "SELECT generation_requested_at FROM prj_projects WHERE id = ?", projectId)
                .get("generation_requested_at"))
                .isNull(); // 拒绝不点火
    }

    @Test
    void given_no_prd_when_confirm_then_prj_018() {
        Project project = projectRepository.save(Project.create("访谈中项目", null,
                ProjectEndpointType.SYSTEM, 9909L, OWNER, null));
        topupService.topup(null, project.getId(), 10_000, null, null);
        billingAppService.flipToPaid(project.getId(), FlipGate.FORK);

        assertThatThrownBy(() -> gateService.confirmFirstBuild(project.getId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_PRD_NOT_PRODUCED.message());
    }

    @Test
    void given_archived_when_confirm_then_prj_013() {
        Long projectId = persistedPaidPrdProject("9910");
        projectRepository.findById(projectId).ifPresent(p -> {
            p.archive();
            projectRepository.save(p);
        });

        assertThatThrownBy(() -> gateService.confirmFirstBuild(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
    }

    @Test
    void given_generation_in_flight_when_confirm_then_prj_017() {
        // 点火竞态兜底：轨道在途（点火落位前的并发窗口）——startGeneration 在途守卫
        Long projectId = persistedPaidPrdProject("9911");
        seedPlan(projectId);
        codingRunTrack.begin(projectId);
        try {
            assertThatThrownBy(() -> gateService.confirmFirstBuild(projectId))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
        }
        finally {
            codingRunTrack.end(projectId);
        }
    }
}
