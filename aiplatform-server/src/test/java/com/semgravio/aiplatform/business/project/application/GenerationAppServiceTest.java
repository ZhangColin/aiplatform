package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentTurnFailure;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.AgentscopeHarnessAgentFactory;
import com.semgravio.aiplatform.base.agentscope.FileChange;
import com.semgravio.aiplatform.base.agentscope.KernelSystemPrompt;
import com.semgravio.aiplatform.base.agentscope.RunHeading;
import com.semgravio.aiplatform.base.agentscope.StageDurations;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.agentscope.AgentShutdownGate;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ConversationEntryKind;
import com.semgravio.aiplatform.business.project.domain.enums.GenerationState;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.ProjectFiles;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 生成编排（#22 验收 + #24 知识命中前置注入 + #104 生成轨道——先起服 + 纵向切片逐段）：
 * 编码命令全要素（coder-{projectId} 会话稳定绑定、EXECUTOR 配置与长任务超时、owner
 * 寻址、计量 dims、项目工作区、流关联）；工作区布局资产就位先于首试下发；知识命中
 * 前置注入；成功收口落 generated_at。#604 起失败语义＝明确展示＋重跑即恢复——
 * 尝试环/静默重试/免预算重试账随旧 run 尝试体一并退役（本类重试系用例随机制删除）。
 *
 * <p><b>生成轨道（#104）</b>：阶段 0（先起服白底页）固定前置，其后按切片计划顺序逐片
 * 派 run——轨道顺序派 N run、阶段 0 先于切片、每片收口发 run-finish（收口扩载）、
 * 失败不跳片（某片超限转终态 run-failed、generated_at 落最后一片收口）。</p>
 *
 * <p><b>首屏界面先行与入口文件写全（#339）</b>：首片语义＝先出可点的界面（首片
 * prompt 界面先行、其后各片端到端纵向切片）；收口判据＝8081 可达＋入口文件完整
 * （残缺「其余代码略」式占位不收口）。</p>
 */
@IntegrationTest
class GenerationAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    /** 退役名（#82）：静默重试守卫的断言面——重试信号不出用户面事件流。 */
    private static final String RETIRED_RETRYING = "run-retrying";

    @Autowired
    private GenerationAppService appService;

    /** 详情读口（#222 生成态投影断言面——REST 行为，不触内部方法）。 */
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

    /** PRD 落盘登记适配器（#542 两入口退役断言——真 savePrd 回调侧零派发）。 */
    @Autowired
    private com.semgravio.aiplatform.business.project.infrastructure.PrdArtifactAdapter prdArtifacts;

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

    /** 停止面（#359 生成取消）——停止请求的注入口（测试经公开 requestStop 触发）。 */
    @Autowired
    private CodingRunTrack codingRunTrack;

    /** 两命中的检索桩（下发前置注入 happy path；#466 多类同池：PRD＋沉淀物）。 */
    private void givenKnowledgeHits() {
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。"),
                new KnowledgeHit("MODULE", "平台沉淀", "预约时段管理模块",
                        "时段表按周模板配置，节假日单独覆盖。")));
    }

    @BeforeEach
    void ensureChannelKeyForRealResolution() {
        // 真解析链护卫（ADR-0031）：命令构建经 ModelConfigAppService 解析种子配置行（V79 收敛后＝deepseek 官方），
        // 测试环境无 key 注入——幂等补测试 key
        ModelConfigSeed.ensureChannelKey(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_demo_versions");
        jdbcTemplate.update("DELETE FROM prj_agent_configs");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
        // 配置夹具归零（先子后父：候选行 FK 指配置——V79 起候选表在库）
        jdbcTemplate.update("DELETE FROM prj_model_config_candidates WHERE config_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_configs WHERE config_key LIKE 't-%'");
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            // submit 起返回 Future（#359 停止通道）：直通已同步跑完——已完成 future
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(sessionExecutor).submitGeneration(any(), any());
    }

    /** 智能体边界正常收口脚本：converse 返回正常收口的 AgentReply（无挂起面）。 */
    private void givenConverseSucceeds(String text) {
        when(agentClient.converse(any(AgentCommand.class), any()))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0, AgentCommand.class);
                    return new AgentReply(command.runId(), text);
                });
    }

    /** 智能体边界正常收口并发出 run-finish（收口扩载断言用：closing 只在真收口时产生）。 */
    private void givenConverseFinishing(String text) {
        when(agentClient.converse(any(AgentCommand.class), any()))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0, AgentCommand.class);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                            Map.of(AgentEventTypes.FINISH_FIELD, "end")));
                    return new AgentReply(command.runId(), text);
                });
    }

    /**
     * 链式收口脚本（thenThrow/thenReturn 链的带验证登记形，登记仍作观测面——
     * #435 起非收口要件）：第 n 次调用按序取 outcomes（{@link AgentReply}＝成功回复〔先登记
     * verify〕、{@link RuntimeException}＝该次失败）；超出链长重复末位（Mockito
     * 链语义同形）。
     */
    private void givenConverseChain(Object... outcomes) {
        AtomicInteger calls = new AtomicInteger();
        // do 家族（首桩与重打桩通吃）：when(...) 家族重打桩时会先参调旧链桩抛错
        doAnswer(invocation -> {
            Object outcome = outcomes[Math.min(calls.getAndIncrement(), outcomes.length - 1)];
            if (outcome instanceof RuntimeException failure) {
                throw failure;
            }
            AgentCommand command = invocation.getArgument(0);
            return (AgentReply) outcome;
        }).when(agentClient).converse(any(), any());
    }

    /** 工作区 exec 全成功（收口判据双件＋预览 URL 的统一桩）。 */
    private void givenWorkspaceExecSucceeds() {
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        // 收口推 URL（#105）：探活通过后取预览 URL（成功路径的统一桩）
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

    /** 首建点火时点读回（null = 首建未派发——岔路口卡/PRD 确认按钮的呈现条件成立）。 */
    private java.sql.Timestamp generationRequestedAt(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT generation_requested_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId);
    }

    // ---------- 内核 run 生命周期（#601 接线；切片/轨道机器 #602 已拆） ----------

    @Test
    void given_shutdown_interrupts_inflight_call_when_attempt_fails_then_no_retry_no_terminal_failure() {
        // #477 关停旁路（2026-11-12 19:03 生产实录，#604 单发形态顺延）：SIGTERM 优雅
        // 关停中断在飞模型调用（AgentShuttingDownException 经 AgentTurnFailure 上浮）
        // ——关停错误按关停中断收场：不发终态事件；run 落「中断可重跑」态（片保持
        // 待跑、无 run-failed），后续片不再派发（关停宽限窗内不拉起排队轮）
        Long projectId = persistedProject("9817");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (converseCalls.incrementAndGet() == 1) {
                // 首试在飞时关停置位（内核 JVM 钩子语义；经 base.agentscope 测试缝
                // ——business 不直触内核）＋流段失败上浮（关停态下一切失败按关停
                // 中断收场，异常形不限关停异常——真实链形不可在 RUNNING 态构造）
                AgentShutdownGate.enterShutdownForTesting();
                throw new AgentTurnFailure("智能体调用失败（runId=" + command.runId()
                        + "）：Agent is shutting down", new IllegalStateException("interrupted"),
                        List.of(), StageDurations.zero());
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId);

        try {
            // 单发一次尝试即关停收场——事故形态（升档秒败连环烧预算）不复现
            assertThat(converseCalls.get()).isEqualTo(1);
            // 中断可重跑：无 run-failed 失败终态、不落完成位（内核单 run——重跑即重发）
            verify(eventsAppService, never())
                    .publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
            assertThat(generatedAt(projectId)).isNull();
        }
        finally {
            AgentShutdownGate.exitShutdownForTesting();
        }
    }

    @Test
    void given_slices_when_generate_then_commands_carry_work_message_heading() {
        // #118 工作消息头部标题（#601 内核形态顺延）：内核 run 携「系统初始化」
        //（无进度——非切片），run-start 的 slice 字段呈现源
        Long projectId = persistedProject("9821");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::heading)
                .containsExactly(RunHeading.titled(GenerationAppService.STAGE0_TITLE));
    }

    @Test
    void given_stop_requested_mid_slice_when_generate_then_no_retry_stopped_terminal_and_resumable() {
        // 灵魂用例（#359 AC②③，#601 内核形态顺延）：内核 run 在途时用户叫停——停止
        // 不走重试环（一次尝试即收场）、run-failed 载荷带 stopped=true（失败账口径
        // 如实收口）；「继续生成」照常可续——重发即新内核 run（收口判据照跑）
        Long projectId = persistedProject("9845");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            if (converseCalls.incrementAndGet() == 1) {
                // 首试在途被中断（Future.cancel 线程中断打断模型流的现场等价物）
                codingRunTrack.requestStop(projectId);
                throw new IllegalStateException("java.lang.InterruptedException");
            }
            return new AgentReply(command.runId(), "完成");
        });

        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId);

        // 停止不重试：首试一次 converse 即收场（重试环被停止旁路）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        // run-failed(stopped=true) 锚被停场次（失败账口径——run 失败仍是唯一失败终态）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                run.runId().equals(payload.get(EventsAppService.RUN_FIELD))
                        && Boolean.TRUE.equals(payload.get(AgentEventTypes.RUN_STOPPED_FIELD))));
        // 在途 run 不落完成位（重跑入口不受投影档位影响）
        assertThat(generatedAt(projectId)).isNull();
        assertThat(queryAppService.detail(projectId).generationState())
                .isNotIn(GenerationState.GENERATING, GenerationState.GENERATED);
        // 重跑：新内核 run 照跑收口判据
        appService.startGeneration(projectId);
        verify(agentClient, times(2)).converse(any(), any());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_knowledge_hits_when_generate_slices_then_knowledge_injected_only_stage0() {
        // #114 一次切入一次注入（#601 内核形态顺延）：知识命中前置注入只在首跑，
        // 不逐轮重检索重注入（注入口径不膨胀）
        Long projectId = persistedProject("9831");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("完成");
        givenKnowledgeHits();

        appService.dispatchGenerationOnTurnClose(projectId);

        // 内核单 run：检索一次、首跑 prompt 前置知识块
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt());
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(0).prompt()).startsWith("【平台知识库·相似知识】");
    }

    @Test
    void given_slices_when_generate_then_demo_seed_applied_once_at_last_closing() {
        // #340 演示种子预置（#601 内核形态顺延）：内核单 run 单收口——收口即施用
        // 种子（Demo 将可见前一刻、表已由 run 长出），先于 preview-ready（用户首屏
        // 即有内容可看）
        Long projectId = persistedProject("9833");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId);

        ArgumentCaptor<WorkspaceExecCommand> execs = ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(1)).exec(eq("9833"), execs.capture());
        List<String> seedExecs = execs.getAllValues().stream()
                .map(WorkspaceExecCommand::command)
                .filter(cmd -> cmd.contains("/opt/seed/apply.sh"))
                .toList();
        assertThat(seedExecs).hasSize(1);
        // 施用先于 preview-ready（seed → exposePreview 的相对序）
        InOrder order = inOrder(workspaceLifecycleAppService);
        order.verify(workspaceLifecycleAppService).exec(eq("9833"),
                argThat(cmd -> cmd != null && cmd.command().contains("/opt/seed/apply.sh")));
        order.verify(workspaceLifecycleAppService).exposePreview(anyString());
    }

    @Test
    void given_seed_exec_fails_when_generate_then_last_closing_not_blocked() {
        // #340 失败不阻断：种子施用抛错也不影响收口（首版落位照常）
        Long projectId = persistedProject("9834");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("完成");
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("/opt/seed/apply.sh"))))
                .thenThrow(new IllegalStateException("容器 exec 失败"));

        appService.dispatchGenerationOnTurnClose(projectId);

        assertThat(generatedAt(projectId)).isNotNull();
        // 内核收口推一次 preview-ready（种子施用后的终态首屏）
        verify(eventsAppService, times(1)).publishNotification(eq(ProjectEventTypes.PREVIEW_READY),
                anyMap());
    }

    @Test
    void given_slice_closes_when_generate_then_preview_ready_pushed_with_url() {
        // #105 URL 事件驱动（#601 内核形态顺延）：收口（8081 探活通过）推
        // preview-ready(URL)——前端 bridge 写预览查询缓存，免 3s 轮询拿 URL
        Long projectId = persistedProject("9820");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId);

        // 内核收口推一次 preview-ready
        verify(eventsAppService, times(1)).publishNotification(eq(ProjectEventTypes.PREVIEW_READY),
                argThat(payload -> projectId.toString().equals(payload.get("projectId"))
                        && "http://localhost:30080".equals(payload.get("url"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_run_fails_when_generate_then_failure_face_persisted_same_payload_as_sse() {
        // #418 灵魂用例（观测地基批）的 #604 单发形态：本场 run 失败即转终态——失败面
        // （逐尝试墙钟/配置/失败原因/本场累计）随 run-failed SSE 扩载与对话史落库
        // 同载荷（同收尾卡「SSE 扩载与落库同载荷」先例）——失败作废可归因（#313「33%
        // 失败作废」从此可测），速度观测不再只看成功者；平台分析口径、前端不渲染
        // （同 durationBreakdown）。失败语义＝明确展示＋重跑即恢复（不自动重试）。
        Long projectId = persistedProject("9860");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(agentClient.converse(any(), any())).thenThrow(new IllegalStateException("起服失败"));

        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId);

        // SSE 扩载：run-failed 载荷携 failure 面——单发 attempts 恰一条（带轮次/
        // 配置/墙钟/桶）、error = 末次错误现场原文、durationMs = 本场累计墙钟
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), payloads.capture());
        assertThat(payloads.getValue().get(EventsAppService.RUN_FIELD)).isEqualTo(run.runId());
        Map<String, Object> face = (Map<String, Object>) payloads.getValue()
                .get(AgentEventTypes.FAILURE_FIELD);
        assertThat(face).isNotNull();
        assertThat(face).containsEntry("error", "起服失败");
        assertThat(((Number) face.get("durationMs")).longValue()).isGreaterThanOrEqualTo(0L);
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) face.get("attempts");
        assertThat(attempts).hasSize(1); // 单发：一次尝试即终态（#604 失败新语义）
        assertThat(attempts).extracting(attempt -> ((Number) attempt.get("attempt")).intValue())
                .containsExactly(1);
        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(String.valueOf(attempt.get("modelId"))).isNotBlank(); // 逐尝试配置可归因
            assertThat(((Number) attempt.get("durationMs")).longValue()).isGreaterThanOrEqualTo(0L);
        });
        // 落库同载荷：对话史读回失败留账行（kind=8，锚失败 runId）——失败面与 SSE
        // 载荷同构（JSONB 回读数值窄化，按值归一比对）
        Map<String, Object> persisted = conversationHistory.read(projectId).stream()
                .filter(entry -> entry.failure() != null)
                .reduce((first, second) -> second).orElseThrow().failure();
        assertThat(normalized(persisted)).isEqualTo(normalized(face));
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_stop_requested_mid_slice_when_generate_then_stopped_failure_face_ledgered() {
        // #418 停止口径（AC④行为不变·仅增落库面）：run-failed(stopped=true) 照常收口
        // （不重试不回滚、已收口片保留），失败面携 stopped=true＋中断尝试的逐尝试账
        // ——停止是用户动作无失败原因（不携 error）；同载荷落库
        Long projectId = persistedProject("9861");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            if (converseCalls.incrementAndGet() == 1) {
                codingRunTrack.requestStop(projectId);
                throw new IllegalStateException("java.lang.InterruptedException");
            }
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId);

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED),
                payloads.capture());
        Map<String, Object> face = (Map<String, Object>) payloads.getValue()
                .get(AgentEventTypes.FAILURE_FIELD);
        assertThat(payloads.getValue())
                .containsEntry(AgentEventTypes.RUN_STOPPED_FIELD, true); // 停止口径不变
        assertThat(face)
                .containsEntry(AgentEventTypes.RUN_STOPPED_FIELD, true)
                .doesNotContainKey("error"); // 停止无失败原因可报
        assertThat(((Number) face.get("durationMs")).longValue()).isGreaterThanOrEqualTo(0L);
        // 中断尝试留账：切片 1 首试被打断——恰一条逐尝试（部分观察，零桶如实）
        assertThat((List<Map<String, Object>>) face.get("attempts")).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt).containsEntry("attempt", 1);
                    assertThat(((Number) attempt.get("durationMs")).longValue())
                            .isGreaterThanOrEqualTo(0L);
                });
        // 同载荷落库（停止口径的失败留账行）
        Map<String, Object> persisted = conversationHistory.read(projectId).stream()
                .filter(entry -> entry.failure() != null)
                .reduce((first, second) -> second).orElseThrow().failure();
        assertThat(normalized(persisted)).isEqualTo(normalized(face));
    }

    @Test
    void given_success_when_generate_then_generated_at_persisted_on_last_slice() {
        // #104 generated_at 落位（口径顺延）：内核 run 全程成功即落位
        Long projectId = persistedProject("9804");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId);

        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 生成态投影（#222；#604 四态归一——INTERRUPTED 枚举随旧失败语义退役） ----------

    @Test
    void given_generation_lifecycle_when_detail_then_state_projection() {
        // 灵魂用例（#222 AC① 顺延）：从未生成 →（派发起跑，含排队段）生成中 →（失败
        // 终态——单发即终态，#604 失败新语义；失败档并入从未生成）→（「继续生成」
        // 重跑起跑）生成中 →（收口）已生成——投影由 generated_at＋在途标记派生，
        // 读口 = REST 详情（与 SSE 会话态无关）
        Long projectId = persistedProject("9855");
        givenWorkspaceExecSucceeds();
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.NEVER_GENERATED);

        // 派发即生成中（已提交未起跑的排队段也算在途——在途标记先于 run 执行）
        List<Runnable> submitted = new ArrayList<>();
        doAnswer(invocation -> {
            submitted.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            submitted.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());
        appService.dispatchGenerationOnTurnClose(projectId);
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.GENERATING);

        // 起跑后本场失败转终态 → 未生成不在途（「继续生成」出口不受档位影响；
        // 进程重启丢在途标记同落此档）
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("起服失败"));
        submitted.remove(0).run();
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.NEVER_GENERATED);

        // 「继续生成」（REST 出口）→ 再次生成中；收口 → 已生成（generated_at 落位
        // 恒赢——此后迭代/修正在途不再改变生成态）
        givenConverseChain(new AgentReply("r0", "完成"), new AgentReply("r1", "完成"));
        appService.startGeneration(projectId);
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.GENERATING);
        submitted.remove(0).run();
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.GENERATED);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 命令与资产 ----------

    @Test
    void given_project_when_generate_then_kernel_command_bound_to_kernel_session() {
        // 编码命令全要素（内核首 run）：kernel 会话稳定绑定 + owner 寻址 +
        // 模型配置解析 + 长任务超时 + 计量 dims + 项目工作区 + 流关联
        Long projectId = persistedProject("9805");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("已生成");

        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        AgentCommand value = command.getAllValues().get(0); // 内核首 run
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo(GenerationAppService.KERNEL_FIRST_RUN_PROMPT);
        assertThat(value.sessionId())
                .isEqualTo(GenerationAppService.kernelSession(projectId, run.runId()));
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        // 内核形态四件（#601）：权威系统提示＋内核装配键＋kernelForm＋无工具规格
        assertThat(value.systemPrompt()).isEqualTo(KernelSystemPrompt.text())
                .contains("0.0.0.0:8081").contains("docs/PRD.md");
        assertThat(value.agentKey())
                .isEqualTo(AgentscopeHarnessAgentFactory.KERNEL_AGENT_NAME);
        assertThat(value.kernelForm()).isTrue();
        assertThat(value.toolSpec()).isNull();
        assertThat(value.modelString()).isEqualTo(ModelConfigSeed.FLASH_MODEL_STRING);
        // #322 活超时替换一次性总墙钟：编码命令只携静默判死阈值，不再携总时长上限
        assertThat(value.timeout()).isNull();
        assertThat(value.silenceTimeout()).isEqualTo(properties.getSilenceTimeout());
        assertThat(value.workspaceId()).isEqualTo("9805");
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).isEqualTo(UsageDims.of(projectId,
                UsageDims.kindOf(AgentProfile.EXECUTOR),
                GenerationAppService.kernelSession(projectId, run.runId())));
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
    }

    @Test
    void given_fork_card_when_start_generation_then_kernel_command_reaches_client() {
        // #601 接线验收缝（岔路口「立即生成」入口）：startGeneration 直派——到达
        // 客户端的命令为内核形态（权威系统提示＋内核装配键 generation-kernel＋
        // kernelForm＋无工具规格），与付费确认/收口自动共用同一派发编排
        Long projectId = persistedProject("9806");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("已生成");

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        AgentCommand value = command.getAllValues().get(0);
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo(GenerationAppService.KERNEL_FIRST_RUN_PROMPT);
        assertThat(value.sessionId())
                .isEqualTo(GenerationAppService.kernelSession(projectId, run.runId()));
        assertThat(value.systemPrompt()).isEqualTo(KernelSystemPrompt.text())
                .contains("0.0.0.0:8081").contains("docs/PRD.md");
        assertThat(value.agentKey())
                .isEqualTo(AgentscopeHarnessAgentFactory.KERNEL_AGENT_NAME);
        assertThat(value.kernelForm()).isTrue();
        assertThat(value.toolSpec()).isNull();
        // 收口：双件判据过 → 生成落位（点火即事实，PRJ_017 幂等拒绝的现场）
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_executor_config_override_when_generate_then_stage0_command_carries_library_values() {
        // #251 装配断言（真命令构建缝，ADR-0021 库值优先）：设执行体运营配置库行后
        // 编码命令实取库值（prompt 与模型配置两腿）；缺省回落腿由上方枚举默认断言钉死。
        // #341 起 Demo 阶段（executor）缺省＝快模型档，故覆盖档取强模型档以示库值
        // 优先（覆盖值≠缺省值，断言才有别于回落腿）。
        // #252 增强工具开关同批编入 toolSpec（进缓存键保持规格一致）；#545 的构建轨
        // 出图增强件（gi）随 #604 出图开关退役，规格串只余 ws/fu 两件——内核座命令
        // 不携工具规格（工具恰四件经装配结构性兑现）
        Long projectId = persistedProject("9870");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("已生成");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, system_prompt, model_config_key)
                VALUES ('executor', ?, 'pro')
                """, "执行体覆盖协议：开工先列步骤。");

        appService.dispatchGenerationOnTurnClose(projectId);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        AgentCommand kernel = command.getAllValues().get(0);
        // 内核形态：系统提示＝权威版本（不取配置覆盖值——权威版本住代码，
        // #595/#601）；模型配置库值仍生效（供给链不绕开）；工具面恰四件经装配
        // 兑现、本层不发放工具规格
        assertThat(kernel.systemPrompt()).isEqualTo(KernelSystemPrompt.text());
        assertThat(kernel.modelString()).isEqualTo("deepseek:deepseek-v4-pro");
        assertThat(kernel.toolSpec()).isNull();
    }


    @Test
    void given_knowledge_hits_when_generate_then_prefix_injected_before_stage0_prompt() {
        // 知识命中前置注入（#24，#601 内核形态顺延）：query = 内核首跑 prompt，命中块
        // 拼在任务 prompt 前
        Long projectId = persistedProject("9807");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("已生成");
        givenKnowledgeHits();

        appService.dispatchGenerationOnTurnClose(projectId);

        verify(knowledgePort).retrieve(
                eq(GenerationAppService.KERNEL_FIRST_RUN_PROMPT), eq(5));
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .startsWith("【平台知识库·相似知识】")
                .contains("〔宠物医院预约平台｜历史需求〕PRD·宠物医院预约")
                .contains("〔平台沉淀｜功能模块〕预约时段管理模块")
                .contains("非用户的确认信息，不构成对当前需求的约束")
                .endsWith("————\n\n" + GenerationAppService.KERNEL_FIRST_RUN_PROMPT);
    }

    @Test
    void given_retrieval_failure_when_generate_then_degraded_plain_prompt_run_proceeds() {
        // 检索失败降级空注入：任务 prompt 原样下发，run 不被知识面阻断
        Long projectId = persistedProject("9808");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("已生成");
        doThrow(new RuntimeException("pgvector 抖动")).when(knowledgePort)
                .retrieve(anyString(), anyInt());

        appService.dispatchGenerationOnTurnClose(projectId);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .isEqualTo(GenerationAppService.KERNEL_FIRST_RUN_PROMPT);
    }

    // ---------- 收口判据与收口扩载 ----------

    @Test
    void given_converse_ok_but_service_unreachable_when_generate_then_no_generated_at_and_reinitiate_exit() {
        // 假完成（#35）：converse 正常结束但 8081 不可达——核验不过不落
        // generated_at，本场失败转终态（#604 不自动重试），重跑入口仍在；重跑
        //（点火入口/「继续生成」同口）即新内核 run 照跑收口判据
        Long projectId = persistedProject("9810");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds(); // 工作区 exec 全成功
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("curl"))))
                .thenReturn(new ExecResultResponse("", "Connection refused", 7));
        givenConverseChain(new AgentReply("run-fake", "很抱歉，目前系统尚未真正实现出来"));

        appService.dispatchGenerationOnTurnClose(projectId);

        // 假完成 → 本场失败转终态（#604 单发无重试）：converse 一次、不派切片
        verify(agentClient, times(1)).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(RETIRED_RETRYING), anyMap());
        assertThat(generatedAt(projectId)).isNull();

        // 项目不被空壳锁死：generated_at 未落 = 重跑入口在——核验改可达后重跑即成功
        //（内核不消费计划，重跑即新内核 run 照跑收口判据）
        doReturn(new ExecResultResponse("", "", 0))
                .when(workspaceLifecycleAppService).exec(any(), any());
        appService.startGeneration(projectId);
        // 重发 = 新内核 run 一次 converse 收口
        verify(agentClient, times(2)).converse(any(), any());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 首屏界面先行与入口文件写全（#339） ----------

    // ---------- 产出证明闸（#420 空转片防呆：零 diff＋无「能力已存在」确认＝结构性拒绝） ----------

    // ---------- 收口判据与探针（#605 起只余 build 探活＋8081 探活双件） ----------

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_generation_when_closes_then_run_finish_carries_five_field_closing() {
        // #617 收口扩载·生成轨道五字段骨架；#619 变更清单来源改工作区真实 diff：
        // 用时 / 判据逐件（build＋预览）/ 内核自述（内核末次文本）/ 变更清单
        // （跑前快照基线 + 收口 numstat，不再用工具参数推算）/ 预览入口由前端
        // 既有预览查询提供；旧平台句式摘要与「需求文档／系统」判定键不再写
        Long projectId = persistedProject("9811");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenSnapshotAndNumstat("12\t3\tsrc/App.jsx\n60\t0\tsrc/pages/Home.jsx\n");
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            // 工具参数观察与真实 diff 刻意不同——收尾卡必须取 diff（命令行改的
            // 文件也计入、行数为真实结果），并证明来源已切换
            return new AgentReply(command.runId(), "系统已生成", null, List.of(
                    new FileChange("/src/App.jsx", 40, 0),
                    new FileChange("/src/pages/Home.jsx", 60, 0)));
        });

        appService.dispatchGenerationOnTurnClose(projectId);

        // 内核单 run 单收口；断言收尾卡
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceClosing =
                (Map<String, Object>) payloads.getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD);
        // 内核自述 = 内核末次文本（本轮 AgentReply.text）
        assertThat(sliceClosing).containsEntry("narrative", "系统已生成");
        // 判据逐件如实（build＋预览双件）
        assertThat((Map<String, Object>) sliceClosing.get("criteria"))
                .containsEntry("buildPassed", true)
                .containsEntry("previewPassed", true);
        // 旧平台句式摘要与「需求文档／系统」判定键退役
        assertThat(sliceClosing).doesNotContainKeys(
                "summary", "prdChanged", "prdNote", "systemChanged", "systemNote");
        // 变更清单 = 真实 diff 行数（不是 FileChange 的 40/60），按路径排序；
        // 首建不携 patch（目录汇总归 #620，本票先不造无用载荷）
        assertThat((List<Map<String, Object>>) sliceClosing.get("files")).containsExactly(
                Map.of("path", "/src/App.jsx", "added", 12, "removed", 3),
                Map.of("path", "/src/pages/Home.jsx", "added", 60, "removed", 0));
        assertThat(sliceClosing).doesNotContainKey("filesUnavailable");
        assertThat((Long) sliceClosing.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_generation_when_runs_then_snapshot_commit_before_model_converse_and_not_a_version() {
        // #619：每次编码 run 起跑前自动快照提交（幂等仓库 + 全量提交），先于模型
        // 对话；快照 commit 不带 Rollback-From trailer——不进用户面版本序列
        Long projectId = persistedProject("9825");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenSnapshotAndNumstat("4\t0\tsrc/App.jsx\n");
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId);

        InOrder order = inOrder(workspaceLifecycleAppService, agentClient);
        order.verify(workspaceLifecycleAppService).exec(eq("9825"),
                argThat(cmd -> cmd != null && cmd.command().contains("-m 'snapshot ")));
        order.verify(agentClient).converse(any(), any());
        ArgumentCaptor<WorkspaceExecCommand> execs = ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(1)).exec(eq("9825"), execs.capture());
        List<String> snapshotCommands = execs.getAllValues().stream()
                .map(WorkspaceExecCommand::command)
                .filter(command -> command.contains("-m 'snapshot "))
                .toList();
        assertThat(snapshotCommands).hasSize(1);
        assertThat(snapshotCommands.get(0))
                .contains("git add -A")
                .doesNotContain("Rollback-From");
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_snapshot_fails_when_closes_then_run_succeeds_and_changes_unavailable() {
        // #619 降级：快照失败只降级变更清单（标注「变更清单不可用」），生成照常
        // 成功、其余字段照常——不把 git 副作用升级成生成失败
        Long projectId = persistedProject("9826");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("-m 'snapshot "))))
                .thenReturn(new ExecResultResponse("", "fatal: unable to write", 128));
        givenConverseFinishing("完成");

        appService.dispatchGenerationOnTurnClose(projectId);

        assertThat(generatedAt(projectId)).isNotNull(); // 生成照常成功
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payloads.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).containsEntry("filesUnavailable", true)
                .containsEntry("narrative", "完成");
        assertThat((List<Map<String, Object>>) closing.get("files")).isEmpty();
        // 其余字段照常：判据逐件与用时仍在
        assertThat((Map<String, Object>) closing.get("criteria"))
                .containsEntry("buildPassed", true)
                .containsEntry("previewPassed", true);
        assertThat((Long) closing.get("durationMs")).isGreaterThanOrEqualTo(0L);
        // 快照失败即无基线——不再尝试 diff
        verify(workspaceLifecycleAppService, never()).exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("--numstat")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_diff_fails_when_closes_then_run_succeeds_and_changes_unavailable() {
        // #619 降级：快照成功但收口 diff 执行失败——生成仍成功，变更清单标注不可用
        Long projectId = persistedProject("9827");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("-m 'snapshot "))))
                .thenReturn(new ExecResultResponse("a1b2c3d", "", 0));
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("--numstat"))))
                .thenReturn(new ExecResultResponse("", "fatal: bad revision", 128));
        givenConverseFinishing("完成");

        appService.dispatchGenerationOnTurnClose(projectId);

        assertThat(generatedAt(projectId)).isNotNull();
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payloads.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).containsEntry("filesUnavailable", true);
        assertThat((List<Map<String, Object>>) closing.get("files")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_real_diff_when_closes_then_conversation_history_same_payload() {
        // #619 AC：对话史落库与 SSE 收尾载荷同形——读回落库条目，变更清单
        // （文件级行数与不可用标记）按值一致
        Long projectId = persistedProject("9828");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenSnapshotAndNumstat("7\t2\tsrc/App.jsx\n");
        givenConverseFinishing("完成");

        appService.dispatchGenerationOnTurnClose(projectId);

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payloads.getValue().get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> persisted = conversationHistory.read(projectId).stream()
                .filter(entry -> entry.closing() != null)
                .reduce((first, second) -> second).orElseThrow().closing();
        assertThat(normalized(persisted)).isEqualTo(normalized(closing));
    }

    /** 跑前快照成功（回读 hash）＋收口 numstat 脚本（#619 变更清单真实 diff 缝）。 */
    private void givenSnapshotAndNumstat(String numstat) {
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("-m 'snapshot "))))
                .thenReturn(new ExecResultResponse("a1b2c3d", "", 0));
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("--numstat"))))
                .thenReturn(new ExecResultResponse(numstat, "", 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_durations_when_closes_then_closing_carries_duration_breakdown() {
        // #111 收口扩载·阶段耗时分布：三桶齐备（llmMs / toolsMs 按工具名分桶·command
        // 按命令归组嵌套 / closingMs 收口尾序）+ 逐尝试分布；委派族工具
        // （agent_spawn 旧名＋agent_send/wait_async_results）#604 起如实入桶
        // （除名规则随委派机器退役，双计口径不复存在）；SSE 扩载与对话史落库同载荷；
        // 桶计与 durationMs 一致性守卫（允许小误差，量级不符即埋点有洞）；验证桶
        // verifyMs 随验证机器退役（#605）
        Long projectId = persistedProject("9818");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "系统已生成", null, List.of(),
                    new StageDurations(5, Map.of("write_file", 2L, "agent_spawn", 9L,
                            "agent_send", 40L, "wait_async_results", 11L),
                            Map.of("install", 3L), Map.of("verify", 1L)));
        });

        appService.dispatchGenerationOnTurnClose(projectId);

        // 内核单 run 单收口；断言收尾卡
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payloads.getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> breakdown =
                (Map<String, Object>) closing.get("durationBreakdown");
        assertThat(breakdown).isNotNull()
                .containsEntry("llmMs", 5L)
                .doesNotContainKey("verifyMs");
        Map<String, Object> toolsMs = (Map<String, Object>) breakdown.get("toolsMs");
        assertThat(toolsMs)
                .containsEntry("write_file", 2L)
                // #604 委派族除名规则随委派机器退役（双计口径不复存在）——出现即
                // 普通桶如实携带
                .containsEntry("agent_spawn", 9L)
                .containsEntry("agent_send", 40L)
                .containsEntry("wait_async_results", 11L);
        assertThat((Map<String, Object>) toolsMs.get("command"))
                .containsExactly(Map.entry("install", 3L));
        long closingMs = ((Number) breakdown.get("closingMs")).longValue();
        assertThat(closingMs).isGreaterThanOrEqualTo(0L);
        // 逐尝试分布：单次成功尝试（首试）
        assertThat((List<Map<String, Object>>) breakdown.get("attempts")).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt).containsEntry("attempt", 1);
                    assertThat(attempt).containsEntry("llmMs", 5L);
                    assertThat(attempt).doesNotContainKey("verifyMs");
                    assertThat((Map<String, Object>) attempt.get("toolsMs"))
                            .containsEntry("write_file", 2L)
                            .containsEntry("agent_spawn", 9L)
                            .containsEntry("agent_send", 40L)
                            .containsEntry("wait_async_results", 11L);
                    assertThat(((Number) attempt.get("durationMs")).longValue())
                            .isGreaterThanOrEqualTo(0L);
                });
        // 一致性守卫：桶计 ≈ durationMs（量级守卫——纳秒/微秒单位混淆即超容差；
        // 正常全 mock 内联管道为毫秒级，2s 容差只放行管道抖动）
        long bucketSum = 5L + 2L + 9L + 40L + 11L + 3L + closingMs;
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
                .containsKey("llmMs").containsKey("toolsMs")
                .containsKey("closingMs").containsKey("attempts")
                .doesNotContainKey("verifyMs");
        assertThat(((Number) persistedBreakdown.get("llmMs")).longValue()).isEqualTo(5L);
        assertThat((Map<String, Object>) persistedBreakdown.get("toolsMs"))
                .containsKey("command")
                // #604 委派族如实入桶（读回落库同载荷同口径）
                .containsEntry("agent_spawn", 9)
                .containsEntry("agent_send", 40)
                .containsEntry("wait_async_results", 11);
    }

    // ---------- 守卫 ----------

    @Test
    void given_generation_in_flight_when_trigger_again_then_prj_017() {
        // 在途守卫（含已提交未起跑）：异步轨道占位期间重复触发拒绝——重发走 REST
        // 入口（内核不消费计划/交接物），dispatch 直撞在途占位
        Long projectId = persistedProject("9813");
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("已生成");
        List<Runnable> queued = new ArrayList<>();
        doAnswer(invocation -> {
            queued.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            queued.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());

        appService.dispatchGenerationOnTurnClose(projectId);

        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
        verify(agentClient, never()).converse(any(), any());

        // 排队任务收尾（守卫释放，不污染同上下文的后续测试）
        givenConverseChain(new AgentReply("run-x", "系统已生成"));
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
        Long projectId = projectRepository.save(Project.create("无 PRD 项目", null, 9816L, OWNER, null)).getId();

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

    // ---------- 首建派发门（#542 访谈②：两入口退役——首产回调与轮收口兜一轮删、快道/按钮点火） ----------

    @Test
    void given_turn_close_dispatch_then_kernel_chain_and_first_build_requested() {
        // 灵魂例（#462 收口精化的存活半边，#542/#602 语义顺延）：快道收口观测派发
        // （dispatchGenerationOnTurnClose 只在快道事实/设计定稿在场时被调用）——
        // 直派内核全链（切片/轨道机器已拆，无计划落轨面）
        Long projectId = persistedProject("9880");
        seedConfirmedVersion(projectId);
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "完成");
        });

        GenerationAppService.GenerationRun run =
                appService.dispatchGenerationOnTurnClose(projectId);

        assertThat(run).isNotNull();
        assertThat(generatedAt(projectId)).isNotNull();
        // 首建点火时点落位（#542 派发门——卡/门「未派发」读点的事实源）
        assertThat(jdbcTemplate.queryForMap(
                "SELECT generation_requested_at FROM prj_projects WHERE id = ?", projectId)
                .get("generation_requested_at")).isNotNull();
    }

    @Test
    void given_prd_produced_without_user_trigger_then_no_auto_dispatch() throws Exception {
        // 灵魂例（#542 AC③：两入口退役断言）——PRD 产出不再自动起跑：首产回调
        // （dispatchFirstDemoOnPrdProduced 已删）只剩事实登记——经真适配器回调产出
        // PRD，零编码 run、点火不落位（全域无无条件自动路径；点火＝岔路口「立即
        // 生成」/付费确认/快道，另见 MainAgentAppServiceTest 自然收口用例）
        Long projectId = persistedProject("9881");
        seedConfirmedVersion(projectId);
        Project project = projectRepository.findById(projectId).orElseThrow();
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();

        // 真 savePrd 落盘回调（适配器 bean 直调——状态位置位＋document-updated，
        // 零派发）
        prdArtifacts.onWritten(Long.toString(project.getWorkspaceId()));

        assertThat(projectRepository.findById(projectId).orElseThrow().getPrdProducedAt())
                .isNotNull(); // 事实登记照旧
        verify(agentClient, never()).converse(any(), any()); // 零编码 run
        assertThat(generatedAt(projectId)).isNull();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT generation_requested_at FROM prj_projects WHERE id = ?", projectId)
                .get("generation_requested_at")).isNull(); // 点火不落位
    }

    @Test
    void given_first_demo_failed_when_retrigger_then_redispatch_from_track() {
        // 失败重发同口径（#604：失败＝明确展示＋重跑即恢复，不自动重试）：首派失败
        // 后项目仍「未生成」——重跑（startGeneration：岔路口「立即生成」/PRD 确认/前端
        // 「继续生成」同口）再触发即新内核 run（既有工作区重跑，收口判据照跑）。首派
        // 走收口观测入口，重跑走点火入口路径
        Long projectId = persistedProject("9883");
        seedConfirmedVersion(projectId);
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command == null) {
                return null; // matcher 空参调用（重打桩触发旧 answer）直接过
            }
            throw new IllegalStateException("起服失败"); // 审核段失败（重试耗尽转终态）
        });

        GenerationAppService.GenerationRun first =
                appService.dispatchGenerationOnTurnClose(projectId);

        assertThat(first).isNotNull();
        assertThat(generatedAt(projectId)).isNull(); // 失败——仍「未生成」
        // 重跑（点火入口路径）：新内核 run 即完成（重打桩的 matcher 空参调用触发
        // 旧 answer——旧桩对 null 命令直接过，同既有先例）
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return command != null ? new AgentReply(command.runId(), "完成") : null;
        });
        GenerationAppService.GenerationRun again = appService.startGeneration(projectId);

        assertThat(again).isNotNull();
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_first_build_fails_when_run_ends_then_ignition_entry_reopened_for_rerun() {
        // #604 失败新语义＝明确展示＋重跑即恢复：「继续生成」断点续跑入口随续跑机器
        // 退役，重跑改走点火入口——首建终态失败即清点火位（generation_requested_at），
        // 岔路口卡/PRD 确认按钮「首建未派发」呈现条件复现，用户重走点火入口即重跑。
        // （成功收口不清：已生成，入口本就不在场——见
        // given_turn_close_dispatch_then_kernel_chain_and_first_build_requested）
        Long projectId = persistedProject("9884");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(agentClient.converse(any(), any())).thenThrow(new IllegalStateException("起服失败"));

        appService.dispatchGenerationOnTurnClose(projectId);

        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
        assertThat(generatedAt(projectId)).isNull(); // 仍「未生成」
        assertThat(generationRequestedAt(projectId)).isNull(); // 点火位清——点火入口复现
    }

    @Test
    void given_first_build_stopped_by_user_when_run_ends_then_ignition_entry_reopened_for_rerun() {
        // 停止同口径（#359 用户停止＝中断非判死）：清点火位，重跑入口照常在场
        Long projectId = persistedProject("9885");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            codingRunTrack.requestStop(projectId);
            throw new IllegalStateException("java.lang.InterruptedException");
        });

        appService.dispatchGenerationOnTurnClose(projectId);

        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
        assertThat(generatedAt(projectId)).isNull(); // 仍「未生成」
        assertThat(generationRequestedAt(projectId)).isNull(); // 点火位清——点火入口复现
    }

    // ---------- Demo 版本化（#333）：生成收口自动成版（锚起跑时确认版本） ----------

    @Test
    void given_confirmed_prd_when_generation_closes_then_demo_v1_persisted() {
        // 灵魂例（#333 AC①）：构建收口自动成版——首版 v1 随 generated_at 同刻落，
        // 锚＝起跑时已确认 PRD 版本、run 锚＝末片收口 run（「查看当时」接续缝）。
        // 首派走收口入口（#462：产 PRD 同转收口携计划交接物直派）
        Long projectId = persistedProject("9884");
        seedConfirmedVersion(projectId);
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId);

        verify(agentClient, times(1)).converse(commands.capture(), any());
        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT version_no, prd_version_no, run_id FROM prj_demo_versions"
                        + " WHERE project_id = ?",
                projectId);
        assertThat(version.get("version_no")).isEqualTo(1);
        assertThat(version.get("prd_version_no")).isEqualTo(1);
        // run 锚＝内核收口 run（单 run 成版）
        assertThat(version.get("run_id"))
                .isEqualTo(commands.getAllValues().get(commands.getAllValues().size() - 1).runId());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_prd_v2_confirmed_mid_run_when_closure_then_version_anchors_v1() {
        // 在途 Demo 照跑完收口绑旧版本（#333 AC③）：起跑后、收口前 PRD 出新版
        // （converse 侧效插入确认版 v2）——成版仍锚起跑时的 v1，不随后续确认漂移。
        // 首派走收口入口（#462 触发平移后收口单点直派）
        Long projectId = persistedProject("9885");
        seedConfirmedVersion(projectId);
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            // 在途插确认：PRD v2 在生成 run 进行中落库（幂等——阶段 0/切片各跑一
            // 次 converse，冲突即已插过）
            jdbcTemplate.update("""
                    INSERT INTO prj_prd_versions (id, project_id, version_no, content, confirmed_at,
                        prd_produced_at, created_at, updated_at)
                    VALUES (?, ?, 2, '# PRD v2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (project_id, version_no) DO NOTHING
                    """, System.nanoTime(), projectId);
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId);

        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT version_no, prd_version_no FROM prj_demo_versions WHERE project_id = ?",
                projectId);
        assertThat(version.get("version_no")).isEqualTo(1);
        assertThat(version.get("prd_version_no")).isEqualTo(1); // 绑旧版（起跑时 v1）
    }

    @Test
    void given_no_confirmed_version_when_generation_closes_then_null_anchor() {
        // 无确认版起跑（设计类终点承接/legacy）：成版锚空——不虚构 PRD 关联
        Long projectId = persistedProject("9886");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId);

        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT version_no, prd_version_no FROM prj_demo_versions WHERE project_id = ?",
                projectId);
        assertThat(version.get("version_no")).isEqualTo(1);
        assertThat(version.get("prd_version_no")).isNull();
    }

    // ---------- 首 Demo 在途的意见排队补派（#360：不静默丢——入队+明示+收口合并补派） ----------

    /** 轨道任务收集（在途窗口测试）：submit 只登记不跑，测试手动驱动模拟异步轨道。 */
    private List<Runnable> givenTrackCollected() {
        List<Runnable> tracks = new ArrayList<>();
        doAnswer(invocation -> {
            tracks.add((Runnable) invocation.getArgument(1));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            tracks.add((Runnable) invocation.getArgument(1));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(sessionExecutor).submitGeneration(any(), any());
        return tracks;
    }

    @Test
    void given_first_demo_in_flight_when_turn_opinions_arrive_then_notified_and_merged_fix_after_close()
            throws InterruptedException {
        // 灵魂用例（#360 AC①）：首 Demo 在途时意见轮收口的派发不再静默丢——意见入队
        // + 对话面平台明示（guide-reply 复用，prompt 缺省不再重建用户气泡）；生成
        // 收口成功后排空队列，多条意见合并为一场更新 run 补派（修正轨排队合并同构）
        Long projectId = persistedProject("9860");
        givenWorkspaceExecSucceeds();
        List<Runnable> tracks = givenTrackCollected();
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fixDone = new CountDownLatch(1);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            if (command.sessionId().contains("-kernel-")) {
                // 内核首 run 在途挂住——制造首 Demo 生成在途窗口
                generating.countDown();
                release.await();
            }
            if (command.sessionId().contains("-fix-")) {
                // 排空补派的更新 run：内核收口（平台侧 build＋探活判据照跑）
                fixDone.countDown();
                return new AgentReply(command.runId(), "更新完成");
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId);
        Thread generationWorker = new Thread(tracks.remove(0));
        generationWorker.start();
        assertThat(generating.await(5, TimeUnit.SECONDS)).isTrue();

        // 在途窗口内两条意见轮收口：不再静默跳过——入队不派（无新 converse）+ 平台明示
        assertThat(appService.dispatchGenerationOnTurnClose(projectId,
                new GenerationAppService.TurnOpinion("列表加筛选", null, "turn-1"))).isNull();
        assertThat(appService.dispatchGenerationOnTurnClose(projectId,
                new GenerationAppService.TurnOpinion("加导出功能", "PRD 已加导出节", "turn-2")))
                .isNull();
        verify(agentClient, times(1)).converse(any(), any());
        // 平台明示（guide-reply 复用型）：锚各自意见轮、无 prompt 键（用户气泡已由
        // 意见轮自落，不重复重建）、定型文案告知排队事实
        for (String turnRunId : List.of("turn-1", "turn-2")) {
            verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY),
                    argThat(payload -> turnRunId.equals(payload.get(EventsAppService.RUN_FIELD))
                            && !payload.containsKey(AgentEventTypes.GUIDE_PROMPT_FIELD)
                            && GenerationAppService.IN_FLIGHT_OPINION_NOTICE
                                    .equals(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))));
        }
        // 明示同步落库（#89 闭史）：guide 行挂各自意见轮 runId，刷新回访不丢
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_conversation_entries WHERE project_id = ?"
                        + " AND kind = 6 AND run_id IN ('turn-1', 'turn-2')",
                Integer.class, projectId)).isEqualTo(2);

        // 首 Demo 收口成功（generated_at 落位）→ 排空队列：合并为一场更新 run 补派
        release.countDown();
        generationWorker.join(5000);
        assertThat(generatedAt(projectId)).isNotNull();
        assertThat(tracks).hasSize(1); // 排空只补派一场（合并），不逐条烧 run
        Thread fixWorker = new Thread(tracks.remove(0));
        fixWorker.start();
        assertThat(fixDone.await(5, TimeUnit.SECONDS)).isTrue();
        fixWorker.join(5000);
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        // 补派 prompt = 内核修正轨交接物拼装（逐轮配对：各轮「意见 → 修订说明」）
        assertThat(commands.getAllValues().get(1).prompt()).isEqualTo(
                IterationAppService.kernelFixRunPrompt(new IterationAppService.FixHandoff(
                        List.of(new IterationAppService.FixHandoff.Round("列表加筛选", null),
                                new IterationAppService.FixHandoff.Round("加导出功能", "PRD 已加导出节")))));
    }

    @Test
    void given_stopped_first_demo_when_turn_opinion_queued_then_resume_digests_without_deadlock()
            throws InterruptedException {
        // #360 AC②：停止收口不排空（队列保留）、「继续生成」续跑成功收口时消化——
        // 与恢复出口不冲突（不自锁、不重复派）；生成成功后「继续生成」出口按已生成
        // 口径拒绝（PRJ_017），不与补派的更新 run 抢轨道
        Long projectId = persistedProject("9861");
        givenWorkspaceExecSucceeds();
        List<Runnable> tracks = givenTrackCollected();
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch fixDone = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean(false);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            if (command.sessionId().contains("-kernel-") && !released.get()) {
                // 首 Demo 内核 run 在途挂住，等停止请求落位（Future.cancel 线程中断的
                // 现场等价物——停止检查点在中断侧，此处以中断形态异常收场）
                generating.countDown();
                while (!codingRunTrack.stopRequested(projectId)) {
                    Thread.sleep(50);
                }
                throw new RuntimeException("java.lang.InterruptedException");
            }
            if (command.sessionId().contains("-fix-")) {
                fixDone.countDown();
                return new AgentReply(command.runId(), "更新完成");
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId);
        Thread generationWorker = new Thread(tracks.remove(0));
        generationWorker.start();
        assertThat(generating.await(5, TimeUnit.SECONDS)).isTrue();

        // 在途窗口入队一条意见后用户停止首 Demo
        assertThat(appService.dispatchGenerationOnTurnClose(projectId,
                new GenerationAppService.TurnOpinion("列表加筛选", null, "turn-1"))).isNull();
        assertThat(codingRunTrack.requestStop(projectId)).isTrue();
        generationWorker.join(5000);
        // 停止收口：run-failed(stopped) 终态、不排空（无补派任务提交）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED),
                argThat(payload -> Boolean.TRUE.equals(payload.get(AgentEventTypes.RUN_STOPPED_FIELD))));
        assertThat(tracks).isEmpty();
        assertThat(generatedAt(projectId)).isNull();

        // 「继续生成」续跑：成功收口后排空消化排队意见（合并为一场更新 run 补派）
        released.set(true);
        appService.startGeneration(projectId);
        assertThat(tracks).hasSize(1);
        Thread resumeWorker = new Thread(tracks.remove(0));
        resumeWorker.start();
        resumeWorker.join(10_000);
        assertThat(generatedAt(projectId)).isNotNull();
        assertThat(tracks).hasSize(1); // 排空补派一场（合并）——不逐条烧 run
        Thread fixWorker = new Thread(tracks.remove(0));
        fixWorker.start();
        assertThat(fixDone.await(5, TimeUnit.SECONDS)).isTrue();
        fixWorker.join(5000);
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().stream()
                .filter(command -> command.sessionId().contains("-fix-"))
                .findFirst().orElseThrow().prompt())
                .isEqualTo(IterationAppService.kernelFixRunPrompt(new IterationAppService.FixHandoff(
                        List.of(new IterationAppService.FixHandoff.Round("列表加筛选", null)))));
        // 已生成后「继续生成」出口不再可达（PRJ_017）——补派的更新 run 独占轨道，不自锁
        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
    }

    // ---------- 测试数据 ----------

    /** 可生成形态的项目（PRD 已产出、未生成、未归档）。 */
    private Long persistedProject(String workspaceId) {
        Project project = Project.create("生成项目", null, Long.parseLong(workspaceId), OWNER, null);
        project.markPrdProduced();
        return projectRepository.save(project).getId();
    }

    /**
     * JSONB 回读数值窄化归一（#418 同载荷比对的归一面）：Number 按值折 long、
     * 容器递归——SSE 载荷（内存形 Long/Integer）与对话史回读形按值等价可比。
     */
    private static Object normalized(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, val) -> normalized.put(key, normalized(val)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(GenerationAppServiceTest::normalized).toList();
        }
        return value;
    }

    /** PRD 版本行在场（legacy 记行形态夹具——#462 起与派发门无关，仅取数事实）。 */
    private void seedConfirmedVersion(Long projectId) {
        Project project = projectOf(projectId);
        jdbcTemplate.update("""
                INSERT INTO prj_prd_versions (id, project_id, version_no, content, confirmed_at,
                    prd_produced_at, created_at, updated_at)
                VALUES (?, ?, 1, '# PRD', CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, System.nanoTime(), projectId, project.getPrdProducedAt());
    }

    private Long persistedArchivedProject(String workspaceId) {
        Project project = Project.create("归档生成项目", null, Long.parseLong(workspaceId), OWNER, null);
        project.archive();
        return projectRepository.save(project).getId();
    }

    // ---------- 模型档（#606 模型路由退役后：run 直接取执行体生效档，收口无
    // modelTier 判定留档） ----------

    @Test
    void given_executor_model_config_override_when_dispatch_then_override_tier_used() {
        // 库覆盖即生效档：一次 run 一支配置（单发无升档腿）；收尾卡不再携
        // modelTier（路由判定留档随 #606 退役）
        Long projectId = persistedProject("9854");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, model_config_key)
                VALUES ('executor', 'pro')
                """);
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString)
                .containsExactly("deepseek:deepseek-v4-pro");

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing = (Map<String, Object>) payloads
                .getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).doesNotContainKey("modelTier");
        // 供给链归因仍在：逐尝试生效配置随 durationBreakdown 留档
        Map<String, Object> breakdown = (Map<String, Object>) closing.get("durationBreakdown");
        assertThat((List<Map<String, Object>>) breakdown.get("attempts"))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt).containsEntry("modelId", "pro"));
    }

    @Test
    void given_no_executor_override_when_dispatch_then_enum_default_tier_used() {
        // 无覆盖：枚举缺省快档（flash）——单发一次 run 一支配置
        Long projectId = persistedProject("9855");
        givenSessionExecutorRunsInline();
        givenWorkspaceExecSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "deepseek:deepseek-flash");
    }

    /** 系统＋设计终点、PRD 已产出的项目（构建门用例的前置形态）。 */
    private Project projectOf(Long projectId) {
        return projectRepository.findById(projectId).orElseThrow();
    }

    private Long persistedGeneratedProject(String workspaceId) {
        Project project = Project.create("已生成项目", null, Long.parseLong(workspaceId), OWNER, null);
        project.markGenerated();
        return projectRepository.save(project).getId();
    }
}
