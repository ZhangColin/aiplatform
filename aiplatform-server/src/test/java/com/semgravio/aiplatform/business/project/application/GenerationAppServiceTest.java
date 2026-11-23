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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.fasterxml.jackson.databind.ObjectMapper;

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
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.business.project.domain.aggregate.DesignItem;
import com.semgravio.aiplatform.business.project.domain.aggregate.GenerationSegment;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ConversationEntryKind;
import com.semgravio.aiplatform.business.project.domain.enums.GenerationState;
import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;
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
 * 前置注入；失败自动静默重试；成功收口落 generated_at。
 *
 * <p><b>生成轨道（#104）</b>：阶段 0（先起服白底页）固定前置，其后按切片计划顺序逐片
 * 派 run——轨道顺序派 N run、阶段 0 先于切片、每片收口发 run-finish（收口扩载）、
 * 失败不跳片（某片超限转终态 run-failed、generated_at 落最后一片收口）。</p>
 *
 * <p><b>首屏界面先行与入口文件写全（#339）</b>：首片语义＝先出可点的界面（首片
 * prompt 界面先行、其后各片端到端纵向切片）；收口判据＝8081 可达＋入口文件完整
 * （残缺「其余代码略」式占位不收口、静默重试修全）。</p>
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

    /** PRD 落盘登记适配器（#542 两入口退役断言——真 savePrd 回调侧零派发）。 */
    @Autowired
    private com.semgravio.aiplatform.business.project.infrastructure.PrdArtifactAdapter prdArtifacts;

    /** 轨道表写口（计划在场预置——退役前首产回调「计划在场即派」形的复刻）。 */
    @Autowired
    private com.semgravio.aiplatform.business.project.domain.repository.GenerationSegmentRepository generationSegments;

    /** 验证事实登记口（#352 收口卡验证统计脚本：模拟 verify 子智能体轮内工具登记）。 */
    @Autowired
    private VerificationFacts verificationFacts;

    /** finish_edit 事实登记口（#360 排空补派的更新 run 收口脚本）。 */
    @Autowired
    private FinishEditFacts finishEditFacts;

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
        // #543 风格物化写面缺省桩（首个构建派发＝平台确定性写基座四件）：通用成功，
        // 用例专属注入（合并冲突/磁盘满等）在测试体内后注册，按 Mockito 后桩优先
        // 覆盖本位；断言「零写入」的用例走 verify(never())（桩不产生调用）
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("123", "", 0));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_design_specs");
        jdbcTemplate.update("DELETE FROM prj_design_items");
        jdbcTemplate.update("DELETE FROM prj_generation_segments");
        jdbcTemplate.update("DELETE FROM prj_generation_plan_journeys");
        jdbcTemplate.update("DELETE FROM prj_generation_plan_deferrals");
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_demo_versions");
        jdbcTemplate.update("DELETE FROM prj_agent_configs");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
        // 配置夹具归零（先子后父：候选行 FK 指配置——V79 起候选表在库）
        jdbcTemplate.update("DELETE FROM prj_model_config_candidates WHERE config_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_configs WHERE config_key LIKE 't-%'");
        // #543 风格层 kill-switch 归位（缺省生效态——共享行，绝不把关停态留给下游）
        jdbcTemplate.update("UPDATE prj_style_direction_switch SET enabled = TRUE WHERE id = 1");
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
                    verifyRan(command);
                    return new AgentReply(command.runId(), text);
                });
    }

    /**
     * 模拟 verify 子智能体轮内登记（#424 片轻验；#435 起验证非收口要件、观测面
     * 不动）：登记仍进验证统计（收尾卡事实源）——converse 桩内调用即「verify 已
     * 跑并登记」；按工作区登记一次
     * （run 起跑清残留后逐 run 重登，多尝试不虚增复验轮次）。零验证场景的测试
     * 不调用本位（如 {@code given_zero_verification_slice_...}）。
     */
    private void verifyRan(AgentCommand command) {
        if (!verificationFacts.hasRegistration(command.workspaceId())) {
            verificationFacts.record(command.workspaceId(), List.of(
                    new VerificationFacts.Check("首页主径可走通（刷新仍在）", "browser", true, null)));
        }
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
            verifyRan(command);
            return (AgentReply) outcome;
        }).when(agentClient).converse(any(), any());
    }

    /** 首试失败、此后照常收口脚本（静默重试/路由升档用例的失败注入面——#354）。 */
    private void givenConverseFailsThenSucceeds(String failure) {
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            if (converseCalls.incrementAndGet() == 1) {
                throw new IllegalStateException(failure);
            }
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "完成");
        });
    }

    private void givenAgentsMdWriteSucceeds() {
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        // #543 风格物化写面（首个构建派发四件落位——本类不断言其内容，缺省成功）
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("123", "", 0));
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

    /** 计划旅程行读口（#423 断言面：role / entry / key_actions / prd_produced_at）。 */
    private List<Map<String, Object>> journeyRows(Long projectId) {
        return jdbcTemplate.queryForList(
                "SELECT role, entry, key_actions, prd_produced_at"
                        + " FROM prj_generation_plan_journeys WHERE project_id = ? ORDER BY ord",
                projectId);
    }

    /** 计划推迟行读口（ADR-0050 断言面：item / prd_produced_at）。 */
    private List<Map<String, Object>> deferralRows(Long projectId) {
        return jdbcTemplate.queryForList(
                "SELECT item, prd_produced_at"
                        + " FROM prj_generation_plan_deferrals WHERE project_id = ? ORDER BY ord",
                projectId);
    }

    /** 项目 PRD 版本锚读口（锚一致性断言的独立事实源）。 */
    private java.sql.Timestamp projectPrdAnchor(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT prd_produced_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId);
    }

    // ---------- 生成轨道（#104：阶段 0 先起服 + 纵向切片逐段） ----------

    @Test
    void given_shutdown_interrupts_inflight_call_when_attempt_fails_then_no_retry_no_terminal_failure() {
        // #477 关停旁路（2026-11-12 19:03 生产实录）：SIGTERM 优雅关停中断在飞模型调用
        //（AgentShuttingDownException 经 AgentTurnFailure 上浮）——关停错误一律不可重试：
        // 不烧尝试预算、不升档、不触发下一轮尝试；run 落「中断可续跑」态而非终态失败
        //（片保持待跑、无 run-failed），后续片不再派发（关停宽限窗内不拉起排队轮）
        Long projectId = persistedProject("9817");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
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

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        try {
            // 一次尝试即关停收场——事故形态（升档秒败×3 烧满转终态）不复现
            assertThat(converseCalls.get()).isEqualTo(1);
            // 中断可续跑：阶段 0 保持待跑（不落 FAILED）、切片 1/2 不派、无 run-failed
            assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                    .containsExactly(1, 1, 1);
            verify(eventsAppService, never())
                    .publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
            assertThat(generatedAt(projectId)).isNull();
        }
        finally {
            AgentShutdownGate.exitShutdownForTesting();
        }
    }

    @Test
    void given_channel_instant_fails_when_llm_zero_then_no_attempt_budget_burned() {
        // #477 通道级秒败免预算（11-12 19:03 实录：llmMs=0 秒败烧尝试、疑 #467 同族）：
        // AgentTurnFailure 且 llmMs=0（未发生真实模型调用——连接/凭证级通道错误）不
        // 消耗正常重试预算——进独立免预算重试账（带退避、封顶），真实尝试序不前移
        //（重试现场仍「已失败 0/N」）、不因秒败升档；免预算额度内真实调用恢复即收口
        Long projectId = persistedProject("9818");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        properties.setChannelInstantRetryBackoff(Duration.ofMillis(1)); // 免预算退避不拖慢测试
        try {
            AgentTurnFailure channelFail = new AgentTurnFailure(
                    "智能体调用失败：Connection refused",
                    new IllegalStateException("Connection refused"),
                    List.of(), StageDurations.zero()); // llmMs=0——未发生真实模型调用
            givenConverseChain(channelFail, channelFail, channelFail,
                    new AgentReply("r", "完成"));

            appService.dispatchGenerationOnTurnClose(projectId,
                    new BuildPlan(List.of("用户能注册登录")));

            ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
            verify(agentClient, atLeast(4)).converse(commands.capture(), any());
            List<AgentCommand> all = commands.getAllValues();
            // 秒败×3 全走免预算重试（尝试序不前移）：重发同首试 prompt（干净重发
            // ——未发生真实模型调用，无错误现场可携）；旧口径（烧预算）第 2 次起是
            // 重试 prompt（「已失败 1/3」起计数）、3 次烧满转终态
            assertThat(all.subList(1, 4)).extracting(AgentCommand::prompt)
                    .allSatisfy(prompt -> assertThat(prompt).isEqualTo(all.get(0).prompt()));
            // 免预算额度内通道恢复即收口：阶段 0 收口、生成完成位落定（不终态失败）
            assertThat(generatedAt(projectId)).isNotNull();
            verify(eventsAppService, never())
                    .publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
        }
        finally {
            properties.setChannelInstantRetryBackoff(Duration.ofSeconds(2));
        }
    }

    @Test
    void given_slices_when_generate_then_commands_carry_work_message_heading() {
        // #118 工作消息头部标题（#601 内核形态顺延）：内核 run 携「系统初始化」
        //（无进度——非切片），run-start 的 slice 字段呈现源
        Long projectId = persistedProject("9821");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::heading)
                .containsExactly(RunHeading.titled(GenerationAppService.STAGE0_TITLE));
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

    // ---------- 并行波可观测档案（#419：fork 文件集落档＋宽度遥测——结构化日志留档） ----------

    /** fork diff 桩（改/增两件——合并档案文件集的断言面）。 */
    private static final String FORK_DIFF_STUB =
            "diff --git a/src/app/page.tsx b/src/app/page.tsx\n"
                    + "index 1111111..2222222 100644\n"
                    + "--- a/src/app/page.tsx\n+++ b/src/app/page.tsx\n"
                    + "@@ -1 +1 @@\n-old\n+new\n"
                    + "diff --git a/src/lib/auth.ts b/src/lib/auth.ts\n"
                    + "new file mode 100644\n"
                    + "--- /dev/null\n+++ b/src/lib/auth.ts\n"
                    + "@@ -0,0 +1 @@\n+export const x = 1;\n";

    /** 留档捕获辅件：按标签过滤捕获事件并解析 JSON 正文（回放口径）。 */
    private static List<Map<String, Object>> archivedPayloads(
            ListAppender<ILoggingEvent> appender, String tag, ObjectMapper json) throws Exception {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            if (!event.getFormattedMessage().startsWith(tag)) {
                continue;
            }
            payloads.add(json.readValue(event.getFormattedMessage().substring(tag.length()),
                    Map.class));
        }
        return payloads;
    }

    // ---------- 生成取消（#359：在途 run 可停止——停止＝中断口径，不重试不回滚） ----------

    @Test
    void given_stop_requested_mid_slice_when_generate_then_no_retry_stopped_terminal_and_resumable() {
        // 灵魂用例（#359 AC②③，#601 内核形态顺延）：内核 run 在途时用户叫停——停止
        // 不走重试环（一次尝试即收场）、run-failed 载荷带 stopped=true（失败账口径
        // 如实收口）；「继续生成」照常可续——重发即新内核 run（收口判据照跑）
        Long projectId = persistedProject("9845");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
            if (converseCalls.incrementAndGet() == 1) {
                // 首试在途被中断（Future.cancel 线程中断打断模型流的现场等价物）
                codingRunTrack.requestStop(projectId);
                throw new IllegalStateException("java.lang.InterruptedException");
            }
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(List.of("用户能注册登录", "用户能下单支付"));
        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 停止不重试：首试一次 converse 即收场（重试环被停止旁路）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        // run-failed(stopped=true) 锚被停场次（失败账口径——run 失败仍是唯一失败终态）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                run.runId().equals(payload.get(EventsAppService.RUN_FIELD))
                        && Boolean.TRUE.equals(payload.get(AgentEventTypes.RUN_STOPPED_FIELD))));
        // 在途 run 不落完成位、片行保持待跑（PENDING 非 FAILED）
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(1, 1, 1);
        assertThat(generatedAt(projectId)).isNull();
        // 四态投影如实：在途标记已释放 → 生成中断（不显假「生成中」）
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.INTERRUPTED);
        // 「继续生成」重发续跑：新内核 run 照跑收口判据
        appService.startGeneration(projectId);
        verify(agentClient, times(2)).converse(any(), any());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_legacy_null_depends_when_plan_of_then_sequential_not_parallel() {
        // 存量旧片行（V41 前，depends_on 未设 = NULL）还原为全串行——不误判为「全无
        // 依赖可并行」（旧计划语义 = 每片依赖前一片）
        Long projectId = persistedProject("9842");
        LocalDateTime prdAnchor = projectRepository.findById(projectId).orElseThrow().getPrdProducedAt();
        long ts = System.nanoTime();
        jdbcTemplate.update("INSERT INTO prj_generation_segments "
                + "(id, project_id, ord, description, status, prd_produced_at, depends_on) "
                + "VALUES (?, ?, 0, '系统初始化', 1, ?, NULL)", ts, projectId, prdAnchor);
        jdbcTemplate.update("INSERT INTO prj_generation_segments "
                + "(id, project_id, ord, description, status, prd_produced_at, depends_on) "
                + "VALUES (?, ?, 1, '首片', 1, ?, NULL)", ts + 1, projectId, prdAnchor);
        jdbcTemplate.update("INSERT INTO prj_generation_segments "
                + "(id, project_id, ord, description, status, prd_produced_at, depends_on) "
                + "VALUES (?, ?, 2, '第二片', 1, ?, NULL)", ts + 2, projectId, prdAnchor);
        jdbcTemplate.update("INSERT INTO prj_generation_segments "
                + "(id, project_id, ord, description, status, prd_produced_at, depends_on) "
                + "VALUES (?, ?, 3, '第三片', 1, ?, NULL)", ts + 3, projectId, prdAnchor);

        BuildPlan plan = appService.planOf(projectId);

        assertThat(plan.dependsOn(0)).isEmpty();
        assertThat(plan.dependsOn(1)).isEqualTo(Set.of(1));
        assertThat(plan.dependsOn(2)).isEqualTo(Set.of(2));
        assertThat(SliceSchedule.parallelWaves(plan))
                .containsExactly(List.of(2), List.of(3));
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
    void given_knowledge_hits_when_generate_slices_then_knowledge_injected_only_stage0() {
        // #114 一次切入一次注入（#601 内核形态顺延）：知识命中前置注入只在首跑，
        // 不逐轮重检索重注入（注入口径不膨胀）
        Long projectId = persistedProject("9831");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");
        givenKnowledgeHits();

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        // 内核单 run：检索一次、首跑 prompt 前置知识块
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt());
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(0).prompt()).startsWith("【平台知识库·相似知识】");
    }

    @Test
    void given_slices_when_generate_then_demo_seed_applied_once_at_last_closing() {
        // #340 演示种子预置：生成轨道只有末段收口（Demo 将可见前一刻、表已由 run 长出）
        // 施用种子——中段收口不种（不扰动后续片的模型自检），全场一次；施用先于
        // 末次 preview-ready（用户首屏即有内容可看）
        Long projectId = persistedProject("9833");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        ArgumentCaptor<WorkspaceExecCommand> execs = ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(1)).exec(eq("9833"), execs.capture());
        List<String> seedExecs = execs.getAllValues().stream()
                .map(WorkspaceExecCommand::command)
                .filter(cmd -> cmd.contains("/opt/seed/apply.sh"))
                .toList();
        // 阶段 0 + 2 片 = 3 次收口，只在末段种一次
        assertThat(seedExecs).hasSize(1);
        // 施用先于末次 preview-ready（seed → exposePreview 的相对序）
        InOrder order = inOrder(workspaceLifecycleAppService);
        order.verify(workspaceLifecycleAppService).exec(eq("9833"),
                argThat(cmd -> cmd != null && cmd.command().contains("/opt/seed/apply.sh")));
        order.verify(workspaceLifecycleAppService).exposePreview(anyString());
    }

    @Test
    void given_seed_exec_fails_when_generate_then_last_closing_not_blocked() {
        // #340 失败不阻断：种子施用抛错也不影响收口（片全收口、首版落位照常）
        Long projectId = persistedProject("9834");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("/opt/seed/apply.sh"))))
                .thenThrow(new IllegalStateException("容器 exec 失败"));

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录")));

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
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录")));

        // 内核收口推一次 preview-ready
        verify(eventsAppService, times(1)).publishNotification(eq(ProjectEventTypes.PREVIEW_READY),
                argThat(payload -> projectId.toString().equals(payload.get("projectId"))
                        && "http://localhost:30080".equals(payload.get("url"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_attempts_exhausted_when_generate_then_failure_face_persisted_same_payload_as_sse() {
        givenPerSliceSessions();
        // #418 灵魂用例（观测地基批）：重试耗尽转终态——失败面（逐尝试墙钟/配置/失败
        // 原因/本场累计）随 run-failed SSE 扩载与对话史落库同载荷（同收尾卡「SSE 扩载
        // 与落库同载荷」先例）——失败作废可归因（#313「33% 失败作废」从此可测），
        // 速度观测不再只看成功者；平台分析口径、前端不渲染（同 durationBreakdown）
        Long projectId = persistedProject("9860");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenThrow(new IllegalStateException("起服失败"));

        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId,
                SINGLE_SLICE_PLAN);

        // SSE 扩载：run-failed 载荷携 failure 面——逐尝试条数 = 烧满重试次数（每条带
        // 轮次/配置/墙钟/桶）、error = 末次错误现场原文、durationMs = 本场累计墙钟
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), payloads.capture());
        assertThat(payloads.getValue().get(EventsAppService.RUN_FIELD)).isEqualTo(run.runId());
        Map<String, Object> face = (Map<String, Object>) payloads.getValue()
                .get(AgentEventTypes.FAILURE_FIELD);
        assertThat(face).isNotNull();
        assertThat(face).containsEntry("error", "起服失败");
        assertThat(((Number) face.get("durationMs")).longValue()).isGreaterThanOrEqualTo(0L);
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) face.get("attempts");
        assertThat(attempts).hasSize(properties.getMaxAttempts());
        assertThat(attempts).extracting(attempt -> ((Number) attempt.get("round")).intValue())
                .containsOnly(1);
        assertThat(attempts).extracting(attempt -> ((Number) attempt.get("attempt")).intValue())
                .containsExactly(IntStream
                        .rangeClosed(1, properties.getMaxAttempts()).boxed().toArray(Integer[]::new));
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
        givenAgentsMdWriteSucceeds();
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            if (converseCalls.incrementAndGet() == 1) {
                codingRunTrack.requestStop(projectId);
                throw new IllegalStateException("java.lang.InterruptedException");
            }
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

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
                    assertThat(attempt).containsEntry("round", 1).containsEntry("attempt", 1);
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
        // 灵魂用例（#220 AC①，#601 内核形态顺延）：PRD 产出后切片计划旁路落库（阶段
        // 0 + 逐片——表与呈现面读面维持事实连续，内核不消费）；计划与片进度的事实
        // 源是表——进程内无副本，「模拟重启」后仍可查即「直接查表」（run 无表口径
        // 的精确例外）
        Long projectId = persistedProject("9840");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        BuildPlan plan = new BuildPlan(List.of("用户能注册登录", "用户能下单支付"));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        verify(agentClient, times(1)).converse(any(), any());
        // 片集：阶段 0 固定题 + 两片切片句，PRD 版本锚 = 项目 prd_produced_at
        List<Map<String, Object>> rows = segmentRows(projectId);
        assertThat(rows).extracting(row -> row.get("description")).containsExactly(
                GenerationAppService.STAGE0_TITLE, "用户能注册登录", "用户能下单支付");
        // 内核不逐片收口——片行保持待跑（计划记录面，收口状态随旧口径留空）
        assertThat(rows).extracting(row -> row.get("status"))
                .containsExactly(1, 1, 1); // 全部待跑
        assertThat(jdbcTemplate.queryForObject(
                "SELECT prd_produced_at FROM prj_generation_segments WHERE project_id = ? AND ord = 0",
                java.sql.Timestamp.class, projectId))
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT prd_produced_at FROM prj_projects WHERE id = ?",
                        java.sql.Timestamp.class, projectId));
        // 断点 = 表中最深收口片（#221 口径遗留）：无收口片 → -1
        assertThat(appService.deepestClosedSegmentOf(projectId)).isEqualTo(-1);
        // 计划读回（轨道表还原 BuildPlan，PRD 锚一致）
        assertThat(appService.planOf(projectId)).isEqualTo(plan);
    }

    @Test
    void given_prd_revised_when_dispatch_with_new_plan_then_table_replaced_no_residue() {
        givenPerSliceSessions();
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

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("新切片一", "新切片二", "新切片三")));

        // 新计划落表替换（内核不消费计划——执行结构由提示词权威版本＋PRD 正本承载；
        // 两轮各烧满重试——表替换与跑次解耦）
        verify(agentClient, atLeast(properties.getMaxAttempts() + 1)).converse(any(), any());
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
    void given_plan_with_journeys_and_sizes_when_recorded_then_both_persisted_with_prd_anchor() {
        // #423 AC② 两件落库随 PRD 版本走：核心旅程行落计划旅程表（锚 = 项目
        // prd_produced_at）、每片规模标签落片行 size_label（阶段 0 恒无标签）；
        // planOf 读回全量四件（表即事实源——进程重启不丢）
        Long projectId = persistedProject("9843");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenThrow(new IllegalStateException("起服失败"));

        BuildPlan plan = new BuildPlan(
                List.of("用户能注册登录", "用户能下单支付"),
                BuildPlan.sequentialDependencies(List.of("用户能注册登录", "用户能下单支付")),
                List.of(new BuildPlan.CoreJourney("买家", "首页", "注册登录→浏览商品→下单支付")),
                List.of(SliceSizeLabel.SMALL, SliceSizeLabel.LARGE));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 片行规模标签：逐片落 code（1=小 2=中 3=大），阶段 0 无标签
        assertThat(jdbcTemplate.queryForList(
                "SELECT size_label FROM prj_generation_segments WHERE project_id = ? ORDER BY ord",
                projectId))
                .extracting(row -> row.get("size_label"))
                .containsExactly(null, 1, 3);
        // 旅程行：三要素逐条落、锚 = 项目 PRD 版本锚（同片行锚）
        List<Map<String, Object>> journeys = journeyRows(projectId);
        assertThat(journeys).hasSize(1);
        assertThat(journeys.get(0)).containsEntry("role", "买家")
                .containsEntry("entry", "首页")
                .containsEntry("key_actions", "注册登录→浏览商品→下单支付");
        assertThat((java.sql.Timestamp) journeys.get(0).get("prd_produced_at"))
                .isEqualTo(projectPrdAnchor(projectId));
        // 读回（轨道表还原 BuildPlan 全量四件）
        assertThat(appService.planOf(projectId)).isEqualTo(plan);
    }

    @Test
    void given_prd_revised_when_replan_then_journeys_and_sizes_replaced_no_residue() {
        // #423 AC② 演进重产：PRD 演进（锚换新）后重产计划——旅程行整组替换（旧旅程
        // 无残留）、片行标签随片行整组替换重落（旧标签无残留）、锚 = 修订后 PRD 版本
        Long projectId = persistedProject("9844");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenThrow(new IllegalStateException("起服失败"));

        appService.dispatchGenerationOnTurnClose(projectId, new BuildPlan(
                List.of("旧切片一", "旧切片二"),
                BuildPlan.sequentialDependencies(List.of("旧切片一", "旧切片二")),
                List.of(new BuildPlan.CoreJourney("旧角色", "旧首页", "旧动作串")),
                List.of(SliceSizeLabel.SMALL, SliceSizeLabel.SMALL)));
        revisePrd(projectId);

        BuildPlan replan = new BuildPlan(
                List.of("新切片一", "新切片二", "新切片三"),
                BuildPlan.sequentialDependencies(List.of("新切片一", "新切片二", "新切片三")),
                List.of(new BuildPlan.CoreJourney("新角色", "新首页", "新动作串"),
                        new BuildPlan.CoreJourney("新角色二", "新后台", "新动作串二")),
                List.of(SliceSizeLabel.MEDIUM, SliceSizeLabel.LARGE, SliceSizeLabel.LARGE));
        appService.dispatchGenerationOnTurnClose(projectId, replan);

        // 旅程行整组替换：只含新计划旅程（旧旅程无残留）、锚 = 修订后 PRD 版本锚
        List<Map<String, Object>> journeys = journeyRows(projectId);
        assertThat(journeys).extracting(journey -> journey.get("role"))
                .containsExactly("新角色", "新角色二");
        assertThat((java.sql.Timestamp) journeys.get(0).get("prd_produced_at"))
                .isEqualTo(projectPrdAnchor(projectId));
        // 片行标签随整组替换重落（旧标签无残留；阶段 0 恒无标签；code 口径）
        assertThat(jdbcTemplate.queryForList(
                "SELECT size_label FROM prj_generation_segments WHERE project_id = ? ORDER BY ord",
                projectId))
                .extracting(row -> row.get("size_label"))
                .containsExactly(null, 2, 3, 3);
        // 读回 = 新计划全量（旧两件不沿用）
        assertThat(appService.planOf(projectId)).isEqualTo(replan);
    }

    @Test
    void given_plan_with_deferred_items_when_recorded_then_deferrals_persisted_with_prd_anchor() {
        // ADR-0050 推迟段落库随 PRD 版本走：推迟行落计划推迟表（锚 = 项目
        // prd_produced_at）；planOf 读回全量五件（表即事实源——进程重启不丢）
        Long projectId = persistedProject("9845");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenThrow(new IllegalStateException("起服失败"));

        BuildPlan plan = new BuildPlan(
                List.of("用户能注册登录"),
                BuildPlan.sequentialDependencies(List.of("用户能注册登录")),
                List.of(new BuildPlan.CoreJourney("买家", "首页", "注册登录→浏览商品→下单支付")),
                List.of(SliceSizeLabel.SMALL),
                List.of("支付：下单结算界面走通，停在不真扣款", "通知：站内呈现，不发真实推送"));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        List<Map<String, Object>> deferrals = deferralRows(projectId);
        assertThat(deferrals).extracting(row -> row.get("item"))
                .containsExactly("支付：下单结算界面走通，停在不真扣款", "通知：站内呈现，不发真实推送");
        assertThat((java.sql.Timestamp) deferrals.get(0).get("prd_produced_at"))
                .isEqualTo(projectPrdAnchor(projectId));
        // 读回（轨道表还原 BuildPlan 全量五件）
        assertThat(appService.planOf(projectId)).isEqualTo(plan);
    }

    @Test
    void given_prd_revised_when_replan_then_deferrals_replaced_no_residue() {
        // ADR-0050 演进重产：PRD 演进（锚换新）后重产计划——推迟行整组替换（旧推迟
        // 项无残留）、锚 = 修订后 PRD 版本
        Long projectId = persistedProject("9846");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenThrow(new IllegalStateException("起服失败"));

        appService.dispatchGenerationOnTurnClose(projectId, new BuildPlan(
                List.of("旧切片一"),
                BuildPlan.sequentialDependencies(List.of("旧切片一")),
                List.of(new BuildPlan.CoreJourney("旧角色", "旧首页", "旧动作串")),
                List.of(SliceSizeLabel.SMALL),
                List.of("旧推迟项")));
        revisePrd(projectId);

        BuildPlan replan = new BuildPlan(
                List.of("新切片一"),
                BuildPlan.sequentialDependencies(List.of("新切片一")),
                List.of(new BuildPlan.CoreJourney("新角色", "新首页", "新动作串")),
                List.of(SliceSizeLabel.MEDIUM),
                List.of("新推迟项一", "新推迟项二"));
        appService.dispatchGenerationOnTurnClose(projectId, replan);

        List<Map<String, Object>> deferrals = deferralRows(projectId);
        assertThat(deferrals).extracting(row -> row.get("item"))
                .containsExactly("新推迟项一", "新推迟项二");
        assertThat((java.sql.Timestamp) deferrals.get(0).get("prd_produced_at"))
                .isEqualTo(projectPrdAnchor(projectId));
        // 读回 = 新计划全量（旧推迟项不沿用）
        assertThat(appService.planOf(projectId)).isEqualTo(replan);
    }

    // ---------- 断点续跑（#221：继续生成从断点接续，已收口片不重做） ----------

    // ---------- 失败回滚本段起点（#353：重试耗尽→留账+回滚段起点+新会话重跑一轮） ----------

    /** 段起点捕获桩的假 hash（hex 形态，过版本层引用校验）。 */
    private static final String FAKE_SEGMENT_START = "a1b2c3d4e5f6";

    /**
     * 段起点捕获可得的 git 桩（#353 生产形态）：rev-parse / 锚 commit 回假 hash
     * （段起跑恒能捕获段起点），其余 exec 全通；产出证明探针（#420 --name-only）
     * 缺省回有产出（正常干活——空转场景由 {@link #givenWorkspaceGitReadyWithProofScript}
     * 的脚本位改写）。缺省桩（{@link #givenAgentsMdWriteSucceeds} 的空 stdout）走
     * 「捕获不到段起点」的降级路径（不重跑、闸跳过、直接终态）——既有失败测试即
     * 该路径的断言面。
     */
    private void givenWorkspaceGitReady() {
        when(workspaceLifecycleAppService.exec(any(), any())).thenAnswer(invocation -> {
            WorkspaceExecCommand cmd = invocation.getArgument(1);
            if (cmd.command().contains("git rev-parse")) {
                return new ExecResultResponse(FAKE_SEGMENT_START + "\n", "", 0);
            }
            if (cmd.command().contains("git diff --name-only")) {
                return new ExecResultResponse("src/app/page.tsx\n", "", 0);
            }
            return new ExecResultResponse("", "", 0);
        });
        when(workspaceLifecycleAppService.exposePreview(anyString()))
                .thenReturn(URI.create("http://localhost:30080"));
    }

    // ---------- 存量恢复（#223：无表项目对照收尾卡标已完片、只补缺口） ----------

    // ---------- 生成态四态投影（#222：REST 配置与「继续生成」出口的推导输入） ----------

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
        doAnswer(invocation -> {
            submitted.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());
        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.GENERATING);

        // 起跑后重试耗尽转终态失败 → 生成中断（「继续生成」出口的配置；进程重启丢
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
        givenConverseChain(new AgentReply("r0", "阶段0完成"), new AgentReply("r1", "切片1完成"));
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

        String inventory = GenerationAppService.resumeInventoryBlock(0, List.of(stage0), null,
                /* failedSegmentRolledBack= */ true);

        assertThat(inventory)
                .contains("自动重试耗尽后失败")
                .doesNotContain("最近错误现场")
                .contains("已收口进度：尚无")
                // #353 失败段干净重做口径：失败现场已回滚本段起点——从头完成而非脏续
                .contains("回滚到本段起点")
                .contains("从头完成本段任务")
                .doesNotContain("保留已对的工作");
    }

    // ---------- 命令与资产 ----------

    @Test
    void given_project_when_generate_then_kernel_command_bound_to_kernel_session() {
        givenPerSliceSessions();
        // 编码命令全要素（阶段 0 首 run 代表）：coder 会话稳定绑定 + owner 寻址 +
        // EXECUTOR 配置 + 长任务超时 + 计量 dims + 项目工作区 + 流关联
        Long projectId = persistedProject("9805");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");

        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId,
                SINGLE_SLICE_PLAN);

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
        givenAgentsMdWriteSucceeds();
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
        // #252 增强工具开关同批编入 toolSpec（进缓存键保持规格一致）；#545 起
        // executor 槽有构建轨出图增强件（gi），缺省规格三件全开
        // ws=true,fu=true,gi=true
        Long projectId = persistedProject("9870");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, system_prompt, model_config_key)
                VALUES ('executor', ?, 'pro')
                """, "执行体覆盖协议：开工先列步骤。");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

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
        // 知识命中前置注入（#24，#601 内核形态顺延）：query = 内核首跑 prompt，命中块
        // 拼在任务 prompt 前
        Long projectId = persistedProject("9807");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        givenKnowledgeHits();

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

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
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        doThrow(new RuntimeException("pgvector 抖动")).when(knowledgePort)
                .retrieve(anyString(), anyInt());

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .isEqualTo(GenerationAppService.KERNEL_FIRST_RUN_PROMPT);
    }

    @Test
    void given_stage0_first_attempt_fails_when_retry_then_silent_retry_and_track_completes() {
        // 失败自动静默重试（#601 内核形态：首试失败→重试成功→收口判据过）：重试续作
        // 轨换内核重试 prompt（不重注入）、重试尝试内部 runId 逐次换新、中间信号零发；
        // 全程成功 → generated_at 落位
        Long projectId = persistedProject("9809");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenKnowledgeHits(); // 首试带前置注入，重试不重注入（注入块已在会话历史）
        givenConverseChain(new IllegalStateException("首次尝试中断"),
                new AgentReply("run-2", "系统已生成"));

        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId,
                SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        List<AgentCommand> attempts = command.getAllValues();
        assertThat(attempts.get(0).runId()).isEqualTo(run.runId()); // 首试 = 首 run 身份
        assertThat(attempts.get(0).prompt())
                .endsWith("————\n\n" + GenerationAppService.KERNEL_FIRST_RUN_PROMPT);
        assertThat(attempts.get(1).runId()).isNotEqualTo(run.runId()); // 重试内部 runId
        assertThat(attempts.get(1).prompt())
                .contains("错误现场：首次尝试中断")
                .contains("进展现场（平台可观测事实）")
                .contains("已累计用时")
                .contains("已落盘文件变更 尚无")
                .endsWith("直至 pnpm build 通过且 curl http://localhost:8081 有响应。");

        // 静默重试（#82/#84）：无重试信号、无逐次 error（run 失败为唯一失败终态）
        verify(eventsAppService, never()).publishAgentEvent(eq(RETIRED_RETRYING), anyMap());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.ERROR), anyMap());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 收口判据与收口扩载 ----------

    @Test
    void given_converse_ok_but_service_unreachable_when_generate_then_no_generated_at_and_reinitiate_exit() {
        givenPerSliceSessions();
        // 假完成（#35）：阶段 0 converse 正常结束但 8081 不可达——核验不过不落
        // generated_at，走既有重试/终态失败路径（阶段 0 失败不派切片），「继续生成」出口
        // 仍在；重发（REST 路径无交接物）沿用表内现行计划（#220 PRD 锚一致即有效）
        Long projectId = persistedProject("9810");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds(); // AGENTS.md 写入（无 curl 字样）成功
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("curl"))))
                .thenReturn(new ExecResultResponse("", "Connection refused", 7));
        givenConverseChain(new AgentReply("run-fake", "很抱歉，目前系统尚未真正实现出来"));

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 阶段 0 假完成 → 静默重试到超限转终态：converse 满 maxAttempts 次、不派切片
        verify(agentClient, times(properties.getMaxAttempts())).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(RETIRED_RETRYING), anyMap());
        assertThat(generatedAt(projectId)).isNull();

        // 项目不被空壳锁死：generated_at 未落 = 「继续生成」出口在——核验改可达后重发即成功
        //（重发无交接物：内核不消费计划，重发即新内核 run 照跑收口判据）
        doReturn(new ExecResultResponse("", "", 0))
                .when(workspaceLifecycleAppService).exec(any(), any());
        appService.startGeneration(projectId);
        // 重发 = 新内核 run 一次 converse 收口
        verify(agentClient, times(properties.getMaxAttempts() + 1)).converse(any(), any());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 首屏界面先行与入口文件写全（#339） ----------

    // ---------- 产出证明闸（#420 空转片防呆：零 diff＋无「能力已存在」确认＝结构性拒绝） ----------

    /**
     * 三闸切回旧行为（#435 缺省闸关——闸开用例显式开闸）：直写 executor 行三开关
     * true＝#424/#420/#427 现行为（产出证明/验证在场拒绝—补做链路与整轨终验的
     * 既有用例承载面）。砍税缺省面由 given_presence_and_proof_gates_closed_.../
     * given_final_verification_gate_closed_... 锚定。
     */
    private void givenClosingGatesOpen() {
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs
                    (agent_key, web_search_enabled, fetch_url_enabled, routing_enabled,
                     verification_presence_gate_enabled, proof_of_output_gate_enabled,
                     final_verification_gate_enabled)
                VALUES ('executor', TRUE, TRUE, TRUE, TRUE, TRUE, TRUE)
                ON CONFLICT (agent_key) DO UPDATE SET
                    verification_presence_gate_enabled
                        = EXCLUDED.verification_presence_gate_enabled,
                    proof_of_output_gate_enabled = EXCLUDED.proof_of_output_gate_enabled,
                    final_verification_gate_enabled = EXCLUDED.final_verification_gate_enabled
                """);
    }

    /**
     * 基座 lint 强制档阻断开关切回旧行为（#541 缺省＝让路——阻断形态用例显式切回）：
     * 直写 executor 行 robust_lint_blocking_enabled=TRUE（强制档违规喂修轮修一轮＋
     * 修不完收口结构性拒绝＝#425 原阻断形态——回滚保险承载面）。与
     * {@link #givenClosingGatesOpen}/{@link #givenPerSliceSessions} 可叠加（列各写各的）。
     * 让路缺省面由 given_bare_client_..._default_... 系新用例锚定。
     */
    private void givenRobustLintBlocking() {
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs
                    (agent_key, web_search_enabled, fetch_url_enabled, routing_enabled,
                     robust_lint_blocking_enabled)
                VALUES ('executor', TRUE, TRUE, TRUE, TRUE)
                ON CONFLICT (agent_key) DO UPDATE SET
                    robust_lint_blocking_enabled = EXCLUDED.robust_lint_blocking_enabled
                """);
    }

    /**
     * 切片机器切回旧行为（#436 缺省＝单会话开＋并行机器关——旧行为用例显式切回）：
     * 直写 executor 行 single_session_generation_enabled=FALSE（每片新会话，
     * {@code coder-{projectId}-slice-n}）＋ parallel_slice_machine_enabled=TRUE
     * （宽波 fork 并行）。与 {@link #givenClosingGatesOpen} 可叠加（列各写各的）。
     * 单会话缺省面由 given_single_session_... 系新用例锚定。
     */
    private void givenPerSliceSessions() {
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs
                    (agent_key, web_search_enabled, fetch_url_enabled, routing_enabled,
                     single_session_generation_enabled, parallel_slice_machine_enabled)
                VALUES ('executor', TRUE, TRUE, TRUE, FALSE, TRUE)
                ON CONFLICT (agent_key) DO UPDATE SET
                    single_session_generation_enabled
                        = EXCLUDED.single_session_generation_enabled,
                    parallel_slice_machine_enabled = EXCLUDED.parallel_slice_machine_enabled
                """);
    }

    /**
     * 切片机器半开（#436 三态之「每片新会话＋并行机器关」）：直写 executor 行
     * single_session_generation_enabled=FALSE＋parallel_slice_machine_enabled=FALSE
     * ——每片新会话保留（coder-{projectId}-slice-n），但 fork/波/并行/合并全关，
     * 宽波逐片主工作区串行。
     */
    private void givenPerSliceSessionsSerialOnly() {
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs
                    (agent_key, web_search_enabled, fetch_url_enabled, routing_enabled,
                     single_session_generation_enabled, parallel_slice_machine_enabled)
                VALUES ('executor', TRUE, TRUE, TRUE, FALSE, FALSE)
                ON CONFLICT (agent_key) DO UPDATE SET
                    single_session_generation_enabled
                        = EXCLUDED.single_session_generation_enabled,
                    parallel_slice_machine_enabled = EXCLUDED.parallel_slice_machine_enabled
                """);
    }

    /**
     * 产出证明探针脚本位（#420）：key＝工作区（并行波下 fork 副本各跑各的闸——
     * 按工作区隔离不串扰）、true＝零 diff 空转、缺省 false＝有产出。
     */
    private final Map<String, AtomicBoolean> idleProofs = new ConcurrentHashMap<>();

    private AtomicBoolean idleProof(String workspace) {
        return idleProofs.computeIfAbsent(workspace, key -> new AtomicBoolean(false));
    }

    /**
     * 产出证明闸桩（#420）：git 就绪（段起点可捕获）＋diff 探针读脚本位
     * {@link #idleProofs}（探针命令带 --name-only，与留账的 --cached diff 区分）。
     */
    private void givenWorkspaceGitReadyWithProofScript() {
        when(workspaceLifecycleAppService.exec(any(), any())).thenAnswer(invocation -> {
            WorkspaceExecCommand cmd = invocation.getArgument(1);
            if (cmd == null) {
                return new ExecResultResponse("", "", 0);
            }
            if (cmd.command().contains("git rev-parse")) {
                return new ExecResultResponse(FAKE_SEGMENT_START + "\n", "", 0);
            }
            if (cmd.command().contains("git diff --name-only")) {
                boolean idle = idleProof((String) invocation.getArgument(0)).get();
                return new ExecResultResponse(
                        idle ? "" : "src/app/page.tsx\n", "", 0);
            }
            return new ExecResultResponse("", "", 0);
        });
        when(workspaceLifecycleAppService.exposePreview(anyString()))
                .thenReturn(URI.create("http://localhost:30080"));
    }

    // ---------- 验证在场硬门（#424；#433 开关化、#435 缺省闸关——闸开用例显式切回） ----------

    // ---------- 整轨终验＋fix 循环＋已知缺陷叙事（#427） ----------

    /** 单旅程计划（#427 整轨终验的载荷轴——旅程清单随计划同批）。 */
    private static final BuildPlan JOURNEY_PLAN = new BuildPlan(
            List.of("用户能注册登录", "用户能下单支付"),
            BuildPlan.sequentialDependencies(List.of("用户能注册登录", "用户能下单支付")),
            List.of(new BuildPlan.CoreJourney("买家", "首页", "注册登录→浏览商品→下单支付")),
            List.of(SliceSizeLabel.SMALL, SliceSizeLabel.MEDIUM));

    @Test
    void given_protocol_constants_when_inspect_then_ui_first_and_entry_completeness_anchored() {
        // #339 协议面（经运营配置面可调）：主智能体切片计划约定与补产提示词同向
        // 承载首片界面先行；执行体协议承载入口文件写全约定
        assertThat(AgentProfile.MAIN.systemPrompt())
                .contains("先做出可点的界面")
                .contains("界面骨架")
                .contains("预览立即可点");
        assertThat(MainAgentAppService.BUILD_PLAN_REQUEST_PROMPT).contains("先做出可点的界面");
        assertThat(AgentProfile.EXECUTOR.systemPrompt())
                .contains("入口文件")
                .contains(ProjectFiles.BASELINE_ENTRY)
                .contains("其余代码略")
                // #435 verify 自决化：验证降为自决建议（report_verification 不再是收口
                // 要件；闸态以平台回执为准）＋自报验收要点＋修复—复验轮次预算
                .contains("自报本片验收要点")
                .contains("验证不是收口要件")
                .contains("闸态以平台回执为准")
                .contains("轮次预算");
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
            verifyRan(command);
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

        // 内核单 run 单收口；断言收尾卡
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceClosing =
                (Map<String, Object>) payloads.getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD);
        assertThat(sliceClosing)
                .containsEntry("summary", GenerationAppService.KERNEL_CLOSING_SUMMARY)
                .containsEntry("prdChanged", false)
                .containsEntry("systemChanged", true)
                .containsEntry("systemNote", GenerationAppService.KERNEL_CLOSING_SUMMARY);
        assertThat(sliceClosing).doesNotContainKey("prdNote");
        // #424 观测面：verifyRan 脚本位登记进收尾卡验证统计——统计呈现随新载荷
        // 口径一致（#435 起登记非收口要件，但登记的统计照实呈现）
        assertThat((Map<String, Object>) sliceClosing.get(AgentEventTypes.VERIFICATION_FIELD))
                .containsEntry(AgentEventTypes.VERIFICATION_TOTAL_FIELD, 1)
                .containsEntry(AgentEventTypes.VERIFICATION_ROUNDS_FIELD, 1);
        assertThat((List<Map<String, Object>>) sliceClosing.get("files")).hasSize(2);
        assertThat((Long) sliceClosing.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_durations_when_closes_then_closing_carries_duration_breakdown() {
        // #111 收口扩载·阶段耗时分布：四桶齐备（llmMs / toolsMs 按工具名分桶·command
        // 按命令归组嵌套 / verifyMs 源账 / closingMs 收口尾序）+ 逐尝试分布；委派族
        // 工具（agent_spawn 旧名＋agent_send/wait_async_results——#417 补全）除名
        // （跨距与验证源账重叠，入桶即双计）；SSE 扩载与对话史落库同载荷；
        // 桶计与 durationMs 一致性守卫（允许小误差，量级不符即埋点有洞）
        Long projectId = persistedProject("9818");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
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

        appService.dispatchGenerationOnTurnClose(projectId, new BuildPlan(List.of("用户能注册登录")));

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
                .containsEntry("verifyMs", 1L);
        Map<String, Object> toolsMs = (Map<String, Object>) breakdown.get("toolsMs");
        assertThat(toolsMs)
                .containsEntry("write_file", 2L)
                .doesNotContainKey("agent_spawn") // 委派族除名（防与验证源账双计）
                .doesNotContainKey("agent_send")
                .doesNotContainKey("wait_async_results");
        assertThat((Map<String, Object>) toolsMs.get("command"))
                .containsExactly(Map.entry("install", 3L));
        long closingMs = ((Number) breakdown.get("closingMs")).longValue();
        assertThat(closingMs).isGreaterThanOrEqualTo(0L);
        // 逐尝试分布：单次成功尝试（首试）
        assertThat((List<Map<String, Object>>) breakdown.get("attempts")).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt).containsEntry("attempt", 1);
                    assertThat(attempt).containsEntry("llmMs", 5L);
                    assertThat(attempt).containsEntry("verifyMs", 1L);
                    assertThat((Map<String, Object>) attempt.get("toolsMs"))
                            .containsEntry("write_file", 2L)
                            .doesNotContainKey("agent_spawn")
                            .doesNotContainKey("agent_send")
                            .doesNotContainKey("wait_async_results");
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
                .containsKey("llmMs").containsKey("toolsMs").containsKey("verifyMs")
                .containsKey("closingMs").containsKey("attempts");
        assertThat(((Number) persistedBreakdown.get("llmMs")).longValue()).isEqualTo(5L);
        assertThat((Map<String, Object>) persistedBreakdown.get("toolsMs"))
                .containsKey("command")
                .doesNotContainKey("agent_spawn")
                .doesNotContainKey("agent_send")
                .doesNotContainKey("wait_async_results");
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_first_attempt_fails_when_retry_then_breakdown_carries_each_attempt() {
        // #111 静默重试代价可归因：每次尝试各自带分布——本例用裸异常模拟无流段
        // 观察可携的失败（前段/判据类），仍带墙钟与零桶；客户端流失败的部分观察
        // 留账见 #323 灵魂例，成功尝试带完整桶
        Long projectId = persistedProject("9819");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("首次尝试中断"))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    verifyRan(command);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                            Map.of(AgentEventTypes.FINISH_FIELD, "end")));
                    return new AgentReply(command.runId(), "系统已生成", null, List.of(),
                            new StageDurations(7, Map.of(), Map.of("test", 4L), Map.of()));
                })
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    verifyRan(command);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                            Map.of(AgentEventTypes.FINISH_FIELD, "end")));
                    return new AgentReply(command.runId(), "切片完成");
                });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 内核首试中段崩 → 重试成功收口：收尾卡 attempts 恰两条
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
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
                .containsEntry("verifyMs", 0L);
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

    @Test
    @SuppressWarnings("unchecked")
    void given_failed_attempt_with_observation_when_retry_then_account_kept_and_scene_attached() {
        // 灵魂用例（#323 失败留账＋重试携进展现场）：阶段 0 首试中段失败且失败携部分
        // 观察（AgentTurnFailure：已提交文件变更＋已闭合区间阶段耗时；计划快照经
        // 事件流观察）→ 静默重试成功收口——①收尾卡耗时分布：失败尝试带部分桶
        //（不清零）、run 级桶跨尝试含失败尝试；②变更清单含失败尝试已落盘的文件；
        // ③重试 prompt 携进展现场（已干多久/干到哪——累计用时、已落盘文件、计划
        // 进度），裸异常原文不再是唯一现场（#221 错误现场仍在，原地修不回归）
        Long projectId = persistedProject("9833");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any()))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    verifyRan(command);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                            Map.of("prompt", command.prompt())));
                    sink.accept(scripted(AgentEventTypes.PART_PLAN, command.runId(),
                            Map.of(AgentEventTypes.PART_PLAN_STEPS_FIELD, List.of(
                                    Map.of(AgentEventTypes.PART_PLAN_STEP_ID_FIELD, "s1",
                                            AgentEventTypes.PART_PLAN_STEP_TITLE_FIELD, "初始化项目",
                                            AgentEventTypes.PART_PLAN_STEP_STATE_FIELD,
                                            AgentEventTypes.PART_PLAN_STATE_COMPLETED),
                                    Map.of(AgentEventTypes.PART_PLAN_STEP_ID_FIELD, "s2",
                                            AgentEventTypes.PART_PLAN_STEP_TITLE_FIELD, "实现注册页",
                                            AgentEventTypes.PART_PLAN_STEP_STATE_FIELD,
                                            AgentEventTypes.PART_PLAN_STATE_IN_PROGRESS)))));
                    throw new AgentTurnFailure(
                            "智能体调用失败（runId=" + command.runId() + "）：切片1中断",
                            new RuntimeException("切片1中断"),
                            List.of(new FileChange("src/failed.tsx", 40, 2)),
                            new StageDurations(3, Map.of("write_file", 7L), Map.of(), Map.of()));
                })
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    verifyRan(command);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                            Map.of(AgentEventTypes.FINISH_FIELD, "end")));
                    return new AgentReply(command.runId(), "系统已生成", null,
                            List.of(new FileChange("src/lib.js", 10, 0)),
                            new StageDurations(7, Map.of(), Map.of(), Map.of()));
                });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 重试 prompt 携进展现场（#323）：错误现场＋已干多久/干到哪的平台可观测事实
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(1).prompt())
                .contains("错误现场：")
                .contains("切片1中断")
                .contains("进展现场（平台可观测事实）")
                .contains("已累计用时")
                .contains("已落盘文件变更 1 件：src/failed.tsx（+40/-2）")
                .contains("最近计划进度：")
                .contains("✓ 初始化项目")
                .contains("● 实现注册页")
                .contains("不要重做");

        // 收口收尾卡：失败尝试留账——部分桶不清零、run 级桶跨尝试含失败尝试、
        // 变更清单含失败尝试已落盘的文件（活动量口径：同路径跨尝试〔含失败〕合并）
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payloads.getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> breakdown =
                (Map<String, Object>) closing.get("durationBreakdown");
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) breakdown.get("attempts");
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0))
                .containsEntry("attempt", 1)
                .containsEntry("llmMs", 3L);
        assertThat((Map<String, Object>) attempts.get(0).get("toolsMs"))
                .containsEntry("write_file", 7L);
        assertThat(attempts.get(1))
                .containsEntry("attempt", 2)
                .containsEntry("llmMs", 7L);
        assertThat(breakdown).containsEntry("llmMs", 10L); // run 级 = 失败 3 + 成功 7
        assertThat((Map<String, Object>) breakdown.get("toolsMs"))
                .containsEntry("write_file", 7L);
        assertThat((List<Map<String, Object>>) closing.get("files"))
                .extracting(file -> file.get("path"))
                .containsExactlyInAnyOrder("src/failed.tsx", "src/lib.js");
    }

    // ---------- 基座 lint 两档（#425：强制档拦裸写/预置档拦自写不拦不用） ----------

    /** 基座 lint 脚本位（key＝工作区）：强制档/预置档 grep 的 stdout（缺省空＝零命中）。 */
    private final Map<String, String> robustLintMandatory = new ConcurrentHashMap<>();
    private final Map<String, String> robustLintPreset = new ConcurrentHashMap<>();

    /**
     * 基座 lint 桩（#425）：两档 grep 按命令分流读脚本位（空 stdout＝退出码 1 零命中）
     * ——须在 {@link #givenWorkspaceGitReadyWithProofScript} 之后打（窄桩后注册优先；
     * 探针与既有命令走既有宽桩 exit 0＝健壮件在场）。
     */
    private void givenRobustKitPresentWithLintScript() {
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd != null && cmd.command().contains("grep -rnE")
                        && cmd.command().contains("createClient"))))
                .thenAnswer(invocation -> {
                    String stdout = robustLintMandatory.getOrDefault(
                            (String) invocation.getArgument(0), "");
                    return new ExecResultResponse(stdout, "", stdout.isEmpty() ? 1 : 0);
                });
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd != null && cmd.command().contains("grep -rnE")
                        && cmd.command().contains("pbkdf2"))))
                .thenAnswer(invocation -> {
                    String stdout = robustLintPreset.getOrDefault(
                            (String) invocation.getArgument(0), "");
                    return new ExecResultResponse(stdout, "", stdout.isEmpty() ? 1 : 0);
                });
    }

    // ---------- 遵守三件套（#296）：派发物化接线＋收口扫描与自动修一轮 ----------

    /** oxlint 违规诊断输出（真实 --format json 形状）。 */
    private static final String OXLINT_VIOLATIONS_JSON = """
            { "diagnostics": [{"message": "\\"bg-red-500\\" uses the raw Tailwind palette. Use a theme token.",
            "code": "shadcn(no-raw-colors)","severity": "error","filename": "src/app/page.tsx",
            "labels": [{"span": {"line": 3}}]}]}
            """;

    /** 收尾卡里的设计规范扫描载荷（payload → closing → designLint 两层取值）。 */
    private static Map<String, Object> lintOf(ArgumentCaptor<Map<String, Object>> payloads,
            int index) {
        Map<String, Object> closing = (Map<String, Object>) payloads.getAllValues().get(index)
                .get(AgentEventTypes.CLOSING_FIELD);
        return (Map<String, Object>) closing.get("designLint");
    }

    /** 带规范正本的项目（遵守链的分岔依据——直插正本行，免提炼链路）。 */
    private void givenSpecRow(Long projectId) {
        jdbcTemplate.update(
                "INSERT INTO prj_design_specs (id, project_id, source_draft_path, source_run_id, tokens)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb)",
                4821000000000000001L, projectId, "/design/home-1.html", "run-final-1",
                "{\"--primary\": \"#166534\"}");
    }

    /** 平台落盘写桩（物化路径的 execWithStdin——缺省成功）。 */
    private void givenPlatformWriteSucceeds() {
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("123", "", 0));
    }

    /** oxlint 扫描违规桩（后注册优先——命令含 oxlint 的 exec 分岔到本桩）。 */
    private void givenOxlintViolations() {
        when(workspaceLifecycleAppService.exec(anyString(),
                argThat(command -> command != null && command.command().contains("oxlint"))))
                .thenReturn(new ExecResultResponse(OXLINT_VIOLATIONS_JSON, "", 1));
    }

    @Test
    void given_no_spec_when_dispatch_then_agents_md_plain_and_zero_materialization() {
        // 一等断言（#296 验收⑤＋#543 kill-switch 关＝回无规范现状）：风格层关停的
        // 无规范项目派发——AGENTS.md 不带设计规范节、物化零写入（DESIGN.md／lint
        // 配置皆不落，基座行为零改变）
        jdbcTemplate.update("UPDATE prj_style_direction_switch SET enabled = FALSE WHERE id = 1");
        Long projectId = persistedProject("9820");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<WorkspaceExecCommand> execs =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(1)).exec(eq("9820"), execs.capture());
        assertThat(execs.getAllValues()).extracting(WorkspaceExecCommand::command)
                .anySatisfy(command -> assertThat(command).contains("AGENTS.md"))
                .noneSatisfy(command -> assertThat(command).contains("## 设计规范"))
                .noneSatisfy(command -> assertThat(command).contains("DESIGN.md"));
        verify(workspaceLifecycleAppService, never()).execWithStdin(any(), any(), any());
    }

    @Test
    void given_spec_when_dispatch_then_materialized_and_agents_md_carries_design_section() {
        // 灵魂用例（#296 验收①）：带规范项目首个构建起跑——物化落位（规则文件＋
        // lint 配置）＋AGENTS.md 拼入设计规范节（提示词注入的工作区腿）
        Long projectId = persistedProject("9821");
        givenSpecRow(projectId);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenPlatformWriteSucceeds();
        givenConverseSucceeds("已生成");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<WorkspaceExecCommand> execs =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(1)).exec(eq("9821"), execs.capture());
        assertThat(execs.getAllValues()).extracting(WorkspaceExecCommand::command)
                .anySatisfy(command -> assertThat(command)
                        .contains("AGENTS.md").contains("## 设计规范"));
        ArgumentCaptor<WorkspaceExecCommand> writes =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(2)).execWithStdin(eq("9821"),
                writes.capture(), any());
        assertThat(writes.getAllValues()).extracting(WorkspaceExecCommand::command)
                .anySatisfy(command -> assertThat(command).contains("DESIGN.md"))
                .anySatisfy(command -> assertThat(command).contains(".oxlintrc.json"));
    }

    /** 健壮件缺席桩（后注册优先）：探针命令（test -f 基座健壮件）退非 0——存量工作区形。 */
    private void givenRobustKitAbsent() {
        when(workspaceLifecycleAppService.exec(anyString(),
                argThat(command -> command != null
                        && command.command().equals(GenerationAppService.robustKitProbeCommand()))))
                .thenReturn(new ExecResultResponse("", "not found", 1));
    }

    @Test
    void given_robust_kit_present_when_dispatch_then_agents_md_carries_stack_rules_section() {
        // #421 验收①（栈规则集腿）：新基座工作区（探针在场）派发——AGENTS.md 拼入
        // 本栈写法规则节（件用法示例＋用预置件规则），且写入命令不携内嵌双引号
        // （Windows argv fail-fast 面，①族纪律——节内容改坏即红）
        Long projectId = persistedProject("9830");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds(); // exec 全退 0 ⇒ 探针在场 ⇒ 节拼入
        givenConverseSucceeds("已生成");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<WorkspaceExecCommand> execs =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(2)).exec(eq("9830"), execs.capture());
        assertThat(execs.getAllValues()).extracting(WorkspaceExecCommand::command)
                .anySatisfy(command -> assertThat(command).contains("test -f"))
                .anySatisfy(command -> assertThat(command)
                        .contains("AGENTS.md")
                        .contains("本栈写法规则")
                        .contains("src/lib/robust/data-access")
                        .contains("不自己手写"))
                .allSatisfy(command -> assertThat(command).doesNotContain("\""));
    }

    @Test
    void given_legacy_workspace_when_dispatch_then_stack_rules_section_not_backfilled() {
        // #421 存量不回补：健壮件不在场（探针退 1）——AGENTS.md 不拼栈规则集节
        // （存量工作区没有件，节内容指路不存在文件即误导；不回补口径结构性落地）
        Long projectId = persistedProject("9831");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenRobustKitAbsent();
        givenConverseSucceeds("已生成");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<WorkspaceExecCommand> execs =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(1)).exec(eq("9831"), execs.capture());
        assertThat(execs.getAllValues()).extracting(WorkspaceExecCommand::command)
                .anySatisfy(command -> assertThat(command).contains("AGENTS.md"))
                .noneSatisfy(command -> assertThat(command).contains("本栈写法规则"));
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
        doAnswer(invocation -> {
            queued.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

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
        Long projectId = projectRepository.save(Project.create("无 PRD 项目", null, null, 9816L, OWNER, null)).getId();

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
        givenConverseChain(new AgentReply("run-y", "系统已生成"));
        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);
        // 重发 = 新内核 run 一次 converse
        verify(agentClient, times(1)).converse(any(), any());
    }

    // ---------- 首建派发门（#542 访谈②：两入口退役——首产回调与轮收口兜一轮删、快道/按钮点火） ----------

    @Test
    void given_no_plan_when_turn_close_dispatch_with_handover_then_full_chain() {
        // 灵魂例（#462 收口精化的存活半边，#542 语义顺延）：快道收口观测派发
        // （dispatchGenerationOnTurnClose 只在快道事实/设计定稿在场时被调用）携
        // buildPlanFacts 交接物——直派全链：计划落轨＋阶段 0＋逐片
        Long projectId = persistedProject("9880");
        seedConfirmedVersion(projectId);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            return new AgentReply(command.runId(), "完成");
        });

        GenerationAppService.GenerationRun run =
                appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        assertThat(run).isNotNull();
        assertThat(appService.planOf(projectId)).isEqualTo(SINGLE_SLICE_PLAN);
        // 内核不逐片收口——无收口片，断点口径遗留为 -1
        assertThat(appService.deepestClosedSegmentOf(projectId)).isEqualTo(-1);
        assertThat(generatedAt(projectId)).isNotNull();
        // 首建点火时点落位（#542 派发门——卡/门「未派发」读点的事实源）
        assertThat(jdbcTemplate.queryForMap(
                "SELECT generation_requested_at FROM prj_projects WHERE id = ?", projectId)
                .get("generation_requested_at")).isNotNull();
    }

    @Test
    void given_prd_produced_without_user_trigger_then_no_auto_dispatch() throws Exception {
        // 灵魂例（#542 AC③：两入口退役断言）——PRD 产出不再自动起跑：首产回调
        // （dispatchFirstDemoOnPrdProduced 已删）只剩事实登记，计划在场也不派——
        // 轨道表预置计划后经真适配器回调产出 PRD，零编码 run、点火不落位（全域无
        // 无条件自动路径；点火＝岔路口「立即生成」/付费确认/快道，另见
        // MainAgentAppServiceTest 自然收口用例）
        Long projectId = persistedProject("9881");
        seedConfirmedVersion(projectId);
        // 计划在场（退役前首产回调「计划在场即派」的少数形——现在也不派）
        Project project = projectRepository.findById(projectId).orElseThrow();
        generationSegments.save(GenerationSegment.pending(projectId, 0,
                GenerationAppService.STAGE0_TITLE, project.getPrdProducedAt()));
        generationSegments.save(GenerationSegment.pending(projectId, 1,
                "用户能注册登录", project.getPrdProducedAt()));
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();

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
        // 失败重发同口径（#542：重试面＝「继续生成」按钮，不重复确认/不再出卡）：
        // 首派失败（阶段 0 起服耗尽转终态）后项目仍「未生成」——「继续生成」
        // （startGeneration）再触发即断点续跑（计划已在轨道表，不重产计划）。
        // 首派走快道收口观测（产 PRD 同转计划交接物在场），重发走按钮路径
        Long projectId = persistedProject("9883");
        seedConfirmedVersion(projectId);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command == null) {
                return null; // matcher 空参调用（重打桩触发旧 answer）直接过
            }
            verifyRan(command);
            throw new IllegalStateException("起服失败"); // 审核段失败（重试耗尽转终态）
        });

        GenerationAppService.GenerationRun first =
                appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        assertThat(first).isNotNull();
        assertThat(generatedAt(projectId)).isNull(); // 失败——仍「未生成」
        // 重发（「继续生成」按钮路径，计划在轨道表）：续跑即完成（重打桩的 matcher
        // 空参调用触发旧 answer——旧桩对 null 命令直接过，同既有先例）
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            return command != null ? new AgentReply(command.runId(), "完成") : null;
        });
        GenerationAppService.GenerationRun again = appService.startGeneration(projectId);

        assertThat(again).isNotNull();
        assertThat(generatedAt(projectId)).isNotNull();
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
        givenAgentsMdWriteSucceeds();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

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
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
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

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

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
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

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
        givenPerSliceSessions();
        // 灵魂用例（#360 AC①）：首 Demo 在途时意见轮收口的派发不再静默丢——意见入队
        // + 对话面平台明示（guide-reply 复用，prompt 缺省不再重建用户气泡）；生成
        // 收口成功后排空队列，多条意见合并为一场更新 run 补派（修正轨排队合并同构）
        Long projectId = persistedProject("9860");
        givenAgentsMdWriteSucceeds();
        List<Runnable> tracks = givenTrackCollected();
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fixDone = new CountDownLatch(1);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
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

        BuildPlan plan = new BuildPlan(List.of("用户能注册登录"));
        appService.dispatchGenerationOnTurnClose(projectId, plan);
        Thread generationWorker = new Thread(tracks.remove(0));
        generationWorker.start();
        assertThat(generating.await(5, TimeUnit.SECONDS)).isTrue();

        // 在途窗口内两条意见轮收口：不再静默跳过——入队不派（无新 converse）+ 平台明示
        assertThat(appService.dispatchGenerationOnTurnClose(projectId, null,
                new GenerationAppService.TurnOpinion("列表加筛选", null, "turn-1"))).isNull();
        assertThat(appService.dispatchGenerationOnTurnClose(projectId, null,
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
        givenPerSliceSessions();
        // #360 AC②：停止收口不排空（队列保留）、「继续生成」续跑成功收口时消化——
        // 与恢复出口不冲突（不自锁、不重复派）；生成成功后「继续生成」出口按已生成
        // 口径拒绝（PRJ_017），不与补派的更新 run 抢轨道
        Long projectId = persistedProject("9861");
        givenAgentsMdWriteSucceeds();
        List<Runnable> tracks = givenTrackCollected();
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch fixDone = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean(false);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
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

        BuildPlan plan = new BuildPlan(List.of("用户能注册登录"));
        appService.dispatchGenerationOnTurnClose(projectId, plan);
        Thread generationWorker = new Thread(tracks.remove(0));
        generationWorker.start();
        assertThat(generating.await(5, TimeUnit.SECONDS)).isTrue();

        // 在途窗口入队一条意见后用户停止首 Demo
        assertThat(appService.dispatchGenerationOnTurnClose(projectId, null,
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
        Project project = Project.create("生成项目", null, null, Long.parseLong(workspaceId), OWNER, null);
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

    /** 模拟 PRD 修订落定（savePrd 写出后的库事实）：刷新 prd_produced_at——版本锚换新值。 */
    private void revisePrd(Long projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markPrdProduced();
        projectRepository.save(project);
    }

    private Long persistedArchivedProject(String workspaceId) {
        Project project = Project.create("归档生成项目", null, null, Long.parseLong(workspaceId), OWNER, null);
        project.archive();
        return projectRepository.save(project).getId();
    }

    // ---------- #291 构建门定稿放行：稿入起跑上下文 ----------

    @Test
    void given_finalized_items_when_prompt_assembled_then_draft_context_listed() {
        // 定稿稿引用块（#291）：已定稿件逐条列出（设计物标题＋定稿稿路径），空清单
        // 零注入（纯系统项目不携带）
        assertThat(GenerationAppService.finalizedDesignsContext(List.of())).isEmpty();
        DesignItem item = DesignItem.pending(1L, 1, "品牌主 logo——方形构图",
                LocalDateTime.now());
        item.finalize("run-x", "/design/logo-2.html");
        assertThat(GenerationAppService.finalizedDesignsContext(List.of(item)))
                .contains("已定稿的设计稿")
                .contains("品牌主 logo——方形构图")
                .contains("/design/logo-2.html");
    }

    @Test
    void given_system_design_all_finalized_when_generation_dispatched_then_gate_opens() {
        // #291 验收④第二岔的生成面：设计类终点清单全部定稿——生成门放行（PRJ_042
        // 只拦未定稿完），起跑上下文携定稿稿引用块（阶段 0 与切片同携——规范源
        // 全程在场）
        Long projectId = persistedSystemDesignProject("9871");
        seedFinalizedItem(projectId, projectOf(projectId).getPrdProducedAt());
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            return command != null ? new AgentReply(command.runId(), "已构建", null, List.of())
                    : null;
        });

        GenerationAppService.GenerationRun run =
                appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        assertThat(run).isNotNull();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        // 内核首跑 prompt 前置定稿稿引用块（#601 沿用 #291——规范源在场）
        assertThat(commands.getAllValues()).extracting(AgentCommand::prompt)
                .allSatisfy(prompt -> assertThat(prompt)
                        .contains("已定稿的设计稿")
                        .contains("/design/home-1.html"));
    }

    @Test
    void given_system_design_partially_finalized_when_start_generation_then_still_gated() {
        // 门语义（#291 修订）：PRJ_042 拦「未全部定稿」——已收口未定稿（设计进行中）
        // 仍不放行；文案如实告知构建从定稿设计稿长出
        Long projectId = persistedSystemDesignProject("9872");
        seedFinalizedItem(projectId, projectOf(projectId).getPrdProducedAt());
        jdbcTemplate.update("UPDATE prj_design_items SET status = 2 WHERE project_id = ?",
                projectId); // 一件退回已收口（未定稿）

        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_DESIGN_ENDPOINT.message());
    }

    // ---------- 模型路由（#354：路由判定优先、人工覆盖兜底；钉死位＝路由关） ----------

    @Test
    void given_default_routing_when_first_attempt_fails_then_retry_escalates_to_strong() {
        // 失败升档（历史失败信号，#601 内核形态顺延）：首试快档（枚举缺省）→ 重试
        // 升强档——换档不换会话（重试续本 run 会话，上下文连续）
        Long projectId = persistedProject("9850");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseFailsThenSucceeds("起服失败");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "deepseek:deepseek-flash", // 首试——无信号、快档
                "deepseek:deepseek-v4-pro"); // 重试——失败升档
        assertThat(commands.getAllValues().get(0).sessionId())
                .isEqualTo(commands.getAllValues().get(1).sessionId());

        // 收尾卡留档：收口带路由判定（升强档＋理由＋判定依据）
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> stage0Tier = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD))
                .get("modelTier");
        assertThat(stage0Tier)
                .containsEntry("modelId", "pro")
                .containsEntry("source", "routed");
        assertThat((List<String>) stage0Tier.get("reasons")).contains("重试升档（第 2 次尝试）");
        assertThat((Map<String, Object>) stage0Tier.get("signals"))
                .containsEntry("attempt", 2)
                .containsEntry("sizeLabel", null); // 内核无片标签——规模标签信号缺位
        // 留档可查：modelTier 随收尾卡落对话史（JSONB 同载荷）——哪 run 用了哪档的
        // 持久留档面（失败 run 无收尾卡、以结构化日志为面）
        List<Map<String, Object>> closings = jdbcTemplate.queryForList(
                "SELECT closing->'modelTier'->>'modelId' AS model_config_key FROM"
                        + " prj_conversation_entries WHERE project_id = ? AND kind = ?"
                        + " ORDER BY id",
                projectId, ConversationEntryKind.CLOSING.getCode());
        assertThat(closings).extracting(row -> row.get("model_config_key"))
                .containsExactly("pro"); // 收口——重试升档后的强档
    }

    @Test
    void given_large_file_surface_when_dispatch_then_strong_from_first_attempt() {
        // 文件面大小信号：应用文件面（探针输出已扣基座基数）≥ 阈值（代码缺省 30，
        // #426 排除基座基数后重校）——首试即升强档。结构性信号开关开（#454：缺省
        // 关＝不参与判定——本用例验证开关开态的升档链）
        Long projectId = persistedProject("9851");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, routing_file_count_signal_enabled)
                VALUES ('executor', true)
                """);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("git ls-files"))))
                .thenReturn(new ExecResultResponse(" 87\n", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString)
                .containsExactly("deepseek:deepseek-v4-pro");

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceTier = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD))
                .get("modelTier");
        assertThat((List<String>) sliceTier.get("reasons")).contains("文件面超出阈值（87 ≥ 30）");
        assertThat((Map<String, Object>) sliceTier.get("signals")).containsEntry("fileCount", 87);
    }

    @Test
    void given_manual_override_with_routing_on_when_retry_then_escalation_crosses_override() {
        // 路由判定优先、人工覆盖兜底：覆盖是基础档不是钉死——首试走人工档、
        // 重试升强档（升档越过覆盖）；下一段回落人工档。doubao-flagship 已随
        // V79 退场（火山下线收尾）——人工档用 t- 夹具配置行（挂 deepseek 通道）
        Long projectId = persistedProject("9853");
        jdbcTemplate.update("""
                INSERT INTO prj_model_configs (config_key, provider_key, model_name, enabled, display_name)
                VALUES ('t-manual', 'deepseek', 'deepseek-chat-manual', TRUE, '人工档夹具')
                """);
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, model_config_key)
                VALUES ('executor', 't-manual')
                """);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseFailsThenSucceeds("起服失败");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "deepseek:deepseek-chat-manual", // 首试——无信号、人工档兜底
                "deepseek:deepseek-v4-pro"); // 重试——失败升档（越过人工档）

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> config = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD))
                .get("modelTier");
        assertThat(config)
                .containsEntry("modelId", "pro")
                .containsEntry("source", "routed");
        assertThat((List<String>) config.get("reasons")).contains("重试升档（第 2 次尝试）");
    }

    @Test
    void given_routing_off_with_override_when_retry_then_pinned_manual_tier() {
        // 钉死位＝路由关＋人工档：人工档恒生效——失败重试不升档（既有口径）
        Long projectId = persistedProject("9854");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, model_config_key, routing_enabled)
                VALUES ('executor', 'pro', false)
                """);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseFailsThenSucceeds("起服失败");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "deepseek:deepseek-v4-pro", "deepseek:deepseek-v4-pro");

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> config = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD))
                .get("modelTier");
        assertThat(config)
                .containsEntry("modelId", "pro")
                .containsEntry("source", "pinned")
                .doesNotContainKey("reasons");
    }

    @Test
    void given_routing_off_no_override_when_retry_then_enum_default_kept() {
        // 路由关＋无覆盖：缺省档恒生效（快档）——失败重试也不升档（既有口径零变化）
        Long projectId = persistedProject("9855");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, routing_enabled)
                VALUES ('executor', false)
                """);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("起服失败"))
                .thenAnswer(invocation -> new AgentReply(
                        invocation.getArgument(0, AgentCommand.class).runId(), "完成"));

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "deepseek:deepseek-flash", "deepseek:deepseek-flash");
    }

    /** 系统＋设计终点、PRD 已产出的项目（构建门用例的前置形态）。 */
    private Long persistedSystemDesignProject(String workspaceId) {
        Project project = Project.create("设计先行项目", null, null, Long.parseLong(workspaceId), OWNER, null);
        project.switchEndpoint(ProjectEndpointType.SYSTEM_DESIGN, null);
        project.markPrdProduced();
        return projectRepository.save(project).getId();
    }

    private Project projectOf(Long projectId) {
        return projectRepository.findById(projectId).orElseThrow();
    }

    /** 全部定稿的清单行（单件——门与起跑上下文的断言面）。 */
    private void seedFinalizedItem(Long projectId, LocalDateTime prdAnchor) {
        jdbcTemplate.update("""
                INSERT INTO prj_design_items (id, project_id, ord, title, status, run_id,
                    prd_produced_at, finalized_path, created_at, updated_at)
                VALUES (?, ?, 1, '首页视觉', 4, 'run-x', ?, '/design/home-1.html',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, System.nanoTime(), projectId, prdAnchor);
    }

    private Long persistedGeneratedProject(String workspaceId) {
        Project project = Project.create("已生成项目", null, null, Long.parseLong(workspaceId), OWNER, null);
        project.markGenerated();
        return projectRepository.save(project).getId();
    }
}
