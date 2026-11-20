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
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
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
    private InterviewQuotaAppService interviewQuotaAppService;

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
        ModelConfigSeed.ensureChannelKey(jdbcTemplate);
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
        // #543 风格物化写面缺省桩（确认即派发的首个构建＝平台确定性写基座四件）
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("123", "", 0));
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
        assertThat(gate.forkSettleDue()).isFalse(); // 源未定——无 settle 信号（点了才选定）
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

    // ---------- 「继续讨论」引导流（#544 访谈③，翻转门①——选源＋按池判定） ----------

    @Test
    void given_account_funded_when_continue_by_account_then_source_selected_and_flipped() {
        // 灵魂例（AC①）：访客 M01 登录＋项目平移（归登录编排）后选「由我的账户
        // 付费」→ 账户有余额 → 扣减源一次选定＋翻转回访谈（FlipGate.FORK 门①留痕）
        Long projectId = persistedTrialPrdProject("9920");
        gateService.ensureForkPending(projectId);
        topupService.topup(OWNER, null, 10_000, null, null);

        BuildGateAppService.ForkGuideView view =
                gateService.continueDiscussion(projectId, DeductionSource.ACCOUNT, OWNER);

        assertThat(view.flipped()).isTrue();
        assertThat(view.rechargeRequired()).isFalse();
        assertThat(view.deductionSource()).isEqualTo(DeductionSource.ACCOUNT.getCode());
        // 落库事实：源选定（账户定格）＋翻转锚＋门①留痕
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_account_id, flipped_at, flip_gate"
                        + " FROM bil_project_billing WHERE project_id = ?",
                projectId);
        assertThat(row.get("deduction_source")).isEqualTo(DeductionSource.ACCOUNT.getCode());
        assertThat(row.get("source_account_id")).isEqualTo(OWNER);
        assertThat(row.get("flipped_at")).isNotNull();
        assertThat(row.get("flip_gate")).isEqualTo(FlipGate.FORK.getCode());
        // 卡随翻转熄灭（读面派生——试用态不再成立）；确认门也不在场（首建未点火）
        assertThat(gateService.gateOf(projectId).forkCardPending()).isFalse();
        assertThat(gateService.gateOf(projectId).prdConfirmable()).isTrue();
        assertThat(gateService.gateOf(projectId).forkSettleDue()).isFalse(); // 已翻转无 settle 信号
        // 翻转后五问额度解除（AC——协议分轨回原的读面：付费态额度读口恒空）
        assertThat(interviewQuotaAppService.quotaOf(projectId)).isNull();
    }

    @Test
    void given_account_empty_when_continue_by_account_then_recharge_guided_and_source_locked() {
        // 灵魂例（AC②）：账户无余额 → 引导充值——源已一次选定（到账后重判即
        // 翻转、无需重走选择）、不翻转、卡留岔路口（中途放弃随时改选「立即生成」）
        Long projectId = persistedTrialPrdProject("9921");
        gateService.ensureForkPending(projectId);
        topupService.topup(OWNER, null, 10_000, null, null);
        // 充后扣成零余额（入账口只收正数——直接落池面调平）
        jdbcTemplate.update("UPDATE bil_pools SET balance = 0 WHERE account_id = ?", OWNER);

        BuildGateAppService.ForkGuideView view =
                gateService.continueDiscussion(projectId, DeductionSource.ACCOUNT, OWNER);

        assertThat(view.flipped()).isFalse();
        assertThat(view.rechargeRequired()).isTrue();
        assertThat(view.deductionSource()).isEqualTo(DeductionSource.ACCOUNT.getCode());
        assertThat(view.sourcePoolBalance()).isEqualTo(0L);
        // 源已选定（定格账户）＋未翻转
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_account_id, flipped_at FROM bil_project_billing WHERE project_id = ?",
                projectId);
        assertThat(row.get("deduction_source")).isEqualTo(DeductionSource.ACCOUNT.getCode());
        assertThat(row.get("source_account_id")).isEqualTo(OWNER);
        assertThat(row.get("flipped_at")).isNull();
        // 卡留岔路口（试用态 PENDING 不回清——随时改选「立即生成」）
        assertThat(gateService.gateOf(projectId).forkCardPending()).isTrue();
        assertThat(gateService.gateOf(projectId).forkSettleDue()).isFalse(); // 池零不 settle
        // 源重选拒绝（BILL_008——不可自助改口）：改选项目源吃重复选定错
        // （聚合守卫先行＝DomainException 直抛，message 同源 BillingMessage）
        assertThatThrownBy(() -> billingAppService.selectSource(projectId, DeductionSource.PROJECT, null))
                .hasMessageContaining(BillingMessage.SOURCE_ALREADY_SELECTED.message());
    }

    @Test
    void given_source_selected_when_continue_with_different_source_then_bill_008() {
        // 改口拒绝（code-review 处置——文档契约与实现对齐）：源已随选择一次选定，
        // 引导充值支改点另一付费选项＝异源传参 → BILL_008 显式拒绝（不再静默忽略）
        Long projectId = persistedTrialPrdProject("9929");
        gateService.ensureForkPending(projectId);
        gateService.continueDiscussion(projectId, DeductionSource.PROJECT, null);

        assertThatThrownBy(() -> gateService.continueDiscussion(projectId, DeductionSource.ACCOUNT, OWNER))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.SOURCE_ALREADY_SELECTED.message());
        // 拒绝零落痕：源不被改、未翻转
        Map<String, Object> unchanged = jdbcTemplate.queryForMap(
                "SELECT deduction_source, flipped_at FROM bil_project_billing WHERE project_id = ?",
                projectId);
        assertThat(unchanged.get("deduction_source")).isEqualTo(DeductionSource.PROJECT.getCode());
        assertThat(unchanged.get("flipped_at")).isNull();
    }

    @Test
    void given_source_selected_when_continue_with_same_source_then_rejudged() {
        // 同源重传容忍＝重判语义（code-review 处置）：引导充值支重点同选项不报错，
        // 按既定源重判余额（此处仍零余额 → 再引导）
        Long projectId = persistedTrialPrdProject("9930");
        gateService.ensureForkPending(projectId);
        gateService.continueDiscussion(projectId, DeductionSource.PROJECT, null);

        BuildGateAppService.ForkGuideView view =
                gateService.continueDiscussion(projectId, DeductionSource.PROJECT, null);

        assertThat(view.flipped()).isFalse();
        assertThat(view.rechargeRequired()).isTrue();
    }

    @Test
    void given_project_pool_funded_when_continue_without_source_then_flip_without_login() {
        // 灵魂例（AC④）：后台已充项目池的项目「入账即选定项目源」——选「由本项目
        // 付费」免登录直接翻转（销售演示动线：空 source 直达翻转判定）
        Long projectId = persistedTrialPrdProject("9922");
        gateService.ensureForkPending(projectId);
        topupService.topup(null, projectId, 10_000, null, null); // 入账即选定项目源
        // 免登录语义＝命令侧 accountId 可空（项目源不读账户定格——控制器传
        // RequestContext.getUserId()，访客现场为 null 直达翻转判定）
        // 到账自动翻转读面信号在场：卡∧源已选∧池正（前端回读即 settle，无需点击）
        assertThat(gateService.gateOf(projectId).forkSettleDue()).isTrue();

        BuildGateAppService.ForkGuideView view =
                gateService.continueDiscussion(projectId, null, null);

        assertThat(view.flipped()).isTrue();
        assertThat(view.deductionSource()).isEqualTo(DeductionSource.PROJECT.getCode());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT deduction_source, flipped_at, flip_gate FROM bil_project_billing WHERE project_id = ?",
                projectId);
        assertThat(row.get("deduction_source")).isEqualTo(DeductionSource.PROJECT.getCode());
        assertThat(row.get("flipped_at")).isNotNull();
        assertThat(row.get("flip_gate")).isEqualTo(FlipGate.FORK.getCode());
        assertThat(gateService.gateOf(projectId).forkCardPending()).isFalse();
    }

    @Test
    void given_project_pool_never_funded_when_continue_by_project_then_recharge_guided() {
        // 灵魂例（AC③）：试用项目从未入账、项目池必空——选「由本项目付费」＝
        // 选定项目源＋引导充值（余额快照 null＝池未建的必空判定断言）
        Long projectId = persistedTrialPrdProject("9923");
        gateService.ensureForkPending(projectId);

        BuildGateAppService.ForkGuideView view =
                gateService.continueDiscussion(projectId, DeductionSource.PROJECT, null);

        assertThat(view.flipped()).isFalse();
        assertThat(view.rechargeRequired()).isTrue();
        assertThat(view.deductionSource()).isEqualTo(DeductionSource.PROJECT.getCode());
        assertThat(view.sourcePoolBalance()).isNull(); // 项目池从未入账必空
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT deduction_source, flipped_at FROM bil_project_billing WHERE project_id = ?",
                projectId);
        assertThat(row.get("deduction_source")).isEqualTo(DeductionSource.PROJECT.getCode());
        assertThat(row.get("flipped_at")).isNull();
        assertThat(gateService.gateOf(projectId).forkCardPending()).isTrue(); // 卡留岔路口
    }

    @Test
    void given_recharge_arrived_when_settle_then_flip_without_reselection() {
        // 灵魂例（AC②③收口）：到账即翻转——引导充值支后源已选定，空 source 重判
        // （settle）即翻转回访谈，无需重走选择
        Long projectId = persistedTrialPrdProject("9924");
        gateService.ensureForkPending(projectId);
        BuildGateAppService.ForkGuideView guided =
                gateService.continueDiscussion(projectId, DeductionSource.PROJECT, null);
        assertThat(guided.rechargeRequired()).isTrue();

        topupService.topup(null, projectId, 5_000, null, null); // 到账（项目池充值）

        BuildGateAppService.ForkGuideView settled =
                gateService.continueDiscussion(projectId, null, null);
        assertThat(settled.flipped()).isTrue();
        assertThat(settled.sourcePoolBalance()).isEqualTo(5_000L);
        assertThat(gateService.gateOf(projectId).forkCardPending()).isFalse();
        assertThat(interviewQuotaAppService.quotaOf(projectId)).isNull(); // 五问额度解除
    }

    @Test
    void given_fork_not_pending_when_continue_then_prj_085() {
        // 引导流动作守卫：卡不在场（未自然收口）拒绝——选源/翻转只从岔路口卡发起
        Long projectId = persistedTrialPrdProject("9925"); // 未 ensureForkPending

        assertThatThrownBy(() -> gateService.continueDiscussion(projectId, DeductionSource.PROJECT, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FORK_NOT_PENDING.message());
        // 拒绝零落痕：无 billing 行（源未定事实不变）
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM bil_project_billing WHERE project_id = ?", projectId)).isEmpty();
    }

    @Test
    void given_already_paid_when_continue_then_idempotent_flipped() {
        // 幂等：已付费（他路翻转竞态/重复提交）→ flipped＝true 成功回执，不空翻不报错
        Long projectId = persistedTrialPrdProject("9926");
        gateService.ensureForkPending(projectId);
        topupService.topup(null, projectId, 10_000, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.INTERCEPT); // 他路（门②）先翻

        BuildGateAppService.ForkGuideView view =
                gateService.continueDiscussion(projectId, DeductionSource.ACCOUNT, OWNER);

        assertThat(view.flipped()).isTrue();
        assertThat(view.rechargeRequired()).isFalse();
        // 门别留痕不被本命令覆盖（翻转锚在钱的承诺事实上、不可逆）
        assertThat(jdbcTemplate.queryForMap(
                "SELECT flip_gate FROM bil_project_billing WHERE project_id = ?", projectId)
                .get("flip_gate")).isEqualTo(FlipGate.INTERCEPT.getCode());
    }

    @Test
    void given_source_unset_and_no_source_when_continue_then_bill_010() {
        // 源未定且未随命令选定＝BILL_010（与翻转前置同语义）
        Long projectId = persistedTrialPrdProject("9927");
        gateService.ensureForkPending(projectId);

        assertThatThrownBy(() -> gateService.continueDiscussion(projectId, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.FLIP_SOURCE_REQUIRED.message());
    }

    @Test
    void given_guest_not_logged_in_when_continue_by_account_then_bill_009() {
        // 账户源须先登录绑定：访客（accountId=null）选「由我的账户付费」吃 BILL_009
        // （聚合守卫先行＝DomainException 直抛，message 同源 BillingMessage）
        Long projectId = persistedTrialPrdProject("9928");
        gateService.ensureForkPending(projectId);

        assertThatThrownBy(() -> gateService.continueDiscussion(projectId, DeductionSource.ACCOUNT, null))
                .hasMessageContaining(BillingMessage.SOURCE_ACCOUNT_REQUIRED.message());
        // 拒绝零落痕：无 billing 行（登录后重走选定）
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM bil_project_billing WHERE project_id = ?", projectId)).isEmpty();
    }
}
