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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.FileChange;
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
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
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
@SpringBootTest
class GenerationAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    /** 退役名（#82）：静默重试守卫的断言面——重试信号不出用户面事件流。 */
    private static final String RETIRED_RETRYING = "run-retrying";

    @Autowired
    private GenerationAppService appService;

    @Autowired
    private GenerationProperties properties;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 对话史读口（#111 收口扩载落库断言——SSE 扩载与落库同载荷的读回校验）。 */
    @Autowired
    private ConversationHistoryAppService conversationHistory;

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

    /**
     * 权限作答通道（#83）：mock 时 rails 的 await 返 false——异常残留路径会变续跑
     *（既有用例不触挂起，无影响；挂起/作答行为在 IterationAppServiceTest 镜面）。
     */
    @MockitoBean
    private RunPermissionAppService runPermissionAppService;

    /** 两命中的检索桩（下发前置注入 happy path）。 */
    private void givenKnowledgeHits() {
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。"),
                new KnowledgeHit("PRD", "连锁诊所系统", "PRD·连锁诊所管理", "范围边界：不含库存。")));
    }

    @AfterEach
    void tearDown() {
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
        // 重试 prompt 自足（#114 重试可能落在空会话）：同样携带前片交接与产出约定
        assertThat(all.get(2).prompt()).contains("阶段0交接")
                .contains(GenerationAppService.HANDOFF_PRODUCTION);
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
                eq(Map.of("projectId", projectId.toString(), "url", "http://localhost:30080")));
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

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        verify(agentClient, times(properties.getMaxAttempts())).converse(any(), any());
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                run.runId().equals(payload.get(EventsAppService.RUN_FIELD))));
        assertThat(generatedAt(projectId)).isNull();
    }

    @Test
    void given_slice_fails_when_generate_then_run_failed_and_no_next_slice() {
        // 灵魂用例（#104 失败不跳片）：阶段 0 成功、切片 1 失败——run-failed 锚失败片
        // （切片 1）的 runId，不自动跳切片 2；阶段 0 已成功但不落 generated_at（口径
        // 不变：最后一片收口才落）
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
    }

    @Test
    void given_success_when_generate_then_generated_at_persisted_on_last_slice() {
        // #104 generated_at 落最后一片收口（口径不变）：阶段 0 + 切片全程成功即落位
        Long projectId = persistedProject("9804");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.startGeneration(projectId);

        assertThat(generatedAt(projectId)).isNotNull();
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

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand value = command.getAllValues().get(0); // 阶段 0 首 run
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo(GenerationAppService.stage0Prompt(
                BuildPlan.minimalFallback()));
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
    void given_generate_when_start_then_agents_md_written_before_first_converse() {
        // 工作区布局资产就位先于首试下发：AGENTS.md 平台约定写入工作区根（幂等覆写）
        Long projectId = persistedProject("9806");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");

        appService.startGeneration(projectId);

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
                                && cmd.command().contains("不换栈、不重选型")));
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

        appService.startGeneration(projectId);

        verify(knowledgePort).retrieve(
                eq(GenerationAppService.stage0Prompt(BuildPlan.minimalFallback())), eq(5));
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .startsWith("【平台知识库·相似历史需求】")
                .contains("宠物医院预约平台").contains("非用户的确认信息")
                .endsWith("————\n\n" + GenerationAppService.stage0Prompt(
                        BuildPlan.minimalFallback()));
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

        appService.startGeneration(projectId);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .isEqualTo(GenerationAppService.stage0Prompt(BuildPlan.minimalFallback()));
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

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(command.capture(), any());
        List<AgentCommand> attempts = command.getAllValues();
        assertThat(attempts.get(0).runId()).isEqualTo(run.runId()); // 阶段 0 首试 = 首 run 身份
        assertThat(attempts.get(0).prompt())
                .endsWith("————\n\n" + GenerationAppService.stage0Prompt(
                        BuildPlan.minimalFallback()));
        assertThat(attempts.get(1).runId()).isNotEqualTo(run.runId()); // 重试内部 runId
        assertThat(attempts.get(1).prompt()).isEqualTo(
                GenerationAppService.generationRetryPrompt(BuildPlan.minimalFallback(),
                        "先起服：应用以最小可运行形态跑上 8081", null));
        assertThat(attempts.get(2).prompt())
                .isEqualTo(GenerationAppService.slicePrompt(BuildPlan.minimalFallback(), 0,
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
        // generated_at，走既有重试/终态失败路径（阶段 0 失败不派切片），重新发起出口仍在
        Long projectId = persistedProject("9810");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds(); // AGENTS.md 写入（无 curl 字样）成功
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("curl"))))
                .thenReturn(new ExecResultResponse("", "Connection refused", 7));
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("run-fake", "很抱歉，目前系统尚未真正实现出来"));

        appService.startGeneration(projectId);

        // 阶段 0 假完成 → 静默重试到超限转终态：converse 满 maxAttempts 次、不派切片
        verify(agentClient, times(properties.getMaxAttempts())).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(RETIRED_RETRYING), anyMap());
        assertThat(generatedAt(projectId)).isNull();

        // 项目不被空壳锁死：generated_at 未落 = 重新发起出口在——核验改可达后重发即成功
        doReturn(new ExecResultResponse("", "", 0))
                .when(workspaceLifecycleAppService).exec(any(), any());
        appService.startGeneration(projectId);
        // 重新发起 = 阶段 0 + 切片各一次 converse
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
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "command",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_STARTED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行【首页探活】")));
            sink.accept(scripted(AgentEventTypes.PART_ACTION, command.runId(), Map.of(
                    AgentEventTypes.SOURCE_FIELD, "self-test",
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "st-1",
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "command",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_COMPLETED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行【首页探活】")));
            sink.accept(scripted(AgentEventTypes.PART_ACTION, command.runId(), Map.of(
                    AgentEventTypes.SOURCE_FIELD, "self-test",
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "st-2",
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "command",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_COMPLETED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行【留言落库】")));
            sink.accept(scripted(AgentEventTypes.PART_ACTION, command.runId(), Map.of(
                    AgentEventTypes.SOURCE_FIELD, "self-test",
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "st-3",
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "command",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_FAILED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行【8081 常驻】")));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "系统已生成");
        });

        appService.startGeneration(projectId);

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

        appService.startGeneration(projectId);

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
        // 在途守卫（含已提交未起跑）：异步轨道占位期间重复触发拒绝
        Long projectId = persistedProject("9813");
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        List<Runnable> queued = new ArrayList<>();
        doAnswer(invocation -> {
            queued.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());

        appService.startGeneration(projectId);

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
        // 资产就位失败如实上抛（环境故障口径），不起跑 run、在途守卫释放
        Long projectId = persistedProject("9817");
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "disk full", 1));

        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
        verify(agentClient, never()).converse(any(), any());

        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        doReturn(new AgentReply("run-y", "系统已生成"))
                .when(agentClient).converse(any(), any());
        appService.startGeneration(projectId);
        // 重新发起 = 阶段 0 + 切片各一次 converse
        verify(agentClient, times(2)).converse(any(), any());
    }

    // ---------- 测试数据 ----------

    /** 可生成形态的项目（PRD 已产出、未生成、未归档）。 */
    private Long persistedProject(String workspaceId) {
        Project project = Project.create("生成项目", null, Long.parseLong(workspaceId), OWNER);
        project.markPrdProduced();
        return projectRepository.save(project).getId();
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
