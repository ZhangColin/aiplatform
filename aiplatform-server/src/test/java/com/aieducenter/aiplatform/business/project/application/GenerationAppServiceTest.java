package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
        // 灵魂用例（#104 轨道顺序）：阶段 0（先起服）固定前置，其后按切片计划顺序逐片
        // 派 run——首 run = 阶段 0 起服 prompt、随后逐片切片 prompt，全落 coder 会话
        Long projectId = persistedProject("9800");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        assertThat(all).extracting(AgentCommand::prompt).containsExactly(
                GenerationAppService.STAGE0_RUN_PROMPT,
                GenerationAppService.sliceRunPrompt(1, 2, "用户能注册登录"),
                GenerationAppService.sliceRunPrompt(2, 2, "用户能下单支付"));
        assertThat(all).allSatisfy(cmd ->
                assertThat(cmd.sessionId()).isEqualTo("coder-" + projectId));
        // 每场 run 各自的首试 runId 互不相同（阶段 0 首 run 身份 + 切片逐片新 runId）
        assertThat(all).extracting(AgentCommand::runId).doesNotHaveDuplicates();
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
        assertThat(value.prompt()).isEqualTo(GenerationAppService.STAGE0_RUN_PROMPT);
        assertThat(value.sessionId()).isEqualTo("coder-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).isEqualTo(AgentProfile.EXECUTOR.systemPrompt())
                .contains("0.0.0.0:8081").contains("docs/PRD.md");
        assertThat(value.modelString()).isEqualTo(AgentProfile.EXECUTOR.chatModelString());
        assertThat(value.timeout()).isEqualTo(properties.getTimeout());
        assertThat(value.workspaceId()).isEqualTo("9805");
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).isEqualTo(UsageDims.of(projectId,
                UsageDims.kindOf(AgentProfile.EXECUTOR), "coder-" + projectId));
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
                                && cmd.command().contains("增量长出页面与功能")));
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

        verify(knowledgePort).retrieve(eq(GenerationAppService.STAGE0_RUN_PROMPT), eq(5));
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .startsWith("【平台知识库·相似历史需求】")
                .contains("宠物医院预约平台").contains("非用户的确认信息")
                .endsWith("————\n\n" + GenerationAppService.STAGE0_RUN_PROMPT);
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
                .isEqualTo(GenerationAppService.STAGE0_RUN_PROMPT);
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
                .endsWith("————\n\n" + GenerationAppService.STAGE0_RUN_PROMPT);
        assertThat(attempts.get(1).runId()).isNotEqualTo(run.runId()); // 重试内部 runId
        assertThat(attempts.get(1).prompt()).isEqualTo(
                GenerationAppService.retryRunPrompt("先起服：应用以最小可运行形态跑上 8081"));
        assertThat(attempts.get(2).prompt())
                .endsWith(GenerationAppService.sliceRunPrompt(1, 1, "用户能使用 PRD 描述的全部功能"));

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
