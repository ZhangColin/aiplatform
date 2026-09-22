package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.FileChange;
import com.aieducenter.aiplatform.base.agentscope.RunHeading;
import com.aieducenter.aiplatform.base.agentscope.StageDurations;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.domain.aggregate.GenerationSegment;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.GenerationState;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 生成编排（#22 验收 + #24 知识命中前置注入 + #104 生成轨道——先起服 + 纵向切片逐段）：
 * 编码命令全要素（coder-{projectId} 会话稳定绑定、EXECUTOR 配置与长任务超时、owner
 * 寻址、计量 dims、项目工作区、流关联）；工作区布局资产就位先于首试下发；知识命中
 * 前置注入；失败自动静默重试；成功收口落 generated_at。
 *
 * <p><b>生成轨道（#104）</b>：阶段 0（先起服白底页）固定前置，其后按切片计划顺序逐片
 * 派 run——轨道顺序派 N run、阶段 0 先于切片、每片收口发 run-finish（收口扩载）、
 * 失败不跳片（某片超限转终态 run-failed、generated_at 落最后一片收口）。</p>
 */
@IntegrationTest
class GenerationAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    /** 退役名（#82）：静默重试守卫的断言面——重试信号不出用户面事件流。 */
    private static final String RETIRED_RETRYING = "run-retrying";

    /**
     * 单片计划（#220 minimalFallback 已删——计划缺失走补产兜底，不再兜假计划；
     * 本常量只是等形测试数据，替位原 fallback 断言面）。
     */
    private static final BuildPlan SINGLE_SLICE_PLAN =
            new BuildPlan(List.of("用户能使用 PRD 描述的全部功能"));

    @Autowired
    private GenerationAppService appService;

    /** 详情读口（#222 四态投影断言面——REST 行为，不触内部方法）。 */
    @Autowired
    private ProjectQueryAppService queryAppService;

    @Autowired
    private GenerationProperties properties;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 对话史读口（#111 收口扩载落库断言——SSE 扩载与落库同载荷的读回校验）。 */
    @Autowired
    private ConversationHistoryAppService conversationHistory;

    /** saveBuildPlan 事实登记口（#220 补产轮脚本：模拟主智能体轮内工具调用事实）。 */
    @Autowired
    private BuildPlanFacts buildPlanFacts;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private EventsAppService eventsAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private KnowledgePort knowledgePort;

    /** 两命中的检索桩（下发前置注入 happy path）。 */
    private void givenKnowledgeHits() {
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。"),
                new KnowledgeHit("PRD", "连锁诊所系统", "PRD·连锁诊所管理", "范围边界：不含库存。")));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_generation_segments");
        jdbcTemplate.update("DELETE FROM prj_agent_configs");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
    }

    /** 智能体边界正常收口脚本：converse 返回正常收口的 AgentReply（无挂起面）。 */
    private void givenConverseSucceeds(String text) {
        when(agentClient.converse(any(AgentCommand.class), any()))
                .thenAnswer(invocation -> new AgentReply(
                        invocation.getArgument(0, AgentCommand.class).runId(), text));
    }

    private void givenAgentsMdWriteSucceeds() {
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        // 切片收口推 URL（#105）：探活通过后取预览 URL（成功路径的统一桩）
        when(workspaceLifecycleAppService.exposePreview(anyString()))
                .thenReturn(URI.create("http://localhost:30080"));
    }

    /**
     * 脚本化智能体事件缝（#84 验收）的本地别名：剧本体在
     * {@link AgentEventScripts}（生成/迭代共用，契约变化单点同步）。
     */
    private static AgentEvent scripted(String type, String runId, Map<String, Object> extra) {
        return AgentEventScripts.scripted(type, runId, extra);
    }

    private java.sql.Timestamp generatedAt(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId);
    }

    /** 轨道表片行读口（#220 断言面：ord / description / status（1=待跑 2=已收口 3=失败）/ run_id）。 */
    private List<Map<String, Object>> segmentRows(Long projectId) {
        return jdbcTemplate.queryForList(
                "SELECT ord, description, status, run_id FROM prj_generation_segments"
                        + " WHERE project_id = ? ORDER BY ord",
                projectId);
    }

    // ---------- 生成轨道（#104：阶段 0 先起服 + 纵向切片逐段） ----------

    @Test
    void given_two_slice_plan_when_generate_then_stage0_then_slices_in_order() {
        // 灵魂用例（#104 轨道顺序 + #114 每片新会话）：阶段 0（先起服）固定前置，其后
        // 按切片计划顺序逐片派 run——首 run = 阶段 0 起服 prompt、随后逐片切片 prompt；
        // 每片换新会话（slice-0/slice-1/slice-2），前片交接摘要（收口终文）注入下一片
        Long projectId = persistedProject("9800");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        BuildPlan plan = new BuildPlan(List.of("用户能注册登录", "用户能下单支付"));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        assertThat(all).extracting(AgentCommand::prompt).containsExactly(
                GenerationAppService.stage0Prompt(plan),
                GenerationAppService.slicePrompt(plan, 0, "完成"),
                GenerationAppService.slicePrompt(plan, 1, "完成"));
        // 每片新会话寻址（#114）：阶段 0 = slice-0、切片逐片 slice-{index+1}
        assertThat(all).extracting(AgentCommand::sessionId).containsExactly(
                GenerationAppService.sliceSession(projectId, 0),
                GenerationAppService.sliceSession(projectId, 1),
                GenerationAppService.sliceSession(projectId, 2));
        // 每场 run 各自的首试 runId 互不相同（阶段 0 首 run 身份 + 切片逐片新 runId）
        assertThat(all).extracting(AgentCommand::runId).doesNotHaveDuplicates();
    }

    @Test
    void given_slices_when_generate_then_commands_carry_work_message_heading() {
        // #118 工作消息头部标题：阶段 0 携「系统初始化」（无进度——非切片），切片携
        // 用户语言标题 + 1-based 进度（index/total）——run-start 的 slice 字段呈现源，
        // 前端头部「{标题}（{index}/{total}）」的编发侧事实
        Long projectId = persistedProject("9821");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::heading).containsExactly(
                RunHeading.titled(GenerationAppService.STAGE0_TITLE),
                RunHeading.slice("用户能注册登录", 1, 2),
                RunHeading.slice("用户能下单支付", 2, 2));
    }

    @Test
    void given_generation_prompts_when_narration_convention_then_anchor_present() {
        // #225 叙说密度放宽（原 #119「每工作段先解说后动手」）：阶段 0 与切片 run
        // prompt 及执行体 systemPrompt 同向约定「关键节点才解说」（开工、重大转向、
        // 失败、收口）——事件流不再被自述刷满，折叠不是遮羞布
        assertThat(GenerationAppService.STAGE0_RUN_PROMPT)
                .contains("关键节点");
        assertThat(GenerationAppService.sliceRunPrompt(1, 2, "用户能注册登录"))
                .contains("关键节点");
        assertThat(AgentProfile.EXECUTOR.systemPrompt())
                .contains("关键节点才解说");
    }

    @Test
    void given_executor_protocol_when_inspect_then_external_repo_convention_anchored() {
        // #214 外部仓库流程指令写进执行体工作协议：起手先 clone 进 external/ 再读，
        // 物理规则指路 AGENTS.md——与工作区平台约定分工（物理规则进 AGENTS.md 正本）
        assertThat(AgentProfile.EXECUTOR.systemPrompt())
                .contains("外部仓库惯例")
                .contains("浅克隆进工作区 external/")
                .contains("external/")
                .contains("只读参考")
                .contains("不把仓库内容合并进你交付的系统")
                .contains("见工作区 AGENTS.md");
    }

    @Test
    void given_slice_retries_when_generate_then_retry_same_slice_session_and_handoff_injected() {
        // #114 重试续本片会话 + 片间交接：阶段 0 成功、切片 1 首试失败后重试成功、切片 2
        // 成功——重试续切片 1 会话（不换新）；前片收口终文注入下一片（切片 2 的 prompt
        // 带切片 1 的交接、切片 1 的 prompt 带阶段 0 的交接）
        Long projectId = persistedProject("9830");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("run-s0", "阶段0交接"))
                .thenThrow(new IllegalStateException("切片1中断"))
                .thenReturn(new AgentReply("run-s1", "切片1交接"))
                .thenReturn(new AgentReply("run-s2", "切片2交接"));

        BuildPlan plan = new BuildPlan(List.of("用户能注册登录", "用户能下单支付"));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        // 每片新会话 + 同片重试续本片会话：slice-0 / slice-1 / slice-1（重试）/ slice-2
        assertThat(all).extracting(AgentCommand::sessionId).containsExactly(
                GenerationAppService.sliceSession(projectId, 0),
                GenerationAppService.sliceSession(projectId, 1),
                GenerationAppService.sliceSession(projectId, 1),
                GenerationAppService.sliceSession(projectId, 2));
        // 前片交接注入下一片：切片 1 首试带阶段 0 交接、切片 2 带切片 1 交接
        assertThat(all.get(1).prompt()).contains("阶段0交接");
        assertThat(all.get(3).prompt()).contains("切片1交接");
        // 重试 prompt 自足（#114 重试可能落在空会话）：同样携带前片交接与产出约定；
        // #221 原地修——重试携带错误现场（同片第二次尝试不从头重做，针对错误继续修）
        assertThat(all.get(2).prompt()).contains("阶段0交接")
                .contains(GenerationAppService.HANDOFF_PRODUCTION)
                .contains("错误现场：切片1中断")
                .contains("不要重做");
    }

    @Test
    void given_knowledge_hits_when_generate_slices_then_knowledge_injected_only_stage0() {
        // #114 一次切入一次注入（生成链首片）：知识命中前置注入只在阶段 0，切片不重
        // 检索不重注入（注入口径不膨胀——每片新会话后若逐片注入即膨胀）
        Long projectId = persistedProject("9831");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");
        givenKnowledgeHits();

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        // 阶段 0 + 2 片共 3 场 run，只有阶段 0 检索一次知识
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt());
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        assertThat(all.get(0).prompt()).startsWith("【平台知识库·相似历史需求】");
        assertThat(all.get(1).prompt()).doesNotContain("【平台知识库");
        assertThat(all.get(2).prompt()).doesNotContain("【平台知识库");
    }

    @Test
    void given_slices_when_generate_then_handoff_landed_in_platform_dir() {
        // #114 User Story 3：交接摘要落 .platform/ 平台产物目录（不进用户 git 成版，
        // 与 #107 同向）——阶段 0 + 每片各落一份 slice-handoff-{段号}.md
        Long projectId = persistedProject("9832");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("交接内容");

        appService.dispatchGenerationOnTurnClose(projectId, new BuildPlan(List.of("用户能注册登录")));

        ArgumentCaptor<WorkspaceExecCommand> execs = ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(2)).exec(eq("9832"), execs.capture());
        List<String> handoffWrites = execs.getAllValues().stream()
                .map(WorkspaceExecCommand::command)
                .filter(cmd -> cmd.contains("/workspace/.platform/slice-handoff-"))
                .toList();
        assertThat(handoffWrites).hasSize(2);
        assertThat(handoffWrites.get(0)).contains("slice-handoff-0.md");
        assertThat(handoffWrites.get(1)).contains("slice-handoff-1.md");
    }

    @Test
    void given_slice_closes_when_generate_then_preview_ready_pushed_with_url() {
        // #105 URL 事件驱动：切片收口（阶段 0 + 每片，8081 探活通过后）推
        // preview-ready(URL)——前端 bridge 写预览查询缓存，免 3s 轮询拿 URL
        Long projectId = persistedProject("9820");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录")));

        // 阶段 0 + 1 片 = 2 次收口，各推一次 preview-ready（projectId + url）
        verify(eventsAppService, times(2)).publishNotification(eq(ProjectEventTypes.PREVIEW_READY),
                argThat(payload -> projectId.toString().equals(payload.get("projectId"))
                        && "http://localhost:30080".equals(payload.get("url"))));
    }

    @Test
    void given_scripted_runs_when_generate_then_run_finish_per_slice() {
        // #104 每片收口发 run-finish：阶段 0 + 2 片 = 3 场 run，每场收口恰一次
        // run-finish（收口扩载——真收口才发，判据在 8081 探活）
        Long projectId = persistedProject("9801");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        verify(eventsAppService, times(3)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH), anyMap());
    }

    @Test
    void given_stage0_fails_when_generate_then_run_failed_and_no_slices() {
        // #104 失败语义·阶段 0：阶段 0 起服失败（不可恢复）——烧满重试转终态，不派
        // 任何切片；run-failed 锚阶段 0 首 run 身份；generated_at 不落
        Long projectId = persistedProject("9802");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenThrow(new IllegalStateException("起服失败"));

        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId,
                SINGLE_SLICE_PLAN);

        verify(agentClient, times(properties.getMaxAttempts())).converse(any(), any());
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                run.runId().equals(payload.get(EventsAppService.RUN_FIELD))));
        assertThat(generatedAt(projectId)).isNull();
    }

    @Test
    void given_success_when_generate_then_generated_at_persisted_on_last_slice() {
        // #104 generated_at 落最后一片收口（口径不变）：阶段 0 + 切片全程成功即落位
        Long projectId = persistedProject("9804");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 生成轨道表（#220：切片计划与片状态落库，计划跟 PRD 版本走） ----------

    @Test
    void given_dispatch_when_track_runs_then_plan_and_slice_status_persisted() {
        // 灵魂用例（#220 AC①）：PRD 产出后切片计划落库（阶段 0 + 逐片），片收口状态
        // 落表；计划与片进度的事实源是表——进程内无副本，「模拟重启」后仍可查即
        // 「直接查表」（run 无表口径的精确例外）
        Long projectId = persistedProject("9840");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        BuildPlan plan = new BuildPlan(List.of("用户能注册登录", "用户能下单支付"));
        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId, plan);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        // 片集：阶段 0 固定题 + 两片切片句，PRD 版本锚 = 项目 prd_produced_at
        List<Map<String, Object>> rows = segmentRows(projectId);
        assertThat(rows).extracting(row -> row.get("description")).containsExactly(
                GenerationAppService.STAGE0_TITLE, "用户能注册登录", "用户能下单支付");
        assertThat(rows).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 2); // 全部已收口
        // run 锚：阶段 0 = 首 run 身份（随响应回），切片 = 各片首试 runId（用户面锚）
        assertThat(rows.get(0)).containsEntry("run_id", run.runId());
        assertThat(rows.get(1)).containsEntry("run_id", commands.getAllValues().get(1).runId());
        assertThat(rows.get(2)).containsEntry("run_id", commands.getAllValues().get(2).runId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT prd_produced_at FROM prj_generation_segments WHERE project_id = ? AND ord = 0",
                java.sql.Timestamp.class, projectId))
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT prd_produced_at FROM prj_projects WHERE id = ?",
                        java.sql.Timestamp.class, projectId));
        // 断点 = 表中最深收口片（#221 续跑的消费面）：全部收口 → 末片序
        assertThat(appService.deepestClosedSegmentOf(projectId)).isEqualTo(2);
        // 计划读回（轨道表还原 BuildPlan，PRD 锚一致）
        assertThat(appService.planOf(projectId)).isEqualTo(plan);
    }

    @Test
    void given_slice_fails_when_generate_then_run_failed_and_no_next_slice() {
        // 灵魂用例（#104 失败不跳片 + #220 AC② 片失败落表、断点=最深收口片）：
        // 阶段 0 成功、切片 1 失败——run-failed 锚失败片 runId，不自动跳切片 2；
        // 失败片状态落表（FAILED + run 锚），断点 = 已收口的最深片（= 阶段 0；
        // 失败片是续跑重做对象，不进断点）
        Long projectId = persistedProject("9803");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("stage0", "阶段 0 完成"))
                .thenThrow(new IllegalStateException("切片失败"))
                .thenThrow(new IllegalStateException("切片失败"))
                .thenThrow(new IllegalStateException("切片失败"));

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        // 阶段 0 (1) + 切片 1 重试 3 次 = 4 次 converse；切片 2 不派
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(commands.capture(), any());
        String slice1RunId = commands.getAllValues().get(1).runId();
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                slice1RunId.equals(payload.get(EventsAppService.RUN_FIELD))));
        assertThat(generatedAt(projectId)).isNull();
        // #220 片状态落表：阶段 0 已收口、切片 1 失败（run 锚 = 失败片首试 runId）、
        // 切片 2 待跑；断点 = 最深收口片（= 0，失败片是重做对象不进断点）
        List<Map<String, Object>> rows = segmentRows(projectId);
        assertThat(rows).extracting(row -> row.get("status")).containsExactly(2, 3, 1);
        assertThat(rows.get(1)).containsEntry("run_id", slice1RunId);
        assertThat(rows.get(2)).containsEntry("run_id", null);
        assertThat(appService.deepestClosedSegmentOf(projectId)).isZero();
    }

    @Test
    void given_prd_revised_when_dispatch_with_new_plan_then_table_replaced_no_residue() {
        // #220 AC③ 计划生命周期跟 PRD 版本走：PRD 修订（锚换新）后重产计划——表内
        // 整组替换（旧片不残留续用）、状态随新计划重置；轨道按新计划执行（prompt
        // 轨迹含新切片、不含旧切片）
        Long projectId = persistedProject("9841");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenThrow(new IllegalStateException("起服失败"));

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("旧切片一", "旧切片二")));
        revisePrd(projectId);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("新切片一", "新切片二", "新切片三")));

        // 新计划轨道起跑（阶段 0 prompt 轨迹含新切片、不含旧切片——不沿用旧计划）
        verify(agentClient, atLeast(properties.getMaxAttempts() + 1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(properties.getMaxAttempts()).prompt())
                .contains("新切片一").contains("新切片三")
                .doesNotContain("旧切片一").doesNotContain("旧切片二");
        // 表内整组替换：三片新句（旧句无残留）、锚 = 修订后的 PRD 版本
        assertThat(segmentRows(projectId)).extracting(row -> row.get("description")).containsExactly(
                GenerationAppService.STAGE0_TITLE, "新切片一", "新切片二", "新切片三");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT prd_produced_at FROM prj_generation_segments WHERE project_id = ? AND ord = 0",
                java.sql.Timestamp.class, projectId))
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT prd_produced_at FROM prj_projects WHERE id = ?",
                        java.sql.Timestamp.class, projectId));
    }

    @Test
    void given_prd_revised_when_dispatch_without_plan_then_stale_plan_not_reused_and_redispatch() {
        // #220 AC③+④：PRD 修订后无交接物派发——旧计划锚不一致不沿用（不出现拿旧
        // 计划生成的 run），计划缺失走补产兜底：重派主智能体按 PRD 补产（main 会话
        // 一轮、prompt 请求 saveBuildPlan）；补产轮无果由补产账止住（同 PRD 版本只
        // 补产一次）
        Long projectId = persistedProject("9842");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        // 单脚本分相（Mockito 重打桩会以参调触发旧 throw 桩，故不分设）：首段轨道
        // 起服失败，翻相位后补产轮正常回复但不产计划（无 saveBuildPlan 事实）
        AtomicBoolean planRequestPhase = new AtomicBoolean(false);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            if (!planRequestPhase.get()) {
                throw new IllegalStateException("起服失败");
            }
            return new AgentReply("plan-request", "我看了一下 PRD");
        });

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("旧切片一")));
        int staleRows = segmentRows(projectId).size();
        revisePrd(projectId);
        planRequestPhase.set(true);

        GenerationAppService.GenerationRun run =
                appService.dispatchGenerationOnTurnClose(projectId, /* plan= */ null);

        // 补产轮起跑（返回其 runId）：全程 converse = 首段轨道 3 次失败尝试 + 补产轮
        // 恰一轮（无递归再补），补产轮之后无任何 coder 会话 run
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(properties.getMaxAttempts() + 1)).converse(commands.capture(), any());
        AgentCommand request = commands.getAllValues().get(properties.getMaxAttempts());
        assertThat(request.sessionId()).isEqualTo(MainAgentAppService.SESSION_PREFIX + projectId);
        assertThat(request.prompt()).isEqualTo(MainAgentAppService.BUILD_PLAN_REQUEST_PROMPT);
        assertThat(run.runId()).isEqualTo(request.runId());
        assertThat(commands.getAllValues().stream().skip(properties.getMaxAttempts()))
                .noneMatch(command -> command.sessionId().startsWith(CoderRunAttempts.SESSION_PREFIX));
        // 旧计划行原样在表（未被沿用清换——补产兑现时才整组替换），断点仍在旧收口片
        assertThat(segmentRows(projectId)).hasSize(staleRows);
        assertThat(generatedAt(projectId)).isNull();
        assertThat(appService.planOf(projectId)).as("PRD 已修订，旧计划不沿用").isNull();
        // 断点锚门自持：过期计划的收口/失败片不算现行断点（断点属现行计划）
        assertThat(appService.deepestClosedSegmentOf(projectId)).isEqualTo(-1);
        // 同 PRD 版本再派：补产账拦住（不再烧补产轮）
        assertThat(appService.dispatchGenerationOnTurnClose(projectId, null)).isNull();
        verify(agentClient, times(properties.getMaxAttempts() + 1)).converse(any(), any());
    }

    @Test
    void given_no_plan_when_generate_then_redispatch_produces_plan_and_generation_follows() {
        // #220 AC④ 补产链闭环：计划缺失重派主智能体——补产轮调 saveBuildPlan（事实
        // 登记）后收口，链必达自动派生成（新计划落表、轨道起跑、generated_at 落位）
        Long projectId = persistedProject("9843");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        BuildPlan produced = new BuildPlan(List.of("用户能注册登录", "用户能下单支付"));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith(MainAgentAppService.SESSION_PREFIX)) {
                // 补产轮内主智能体调 saveBuildPlan（工具事实登记）
                buildPlanFacts.record(command.workspaceId(), produced);
                return new AgentReply(command.runId(), "已按 PRD 拟好实施计划");
            }
            return new AgentReply(command.runId(), "完成");
        });

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        // 链：补产轮（main）→ 收口自动派生成 → 阶段 0 + 两片；响应 runId = 补产轮锚
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::sessionId).containsExactly(
                MainAgentAppService.SESSION_PREFIX + projectId,
                GenerationAppService.sliceSession(projectId, 0),
                GenerationAppService.sliceSession(projectId, 1),
                GenerationAppService.sliceSession(projectId, 2));
        assertThat(run.runId()).isEqualTo(commands.getAllValues().get(0).runId());
        assertThat(commands.getAllValues().get(1).prompt()).contains("用户能注册登录");
        // 新计划落表（补产兑现即整组落库）+ 完成
        assertThat(appService.planOf(projectId)).isEqualTo(produced);
        assertThat(appService.deepestClosedSegmentOf(projectId)).isEqualTo(2);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_redispatch_produces_nothing_when_generate_then_bounded_no_fake_plan() {
        // #220 AC④ 补产无果即止：计划缺失重派一轮，补产轮不产计划——不再递归补产、
        // 无假计划、无生成 run；表无片行、generated_at 不落
        Long projectId = persistedProject("9844");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("我看了一下 PRD");

        assertThat(appService.startGeneration(projectId)).isNotNull(); // 补产轮起跑

        verify(agentClient, times(1)).converse(any(), any());
        assertThat(segmentRows(projectId)).isEmpty();
        assertThat(generatedAt(projectId)).isNull();
        // 同 PRD 版本再派（REST 入口）：补产账拦住——同步 409（重复触发口径）且不再烧轮
        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
        verify(agentClient, times(1)).converse(any(), any());
    }

    // ---------- 断点续跑（#221：继续生成从断点接续，已收口片不重做） ----------

    @Test
    void given_terminal_failure_when_regenerate_then_resume_from_breakpoint_with_inventory() {
        // 灵魂用例（#221 AC①②④⑤ 全链）：发起（阶段 0 + 切片 1 收口）→ 中断（切片 2
        // 重试耗尽 run-failed）→ 续跑（REST 重发无交接物——计划与断点取自轨道表，
        // 无进程内副本可依〔「按表续跑」〕；错误现场走进程内终态账，跨进程丢失即降级
        // ——降级口径见下方纯函数用例）→ 收口（跳过已收口片、断点片新会话
        // 起手现状盘点〔进度/中断原因+错误现场/中断前摘要〕、后续片顺序、generated_at
        // 落位）——run-failed 后「继续生成」同走断点续跑，不重头
        Long projectId = persistedProject("9850");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        // 中断前摘要读回：续跑起手 cat .platform/slice-handoff-1.md（断点前片交接的
        // 落盘正本——进程重启后内存终文不在的事实源）
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().startsWith("cat '") && cmd.command().contains("slice-handoff-1.md"))))
                .thenReturn(new ExecResultResponse("切片1交接：注册登录已端到端走通", "", 0));
        // 发起：阶段 0 + 切片 1 成功收口，切片 2 三次尝试耗尽转终态失败
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("s0", "阶段0交接"))
                .thenReturn(new AgentReply("s1", "切片1交接"))
                .thenThrow(new IllegalStateException("切片2起服失败"))
                .thenThrow(new IllegalStateException("切片2起服失败"))
                .thenThrow(new IllegalStateException("切片2起服失败"));

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付", "用户能查询订单")));

        // 中断定格：阶段 0 / 切片 1 已收口、切片 2 失败、切片 3 待跑；断点 = 最深收口片
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 3, 1);
        assertThat(generatedAt(projectId)).isNull();

        // 续跑（REST 路径无交接物：重打桩用 doReturn 家——when(...) 求值先参调旧 throw 桩）
        doReturn(new AgentReply("r2", "切片2续跑完成"), new AgentReply("r3", "切片3完成"))
                .when(agentClient).converse(any(), any());
        GenerationAppService.GenerationRun resume = appService.startGeneration(projectId);

        // 只重跑失败片与后续片：发起 5 次 + 续跑 2 次（切片 2、切片 3）——阶段 0 /
        // 切片 1 不重跑（无其会话的新 converse）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(7)).converse(commands.capture(), any());
        AgentCommand slice2 = commands.getAllValues().get(5);
        AgentCommand slice3 = commands.getAllValues().get(6);
        // 断点片新会话脏续（不复用失败旧会话 slice-2）+ 续跑首 run = 响应锚
        assertThat(slice2.sessionId()).isEqualTo(
                GenerationAppService.resumeSession(projectId, 2, resume.runId()))
                .isNotEqualTo(GenerationAppService.sliceSession(projectId, 2));
        assertThat(slice2.runId()).isEqualTo(resume.runId());
        // 现状盘点（AC②）：已收口进度 + 中断原因（重试耗尽 + 错误现场）+ 脏续纪律；
        // 断点片任务本体仍在（不是只有盘点）
        assertThat(slice2.prompt())
                .contains("续跑现状盘点")
                .contains("系统初始化")
                .contains("切片 1/3「用户能注册登录」")
                .contains("自动重试耗尽后失败")
                .contains("切片2起服失败")
                .contains("不要重做")
                .contains("切片 2/3")
                .contains("用户能下单支付");
        // 中断前摘要：前片交接经 .platform 落盘件读回注入（与片间交接同构）
        assertThat(slice2.prompt()).contains("切片1交接：注册登录已端到端走通");
        // 后续片正常顺序：切片 3 常规会话、无盘点前缀，交接取切片 2 续跑收口的内存终文
        assertThat(slice3.sessionId()).isEqualTo(GenerationAppService.sliceSession(projectId, 3));
        assertThat(slice3.prompt())
                .doesNotContain("续跑现状盘点")
                .contains("用户能查询订单")
                .contains("切片2续跑完成");
        // 收口：全片已收口、断点 = 末片、generated_at 落位（AC①）
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 2, 2);
        assertThat(appService.deepestClosedSegmentOf(projectId)).isEqualTo(3);
        assertThat(generatedAt(projectId)).isNotNull();
        // 全程唯一失败终态：续跑收口后不再发 run-failed
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
    }

    @Test
    void given_interrupted_pending_segment_when_regenerate_then_resume_with_interrupt_reason() {
        // #221 全场景脏续·非错误中断（进程重启/平台中断——片未及落终态，表中待跑；
        // 以终态后拨回待跑模拟重启时的可观察状态）：续跑同路（跳过已收口片、断点片
        // 新会话起手现状盘点），中断原因叙事为「中断」而非「重试耗尽」、不携错误
        // 现场；中断前摘要读不回（落盘缺失）降级空交接不断流
        Long projectId = persistedProject("9851");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds(); // cat 读回走缺省桩（stdout 空）→ 降级空交接
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("s0", "阶段0交接"))
                .thenThrow(new IllegalStateException("切片1失败"))
                .thenThrow(new IllegalStateException("切片1失败"))
                .thenThrow(new IllegalStateException("切片1失败"));

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        // 模拟中断未落终态（进程在终态落表前死亡的可观察状态）：片行拨回待跑
        jdbcTemplate.update(
                "UPDATE prj_generation_segments SET status = 1 WHERE project_id = ? AND ord = 1",
                projectId);

        doReturn(new AgentReply("r1", "切片1续跑完成"), new AgentReply("r2", "切片2完成"))
                .when(agentClient).converse(any(), any());
        appService.startGeneration(projectId);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(6)).converse(commands.capture(), any());
        AgentCommand slice1 = commands.getAllValues().get(4);
        assertThat(slice1.sessionId()).startsWith(GenerationAppService.sliceSession(projectId, 1) + "-");
        assertThat(slice1.prompt())
                .contains("续跑现状盘点")
                .contains("中断（未及收口）")
                .doesNotContain("自动重试耗尽")
                .doesNotContain("错误现场")
                // 交接降级：空交接不注「上一片交接摘要」块，计划轨迹自足
                .doesNotContain("上一片交接摘要")
                .contains("整体切片计划");
        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 存量恢复（#223：无表项目对照收尾卡标已完片、只补缺口） ----------

    @Test
    void given_legacy_project_with_closings_when_resume_then_marked_and_only_gap_runs() {
        // 灵魂用例（#223 AC①）：存量在途项目（生成早于轨道表——表内无片行，已收口
        // 成果只在收尾卡）发起「继续生成」：补产轮 prompt 携已收口成果清单（已完片
        // 照录原文的约定）→ 补产计划落库对照标已完片（run 锚 = 往次收口的用户面
        // run）→ 续跑只跑缺口片（断点片新会话起手现状盘点，已收口进度含存量片）
        // → 缺口收口即 generated_at 落位
        Long projectId = persistedProject("9860");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        // 存量收口事实（往次生成收尾卡）：阶段 0 + 两片已收口
        conversationHistory.recordClosing(projectId, "legacy-run-0",
                Map.of("summary", "起服了系统骨架"));
        conversationHistory.recordClosing(projectId, "legacy-run-1",
                Map.of("summary", "完成切片：用户能注册登录"));
        conversationHistory.recordClosing(projectId, "legacy-run-2",
                Map.of("summary", "完成切片：用户能下单支付"));
        // 补产轮照录已完片原文 + 补缺口片（saveBuildPlan 事实），切片 run 正常收口
        BuildPlan produced = new BuildPlan(List.of("用户能注册登录", "用户能下单支付", "用户能管理商品"));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith(MainAgentAppService.SESSION_PREFIX)) {
                buildPlanFacts.record(command.workspaceId(), produced);
                return new AgentReply(command.runId(), "已对照已有成果拟好实施计划");
            }
            return new AgentReply(command.runId(), "缺口片完成");
        });

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        // 补产轮（main 会话）+ 缺口片恰一场编码 run——阶段 0 与已完片不重跑
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        assertThat(run.runId()).isEqualTo(all.get(0).runId());
        // 补产轮 prompt 携已收口成果清单 + 照录约定（对照的匹配前提）
        assertThat(all.get(0).prompt())
                .contains("起服了系统骨架")
                .contains("完成切片：用户能注册登录")
                .contains("一字不改照录");
        // 缺口片（断点段）新会话脏续：现状盘点起手（已收口进度含存量片）+ 任务本体
        assertThat(all.get(1).sessionId())
                .startsWith(GenerationAppService.sliceSession(projectId, 3) + "-");
        assertThat(all.get(1).prompt())
                .contains("续跑现状盘点")
                .contains("切片 1/3「用户能注册登录」")
                .contains("切片 2/3「用户能下单支付」")
                .contains("切片 3/3")
                .contains("用户能管理商品");
        // 轨道表：存量片对照标已收口（run 锚 = 往次收口 run）、缺口片本次真跑收口
        List<Map<String, Object>> rows = segmentRows(projectId);
        assertThat(rows).extracting(row -> row.get("status")).containsExactly(2, 2, 2, 2);
        assertThat(rows.get(0)).containsEntry("run_id", "legacy-run-0");
        assertThat(rows.get(1)).containsEntry("run_id", "legacy-run-1");
        assertThat(rows.get(2)).containsEntry("run_id", "legacy-run-2");
        assertThat(rows.get(3)).containsEntry("run_id", all.get(1).runId());
        assertThat(appService.deepestClosedSegmentOf(projectId)).isEqualTo(3);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_legacy_project_when_plan_paraphrases_then_no_match_and_full_redo() {
        // #223 降级方向安全：补产未照录原文（切片措辞漂移）——切片对照不上即待跑
        // 重做、不猜语义等价；对得上的照标（阶段 0 固定句不受措辞影响）——多跑不
        // 漏做，部分对照部分跳过
        Long projectId = persistedProject("9861");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        conversationHistory.recordClosing(projectId, "legacy-run-0",
                Map.of("summary", "起服了系统骨架"));
        conversationHistory.recordClosing(projectId, "legacy-run-1",
                Map.of("summary", "完成切片：用户能注册登录"));
        BuildPlan produced = new BuildPlan(List.of("用户能够注册并登录系统", "用户能下单支付"));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith(MainAgentAppService.SESSION_PREFIX)) {
                buildPlanFacts.record(command.workspaceId(), produced);
                return new AgentReply(command.runId(), "已拟好计划");
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.startGeneration(projectId);

        // 补产轮 + 两片（措辞漂移的两片都真跑）= 3 次 converse；阶段 0 对照跳过
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        // 阶段 0 已收口（run 锚 = 往次收口 run）；切片 1 断点段起手（有进度可盘点）
        assertThat(segmentRows(projectId).get(0)).containsEntry("run_id", "legacy-run-0");
        assertThat(commands.getAllValues().get(1).sessionId())
                .startsWith(GenerationAppService.sliceSession(projectId, 1) + "-");
        assertThat(commands.getAllValues().get(1).prompt())
                .contains("续跑现状盘点")
                .contains("系统初始化");
        assertThat(commands.getAllValues().get(2).sessionId())
                .isEqualTo(GenerationAppService.sliceSession(projectId, 2));
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_tracked_project_prd_revised_when_replan_then_no_legacy_marking() {
        // #223 边界：存量对照只认「表内无片行」的首录——PRD 已演进的换锚重产
        // （表内有旧片行）整组替换重置待跑、从头来是既定口径，即便新计划措辞与
        // 旧收口叙事逐字相同也不标（阶段 0 照样真跑）
        Long projectId = persistedProject("9862");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        // 首轮生成中断（阶段 0 收口、切片失败）——表内有片行、generated_at 未落
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("s0", "阶段0交接"))
                .thenThrow(new IllegalStateException("切片失败"))
                .thenThrow(new IllegalStateException("切片失败"))
                .thenThrow(new IllegalStateException("切片失败"));
        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录")));
        revisePrd(projectId);

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能管理商品")));

        // 换锚重产不标已完片：阶段 0 真跑（烧满重试转终态失败），不是对照跳过
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(3, 1, 1);
    }

    @Test
    void given_legacy_fully_closed_when_resume_then_backfilled_without_coder_runs() {
        // #223 空缺口边角：补产计划与存量成果全对上（实际早已生成完、只差 generated_at
        // 落位）——不派任何编码 run，落位直接补上（断点已过末片的存量版）
        Long projectId = persistedProject("9863");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        conversationHistory.recordClosing(projectId, "legacy-run-0",
                Map.of("summary", "起服了系统骨架"));
        conversationHistory.recordClosing(projectId, "legacy-run-1",
                Map.of("summary", "完成切片：用户能注册登录"));
        BuildPlan produced = new BuildPlan(List.of("用户能注册登录"));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith(MainAgentAppService.SESSION_PREFIX)) {
                buildPlanFacts.record(command.workspaceId(), produced);
                return new AgentReply(command.runId(), "对照已有成果，系统已全部完成");
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.startGeneration(projectId);

        // 仅补产轮一次 converse（main 会话）；无编码 run
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(0).sessionId())
                .startsWith(MainAgentAppService.SESSION_PREFIX);
        assertThat(appService.deepestClosedSegmentOf(projectId)).isEqualTo(1);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 生成态四态投影（#222：REST 档位与「继续生成」出口的推导输入） ----------

    @Test
    void given_generation_lifecycle_when_detail_then_four_state_projection() {
        // 灵魂用例（#222 AC①）：从未生成 →（派发起跑，含排队段）生成中 →（失败
        // 终态）生成中断 →（「继续生成」续跑起跑）生成中 →（收口）已生成——投影由
        // 轨道表＋generated_at＋在途标记派生，读口 = REST 详情（与 SSE 会话态无关）
        Long projectId = persistedProject("9855");
        givenAgentsMdWriteSucceeds();
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.NEVER_GENERATED);

        // 派发即生成中（已提交未起跑的排队段也算在途——在途标记先于 run 执行）
        List<Runnable> submitted = new ArrayList<>();
        doAnswer(invocation -> {
            submitted.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());
        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.GENERATING);

        // 起跑后重试耗尽转终态失败 → 生成中断（「继续生成」出口的档位；进程重启丢
        // 在途标记同落此档——有轨道片行而未生成不在途）
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("阶段0起服失败"))
                .thenThrow(new IllegalStateException("阶段0起服失败"))
                .thenThrow(new IllegalStateException("阶段0起服失败"));
        submitted.remove(0).run();
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.INTERRUPTED);

        // 「继续生成」（REST 出口）→ 再次生成中；收口 → 已生成（generated_at 落位
        // 恒赢——此后迭代/修正在途不再改变生成态）
        doReturn(new AgentReply("r0", "阶段0完成"), new AgentReply("r1", "切片1完成"))
                .when(agentClient).converse(any(), any());
        appService.startGeneration(projectId);
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.GENERATING);
        submitted.remove(0).run();
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.GENERATED);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_failed_segment_without_scene_when_inventory_then_reason_degrades() {
        // #221 现状盘点纯函数·降级口径：跨进程终态失败现场丢失（进程内账不在）——
        // 失败叙事仍在（轨道表状态是事实源）、不携「最近错误现场」；断点在阶段 0
        // （无收口片）时进度行渲染「尚无」而非空列表
        LocalDateTime anchor = LocalDateTime.now();
        GenerationSegment stage0 = GenerationSegment.pending(1L, 0,
                GenerationAppService.STAGE0_TITLE, anchor);
        stage0.fail("run-x");

        String inventory = GenerationAppService.resumeInventoryBlock(0, List.of(stage0), null);

        assertThat(inventory)
                .contains("自动重试耗尽后失败")
                .doesNotContain("最近错误现场")
                .contains("已收口进度：尚无");
    }

    // ---------- 命令与资产 ----------

    @Test
    void given_project_when_generate_then_stage0_command_bound_to_coder_session() {
        // 编码命令全要素（阶段 0 首 run 代表）：coder 会话稳定绑定 + owner 寻址 +
        // EXECUTOR 配置 + 长任务超时 + 计量 dims + 项目工作区 + 流关联
        Long projectId = persistedProject("9805");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");

        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId,
                SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand value = command.getAllValues().get(0); // 阶段 0 首 run
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo(GenerationAppService.stage0Prompt(
                SINGLE_SLICE_PLAN));
        assertThat(value.sessionId()).isEqualTo(GenerationAppService.sliceSession(projectId, 0));
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).isEqualTo(AgentProfile.EXECUTOR.systemPrompt())
                .contains("0.0.0.0:8081").contains("docs/PRD.md");
        assertThat(value.modelString()).isEqualTo(AgentProfile.EXECUTOR.chatModelString());
        assertThat(value.timeout()).isEqualTo(properties.getTimeout());
        assertThat(value.workspaceId()).isEqualTo("9805");
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).isEqualTo(UsageDims.of(projectId,
                UsageDims.kindOf(AgentProfile.EXECUTOR),
                GenerationAppService.sliceSession(projectId, 0)));
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.agentKey()).isEqualTo("executor"); // run-start 携配置键（工作消息锚）
    }

    @Test
    void given_executor_config_override_when_generate_then_stage0_command_carries_library_values() {
        // #251 装配断言（真命令构建缝，ADR-0021 库值优先）：设执行体运营配置库行后
        // 编码命令实取库值（prompt 与模型档位两腿）；缺省回落腿由上方枚举默认断言钉死
        Long projectId = persistedProject("9870");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, system_prompt, model_id)
                VALUES ('executor', ?, 'deepseek-v4-flash')
                """, "执行体覆盖协议：开工先列步骤。");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand stage0 = command.getAllValues().get(0);
        assertThat(stage0.systemPrompt()).isEqualTo("执行体覆盖协议：开工先列步骤。");
        assertThat(stage0.modelString()).isEqualTo("deepseek:deepseek-v4-flash");
    }

    @Test
    void given_generate_when_start_then_agents_md_written_before_first_converse() {
        // 工作区布局资产就位先于首试下发：AGENTS.md 平台约定写入工作区根（幂等覆写）
        Long projectId = persistedProject("9806");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        InOrder order = inOrder(workspaceLifecycleAppService, agentClient);
        order.verify(workspaceLifecycleAppService).exec(eq("9806"),
                argThat((WorkspaceExecCommand cmd) ->
                        cmd.command().contains("/workspace/AGENTS.md")
                                && cmd.command().contains("工作区平台约定")
                                && cmd.command().contains("8081")
                                // #44 起服节奏约定进工作区正本（尽早起、增量长）
                                && cmd.command().contains("一开工就跑起来")
                                && cmd.command().contains("增量长出页面与功能")
                                // #113 基座技术栈节进工作区正本（不换栈、不重选型）
                                && cmd.command().contains("基座技术栈")
                                && cmd.command().contains("不换栈、不重选型")
                                // #214 外部仓库物理规则进工作区正本（external/ 资料目录浅克隆、
                                // 克隆带目标目录 external/<仓库名>、不进交付/版本）
                                && cmd.command().contains("external/")
                                && cmd.command().contains("external/<仓库名>")
                                && cmd.command().contains("浅克隆")
                                && cmd.command().contains("不进交付源码包")));
        order.verify(agentClient).converse(any(), any());
    }

    @Test
    void given_knowledge_hits_when_generate_then_prefix_injected_before_stage0_prompt() {
        // 知识命中前置注入（#24）：query = 阶段 0 任务 prompt，命中块拼在任务 prompt 前
        Long projectId = persistedProject("9807");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        givenKnowledgeHits();

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        verify(knowledgePort).retrieve(
                eq(GenerationAppService.stage0Prompt(SINGLE_SLICE_PLAN)), eq(5));
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .startsWith("【平台知识库·相似历史需求】")
                .contains("宠物医院预约平台").contains("非用户的确认信息")
                .endsWith("————\n\n" + GenerationAppService.stage0Prompt(
                        SINGLE_SLICE_PLAN));
    }

    @Test
    void given_retrieval_failure_when_generate_then_degraded_plain_prompt_run_proceeds() {
        // 检索失败降级空注入：任务 prompt 原样下发，run 不被知识面阻断
        Long projectId = persistedProject("9808");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        doThrow(new RuntimeException("pgvector 抖动")).when(knowledgePort)
                .retrieve(anyString(), anyInt());

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .isEqualTo(GenerationAppService.stage0Prompt(SINGLE_SLICE_PLAN));
    }

    @Test
    void given_stage0_first_attempt_fails_when_retry_then_silent_retry_and_track_completes() {
        // 失败自动静默重试（阶段 0 首试失败→重试成功→切片成功）：重试续作轨换
        // RETRY_RUN_PROMPT（不重注入）、重试尝试内部 runId 逐次换新、中间信号零发；
        // 全程成功 → generated_at 落位
        Long projectId = persistedProject("9809");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenKnowledgeHits(); // 首试带前置注入，重试不重注入（注入块已在会话历史）
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("首次尝试中断"))
                .thenReturn(new AgentReply("run-2", "系统已生成"))
                .thenReturn(new AgentReply("run-3", "系统已生成"));

        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId,
                SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(command.capture(), any());
        List<AgentCommand> attempts = command.getAllValues();
        assertThat(attempts.get(0).runId()).isEqualTo(run.runId()); // 阶段 0 首试 = 首 run 身份
        assertThat(attempts.get(0).prompt())
                .endsWith("————\n\n" + GenerationAppService.stage0Prompt(
                        SINGLE_SLICE_PLAN));
        assertThat(attempts.get(1).runId()).isNotEqualTo(run.runId()); // 重试内部 runId
        assertThat(attempts.get(1).prompt()).isEqualTo(
                GenerationAppService.generationRetryPrompt(SINGLE_SLICE_PLAN,
                        GenerationAppService.STAGE0_RETRY_DESC, null, "首次尝试中断"));
        assertThat(attempts.get(2).prompt())
                .isEqualTo(GenerationAppService.slicePrompt(SINGLE_SLICE_PLAN, 0,
                        "系统已生成"));

        // 静默重试（#82/#84）：无重试信号、无逐次 error（run 失败为唯一失败终态）
        verify(eventsAppService, never()).publishAgentEvent(eq(RETIRED_RETRYING), anyMap());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.ERROR), anyMap());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 收口判据与收口扩载 ----------

    @Test
    void given_converse_ok_but_service_unreachable_when_generate_then_no_generated_at_and_reinitiate_exit() {
        // 假完成（#35）：阶段 0 converse 正常结束但 8081 不可达——核验不过不落
        // generated_at，走既有重试/终态失败路径（阶段 0 失败不派切片），「继续生成」出口
        // 仍在；重发（REST 路径无交接物）沿用表内现行计划（#220 PRD 锚一致即有效）
        Long projectId = persistedProject("9810");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds(); // AGENTS.md 写入（无 curl 字样）成功
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("curl"))))
                .thenReturn(new ExecResultResponse("", "Connection refused", 7));
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("run-fake", "很抱歉，目前系统尚未真正实现出来"));

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 阶段 0 假完成 → 静默重试到超限转终态：converse 满 maxAttempts 次、不派切片
        verify(agentClient, times(properties.getMaxAttempts())).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(RETIRED_RETRYING), anyMap());
        assertThat(generatedAt(projectId)).isNull();

        // 项目不被空壳锁死：generated_at 未落 = 「继续生成」出口在——核验改可达后重发即成功
        //（重发无交接物：计划取自轨道表，模拟重启后无进程内计划副本的恢复路径）
        doReturn(new ExecResultResponse("", "", 0))
                .when(workspaceLifecycleAppService).exec(any(), any());
        appService.startGeneration(projectId);
        // 重发（断点续跑自阶段 0 重跑）= 阶段 0 + 切片各一次 converse
        verify(agentClient, times(properties.getMaxAttempts() + 2)).converse(any(), any());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_generation_when_closes_then_run_finish_carries_slice_closing() {
        // #88 收口扩载·生成轨道：切片的收尾卡权威事实随 run-finish 到达——PRD 未动
        // （生成不改 PRD）、系统产出（8081 探活收口事实）、summary = 切片叙事、变更
        // 清单 = 工具调用观察、时长在场
        Long projectId = persistedProject("9811");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "系统已生成", null, List.of(
                    new FileChange("/src/App.jsx", 40, 0),
                    new FileChange("/src/pages/Home.jsx", 60, 0)));
        });

        appService.dispatchGenerationOnTurnClose(projectId, new BuildPlan(List.of("用户能注册登录")));

        // 阶段 0 + 1 片 = 2 场收口；断言切片的收尾卡
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceClosing =
                (Map<String, Object>) payloads.getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD);
        assertThat(sliceClosing)
                .containsEntry("summary", "完成切片：用户能注册登录")
                .containsEntry("prdChanged", false)
                .containsEntry("systemChanged", true)
                .containsEntry("systemNote", "完成切片：用户能注册登录");
        assertThat(sliceClosing).doesNotContainKey("prdNote")
                .doesNotContainKey(AgentEventTypes.SELF_TEST_FIELD); // 无自测动作 → selfTest 可缺省
        assertThat((List<Map<String, Object>>) sliceClosing.get("files")).hasSize(2);
        assertThat((Long) sliceClosing.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_self_test_when_closes_then_closing_carries_self_test_total() {
        // #96 AC①：自测子智能体（source=self-test）的 command 动作按 toolCallId 去重
        // 进收尾卡统计（total = 自测跑了几项测试命令）
        Long projectId = persistedProject("9812");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.PART_ACTION, command.runId(), Map.of(
                    AgentEventTypes.SOURCE_FIELD, "self-test",
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "st-1",
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "execute",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_STARTED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行【首页探活】")));
            sink.accept(scripted(AgentEventTypes.PART_ACTION, command.runId(), Map.of(
                    AgentEventTypes.SOURCE_FIELD, "self-test",
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "st-1",
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "execute",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_COMPLETED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行【首页探活】")));
            sink.accept(scripted(AgentEventTypes.PART_ACTION, command.runId(), Map.of(
                    AgentEventTypes.SOURCE_FIELD, "self-test",
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "st-2",
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "execute",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_COMPLETED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行【留言落库】")));
            sink.accept(scripted(AgentEventTypes.PART_ACTION, command.runId(), Map.of(
                    AgentEventTypes.SOURCE_FIELD, "self-test",
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "st-3",
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "execute",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_FAILED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行【8081 常驻】")));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "系统已生成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceClosing =
                (Map<String, Object>) payloads.getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD);
        // 去重后 3 条自测命令（st-1 的 started/completed 两态只记一条）
        assertThat((Map<String, Object>) sliceClosing.get(AgentEventTypes.SELF_TEST_FIELD))
                .containsEntry(AgentEventTypes.SELF_TEST_TOTAL_FIELD, 3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_durations_when_closes_then_closing_carries_duration_breakdown() {
        // #111 收口扩载·阶段耗时分布：四桶齐备（llmMs / toolsMs 按工具名分桶·command
        // 按命令归组嵌套 / selfTestMs 委派窗 / closingMs 收口尾序）+ 逐尝试分布；agent_spawn
        // 委派调用除名（跨距 ≈ 自测窗，入桶即双计）；SSE 扩载与对话史落库同载荷；
        // 桶计与 durationMs 一致性守卫（允许小误差，量级不符即埋点有洞）
        Long projectId = persistedProject("9818");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "系统已生成", null, List.of(),
                    new StageDurations(5, Map.of("write_file", 2L, "agent_spawn", 9L),
                            Map.of("install", 3L), Map.of("self-test", 1L)));
        });

        appService.dispatchGenerationOnTurnClose(projectId, new BuildPlan(List.of("用户能注册登录")));

        // 阶段 0 + 1 片 = 2 场收口；断言切片的收尾卡
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payloads.getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> breakdown =
                (Map<String, Object>) closing.get("durationBreakdown");
        assertThat(breakdown).isNotNull()
                .containsEntry("llmMs", 5L)
                .containsEntry("selfTestMs", 1L);
        Map<String, Object> toolsMs = (Map<String, Object>) breakdown.get("toolsMs");
        assertThat(toolsMs)
                .containsEntry("write_file", 2L)
                .doesNotContainKey("agent_spawn"); // 委派调用除名（防与自测窗双计）
        assertThat((Map<String, Object>) toolsMs.get("command"))
                .containsExactly(Map.entry("install", 3L));
        long closingMs = ((Number) breakdown.get("closingMs")).longValue();
        assertThat(closingMs).isGreaterThanOrEqualTo(0L);
        // 逐尝试分布：单次成功尝试（首试）
        assertThat((List<Map<String, Object>>) breakdown.get("attempts")).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt).containsEntry("attempt", 1);
                    assertThat(attempt).containsEntry("llmMs", 5L);
                    assertThat(attempt).containsEntry("selfTestMs", 1L);
                    assertThat((Map<String, Object>) attempt.get("toolsMs"))
                            .containsEntry("write_file", 2L)
                            .doesNotContainKey("agent_spawn");
                    assertThat(((Number) attempt.get("durationMs")).longValue())
                            .isGreaterThanOrEqualTo(0L);
                });
        // 一致性守卫：桶计 ≈ durationMs（量级守卫——纳秒/微秒单位混淆即超容差；
        // 正常全 mock 内联管道为毫秒级，2s 容差只放行管道抖动）
        long bucketSum = 5L + 2L + 3L + 1L + closingMs;
        long durationMs = ((Number) closing.get("durationMs")).longValue();
        assertThat(Math.abs(bucketSum - durationMs)).isLessThan(2_000L);

        // 对话史落库同载荷（#89 腿）：读口回放带 durationBreakdown（JSONB 回读数值窄化，
        // 按 Number 断言）
        Map<String, Object> persisted = conversationHistory.read(projectId).stream()
                .filter(entry -> entry.closing() != null)
                .reduce((first, second) -> second).orElseThrow().closing();
        Map<String, Object> persistedBreakdown =
                (Map<String, Object>) persisted.get("durationBreakdown");
        assertThat(persistedBreakdown).isNotNull()
                .containsKey("llmMs").containsKey("toolsMs").containsKey("selfTestMs")
                .containsKey("closingMs").containsKey("attempts");
        assertThat(((Number) persistedBreakdown.get("llmMs")).longValue()).isEqualTo(5L);
        assertThat((Map<String, Object>) persistedBreakdown.get("toolsMs"))
                .containsKey("command")
                .doesNotContainKey("agent_spawn");
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_first_attempt_fails_when_retry_then_breakdown_carries_each_attempt() {
        // #111 静默重试代价可归因：每次尝试各自带分布——中段失败的尝试带墙钟与零桶
        //（事实随异常弃置，口径见 StageDurationFacts）、成功尝试带完整桶
        Long projectId = persistedProject("9819");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("首次尝试中断"))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                            Map.of(AgentEventTypes.FINISH_FIELD, "end")));
                    return new AgentReply(command.runId(), "系统已生成", null, List.of(),
                            new StageDurations(7, Map.of(), Map.of("test", 4L), Map.of()));
                })
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                            Map.of(AgentEventTypes.FINISH_FIELD, "end")));
                    return new AgentReply(command.runId(), "切片完成");
                });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 阶段 0 首试中段崩 → 重试成功：其收尾卡的 attempts 恰两条
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> stage0Closing =
                (Map<String, Object>) payloads.getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> breakdown =
                (Map<String, Object>) stage0Closing.get("durationBreakdown");
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) breakdown.get("attempts");
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0))
                .containsEntry("attempt", 1)
                .containsEntry("llmMs", 0L)
                .containsEntry("selfTestMs", 0L);
        assertThat((Map<String, Object>) attempts.get(0).get("toolsMs")).isEmpty();
        assertThat(((Number) attempts.get(0).get("durationMs")).longValue())
                .isGreaterThanOrEqualTo(0L);
        assertThat(attempts.get(1))
                .containsEntry("attempt", 2)
                .containsEntry("llmMs", 7L);
        assertThat((Map<String, Object>) attempts.get(1).get("toolsMs"))
                .containsExactly(Map.entry("command", Map.of("test", 4L)));
        // run 级四桶 = 跨尝试合计（首试零桶 + 重试桶）
        assertThat(breakdown).containsEntry("llmMs", 7L);
        assertThat((Map<String, Object>) breakdown.get("toolsMs"))
                .containsExactly(Map.entry("command", Map.of("test", 4L)));
    }

    // ---------- 守卫 ----------

    @Test
    void given_generation_in_flight_when_trigger_again_then_prj_017() {
        // 在途守卫（含已提交未起跑）：异步轨道占位期间重复触发拒绝——重发走 REST
        // 入口（无交接物），计划解析自轨道表（#220：派发即落库，在途期间表内已有
        // 现行计划），解析通过后撞在途占位
        Long projectId = persistedProject("9813");
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        List<Runnable> queued = new ArrayList<>();
        doAnswer(invocation -> {
            queued.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
        verify(agentClient, never()).converse(any(), any());

        // 排队任务收尾（守卫释放，不污染同上下文的后续测试）
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("run-x", "系统已生成"));
        queued.forEach(Runnable::run);
    }

    @Test
    void given_archived_or_generated_project_when_generate_then_rejected() {
        Long archivedId = persistedArchivedProject("9814");
        Long generatedId = persistedGeneratedProject("9815");

        assertThatThrownBy(() -> appService.startGeneration(archivedId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.startGeneration(generatedId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
        verify(agentClient, never()).converse(any(), any());
    }

    @Test
    void given_prd_never_produced_when_generate_then_prj_018() {
        // 「无门」指待定项不设门；PRD 从未产出 = 动作成立的前置事实缺失——直连调用
        // 也拦（编码 run 的任务就是读 PRD，无 PRD 起跑只会空烧重试）
        Long projectId = projectRepository.save(Project.create("无 PRD 项目", null,
                9816L, OWNER)).getId();

        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_PRD_NOT_PRODUCED.message());
        verify(agentClient, never()).converse(any(), any());
    }

    @Test
    void given_missing_project_when_generate_then_prj_001() {
        assertThatThrownBy(() -> appService.startGeneration(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_agents_md_write_fails_when_generate_then_no_run_and_guard_released() {
        // 资产就位失败如实上抛（环境故障口径），不起跑 run、在途守卫释放；计划未落库
        //（落库后于资产就位），重发仍带交接物再派
        Long projectId = persistedProject("9817");
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "disk full", 1));

        assertThatThrownBy(() -> appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
        verify(agentClient, never()).converse(any(), any());

        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        doReturn(new AgentReply("run-y", "系统已生成"))
                .when(agentClient).converse(any(), any());
        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);
        // 重发 = 阶段 0 + 切片各一次 converse
        verify(agentClient, times(2)).converse(any(), any());
    }

    // ---------- 测试数据 ----------

    /** 可生成形态的项目（PRD 已产出、未生成、未归档）。 */
    private Long persistedProject(String workspaceId) {
        Project project = Project.create("生成项目", null, Long.parseLong(workspaceId), OWNER);
        project.markPrdProduced();
        return projectRepository.save(project).getId();
    }

    /** 模拟 PRD 修订落定（savePrd 写出后的库事实）：刷新 prd_produced_at——版本锚换新值。 */
    private void revisePrd(Long projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markPrdProduced();
        projectRepository.save(project);
    }

    private Long persistedArchivedProject(String workspaceId) {
        Project project = Project.create("归档生成项目", null, Long.parseLong(workspaceId), OWNER);
        project.archive();
        return projectRepository.save(project).getId();
    }

    private Long persistedGeneratedProject(String workspaceId) {
        Project project = Project.create("已生成项目", null, Long.parseLong(workspaceId), OWNER);
        project.markGenerated();
        return projectRepository.save(project).getId();
    }
}
