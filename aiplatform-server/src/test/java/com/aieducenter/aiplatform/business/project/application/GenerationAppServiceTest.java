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

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 生成编排（#22 验收 + #24 知识命中前置注入）：编码命令全要素（coder-{projectId}
 * 会话稳定绑定、EXECUTOR 配置与长任务超时、owner 寻址、计量 dims（projectId +
 * agentKind=coder + sessionId）、项目工作区、流关联）、工作区布局资产就位先于
 * 首试下发（AGENTS.md 平台约定幂等覆写）、知识命中前置注入首试任务 prompt
 * （query = 任务 prompt；检索失败降级空注入不阻断）、重试不重注入（续同会话，
 * 注入块已在会话历史）、成功收口落 generated_at、失败自动静默重试（中间信号零发
 * + 话术 + 重试 prompt 换轨）、超限转终态（generated_at 不落、在途守卫释放可
 * 重新发起）、守卫组（不存在 / 已归档 / 已生成 / 在途重复触发）。
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
    }

    @Test
    void given_project_when_generate_then_command_bound_to_coder_session() {
        Long projectId = persistedProject("9800");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        // 编码命令全要素：coder 会话稳定绑定 + owner 寻址 + EXECUTOR 配置（平台技术
        // 约定）+ 长任务超时 + 计量 dims（#24：projectId + agentKind=coder +
        // sessionId）+ 项目工作区 + 流关联
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        AgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo(GenerationAppService.GENERATE_RUN_PROMPT);
        assertThat(value.sessionId()).isEqualTo("coder-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).isEqualTo(AgentProfile.EXECUTOR.systemPrompt())
                .contains("0.0.0.0:8081").contains("docs/PRD.md");
        assertThat(value.modelString()).isEqualTo(AgentProfile.EXECUTOR.chatModelString());
        assertThat(value.timeout()).isEqualTo(properties.getTimeout());
        assertThat(value.workspaceId()).isEqualTo("9800");
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).isEqualTo(UsageDims.of(projectId,
                UsageDims.kindOf(AgentProfile.EXECUTOR), "coder-" + projectId));
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.agentKey()).isEqualTo("executor"); // run-start 携配置键（工作消息锚）
    }

    @Test
    void given_knowledge_hits_when_generate_then_prefix_injected_before_task_prompt() {
        // 知识命中前置注入（#24）：query = 首试任务 prompt，命中块拼在任务 prompt 前
        Long projectId = persistedProject("9810");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        givenKnowledgeHits();

        appService.startGeneration(projectId);

        verify(knowledgePort).retrieve(eq(GenerationAppService.GENERATE_RUN_PROMPT), eq(5));
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .startsWith("【平台知识库·相似历史需求】")
                .contains("宠物医院预约平台").contains("非用户的确认信息")
                .endsWith("————\n\n" + GenerationAppService.GENERATE_RUN_PROMPT);
    }

    @Test
    void given_retrieval_failure_when_generate_then_degraded_plain_prompt_run_proceeds() {
        // 检索失败降级空注入：任务 prompt 原样下发，run 不被知识面阻断
        Long projectId = persistedProject("9811");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        doThrow(new RuntimeException("pgvector 抖动")).when(knowledgePort)
                .retrieve(anyString(), anyInt());

        appService.startGeneration(projectId);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .isEqualTo(GenerationAppService.GENERATE_RUN_PROMPT);
    }

    @Test
    void given_generate_when_start_then_agents_md_written_before_first_converse() {
        // 工作区布局资产就位先于首试下发：AGENTS.md 平台约定写入工作区根（幂等覆写）
        Long projectId = persistedProject("9801");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");

        appService.startGeneration(projectId);

        InOrder order = inOrder(workspaceLifecycleAppService, agentClient);
        order.verify(workspaceLifecycleAppService).exec(eq("9801"),
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
    void given_success_when_run_finishes_then_generated_at_persisted() {
        Long projectId = persistedProject("9802");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");

        appService.startGeneration(projectId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    void given_first_attempt_fails_when_retry_then_second_succeeds_silently() {
        Long projectId = persistedProject("9803");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        givenKnowledgeHits(); // 首试带前置注入，重试不重注入（注入块已在会话历史）
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("首次尝试中断"))
                .thenReturn(new AgentReply("run-2", "系统已生成"));

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        // 重试换内部 runId（计量幂等/日志逐次唯一）、prompt 换重试续作轨
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        List<AgentCommand> attempts = command.getAllValues();
        assertThat(attempts.get(0).runId()).isEqualTo(run.runId());
        assertThat(attempts.get(0).prompt())
                .endsWith("————\n\n" + GenerationAppService.GENERATE_RUN_PROMPT);
        assertThat(attempts.get(1).runId()).isNotEqualTo(run.runId());
        assertThat(attempts.get(1).prompt()).isEqualTo(GenerationAppService.RETRY_RUN_PROMPT);
        assertThat(attempts.get(1).sessionId()).isEqualTo("coder-" + projectId);
        // 一次下发一次注入：重试续同会话不重检索
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt());

        // 静默重试（#82/#84）：无重试信号、无逐次 error（run 失败为唯一失败终态）
        verify(eventsAppService, never()).publishAgentEvent(eq(RETIRED_RETRYING), anyMap());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.ERROR), anyMap());

        // 第二次尝试成功 → generated_at 落位
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
        // 重试成功 = 非终态：不发 run-failed（恢复出口不出现——正常流程全自动无门，#56）
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
    }

    /**
     * 脚本化智能体事件缝（#84 验收）的本地别名：剧本体在
     * {@link AgentEventScripts}（生成/迭代共用，契约变化单点同步）。
     */
    private static AgentEvent scripted(String type, String runId, Map<String, Object> extra) {
        return AgentEventScripts.scripted(type, runId, extra);
    }

    @Test
    void given_retryable_error_scripted_when_generate_then_user_stream_single_run_identity() {
        // #84 AC①③：注入可重试错误的脚本化 run——用户面全程无中间错误呈现、无重试
        // 信号（重试不新发 run-start、内部 attempt runId 不出用户面），重试成功只见
        // 工作消息正常生长（部件流连续）+ 正常收口
        Long projectId = persistedProject("9813");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any()))
                // 首试：开场 + 解说 + 动作在途，中途错误（底座补 error 事件）后异常上浮
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(), Map.of(
                            "prompt", command.prompt(), "model", "m", "role", "CODER")));
                    sink.accept(scripted(AgentEventTypes.PART_TEXT, command.runId(), Map.of(
                            AgentEventTypes.PART_TEXT_FIELD, "先搭骨架")));
                    sink.accept(scripted(AgentEventTypes.PART_ACTION, command.runId(), Map.of(
                            AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "tc-1",
                            AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "write_file",
                            AgentEventTypes.PART_ACTION_STATE_FIELD,
                                    AgentEventTypes.PART_ACTION_STATE_RUNNING,
                            AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【首页】")));
                    sink.accept(scripted(AgentEventTypes.ERROR, command.runId(), Map.of(
                            AgentEventTypes.ERROR_MESSAGE_FIELD, "模型调用中断")));
                    throw new IllegalStateException("模型调用中断");
                })
                // 重试尝试（内部新 runId）：真实客户端会再发 run-start 与部件、收口 run-finish
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(), Map.of(
                            "prompt", command.prompt(), "model", "m", "role", "CODER")));
                    sink.accept(scripted(AgentEventTypes.PART_TEXT, command.runId(), Map.of(
                            AgentEventTypes.PART_TEXT_FIELD, "从中断处继续")));
                    sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(), Map.of(
                            AgentEventTypes.FINISH_FIELD, "end")));
                    return new AgentReply(command.runId(), "系统已生成");
                });

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        // 用户面事件序列：恰一次 run-start（锚首试 runId）、部件流连续、自检播报
        //（#85：收口判据核验「检查中 → 通过」，位于真收口 run-finish 前）、run-finish
        // 收口；零 error、零重试开场、零 run-failed——重试族过程事实零外泄
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(7)).publishAgentEvent(types.capture(), payloads.capture());
        assertThat(types.getAllValues()).containsExactly(
                AgentEventTypes.RUN_START, AgentEventTypes.PART_TEXT,
                AgentEventTypes.PART_ACTION, AgentEventTypes.PART_TEXT,
                AgentEventTypes.PART_CHECK, AgentEventTypes.PART_CHECK,
                AgentEventTypes.RUN_FINISH);
        // 自检播报状态序：检查中 → 通过（首试死于 converse 中途、未进核验，恰一对）
        assertThat(payloads.getAllValues().get(4))
                .containsEntry(AgentEventTypes.PART_CHECK_STATE_FIELD,
                        AgentEventTypes.PART_CHECK_STATE_CHECKING)
                .containsEntry(AgentEventTypes.SESSION_FIELD, "coder-" + projectId);
        assertThat(payloads.getAllValues().get(5))
                .containsEntry(AgentEventTypes.PART_CHECK_STATE_FIELD,
                        AgentEventTypes.PART_CHECK_STATE_PASSED);
        assertThat(payloads.getAllValues())
                .allSatisfy(payload -> assertThat(payload.get(AgentEventTypes.RUN_FIELD))
                        .isEqualTo(run.runId()))
                .noneSatisfy(payload -> assertThat(String.valueOf(payload.get("prompt")))
                        .contains("上一次尝试中断")); // 内部重试 prompt 不外泄
        // 重试成功 → generated_at 落位（唯一终态是成功的收口，非失败）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    void given_fake_close_then_retry_scripted_when_generate_then_run_finish_only_at_true_close() {
        // #84 假完成不闪收口：converse 正常返回但收口判据不过（8081 不可达）= 该次
        // 尝试失败走重试——中场 run-finish 不出用户面（定格了又生长 = 重试信号外泄），
        // 真收口才发；工作消息只见连续生长
        Long projectId = persistedProject("9814");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds(); // AGENTS.md 写入（无 curl 字样）成功
        // 收口核验探针：首试不可达、重试可达
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("curl"))))
                .thenReturn(new ExecResultResponse("", "Connection refused", 7))
                .thenReturn(new ExecResultResponse("", "", 0));
        // 剧本：每次尝试都正常收口（开场 + 解说 + run-finish）——判据成败由探针定
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(), Map.of(
                    "prompt", command.prompt(), "model", "m", "role", "CODER")));
            sink.accept(scripted(AgentEventTypes.PART_TEXT, command.runId(), Map.of(
                    AgentEventTypes.PART_TEXT_FIELD, "本尝试完工")));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(), Map.of(
                    AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "完工");
        });

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        // 用户面事件序列：恰一次 run-start（首试）、部件流连续、自检播报（#85：
        // 首试核验未过不出 ❌——部件停在「检查中」，重试不外泄；重试核验过出
        // 「通过」）、恰一次 run-finish（真收口）——全锚首试 runId；零 error、零 run-failed
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(7)).publishAgentEvent(types.capture(), payloads.capture());
        assertThat(types.getAllValues()).containsExactly(
                AgentEventTypes.RUN_START, AgentEventTypes.PART_TEXT,
                AgentEventTypes.PART_CHECK, AgentEventTypes.PART_TEXT,
                AgentEventTypes.PART_CHECK, AgentEventTypes.PART_CHECK,
                AgentEventTypes.RUN_FINISH);
        // 自检状态序：检查中（首试未过，静默）→ 检查中（重试核验）→ 通过——全程无 failed
        ArgumentCaptor<Map<String, Object>> checks = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(3)).publishAgentEvent(eq(AgentEventTypes.PART_CHECK),
                checks.capture());
        assertThat(checks.getAllValues())
                .extracting(payload -> payload.get(AgentEventTypes.PART_CHECK_STATE_FIELD))
                .containsExactly(
                        AgentEventTypes.PART_CHECK_STATE_CHECKING,
                        AgentEventTypes.PART_CHECK_STATE_CHECKING,
                        AgentEventTypes.PART_CHECK_STATE_PASSED);
        assertThat(payloads.getAllValues()).allSatisfy(payload ->
                assertThat(payload.get(AgentEventTypes.RUN_FIELD)).isEqualTo(run.runId()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_generation_when_closes_then_run_finish_carries_generation_closing() {
        // #88 收口扩载·生成轮：收尾卡权威事实随 run-finish 到达——PRD 未动（生成不
        // 改 PRD）、系统产出（8081 探活收口事实）、变更清单 = 工具调用观察、时长在场
        Long projectId = persistedProject("9816");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(), Map.of(
                    "prompt", command.prompt(), "model", "m", "role", "CODER")));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(), Map.of(
                    AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "系统已生成", null, List.of(
                    new FileChange("/src/App.jsx", 40, 0),
                    new FileChange("/src/pages/Home.jsx", 60, 0)));
        });

        appService.startGeneration(projectId);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing)
                .containsEntry("summary", "首次生成了系统")
                .containsEntry("prdChanged", false)
                .containsEntry("systemChanged", true);
        assertThat(closing).doesNotContainKey("prdNote").doesNotContainKey("systemNote");
        assertThat((List<Map<String, Object>>) closing.get("files")).hasSize(2);
        assertThat((Long) closing.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void given_all_attempts_fail_when_exceed_limit_then_terminal_without_generated_at() {
        // #84 AC②：注入不可恢复错误——唯一失败终态（run-failed）呈现，恢复出口可用
        Long projectId = persistedProject("9804");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        // 剧本：每次尝试都开场后中途错误（error 事件 + 异常上浮）——全程不可恢复
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(), Map.of(
                    "prompt", command.prompt(), "model", "m", "role", "CODER")));
            sink.accept(scripted(AgentEventTypes.ERROR, command.runId(), Map.of(
                    AgentEventTypes.ERROR_MESSAGE_FIELD, "持续失败")));
            throw new IllegalStateException("持续失败");
        });

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        // 超限转终态：恰 maxAttempts 次尝试、全程静默（无重试信号 / error）、
        // generated_at 不落
        int maxAttempts = properties.getMaxAttempts();
        ArgumentCaptor<AgentCommand> attempts = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(maxAttempts)).converse(attempts.capture(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(RETIRED_RETRYING), anyMap());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.ERROR), anyMap());
        // 重试尝试的 run-start 不外泄：用户面全程恰一次开场（首试）
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_START), anyMap());
        // 终态收口事件（#56）：run-failed 恰一次、锚该场 run 的用户面标识（首试
        // runId——重试不换新锚，#84）——前端恢复出口只认本事件（run 失败为唯一失败终态）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                projectId.toString().equals(payload.get(EventsAppService.PROJECT_FIELD))
                        && run.runId().equals(payload.get(EventsAppService.RUN_FIELD))));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNull();

        // 在途守卫已释放：用户重新发起兜底路径可再走（generated_at 未落 = 按钮口径仍在）
        // ——重打桩走 doReturn（旧 thenAnswer 仍在效期，when() 内调用会先跑旧桩）
        doReturn(new AgentReply("run-again", "系统已生成"))
                .when(agentClient).converse(any(), any());
        appService.startGeneration(projectId);
        verify(agentClient, times(maxAttempts + 1)).converse(any(), any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    void given_converse_ok_but_service_unreachable_when_generate_then_no_generated_at_and_reinitiate_exit() {
        // 假完成（#35）：converse 正常结束（模型道歉式放弃 / 被 maxIters 掐断）但 8081
        // 不可达——核验不过不落 generated_at，走既有重试/终态失败路径，重新发起出口仍在。
        Long projectId = persistedProject("9812");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds(); // AGENTS.md 写入（无 curl 字样）成功
        // 收口核验探针（curl 8081）不可达 → 核验不过（按命令内容区分，不依赖调用次序）
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("curl"))))
                .thenReturn(new ExecResultResponse("", "Connection refused", 7));
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("run-fake", "很抱歉，目前系统尚未真正实现出来"));

        appService.startGeneration(projectId);

        // 核验不过 → 静默重试到超限转终态：converse 满 maxAttempts 次、中间信号零发
        int maxAttempts = properties.getMaxAttempts();
        verify(agentClient, times(maxAttempts)).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(RETIRED_RETRYING), anyMap());
        // 假完成不落 generated_at（AC①：8081 不可达不再落 generated_at）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNull();

        // 项目不被空壳锁死（AC②）：generated_at 未落 = 重新发起出口在——核验改可达后重发即成功
        doReturn(new ExecResultResponse("", "", 0))
                .when(workspaceLifecycleAppService).exec(any(), any());
        appService.startGeneration(projectId);
        verify(agentClient, times(maxAttempts + 1)).converse(any(), any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    void given_close_check_fails_all_attempts_when_generate_then_check_failed_before_run_failed() {
        // 自检播报（#85）终态面：收口核验全程不过（converse 正常返回但 8081 始终不可达）
        // ——逐次「检查中」（尝试间核验未过不出 ❌，静默重试同构口径），末次未过出 ❌，
        // ❌ 先于 run-failed 到达（同终态窗口）；全锚用户面 run 标识（首试 runId）
        Long projectId = persistedProject("9815");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds(); // AGENTS.md 写入成功；探针（curl）全程不可达
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("curl"))))
                .thenReturn(new ExecResultResponse("", "Connection refused", 7));
        givenConverseSucceeds("很抱歉，没做完"); // 剧本无部件事件——用户面只剩自检与终态

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        int maxAttempts = properties.getMaxAttempts();
        // 自检状态序：逐次尝试各一发 checking（尝试间核验未过不出 ❌），末次核验
        // 落定补一发 failed——恰 maxAttempts+1 条 = checking×n + failed
        ArgumentCaptor<Map<String, Object>> checks = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(maxAttempts + 1)).publishAgentEvent(
                eq(AgentEventTypes.PART_CHECK), checks.capture());
        List<String> expectedStates = new ArrayList<>(
                java.util.Collections.nCopies(maxAttempts, AgentEventTypes.PART_CHECK_STATE_CHECKING));
        expectedStates.add(AgentEventTypes.PART_CHECK_STATE_FAILED);
        assertThat(checks.getAllValues())
                .extracting(payload -> payload.get(AgentEventTypes.PART_CHECK_STATE_FIELD))
                .containsExactlyElementsOf(expectedStates);
        assertThat(checks.getAllValues()).allSatisfy(payload -> {
            assertThat(payload.get(AgentEventTypes.RUN_FIELD)).isEqualTo(run.runId());
            assertThat(payload.get(AgentEventTypes.SESSION_FIELD)).isEqualTo("coder-" + projectId);
        });
        // ❌ 与 run-failed 同窗口且先于它（前端恢复出口只认 run-failed——❌ 先到不抢终态）
        InOrder order = inOrder(eventsAppService);
        order.verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.PART_CHECK),
                argThat(payload -> AgentEventTypes.PART_CHECK_STATE_FAILED.equals(
                        payload.get(AgentEventTypes.PART_CHECK_STATE_FIELD))));
        order.verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
    }

    @Test
    void given_generation_in_flight_when_trigger_again_then_prj_017() {
        // 在途守卫（含已提交未起跑）：异步轨道占位期间重复触发拒绝
        Long projectId = persistedProject("9805");
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
        Long archivedId = persistedArchivedProject("9806");
        Long generatedId = persistedGeneratedProject("9807");

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
                9809L, OWNER)).getId();

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
        Long projectId = persistedProject("9808");
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
        verify(agentClient).converse(any(), any());
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
