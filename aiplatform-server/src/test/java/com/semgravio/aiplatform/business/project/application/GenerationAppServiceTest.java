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
import com.semgravio.aiplatform.base.agentscope.FileChange;
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

    /** 两命中的检索桩（下发前置注入 happy path）。 */
    private void givenKnowledgeHits() {
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。"),
                new KnowledgeHit("PRD", "连锁诊所系统", "PRD·连锁诊所管理", "范围边界：不含库存。")));
    }

    @BeforeEach
    void ensureChannelKeyForRealResolution() {
        // 真解析链护卫（ADR-0031）：命令构建经 ModelTierAppService 解析种子档位行，
        // 测试环境无 key 注入——幂等补测试 key
        ModelTierSeed.ensureChannelKey(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_design_specs");
        jdbcTemplate.update("DELETE FROM prj_design_items");
        jdbcTemplate.update("DELETE FROM prj_generation_segments");
        jdbcTemplate.update("DELETE FROM prj_generation_plan_journeys");
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_demo_versions");
        jdbcTemplate.update("DELETE FROM prj_agent_configs");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            // submit 起返回 Future（#359 停止通道）：直通已同步跑完——已完成 future
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(sessionExecutor).submit(any(), any());
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
     * 模拟 verify 子智能体轮内登记（#424 片轻验常开硬门后的缺省脚本位）：收口须
     * 验证在场——converse 桩内调用即「verify 已跑并登记」；按工作区登记一次
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
     * 链式收口脚本（thenThrow/thenReturn 链的带验证登记形，#424 常开硬门后收口须
     * 在场）：第 n 次调用按序取 outcomes（{@link AgentReply}＝成功回复〔先登记
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

    /** 项目 PRD 版本锚读口（锚一致性断言的独立事实源）。 */
    private java.sql.Timestamp projectPrdAnchor(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT prd_produced_at FROM prj_projects WHERE id = ?",
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
    void given_independent_slices_when_generate_then_run_in_parallel_on_forks() {
        // #351 切片并行：无依赖独立切片同波，各拿 fork 副本隔离同跑——fork 片的 run 目标
        // = fork 工作区（非项目主工作区）、计量仍归项目账；全片收口才落 generated_at
        Long projectId = persistedProject("9840");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");
        when(workspaceLifecycleAppService.fork(anyString())).thenReturn("9001", "9002");
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));

        // 首片(1) → 切片 2、3 无互依赖（同波并行）；切片 4 依赖 2、3（单片波主工作区串行）
        BuildPlan plan = new BuildPlan(
                List.of("首片", "登录", "商品浏览", "下单"),
                List.of(Set.of(), Set.of(1), Set.of(1), Set.of(2, 3)));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        verify(workspaceLifecycleAppService, times(2)).fork(anyString());
        verify(workspaceLifecycleAppService, times(2)).destroy(anyString());

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(5)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        // 阶段 0 + 首片 + 切片 2、3（fork）+ 切片 4（单片波）= 5 场 run
        assertThat(all).hasSize(5);
        // fork 片 run 目标 = fork 工作区（9001/9002），其余 = 项目主工作区
        Project project = projectRepository.findById(projectId).orElseThrow();
        List<String> forkWorkspaces = all.stream()
                .map(AgentCommand::workspaceId)
                .filter(ws -> !ws.equals(Long.toString(project.getWorkspaceId())))
                .toList();
        assertThat(forkWorkspaces).containsExactlyInAnyOrder("9001", "9002");
        // 计量归项目账（fork 片 usageContext.subject = projectId，不随 fork 漂移）
        assertThat(all).allSatisfy(cmd ->
                assertThat(cmd.usageContext().subject()).isEqualTo(Long.toString(projectId)));
        // 全片收口 → generated_at 落位、轨道表 5 行全已收口
        assertThat(generatedAt(projectId)).isNotNull();
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 2, 2, 2);
    }

    @Test
    void given_failed_fork_slice_when_generate_then_other_slices_merged_and_track_incomplete() {
        // #351 失败副本处置（重试耗尽→跳过不拖垮整轨）：同波内一片失败、其余片照常合并
        // 收口；有失败片则 generated_at 不落位（续跑只补失败片及其下游）
        Long projectId = persistedProject("9841");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(workspaceLifecycleAppService.fork(anyString())).thenReturn("9001", "9002");
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        // 切片 3 的 fork 片反复失败（重试耗尽转终态），其余片照常收口
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
            if (command.sessionId().endsWith("-slice-3")) {
                throw new IllegalStateException("切片3失败");
            }
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(
                List.of("首片", "登录", "商品浏览", "下单"),
                List.of(Set.of(), Set.of(1), Set.of(1), Set.of(2, 3)));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 切片 2 照常合并收口、切片 3 转失败；有失败片 → generated_at 不落位
        assertThat(generatedAt(projectId)).isNull();
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 2, 3, 1);
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
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

    @Test
    @SuppressWarnings("unchecked")
    void given_parallel_waves_when_generate_then_telemetry_archives_complete() throws Exception {
        // 灵魂用例（#419 AC④）：并行波跑后档案字段齐全——①波档案：宽度对照（依赖图
        // 理论宽度 vs 调度实际并发）＋波边界时序（起止墙钟/跨距）＋逐片时距＋波内
        // 收口/失败面；②合并档案：合并文件集（并行安全证据）＋冲突与结局；③单片波
        // 同档（serial 退化）；④检索键齐（projectId/轨道锚/波号——按 run／波检索回放）
        Long projectId = persistedProject("9842");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");
        when(workspaceLifecycleAppService.fork(anyString())).thenReturn("9001", "9002");
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        // fork diff 出合成文件集（fork 工作区上的 forkDiffCommand 定向桩，其余 exec
        // 走既有空成功桩）
        when(workspaceLifecycleAppService.exec(anyString(), argThat(command ->
                        command.command().contains("git diff --full-index"))))
                .thenReturn(new ExecResultResponse(FORK_DIFF_STUB, "", 0));

        Logger telemetryLogger =
                (Logger) LoggerFactory.getLogger(ParallelWaveTelemetry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        telemetryLogger.addAppender(appender);
        try {
            // 首片(1) → 切片 2、3 无互依赖（波 1 并行 fork）；切片 4 依赖 2、3（波 2
            // 单片串行）——两种波形态各留一档
            BuildPlan plan = new BuildPlan(
                    List.of("首片", "登录", "商品浏览", "下单"),
                    List.of(Set.of(), Set.of(1), Set.of(1), Set.of(2, 3)));
            appService.dispatchGenerationOnTurnClose(projectId, plan);
        }
        finally {
            telemetryLogger.detachAppender(appender);
        }

        ObjectMapper json = new ObjectMapper();
        List<Map<String, Object>> waves = archivedPayloads(appender, "[parallel-wave] ", json);
        List<Map<String, Object>> merges = archivedPayloads(appender, "[parallel-merge] ", json);
        assertThat(waves).hasSize(2);
        assertThat(merges).hasSize(2);

        // 波 1（并行 fork）：理论宽度 2、实际并发 1..2（池调度时序非确定——宽度
        // 对照的事实是两值都在场且实际不超过理论）、逐片时距与收口面齐全
        Map<String, Object> parallelWave = waves.get(0);
        assertThat(parallelWave)
                .containsEntry("wave", 1)
                .containsEntry("mode", "parallel")
                .containsEntry("ords", List.of(2, 3))
                .containsEntry("theoreticalWidth", 2)
                .containsEntry("closed", List.of(2, 3))
                .containsEntry("failed", List.of());
        assertThat(((Number) parallelWave.get("projectId")).longValue()).isEqualTo(projectId);
        assertThat((int) parallelWave.get("actualConcurrency")).isBetween(1, 2);
        assertThat(((Number) parallelWave.get("startedAtMs")).longValue())
                .isLessThanOrEqualTo(((Number) parallelWave.get("endedAtMs")).longValue());
        assertThat(((Number) parallelWave.get("durationMs")).longValue()).isNotNegative();
        List<Map<String, Object>> parallelSlices =
                (List<Map<String, Object>>) parallelWave.get("slices");
        assertThat(parallelSlices).hasSize(2);
        assertThat(parallelSlices).allSatisfy(slice -> {
            assertThat(slice).containsOnlyKeys("ord", "runId", "startedAtMs", "endedAtMs",
                    "durationMs", "succeeded");
            assertThat(slice.get("succeeded")).isEqualTo(true);
            assertThat(((Number) slice.get("startedAtMs")).longValue())
                    .isLessThanOrEqualTo(((Number) slice.get("endedAtMs")).longValue());
        });
        assertThat(parallelSlices).extracting(slice -> slice.get("ord"))
                .containsExactlyInAnyOrder(2, 3);

        // 波 2（单片串行退化）：理论宽度 1、实际并发 1、同构字段
        Map<String, Object> serialWave = waves.get(1);
        assertThat(serialWave)
                .containsEntry("wave", 2)
                .containsEntry("mode", "serial")
                .containsEntry("ords", List.of(4))
                .containsEntry("theoreticalWidth", 1)
                .containsEntry("actualConcurrency", 1)
                .containsEntry("closed", List.of(4))
                .containsEntry("failed", List.of());
        assertThat((List<Map<String, Object>>) serialWave.get("slices")).hasSize(1);
        // 检索回放键：同轨两波共用轨道锚（run 检索面）
        assertThat(serialWave.get("trackRunId"))
                .isEqualTo(parallelWave.get("trackRunId"))
                .isNotNull();

        // 合并档案（波 1 两片）：文件集＝合成 diff 的 b 侧全集、无冲突、合并成立
        assertThat(merges).allSatisfy(merge -> {
            assertThat(merge)
                    .containsEntry("wave", 1)
                    .containsEntry("files", List.of("src/app/page.tsx", "src/lib/auth.ts"))
                    .containsEntry("conflicted", false)
                    .containsEntry("merged", true);
            assertThat(((Number) merge.get("projectId")).longValue()).isEqualTo(projectId);
            assertThat(merge.get("runId")).isNotNull();
            assertThat(merge.get("slice")).isIn("登录", "商品浏览");
            assertThat(merge.get("trackRunId")).isEqualTo(parallelWave.get("trackRunId"));
        });
        assertThat(merges).extracting(merge -> merge.get("ord"))
                .containsExactlyInAnyOrder(2, 3);
    }

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
        // 灵魂用例（#359 AC②③）：切片 1 在途时用户叫停——停止不走重试环（一次尝试即
        // 收场）、不触发段回滚（中断口径非判死，已收口片保留、在途片回 PENDING）、
        // run-failed 载荷带 stopped=true（失败账口径如实收口）；「继续生成」照常可续
        // ——续跑 run 起手带现状盘点（中断原因＝用户停止）
        Long projectId = persistedProject("9845");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
            if (converseCalls.incrementAndGet() == 2) {
                // 切片 1 首试在途被中断（Future.cancel 线程中断打断模型流的现场等价物）
                codingRunTrack.requestStop(projectId);
                throw new IllegalStateException("java.lang.InterruptedException");
            }
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(List.of("用户能注册登录", "用户能下单支付"));
        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 停止不重试：阶段 0 + 切片 1 首试 = 2 次 converse（重试环被停止旁路）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        String slice1RunId = commands.getAllValues().get(1).runId();
        // run-failed(stopped=true) 锚被停场次（失败账口径——run 失败仍是唯一失败终态）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                slice1RunId.equals(payload.get(EventsAppService.RUN_FIELD))
                        && Boolean.TRUE.equals(payload.get(AgentEventTypes.RUN_STOPPED_FIELD))));
        // 已收口片保留（阶段 0 CLOSED）、在途片不被判死（PENDING 非 FAILED）、后续片不派
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 1, 1);
        assertThat(generatedAt(projectId)).isNull();
        // 四态投影如实：在途标记已释放 → 生成中断（不显假「生成中」）
        assertThat(queryAppService.detail(projectId).generationState())
                .isEqualTo(GenerationState.INTERRUPTED);
        // 「继续生成」从断点续跑：续跑 run 起手带现状盘点（中断原因＝用户停止），
        // 续跑跑完余轨（切片 1 + 切片 2 两场 converse）
        appService.startGeneration(projectId);
        ArgumentCaptor<AgentCommand> resumed = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(resumed.capture(), any());
        assertThat(resumed.getAllValues().get(2).prompt()).contains("用户停止");
    }

    @Test
    void given_stop_requested_mid_wave_when_generate_then_forks_destroyed_no_merge_no_generated() {
        // #359 并行波停止：波内在途被叫停——fork 全释放（不留孤儿容器）、不合并
        // （已收口前段不动）、generated_at 不落、run-failed(stopped) 收口
        Long projectId = persistedProject("9846");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(workspaceLifecycleAppService.fork(anyString())).thenReturn("9001", "9002");
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
            if (command.sessionId().endsWith("-slice-2")) {
                codingRunTrack.requestStop(projectId);
                throw new IllegalStateException("java.lang.InterruptedException");
            }
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(
                List.of("首片", "登录", "商品浏览", "下单"),
                List.of(Set.of(), Set.of(1), Set.of(1), Set.of(2, 3)));
        GenerationAppService.GenerationRun run = appService.dispatchGenerationOnTurnClose(projectId, plan);

        // run-failed(stopped=true) 收口（锚＝轨道首 run——波内各片 runId 非稳定锚）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                run.runId().equals(payload.get(EventsAppService.RUN_FIELD))
                        && Boolean.TRUE.equals(payload.get(AgentEventTypes.RUN_STOPPED_FIELD))));
        // 波内两片不被判死（PENDING）、后续波不派；已收口前段（阶段 0+首片）保留
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 1, 1, 1);
        assertThat(generatedAt(projectId)).isNull();
        // fork 全释放：起跑的（中断路径）与已完未合并的（停止清理路径）都不留孤儿
        // ——两路幂等双保险（重复 destroy 只是日志），按 id 断言至少释放一次
        verify(workspaceLifecycleAppService, atLeastOnce()).destroy("9001");
        verify(workspaceLifecycleAppService, atLeastOnce()).destroy("9002");
        // 停止面已清：续起新轨不带旧停（generationState 中断 → 可继续生成）
        assertThat(codingRunTrack.stopRequested(projectId)).isFalse();
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
    void given_slice_retries_when_generate_then_retry_same_slice_session_and_handoff_injected() {
        // #114 重试续本片会话 + 片间交接：阶段 0 成功、切片 1 首试失败后重试成功、切片 2
        // 成功——重试续切片 1 会话（不换新）；前片收口终文注入下一片（切片 2 的 prompt
        // 带切片 1 的交接、切片 1 的 prompt 带阶段 0 的交接）
        Long projectId = persistedProject("9830");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        // #424 常开硬门：链式脚本走带验证登记形（成功回复轮先登记 verify）
        givenConverseChain(
                new AgentReply("run-s0", "阶段0交接"),
                new IllegalStateException("切片1中断"),
                new AgentReply("run-s1", "切片1交接"),
                new AgentReply("run-s2", "切片2交接"));

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
        // 阶段 0 + 切片收口各一次＋完成位补推一次（#427：种子施用后的终态首屏）= 3
        verify(eventsAppService, times(3)).publishNotification(eq(ProjectEventTypes.PREVIEW_READY),
                anyMap());
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

        // 阶段 0 + 1 片 = 2 次收口各推一次＋完成位补推一次（#427 种子后终态首屏）= 3
        verify(eventsAppService, times(3)).publishNotification(eq(ProjectEventTypes.PREVIEW_READY),
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
            verifyRan(command);
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
    @SuppressWarnings("unchecked")
    void given_attempts_exhausted_when_generate_then_failure_face_persisted_same_payload_as_sse() {
        // #418 灵魂用例（观测地基批）：重试耗尽转终态——失败面（逐尝试墙钟/档位/失败
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
        // 轮次/档位/墙钟/桶）、error = 末次错误现场原文、durationMs = 本场累计墙钟
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
            assertThat(String.valueOf(attempt.get("modelId"))).isNotBlank(); // 逐尝试档位可归因
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
            if (converseCalls.incrementAndGet() == 2) {
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
    @SuppressWarnings("unchecked")
    void given_rerun_round_also_fails_when_generate_then_failure_face_carries_both_rounds() {
        // #418 回滚重跑失败（AC④行为不变·仅增落库面）：两轮皆败转终态——失败面跨轮
        // 累计（回滚重跑轮不清账，#323 口径）：首轮 3 试＋重跑轮 3 试逐条带轮次与
        // 档位（重跑轮升档可归因），库中读回完整失败面
        Long projectId = persistedProject("9862");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReady();
        givenConverseChain(new AgentReply("s0", "阶段0交接"),
                new IllegalStateException("切片失败"));

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        Map<String, Object> face = conversationHistory.read(projectId).stream()
                .filter(entry -> entry.failure() != null)
                .reduce((first, second) -> second).orElseThrow().failure();
        assertThat(face).containsEntry("error", "切片失败");
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) face.get("attempts");
        assertThat(attempts).hasSize(2 * properties.getMaxAttempts());
        // 分轮归因：首轮 N 条 round=1、重跑轮 N 条 round=2；轮内尝试序号各 1..N
        //（期望序列独立构造——maxAttempts 事实来自测试配置，非被测值回算）
        List<Integer> expectedRounds = new ArrayList<>();
        List<Integer> expectedAttempts = new ArrayList<>();
        for (int round = 1; round <= 2; round++) {
            for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
                expectedRounds.add(round);
                expectedAttempts.add(attempt);
            }
        }
        assertThat(attempts).extracting(attempt -> ((Number) attempt.get("round")).intValue())
                .containsExactlyElementsOf(expectedRounds);
        assertThat(attempts).extracting(attempt -> ((Number) attempt.get("attempt")).intValue())
                .containsExactlyElementsOf(expectedAttempts);
        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(String.valueOf(attempt.get("modelId"))).isNotBlank();
            assertThat(((Number) attempt.get("durationMs")).longValue()).isGreaterThanOrEqualTo(0L);
        });
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
        givenConverseChain(new AgentReply("stage0", "阶段 0 完成"),
                new IllegalStateException("切片失败"),
                new IllegalStateException("切片失败"),
                new IllegalStateException("切片失败"));

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
            verifyRan(command);
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
        givenConverseChain(new AgentReply("s0", "阶段0交接"),
                new AgentReply("s1", "切片1交接"),
                new IllegalStateException("切片2起服失败"),
                new IllegalStateException("切片2起服失败"),
                new IllegalStateException("切片2起服失败"));

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付", "用户能查询订单")));

        // 中断定格：阶段 0 / 切片 1 已收口、切片 2 失败、切片 3 待跑；断点 = 最深收口片
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 3, 1);
        assertThat(generatedAt(projectId)).isNull();

        // 续跑（REST 路径无交接物：重打桩走 do 家族——不参调旧 throw 桩）
        givenConverseChain(new AgentReply("r2", "切片2续跑完成"),
                new AgentReply("r3", "切片3完成"));
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
        // 现状盘点（AC②）：已收口进度 + 中断原因（重试耗尽 + 错误现场）+ 续跑纪律；
        // 断点片任务本体仍在（不是只有盘点）；#353 失败段干净重做口径（失败现场已
        // 回滚本段起点——续跑从干净现场从头重做，不是坏现场叠修）
        assertThat(slice2.prompt())
                .contains("续跑现状盘点")
                .contains("系统初始化")
                .contains("切片 1/3「用户能注册登录」")
                .contains("自动重试耗尽后失败")
                .contains("切片2起服失败")
                .contains("回滚到本段起点")
                .contains("从头重做")
                .doesNotContain("保留已对的工作")
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
        givenConverseChain(new AgentReply("s0", "阶段0交接"),
                new IllegalStateException("切片1失败"),
                new IllegalStateException("切片1失败"),
                new IllegalStateException("切片1失败"));

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        // 模拟中断未落终态（进程在终态落表前死亡的可观察状态）：片行拨回待跑＋
        // 进程内终态现场清空（重启两半都要模拟——#359 起待跑片＋在场现场在生产
        // 只属用户停止〔停止收口写入 STOP_SCENE〕，会呈现停止叙事而非通用中断）
        jdbcTemplate.update(
                "UPDATE prj_generation_segments SET status = 1 WHERE project_id = ? AND ord = 1",
                projectId);
        @SuppressWarnings("unchecked")
        Map<Long, String> scenes = (Map<Long, String>) org.springframework.test.util.ReflectionTestUtils
                .getField(appService, "terminalFailureScenes");
        scenes.remove(projectId);

        givenConverseChain(new AgentReply("r1", "切片1续跑完成"),
                new AgentReply("r2", "切片2完成"));
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

    @Test
    void given_slice_retries_exhausted_when_generate_then_rollback_and_rerun_round_saves_it() {
        // #353 灵魂用例：切片首轮重试耗尽（原地修三连败）→ 失败现场留账落盘（回滚
        // 不丢证据）＋ 工作区 git 回滚本段起点（前段成果不动）＋ 新会话重跑一轮——
        // 重跑成功即照常收口（坏现场不叠修）；重跑首试 prompt 原样重放（干净起点、
        // 不携错误现场）
        Long projectId = persistedProject("9870");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReady();
        BuildPlan plan = new BuildPlan(List.of("用户能注册登录"));
        givenConverseChain(new AgentReply("s0", "阶段0交接"),
                new IllegalStateException("越修越乱"),
                new IllegalStateException("越修越乱"),
                new IllegalStateException("越修越乱"),
                new AgentReply("s1r", "切片1重跑完成"));

        appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 5 场 converse：阶段 0 + 切片 1 首轮 3 连败 + 重跑轮首试即救活
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(5)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        AgentCommand rerun = all.get(4);
        // 重跑轮新会话（旧会话带失败循环历史不续）；首试 prompt 原样重放——干净起点
        // 不携错误现场（非原地修的续作 prompt）
        assertThat(rerun.sessionId())
                .isEqualTo(GenerationAppService.sliceSession(projectId, 1) + "-rr")
                .isNotEqualTo(all.get(1).sessionId());
        assertThat(rerun.prompt())
                .isEqualTo(all.get(1).prompt())
                .doesNotContain("错误现场");
        // 重跑轮 attemptRunId 全新——计量幂等键（USAGE_EVENT_PREFIX+runId）不撞首轮
        assertThat(all).extracting(AgentCommand::runId).doesNotHaveDuplicates();
        // 留账先于回滚：失败现场 diff 落盘 .platform/（回滚不丢证据）→ reset --hard
        // 段起点 + clean（干净回退；段起点 = 捕获桩假 hash）
        InOrder rollbackOrder = inOrder(workspaceLifecycleAppService);
        rollbackOrder.verify(workspaceLifecycleAppService).exec(eq("9870"),
                argThat(cmd -> cmd != null
                        && cmd.command().contains("git diff --cached '" + FAKE_SEGMENT_START + "'")
                        && cmd.command().contains(".platform/failed-scene-segment-1-")));
        rollbackOrder.verify(workspaceLifecycleAppService).exec(eq("9870"),
                argThat(cmd -> cmd != null
                        && cmd.command().contains("git reset --hard '" + FAKE_SEGMENT_START + "'")
                        && cmd.command().contains("git clean -fd")));
        // 救活后照常收口：片全收口、generated_at 落位、零失败终态
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
    }

    @Test
    void given_rerun_round_also_fails_when_generate_then_terminal_with_workspace_rolled_back_twice() {
        // #353 两轮皆败→终态：首轮耗尽回滚重跑、重跑轮再耗尽仍转终态（预算一轮）——
        // 终态前再回滚一次（不变式：终态时工作区 = 段起点，用户续跑从干净现场起）；
        // run-failed 锚失败片首试 runId、片落 FAILED、后片不派、generated_at 不落
        Long projectId = persistedProject("9871");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReady();
        givenConverseChain(new AgentReply("s0", "阶段0交接"),
                new IllegalStateException("切片失败"));

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        // 阶段 0 (1) + 切片 1 两轮各 3 连败 = 7 次 converse；切片 2 不派
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1 + 2 * properties.getMaxAttempts()))
                .converse(commands.capture(), any());
        String slice1RunId = commands.getAllValues().get(1).runId();
        // 回滚两次（重跑前 + 终态前）：reset --hard 段起点各一次
        verify(workspaceLifecycleAppService, times(2)).exec(eq("9871"),
                argThat(cmd -> cmd != null
                        && cmd.command().contains("git reset --hard '" + FAKE_SEGMENT_START + "'")));
        // 留账两次（两轮失败现场各落一盘——回滚不丢证据）
        verify(workspaceLifecycleAppService, times(2)).exec(eq("9871"),
                argThat(cmd -> cmd != null
                        && cmd.command().contains(".platform/failed-scene-segment-1-")));
        // 终态口径不变：run-failed 锚失败片首试 runId、片落 FAILED、后片待跑不派
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                slice1RunId.equals(payload.get(EventsAppService.RUN_FIELD))));
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 3, 1);
        assertThat(generatedAt(projectId)).isNull();
    }

    @Test
    void given_rerun_round_when_generate_then_user_facing_run_start_not_republished() {
        // #353 重跑轮用户面静默（#84 投影同律延伸）：重跑轮 run-start 滤掉（run 身份
        // 全程 = 首试 runId 不换锚）、重跑轮事件归锚首试 runId——用户面只见工作消息
        // 正常生长，不见「重新开始」
        Long projectId = persistedProject("9872");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReady();
        AtomicInteger calls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            if (calls.incrementAndGet() <= 4) {
                // 阶段 0 成功（1 次）+ 切片 1 首轮 3 连败
                if (calls.get() > 1) {
                    throw new IllegalStateException("切片失败");
                }
            }
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录")));

        // run-start 恰好 2 次（阶段 0 首试 + 切片 1 首轮首试）——重跑轮 run-start 被滤
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_START), anyMap());
        // 切片 1 的 run-finish（重跑轮真收口发出）归锚其首轮首试 runId——用户面
        // run 身份全程不变
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(5)).converse(commands.capture(), any());
        String slice1FirstRunId = commands.getAllValues().get(1).runId();
        ArgumentCaptor<Map<String, Object>> finishPayloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                finishPayloads.capture());
        assertThat(finishPayloads.getAllValues().get(1)
                .get(EventsAppService.RUN_FIELD)).isEqualTo(slice1FirstRunId);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_fork_slice_retries_exhausted_when_generate_then_fork_rolled_back_rerun_and_merged() {
        // #353 fork 片回滚重跑：并行波内切片首轮重试耗尽 → fork 内留账＋回滚 fork
        // 起点（fork 时主工作区 HEAD）＋ 新会话重跑——成功即照常按序合并回主工作区
        // （并行片同样不在坏现场叠修）；全片收口照常落 generated_at
        Long projectId = persistedProject("9873");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReady();
        when(workspaceLifecycleAppService.fork(anyString())).thenReturn("9001", "9002");
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        // 切片 3 首轮连败（会话 -slice-3），重跑轮（-slice-3-rr）成功
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
            if (command.sessionId().endsWith("-slice-3")) {
                throw new IllegalStateException("切片3失败");
            }
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(
                List.of("首片", "登录", "商品浏览", "下单"),
                List.of(Set.of(), Set.of(1), Set.of(1), Set.of(2, 3)));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        // fork 内回滚事实：切片 3 的 fork 标识按命令事实取（并行波两片同池竞速，
        // fork 桩 9001/9002 的分配序不固定——以 -slice-3 命令实际跑在的 fork 为准）：
        // 留账 + reset --hard 段起点
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(6)).converse(commands.capture(), any());
        String slice3Fork = commands.getAllValues().stream()
                .filter(cmd -> cmd.sessionId().endsWith("-slice-3"))
                .map(AgentCommand::workspaceId)
                .findFirst().orElseThrow();
        verify(workspaceLifecycleAppService).exec(eq(slice3Fork),
                argThat(cmd -> cmd != null
                        && cmd.command().contains(".platform/failed-scene-segment-3-")));
        verify(workspaceLifecycleAppService).exec(eq(slice3Fork),
                argThat(cmd -> cmd != null
                        && cmd.command().contains("git reset --hard '" + FAKE_SEGMENT_START + "'")));
        // 重跑轮新会话（-rr 后缀）跑在 fork 工作区
        assertThat(commands.getAllValues())
                .anySatisfy(cmd -> {
                    assertThat(cmd.sessionId()).endsWith("-slice-3-rr");
                    assertThat(cmd.workspaceId()).isEqualTo(slice3Fork);
                });
        // 重跑救活后照常合并：全片收口、generated_at 落位、零失败终态
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 2, 2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
    }

    @Test
    void given_merge_conflict_resolution_fails_when_generate_then_main_workspace_rolled_back() {
        // #353 合并失败的残留清理：fork 片成功但合并冲突（机器查命中）→ 冲突裁决
        // run 失败 → 主工作区回滚 merge 前 HEAD（不留半应用/冲突标记残留——后续
        // 合并与续跑在干净主工作区上进行）；失败片落 FAILED、generated_at 不落
        Long projectId = persistedProject("9874");
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.fork(anyString())).thenReturn("9001", "9002");
        when(workspaceLifecycleAppService.exec(any(), any())).thenAnswer(invocation -> {
            WorkspaceExecCommand cmd = invocation.getArgument(1);
            String command = cmd.command();
            if (command.contains("git rev-parse")) {
                return new ExecResultResponse(FAKE_SEGMENT_START + "\n", "", 0);
            }
            if (command.contains("git diff --full-index")) {
                return new ExecResultResponse("fake-patch-bytes", "", 0);
            }
            if (command.contains("git diff --name-only")) {
                return new ExecResultResponse("src/app/page.tsx\n", "", 0); // #420 有产出
            }
            return new ExecResultResponse("", "", 0);
        });
        when(workspaceLifecycleAppService.exposePreview(anyString()))
                .thenReturn(URI.create("http://localhost:30080"));
        // apply --3way 冲突（机器查命中）；裁决 run（-merge- 会话）原地修连败
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("", "conflict", 1));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
            if (command.sessionId().contains("-merge-")) {
                throw new IllegalStateException("裁决失败");
            }
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(
                List.of("首片", "登录", "商品浏览", "下单"),
                List.of(Set.of(), Set.of(1), Set.of(1), Set.of(2, 3)));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 主工作区回滚到 merge 前 HEAD（两 fork 片合并失败各清一次——不留冲突残留）
        verify(workspaceLifecycleAppService, times(2)).exec(eq("9874"),
                argThat(cmd -> cmd != null
                        && cmd.command().contains("git reset --hard '" + FAKE_SEGMENT_START + "'")
                        && cmd.command().contains("git clean -fd")));
        // 两片合并失败落 FAILED、下游片待跑、generated_at 不落
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 3, 3, 1);
        assertThat(generatedAt(projectId)).isNull();
    }

    @Test
    void given_zero_verification_adjudication_when_close_then_rejected_then_verified_merges() {
        // #424 裁决轨同盖（常开无豁免）：合并冲突裁决 run 改主工作区后零验证收口
        // 同样被拒——拒绝回执喂回裁决 run，补验（裁决后系统两侧功能走得通）后照常
        // 合并闭片（裁决天然有产出，产出证明闸不适用；验证在场按主工作区键判）
        Long projectId = persistedProject("9876");
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.fork(anyString())).thenReturn("9001", "9002");
        when(workspaceLifecycleAppService.exec(any(), any())).thenAnswer(invocation -> {
            WorkspaceExecCommand cmd = invocation.getArgument(1);
            String command = cmd.command();
            if (command.contains("git rev-parse")) {
                return new ExecResultResponse(FAKE_SEGMENT_START + "\n", "", 0);
            }
            if (command.contains("git diff --full-index")) {
                return new ExecResultResponse("fake-patch-bytes", "", 0);
            }
            if (command.contains("git diff --name-only")) {
                return new ExecResultResponse("src/app/page.tsx\n", "", 0);
            }
            return new ExecResultResponse("", "", 0);
        });
        when(workspaceLifecycleAppService.exposePreview(anyString()))
                .thenReturn(URI.create("http://localhost:30080"));
        // 两 fork 片合并均冲突（机器查命中）→ 各起裁决 run；裁决 run 首试零验证、
        // 拒绝回执喂回后补验（主工作区键登记）
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("", "conflict", 1));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            if (command.sessionId().contains("-merge-")) {
                if (command.prompt().contains("错误现场")
                        && !verificationFacts.hasRegistration(command.workspaceId())) {
                    verificationFacts.record(command.workspaceId(), List.of(
                            new VerificationFacts.Check("合并后两侧功能主径可走通（刷新仍在）",
                                    "browser", true, null)));
                }
                return new AgentReply(command.runId(), "裁决完成");
            }
            verifyRan(command);
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(
                List.of("首片", "登录", "商品浏览", "下单"),
                List.of(Set.of(), Set.of(1), Set.of(1), Set.of(2, 3)));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 两裁决 run 各两试（零验证被拒 → 补验收口）；拒绝回执进裁决 run 现场
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        List<AgentCommand> merges = new ArrayList<>();
        verify(agentClient, atLeast(8)).converse(commands.capture(), any());
        commands.getAllValues().forEach(cmd -> {
            if (cmd.sessionId().contains("-merge-")) {
                merges.add(cmd);
            }
        });
        assertThat(merges).hasSize(4);
        assertThat(merges.get(1).prompt()).contains("错误现场").contains("验证在场未过");
        assertThat(merges.get(3).prompt()).contains("错误现场").contains("验证在场未过");
        // 补验后裁决照常合并：全片收口、generated_at 落位
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 2, 2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_rollback_fails_when_closing_check_never_passes_then_failed_check_still_emitted() {
        // #353 降级路径的自检播报（#85 口径不破）：有钩但回滚失败（reset exec 非 0
        // → 不重跑直接终态）——末次核验未过照样出 ❌（部件不悬挂「检查中」），
        // ❌ 先于 run-failed 到达
        Long projectId = persistedProject("9875");
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.exec(any(), any())).thenAnswer(invocation -> {
            WorkspaceExecCommand cmd = invocation.getArgument(1);
            String command = cmd.command();
            if (command.contains("git rev-parse")) {
                return new ExecResultResponse(FAKE_SEGMENT_START + "\n", "", 0);
            }
            if (command.contains("curl")) {
                return new ExecResultResponse("", "refused", 7); // 8081 核验恒未过
            }
            if (command.contains("git reset")) {
                return new ExecResultResponse("", "rollback broke", 1); // 回滚失败→不重跑
            }
            return new ExecResultResponse("", "", 0);
        });
        when(workspaceLifecycleAppService.exposePreview(anyString()))
                .thenReturn(URI.create("http://localhost:30080"));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> new AgentReply(
                ((AgentCommand) invocation.getArgument(0)).runId(), "做完了"));

        appService.dispatchGenerationOnTurnClose(projectId, new BuildPlan(List.of("用户能注册登录")));

        // 阶段 0 三轮核验未过 + 回滚失败不重跑 = 3 次 converse 即终态
        verify(agentClient, times(properties.getMaxAttempts())).converse(any(), any());
        // ❌ 照发（降级终态不悬挂）：CHECKING×3 + FAILED×1，且 ❌ 先于 run-failed
        ArgumentCaptor<Map<String, Object>> checks = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(properties.getMaxAttempts() + 1))
                .publishAgentEvent(eq(AgentEventTypes.PART_CHECK), checks.capture());
        assertThat(checks.getAllValues())
                .extracting(payload -> payload.get(AgentEventTypes.PART_CHECK_STATE_FIELD))
                .containsExactly(
                        AgentEventTypes.PART_CHECK_STATE_CHECKING,
                        AgentEventTypes.PART_CHECK_STATE_CHECKING,
                        AgentEventTypes.PART_CHECK_STATE_CHECKING,
                        AgentEventTypes.PART_CHECK_STATE_FAILED);
        InOrder closeOrder = inOrder(eventsAppService);
        closeOrder.verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.PART_CHECK),
                argThat(payload -> AgentEventTypes.PART_CHECK_STATE_FAILED.equals(
                        payload.get(AgentEventTypes.PART_CHECK_STATE_FIELD))));
        closeOrder.verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
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
            verifyRan(command);
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
            verifyRan(command);
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
            verifyRan(command);
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

        String inventory = GenerationAppService.resumeInventoryBlock(0, List.of(stage0), null);

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
        assertThat(value.modelString()).isEqualTo(ModelTierSeed.FLASH_MODEL_STRING);
        // #322 活超时替换一次性总墙钟：编码命令只携静默判死阈值，不再携总时长上限
        assertThat(value.timeout()).isNull();
        assertThat(value.silenceTimeout()).isEqualTo(properties.getSilenceTimeout());
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
        // 编码命令实取库值（prompt 与模型档位两腿）；缺省回落腿由上方枚举默认断言钉死。
        // #341 起 Demo 阶段（executor）缺省＝快模型档，故覆盖档取强模型档以示库值
        // 优先（覆盖值≠缺省值，断言才有别于回落腿）。
        // #252 增强工具开关同批编入 toolSpec（执行体槽无增强件，规格恒缺省开——
        // 进缓存键保持规格一致）
        Long projectId = persistedProject("9870");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("已生成");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, system_prompt, model_id)
                VALUES ('executor', ?, 'pro')
                """, "执行体覆盖协议：开工先列步骤。");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand stage0 = command.getAllValues().get(0);
        assertThat(stage0.systemPrompt()).isEqualTo("执行体覆盖协议：开工先列步骤。");
        assertThat(stage0.modelString()).isEqualTo("volcengine:deepseek-v4-pro-ga-260813");
        assertThat(stage0.toolSpec()).isEqualTo("ws=true,fu=true");
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
        givenConverseChain(new IllegalStateException("首次尝试中断"),
                new AgentReply("run-2", "系统已生成"),
                new AgentReply("run-3", "系统已生成"));

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
        assertThat(attempts.get(1).prompt())
                .contains("错误现场：首次尝试中断")
                .contains("进展现场（平台可观测事实）")
                .contains("已累计用时")
                .contains("已落盘文件变更 尚无")
                .endsWith(GenerationAppService.segmentContext(SINGLE_SLICE_PLAN, null));
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
        givenConverseChain(new AgentReply("run-fake", "很抱歉，目前系统尚未真正实现出来"));

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

    // ---------- 首屏界面先行与入口文件写全（#339） ----------

    @Test
    void given_first_slice_when_generate_then_prompt_ui_first_and_later_slices_vertical() {
        // #339 首屏界面先行：第一片 prompt 语义＝先出可点的界面（界面骨架、预览
        // 立即可点、此片先不接数据与后端逻辑）；第二片起才是端到端纵向切片
        Long projectId = persistedProject("9833");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("首页与主要页面骨架", "用户能注册登录")));

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        // 阶段 0（先起服骨架）语义不变
        assertThat(all.get(0).prompt()).contains("先起服");
        // 第一片：界面先行语义（非端到端纵向切片）
        assertThat(all.get(1).prompt())
                .contains("界面先行")
                .contains("先做出可点的界面")
                .contains("预览立即可点");
        assertThat(all.get(1).prompt()).doesNotContain("数据落库端到端走通");
        // 第二片起：既有端到端纵向切片语义
        assertThat(all.get(2).prompt()).contains("端到端走通");
        assertThat(all.get(2).prompt()).doesNotContain("界面先行");
    }

    @Test
    void given_entry_file_truncated_when_generate_then_not_closed_within_attempts() {
        // #339 收口判据含入口文件完整性：入口文件残缺（「其余代码略」式）不收口
        // ——8081 探活可达也不算真收口（收口判据两件），尝试环静默重试耗尽转终态
        // 失败、generated_at 不落
        Long projectId = persistedProject("9834");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains(ProjectFiles.BASELINE_ENTRY))))
                .thenReturn(new ExecResultResponse(
                        "export default function Home(){ return <main>{/* 其余代码略 */}</main>; }",
                        "", 0));

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        verify(agentClient, times(properties.getMaxAttempts())).converse(any(), any());
        assertThat(generatedAt(projectId)).isNull();
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
    }

    @Test
    void given_entry_file_truncated_then_fixed_when_generate_then_retries_and_closes() {
        // #339 有限重试修全：入口文件首次残缺不收口（驱动原位修补），重试补齐即收口
        // ——阶段 0 首试残→重试全→阶段 0 收口，随后切片一次收口（共 3 次 converse）
        Long projectId = persistedProject("9835");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains(ProjectFiles.BASELINE_ENTRY))))
                .thenReturn(new ExecResultResponse("// 其余代码略", "", 0))
                .thenReturn(new ExecResultResponse(
                        "export default function Home(){ return <main>首页</main>; }", "", 0));

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        verify(agentClient, times(3)).converse(any(), any());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_entry_file_missing_when_generate_then_not_closed() {
        // #339 入口文件取件失败（缺失）＝残缺——不收口（缺件也是首屏打不开），
        // 静默重试耗尽转终态、generated_at 不落
        Long projectId = persistedProject("9836");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains(ProjectFiles.BASELINE_ENTRY))))
                .thenReturn(new ExecResultResponse("", "No such file or directory", 1));

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        verify(agentClient, times(properties.getMaxAttempts())).converse(any(), any());
        assertThat(generatedAt(projectId)).isNull();
    }

    // ---------- 产出证明闸（#420 空转片防呆：零 diff＋无「能力已存在」确认＝结构性拒绝） ----------

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

    @Test
    @SuppressWarnings("unchecked")
    void given_idle_slice_when_close_then_rejected_refed_back_then_work_done_closes() {
        // #420 AC①：切片零 diff＋无「能力已存在」确认的收口被结构性拒绝——拒绝回执
        // 进同 run 现场（重试 prompt 携带「产出证明未过」与补做指引），执行体补做
        // （动了文件）后正常收口；空转拒绝史随收尾卡判别标注如实呈现
        Long projectId = persistedProject("9837");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            // 切片 1：首试空转（宣告完成但零文件变更）、拒绝回执喂回后补做干活
            // ——脚本位随场改写（首试 prompt 无错误现场＝空转轮）
            if (command.sessionId().endsWith("-slice-1")) {
                idleProof("9837").set(!command.prompt().contains("错误现场"));
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 阶段 0 一次收口 + 切片 1 两试（空转被拒 → 补做收口）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        AgentCommand sliceRetry = commands.getAllValues().get(2);
        // 拒绝回执进同 run 现场：重试 prompt 携带产出证明拒绝与两出路指引
        assertThat(sliceRetry.prompt())
                .contains("错误现场")
                .contains("产出证明未过")
                .contains("能力已存在");
        // 补做后正常收口：片全收口 + 拒绝史标注随收尾卡（idleRejections=1）
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceClosing = (Map<String, Object>) payloads.getAllValues()
                .get(1).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> proof = (Map<String, Object>) sliceClosing
                .get(CoderRunAttempts.CLOSING_PROOF_FIELD);
        assertThat(proof).containsEntry(ProofOfOutputGate.IDLE_REJECTIONS_FIELD, 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_idle_slice_never_proving_when_generate_then_rollback_rerun_then_terminal_failed() {
        // #420 AC②：空转拒绝耗尽（原地补做三连拒——拒绝回执喂回仍零 diff）→ 段级
        // 自愈回滚重跑（复用 #353 既有机制：留账＋回滚段起点＋新会话重跑一轮，不建
        // 新通道），重跑轮仍空转→转终态 run-failed；逐尝试档位经既有路由「重试／
        // 回滚重跑升档」信号自动升档（attempt+1／round+1——flash→pro，路由面零改动）
        Long projectId = persistedProject("9839");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            // 切片 1 恒空转（宣告完成但零文件变更——重跑轮同形）：快档典型失败形态
            if (command.sessionId().endsWith("-slice-1")
                    || command.sessionId().contains("-slice-1-")) {
                idleProof("9839").set(true);
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        // 阶段 0 (1) + 切片 1 首轮 3 拒 + 重跑轮 3 拒 = 7 次 converse；切片 2 不派
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1 + 2 * properties.getMaxAttempts()))
                .converse(commands.capture(), any());
        // 重跑轮新会话（既有回滚重跑机制——不建新通道）；首试 prompt 原样重放
        AgentCommand rerun = commands.getAllValues().get(4);
        assertThat(rerun.sessionId())
                .isEqualTo(GenerationAppService.sliceSession(projectId, 1) + "-rr");
        assertThat(rerun.prompt()).isEqualTo(commands.getAllValues().get(1).prompt());
        // 留账＋回滚各两次（重跑前＋终态前——#353 不变式：终态时工作区＝段起点）
        verify(workspaceLifecycleAppService, times(2)).exec(eq("9839"),
                argThat(cmd -> cmd != null
                        && cmd.command().contains("git reset --hard '" + FAKE_SEGMENT_START + "'")));
        // 终态：run-failed 锚切片 1 首试 runId、片落 FAILED、后片不派
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED),
                argThat(payload -> payload.get(EventsAppService.RUN_FIELD) != null
                        && payload.containsKey(AgentEventTypes.FAILURE_FIELD)));
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 3, 1);
        assertThat(generatedAt(projectId)).isNull();
    }

    @Test
    void given_idle_rejections_then_escalated_model_tiers_recorded_in_failure_face() {
        // #420 AC② 升档留档：空转拒绝驱动尝试环重试——逐尝试档位经既有路由信号
        // 自动升档（attempt≥2 重试升档、round 2 回滚重跑升档：flash→pro），失败面
        // attempts[].modelId 可归因（#418 落库面承载——平台分析口径）
        Long projectId = persistedProject("9840");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            if (command.sessionId().contains("-slice-1")) {
                idleProof("9840").set(true);
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED),
                payloads.capture());
        Map<String, Object> failure = (Map<String, Object>) payloads.getValue()
                .get(AgentEventTypes.FAILURE_FIELD);
        assertThat(failure.get("error")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("产出证明未过");
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) failure.get("attempts");
        assertThat(attempts).hasSize(2 * properties.getMaxAttempts());
        assertThat(attempts.stream().map(attempt -> attempt.get("modelId")))
                .containsExactly("flash", "pro", "pro", "pro", "pro", "pro");
    }

    @Test
    void given_idle_fork_slice_when_close_then_gate_fires_in_fork_and_merge_saves_it() {
        // #420 fork 片同盖（fork 差异口径）：fork 内空转收口同样被结构性拒绝——拒绝
        // 回执喂回 fork run 同 run 补做；补做后照常合并闭片。脚本位按工作区隔离
        // （并行波内另一 fork 片不受串扰）
        Long projectId = persistedProject("9842");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        when(workspaceLifecycleAppService.fork(anyString())).thenReturn("9001", "9002");
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
            // fork 9001 上的切片 2：首试空转、拒绝回执喂回后补做；fork 9002 照常
            if ("9001".equals(command.workspaceId())) {
                idleProof("9001").set(!command.prompt().contains("错误现场"));
            }
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(
                List.of("首片", "登录", "商品浏览", "下单"),
                List.of(Set.of(), Set.of(1), Set.of(1), Set.of(2, 3)));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 切片 2 两试（fork 内被拒→补做）、其余各一次；拒绝回执进 fork run 现场
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(6)).converse(commands.capture(), any());
        List<AgentCommand> slice2 = commands.getAllValues().stream()
                .filter(cmd -> "9001".equals(cmd.workspaceId())).toList();
        assertThat(slice2).hasSize(2);
        assertThat(slice2.get(1).prompt()).contains("产出证明未过");
        // 补做后照常合并：全片收口、generated_at 落位
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 2, 2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_capability_exists_confirmation_when_idle_slice_then_exempt_close_with_basis() {
        // #420 AC③＋AC⑥：验证确认「能力已存在」（existing 确认形态）＝活已被前片
        // 干完的薄片，走豁免路径正常收口——豁免依据落收尾卡判别标注；真·薄片
        // （diff＞0 干活）不携带标注（阶段 0 即此形——正常收口无 proofOfOutput）
        Long projectId = persistedProject("9838");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            // 切片 1 零 diff，但 verify 子智能体已确认本片能力已存在（豁免路径；
            // 事实键＝工作区 id，对偶 verify 统计测试的登记口径）
            if (command.sessionId().endsWith("-slice-1")) {
                idleProof("9838").set(true);
                verificationFacts.record("9838", List.of(
                        new VerificationFacts.Check("留言提交", "browser", true, null, true)));
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 豁免正常收口：片全收口、generated_at 落位、零拒绝轮（一次收口）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> exemptClosing = (Map<String, Object>) payloads.getAllValues()
                .get(1).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> proof = (Map<String, Object>) exemptClosing
                .get(CoderRunAttempts.CLOSING_PROOF_FIELD);
        assertThat(proof)
                .containsEntry(ProofOfOutputGate.EXEMPTED_FIELD, true)
                .containsEntry(ProofOfOutputGate.EXEMPTION_BASIS_FIELD,
                        "验证确认「能力已存在」：留言提交");
        // 真·薄片（阶段 0 diff＞0 正常干活）不误伤：不携带判别标注
        Map<String, Object> stage0Closing = (Map<String, Object>) payloads.getAllValues()
                .get(0).get(AgentEventTypes.CLOSING_FIELD);
        assertThat(stage0Closing).doesNotContainKey(CoderRunAttempts.CLOSING_PROOF_FIELD);
    }

    // ---------- 验证在场硬门（#424 片轻验常开：本 run 无验证登记不准收口） ----------

    @Test
    @SuppressWarnings("unchecked")
    void given_zero_verification_slice_when_close_then_rejected_refed_back_then_verified_closes() {
        // #424 AC①：本 run 无验证登记（verify 未跑/未报——锚案 s5/s8 零验证形态）的
        // 收口被结构性拒绝——拒绝回执进同 run 现场（重试 prompt 携带「验证在场未过」
        // 与起验指引：自报本片验收要点委派 verify），执行体补跑验证（登记）后正常
        // 收口；验证统计随收尾卡如实呈现（常开硬门下「该片零验证」结构性不可能）
        Long projectId = persistedProject("9843");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            // 切片 1：首试宣告完成但不跑验证（零登记——阶段 0 照常登记作对照）、
            // 拒绝回执喂回后补跑验证（verify 登记进事实面）
            boolean slice1ZeroVerification = command.sessionId().endsWith("-slice-1")
                    && !command.prompt().contains("错误现场");
            if (!slice1ZeroVerification) {
                verificationFacts.record(command.workspaceId(), List.of(
                        new VerificationFacts.Check("留言提交主径可走通", "browser", true, null)));
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 阶段 0 一次收口（验证在场）+ 切片 1 两试（零验证被拒 → 补验后收口）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        AgentCommand sliceRetry = commands.getAllValues().get(2);
        // 拒绝回执进同 run 现场：重试 prompt 携带验证在场拒绝与起验指引
        assertThat(sliceRetry.prompt())
                .contains("错误现场")
                .contains("验证在场未过")
                .contains("验收要点")
                .contains("report_verification");
        // 补验后正常收口：片全收口 + 验证统计随收尾卡（登记在场即统计在场）
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceClosing = (Map<String, Object>) payloads.getAllValues()
                .get(1).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> verification = (Map<String, Object>) sliceClosing
                .get(AgentEventTypes.VERIFICATION_FIELD);
        assertThat(verification)
                .containsEntry(AgentEventTypes.VERIFICATION_TOTAL_FIELD, 1)
                .containsEntry(AgentEventTypes.VERIFICATION_ROUNDS_FIELD, 1);
    }

    @Test
    void given_zero_verification_fork_slice_when_close_then_rejected_in_fork_then_verified_merges() {
        // #424 fork 片同盖：fork 内零验证收口同样被拒（事实按 fork 工作区键隔离——
        // 另一 fork 不串扰），拒绝回执喂回 fork run 补验后照常合并闭片
        Long projectId = persistedProject("9844");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        when(workspaceLifecycleAppService.fork(anyString())).thenReturn("9001", "9002");
        when(workspaceLifecycleAppService.execWithStdin(any(), any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            // fork 9001 上的切片 2：首试零验证（不登记）、拒绝回执喂回后补验登记；
            // fork 9002 照常（对照组：并行波内互不串扰）
            if ("9001".equals(command.workspaceId())) {
                if (command.prompt().contains("错误现场")
                        && !verificationFacts.hasRegistration("9001")) {
                    verificationFacts.record("9001", List.of(
                            new VerificationFacts.Check("下单主径可走通（刷新仍在）", "browser", true, null)));
                }
                return new AgentReply(command.runId(), "完成");
            }
            verifyRan(command);
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(
                List.of("首片", "登录", "商品浏览", "下单"),
                List.of(Set.of(), Set.of(1), Set.of(1), Set.of(2, 3)));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 切片 2 两试（fork 内零验证被拒 → 补验收口）、其余各一次；拒绝回执进 fork run 现场
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(6)).converse(commands.capture(), any());
        List<AgentCommand> slice2 = commands.getAllValues().stream()
                .filter(cmd -> "9001".equals(cmd.workspaceId())).toList();
        assertThat(slice2).hasSize(2);
        assertThat(slice2.get(1).prompt())
                .contains("错误现场")
                .contains("验证在场未过");
        // 补验后照常合并：全片收口、generated_at 落位
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 2, 2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_round_budget_exhausted_with_fails_when_closes_then_verified_and_annotated() {
        // #424 AC③：单场轮次预算生效——运营面配预算 1、一轮验证仍有未过项：收口
        // 带验证统计（在场硬门过——fail 不拦收口也不静默）＋budgetExhausted 如实
        // 标注（达预算停止修复—复验、未过项带入收尾卡呈现）
        Long projectId = persistedProject("9845");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs
                    (agent_key, web_search_enabled, fetch_url_enabled, routing_enabled,
                     verify_round_budget)
                VALUES ('executor', TRUE, TRUE, TRUE, 1)
                ON CONFLICT (agent_key) DO UPDATE SET
                    verify_round_budget = EXCLUDED.verify_round_budget
                """);
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            // 每轮登记一项未过（预算 1 已尽仍修不了——如实收口）
            verificationFacts.record(command.workspaceId(), List.of(
                    new VerificationFacts.Check("留言提交主径", "browser", false, "点了无反应")));
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 未过不拦收口（fail 走修复—复验回路＋如实呈现，不静默、不 run 失败）
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> verification = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD))
                .get(AgentEventTypes.VERIFICATION_FIELD);
        assertThat(verification)
                .containsEntry(AgentEventTypes.VERIFICATION_FAILED_FIELD, 1)
                .containsEntry(AgentEventTypes.VERIFICATION_ROUNDS_FIELD, 1)
                .containsEntry(AgentEventTypes.VERIFICATION_BUDGET_EXHAUSTED_FIELD, true);
    }

    // ---------- 整轨终验＋fix 循环＋已知缺陷叙事（#427） ----------

    /** 单旅程计划（#427 整轨终验的载荷轴——旅程清单随计划同批）。 */
    private static final BuildPlan JOURNEY_PLAN = new BuildPlan(
            List.of("用户能注册登录", "用户能下单支付"),
            BuildPlan.sequentialDependencies(List.of("用户能注册登录", "用户能下单支付")),
            List.of(new BuildPlan.CoreJourney("买家", "首页", "注册登录→浏览商品→下单支付")),
            List.of(SliceSizeLabel.SMALL, SliceSizeLabel.MEDIUM));

    @Test
    void given_journeys_when_all_slices_closed_then_final_verification_run_before_generated() {
        // #427 AC①：全轨末终验——片全收口后、generated_at 落位（「生成完成」叙事）
        // 前，以核心旅程清单为轴起终验 run（真浏览器跨片旅程由 verify 子智能体承载
        // ——编排测试证编排事实：终验 run 在场、载荷带旅程清单、过验才落位）
        Long projectId = persistedProject("9890");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId, JOURNEY_PLAN);

        // 阶段 0 + 切片×2 + 终验 run = 4 场；末场即终验（会话与载荷可辨）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(commands.capture(), any());
        AgentCommand finalVerify = commands.getAllValues().get(3);
        assertThat(finalVerify.sessionId())
                .contains(TrackFinalVerificationAppService.FINAL_VERIFY_SESSION_INFIX);
        assertThat(finalVerify.prompt())
                .contains("核心旅程")
                .contains("买家")
                .contains("注册登录→浏览商品→下单支付");
        assertThat(finalVerify.heading())
                .isEqualTo(RunHeading.titled(TrackFinalVerificationAppService.FINAL_VERIFY_TITLE));
        // 终验过 → generated_at 落位（Demo 首版成版锚 = 终验 run——完成叙事锚）
        assertThat(generatedAt(projectId)).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_id FROM prj_demo_versions WHERE project_id = ? AND version_no = 1",
                String.class, projectId)).isEqualTo(finalVerify.runId());
    }

    @Test
    void given_no_journeys_when_track_completes_then_no_final_verification() {
        // #427 存量口径：计划无核心旅程（V49 前旧计划不回补）＝无终验轴——终验不
        // 起 run，片全收口照旧直接落位（存量行为不变）
        Long projectId = persistedProject("9891");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseSucceeds("完成");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        verify(agentClient, times(2)).converse(any(), any()); // 阶段 0 + 切片，无终验 run
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_final_verification_fail_when_fixed_then_reverified_then_generated() {
        // #427 AC②：终验 fail 拦「生成完成」叙事→失败项自动起终验修复 run（载荷＝
        // 失败项清单）→修复收口后复走终验（防修 A 坏 B）→复走过验才落 generated_at
        Long projectId = persistedProject("9892");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReady();
        AtomicBoolean fixDone = new AtomicBoolean();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            if (command.sessionId().contains(TrackFinalVerificationAppService.FINAL_VERIFY_SESSION_INFIX)) {
                // 首场终验：旅程未过（跨片断点形态）；修复后的复走：全过
                if (!fixDone.get()) {
                    verificationFacts.record(command.workspaceId(), List.of(
                            new VerificationFacts.Check("下单支付", "browser", false, "点击支付无响应")));
                }
                else {
                    verificationFacts.record(command.workspaceId(), List.of(
                            new VerificationFacts.Check("下单支付", "browser", true, null)));
                }
            }
            else if (command.sessionId().contains(TrackFinalVerificationAppService.VFIX_SESSION_INFIX)) {
                // 终验修复 run：finish_edit 事实（changed=true）＋片轻验在场
                finishEditFacts.record(command.workspaceId(), true, "修复了支付提交");
                verifyRan(command);
                fixDone.set(true);
            }
            else {
                verifyRan(command);
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, JOURNEY_PLAN);

        // 阶段 0 + 切片×2 + 终验(fail) + 修复 + 终验(pass) = 6 场
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(6)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        AgentCommand firstVerify = all.get(3);
        AgentCommand fixRun = all.get(4);
        AgentCommand reverify = all.get(5);
        assertThat(fixRun.sessionId())
                .contains(TrackFinalVerificationAppService.VFIX_SESSION_INFIX);
        // 修复 run 载荷＝失败项清单（项名＋证据）
        assertThat(fixRun.prompt())
                .contains("「下单支付」")
                .contains("点击支付无响应")
                .contains("finish_edit");
        // 复走终验在场（另一场终验 run，非首场重试——会话不同）
        assertThat(reverify.sessionId())
                .contains(TrackFinalVerificationAppService.FINAL_VERIFY_SESSION_INFIX)
                .isNotEqualTo(firstVerify.sessionId());
        // 复走过验 → generated_at 落位（成版锚 = 复走终验 run）
        assertThat(generatedAt(projectId)).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_id FROM prj_demo_versions WHERE project_id = ? AND version_no = 1",
                String.class, projectId)).isEqualTo(reverify.runId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_fix_budget_exhausted_when_still_failing_then_version_built_with_known_defects() {
        // #427 AC③：修复预算（缺省两轮）耗尽——版本照成（generated_at/Demo v1 落位）
        // ＋已知缺陷清单如实进完成叙事与收尾卡（closing.knownDefects——前端如实呈现）
        Long projectId = persistedProject("9893");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReady();
        AtomicInteger verifyRounds = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            if (command.sessionId().contains(TrackFinalVerificationAppService.FINAL_VERIFY_SESSION_INFIX)) {
                verifyRounds.incrementAndGet();
                // 每场终验恒未过（修不动——预算耗尽形态）
                verificationFacts.record(command.workspaceId(), List.of(
                        new VerificationFacts.Check("下单支付", "browser", false, "点击支付无响应")));
            }
            else if (command.sessionId().contains(TrackFinalVerificationAppService.VFIX_SESSION_INFIX)) {
                finishEditFacts.record(command.workspaceId(), true, "尝试修复");
                verifyRan(command);
            }
            else {
                verifyRan(command);
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, JOURNEY_PLAN);

        // 阶段 0 + 切片×2 + 终验×3（首验＋两轮修复后复走）+ 修复×2 = 8 场
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(8)).converse(commands.capture(), any());
        assertThat(verifyRounds.get()).isEqualTo(3);
        AgentCommand lastVerify = commands.getAllValues().get(7);
        // 版本照成：generated_at/Demo v1 落位（锚 = 末轮终验 run——完成叙事锚）
        assertThat(generatedAt(projectId)).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_id FROM prj_demo_versions WHERE project_id = ? AND version_no = 1",
                String.class, projectId)).isEqualTo(lastVerify.runId());
        // 已知缺陷清单进末轮终验收尾卡：summary 如实叙事＋knownDefects 清单
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, atLeast(8)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> lastVerifyClosing = payloads.getAllValues().stream()
                .filter(payload -> lastVerify.runId().equals(payload.get(AgentEventTypes.RUN_FIELD)))
                .reduce((first, second) -> second).orElseThrow()
                .entrySet().stream()
                .filter(entry -> AgentEventTypes.CLOSING_FIELD.equals(entry.getKey()))
                .map(entry -> (Map<String, Object>) entry.getValue())
                .findFirst().orElseThrow();
        assertThat(lastVerifyClosing.get(CoderRunAttempts.CLOSING_SUMMARY_FIELD))
                .asString().contains("预算已尽").contains("如实呈现");
        Map<String, Object> knownDefects = (Map<String, Object>) lastVerifyClosing
                .get(CoderRunAttempts.CLOSING_KNOWN_DEFECTS_FIELD);
        assertThat(knownDefects).isNotNull();
        List<Map<String, Object>> defectItems = (List<Map<String, Object>>) knownDefects.get("defects");
        assertThat(defectItems).hasSize(1);
        assertThat(defectItems.get(0))
                .containsEntry("name", "下单支付")
                .containsEntry("detail", "点击支付无响应");
        // 对话史落库同载荷（#89 同载荷先例——刷新回访不丢）
        Map<String, Object> persistedClosing = conversationHistory.read(projectId).stream()
                .filter(entry -> lastVerify.runId().equals(entry.runId()))
                .reduce((first, second) -> second).orElseThrow().closing();
        assertThat(normalized(((Map<String, Object>) persistedClosing
                .get(CoderRunAttempts.CLOSING_KNOWN_DEFECTS_FIELD))))
                .isEqualTo(normalized(knownDefects));
    }

    @Test
    void given_zero_fix_budget_configured_when_failing_then_straight_to_known_defects() {
        // #427 AC② 运营面可调腿：终验修复预算配 0（合法运营选择——不起修复 run）：
        // 终验 fail 直达已知缺陷形态（版本照成＋knownDefects），无修复 run、无复走
        Long projectId = persistedProject("9894");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs
                    (agent_key, web_search_enabled, fetch_url_enabled, routing_enabled,
                     final_verify_fix_budget)
                VALUES ('executor', TRUE, TRUE, TRUE, 0)
                ON CONFLICT (agent_key) DO UPDATE SET
                    final_verify_fix_budget = EXCLUDED.final_verify_fix_budget
                """);
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReady();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            if (command.sessionId().contains(TrackFinalVerificationAppService.FINAL_VERIFY_SESSION_INFIX)) {
                verificationFacts.record(command.workspaceId(), List.of(
                        new VerificationFacts.Check("下单支付", "browser", false, "点击支付无响应")));
            }
            else {
                verifyRan(command);
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, JOURNEY_PLAN);

        // 阶段 0 + 切片×2 + 终验×1（预算 0 即末轮）= 4 场——无修复 run、无复走
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(3).sessionId())
                .contains(TrackFinalVerificationAppService.FINAL_VERIFY_SESSION_INFIX);
        // 版本照成
        assertThat(generatedAt(projectId)).isNotNull();
    }

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
                // #424 片轻验常开：起验自报验收要点＋验证在场硬门＋修复—复验轮次预算
                .contains("自报本片验收要点")
                .contains("无 report_verification 登记不准收口")
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
        assertThat(sliceClosing).doesNotContainKey("prdNote");
        // #424 片轻验常开后收口必带验证在场（verifyRan 脚本位登记）——统计呈现随
        // 新载荷口径一致（旧「验证未登记可缺省」形在收口路径结构性出局）
        assertThat((Map<String, Object>) sliceClosing.get(AgentEventTypes.VERIFICATION_FIELD))
                .containsEntry(AgentEventTypes.VERIFICATION_TOTAL_FIELD, 1)
                .containsEntry(AgentEventTypes.VERIFICATION_ROUNDS_FIELD, 1);
        assertThat((List<Map<String, Object>>) sliceClosing.get("files")).hasSize(2);
        assertThat((Long) sliceClosing.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_verify_reports_when_closes_then_closing_carries_verification_stats() {
        // #352 AC③：验证结果进收口呈现——verify 子智能体经 report_verification 登记
        // （工具→事实的单测在 ReportVerificationToolTest；本缝钉收口装配）：
        // 阶段 0 末轮带未过项（修不了如实标注：failed+failedItems 进卡）、
        // 切片轮修复—复验两轮（末轮快照后写胜出＝全过，轮次如实累计＝2）
        Long projectId = persistedProject("9812");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            if (converseCalls.incrementAndGet() == 1) {
                // 阶段 0：一轮验证，带一个未过项（三面各验——分布进 faces）
                verificationFacts.record("9812", List.of(
                        new VerificationFacts.Check("首页可访问", "browser", true, null),
                        new VerificationFacts.Check("演示数据落库", "db", false, "messages 表 0 行"),
                        new VerificationFacts.Check("应用日志无报错", "log", true, null)));
            } else {
                // 切片轮：修复—复验两轮（首轮未过 → 复验全过——末轮快照后写胜出）
                verificationFacts.record("9812", List.of(
                        new VerificationFacts.Check("留言提交", "browser", false, "点了无反应")));
                verificationFacts.record("9812", List.of(
                        new VerificationFacts.Check("留言提交", "browser", true, null),
                        new VerificationFacts.Check("留言数据落库", "db", true, "messages 表 1 行")));
            }
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "系统已生成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> stage0Closing =
                (Map<String, Object>) payloads.getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> stage0Verification =
                (Map<String, Object>) stage0Closing.get(AgentEventTypes.VERIFICATION_FIELD);
        assertThat(stage0Verification)
                .containsEntry(AgentEventTypes.VERIFICATION_TOTAL_FIELD, 3)
                .containsEntry(AgentEventTypes.VERIFICATION_FAILED_FIELD, 1)
                .containsEntry(AgentEventTypes.VERIFICATION_ROUNDS_FIELD, 1);
        assertThat((Map<String, Object>) stage0Verification.get(AgentEventTypes.VERIFICATION_FACES_FIELD))
                .containsEntry("browser", 1).containsEntry("db", 1).containsEntry("log", 1);
        assertThat((List<String>) stage0Verification.get(AgentEventTypes.VERIFICATION_FAILED_ITEMS_FIELD))
                .containsExactly("演示数据落库");
        Map<String, Object> sliceClosing =
                (Map<String, Object>) payloads.getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> sliceVerification =
                (Map<String, Object>) sliceClosing.get(AgentEventTypes.VERIFICATION_FIELD);
        assertThat(sliceVerification)
                .containsEntry(AgentEventTypes.VERIFICATION_TOTAL_FIELD, 2)
                .containsEntry(AgentEventTypes.VERIFICATION_FAILED_FIELD, 0)
                .containsEntry(AgentEventTypes.VERIFICATION_ROUNDS_FIELD, 2);
        assertThat((Map<String, Object>) sliceVerification.get(AgentEventTypes.VERIFICATION_FACES_FIELD))
                .containsEntry("browser", 1).containsEntry("db", 1).containsEntry("log", 0);
        assertThat((List<String>) sliceVerification.get(AgentEventTypes.VERIFICATION_FAILED_ITEMS_FIELD))
                .isEmpty();
        // 收口消费即清：事实不残留串到下一轨
        assertThat(verificationFacts.consume("9812")).isNull();
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

        // 重试 prompt 携进展现场（#323）：错误现场＋已干多久/干到哪的平台可观测事实
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
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

        // 阶段 0 收口收尾卡：失败尝试留账——部分桶不清零、run 级桶跨尝试含失败尝试、
        // 变更清单含失败尝试已落盘的文件（活动量口径：同路径跨尝试〔含失败〕合并）
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
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

    @Test
    @SuppressWarnings("unchecked")
    void given_bare_client_slice_when_close_then_lint_fix_round_refed_back_then_closes() {
        // #425 AC①：强制档判据命中裸 pg/redis 客户端与裸 fetch——违规清单喂回执行体
        // 同 run 修一轮（对偶设计规范 lint 修轮先例）；修净即正常收口，修轮事实
        //（retried）随收尾卡如实呈现
        Long projectId = persistedProject("9844");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        givenRobustKitPresentWithLintScript();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            // 切片 1：首试扫描命中（修轮喂回）、修轮后修净；阶段 0 干净作对照
            if (command.sessionId().endsWith("-slice-1")) {
                boolean bareOnFirstAttempt = !command.prompt().contains("错误现场");
                robustLintMandatory.put("9844", bareOnFirstAttempt
                        ? "./src/lib/orders.ts:12:const pool = new Pool({ connectionString });\n"
                                + "./src/app/orders/page.tsx:40:const r = await fetch('/api/orders');\n"
                        : "");
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 阶段 0 一次收口 + 切片 1 两试（违规喂回修一轮 → 修净收口）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        AgentCommand sliceRetry = commands.getAllValues().get(2);
        // 违规清单进同 run 现场：重试 prompt 携带基座 lint 修轮指引与 file:line 定位
        assertThat(sliceRetry.prompt())
                .contains("基座 lint 扫描未过")
                .contains("src/lib/orders.ts:12")
                .contains("src/app/orders/page.tsx:40")
                .contains("强制档");
        // 修净后正常收口：片全收口＋generated_at 落位；修轮事实随收尾卡（retried 如实）
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceClosing = (Map<String, Object>) payloads.getAllValues()
                .get(1).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> robustLint = (Map<String, Object>) sliceClosing
                .get(CoderRunAttempts.CLOSING_ROBUST_LINT_FIELD);
        assertThat(robustLint)
                .containsEntry("status", "passed")
                .containsEntry("retried", true);
    }

    @Test
    void given_bare_client_never_fixed_when_generate_then_rejected_rollback_rerun_then_terminal_failed() {
        // #425 AC②前半：强制档修轮用尽仍余＝收口结构性拒绝（拒绝回执喂回同 run），
        // 耗尽走 #353 段级自愈回滚重跑（不建新通道），重跑轮同形再耗尽→转终态
        // run-failed、片 FAILED、后片不派——快档典型「绕不过预置件」形态被闸在片级
        Long projectId = persistedProject("9845");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        givenRobustKitPresentWithLintScript();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            // 切片 1 恒违规（修轮喂回后仍裸写——重跑轮同形）；阶段 0 干净
            if (command.sessionId().endsWith("-slice-1")
                    || command.sessionId().contains("-slice-1-")) {
                robustLintMandatory.put("9845",
                        "./src/lib/orders.ts:12:const pool = new Pool({});\n");
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId,
                new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));

        // 阶段 0 (1) + 切片 1 首轮（修轮 1 + 拒绝 2）+ 重跑轮同形 3 试 = 7 次 converse
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1 + 2 * properties.getMaxAttempts()))
                .converse(commands.capture(), any());
        // 现场次序如实：第 2 试携修轮清单（基座 lint 扫描未过）、第 3 试起携拒绝回执
        //（强制档未过＝收口被拒——不是软收口）
        assertThat(commands.getAllValues().get(2).prompt()).contains("基座 lint 扫描未过");
        assertThat(commands.getAllValues().get(3).prompt()).contains("基座 lint 强制档未过");
        // 段级自愈兜底：留账＋回滚各两次（重跑前＋终态前——#353 不变式）
        verify(workspaceLifecycleAppService, times(2)).exec(eq("9845"),
                argThat((WorkspaceExecCommand cmd) -> cmd != null
                        && cmd.command().contains("git reset --hard")));
        // 终态：run-failed、片 FAILED、后片不派、generated_at 不落
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 3, 1);
        assertThat(generatedAt(projectId)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_preset_session_residual_when_close_then_annotated_not_blocked() {
        // #425 AC②后半＋AC③：预置档（会话件自写）修一轮仍余＝如实标注收尾卡、
        // 不拦收口（对比强制档＝拒绝回路）；未用会话件（阶段 0 零命中）不判违规
        Long projectId = persistedProject("9846");
        givenSessionExecutorRunsInline();
        givenWorkspaceGitReadyWithProofScript();
        givenRobustKitPresentWithLintScript();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            // 切片 1 全程（修轮前后）都余预置档违规；阶段 0 干净作对照（不拦不用）
            if (command.sessionId().endsWith("-slice-1")) {
                robustLintPreset.put("9846",
                        "./src/app/login/actions.ts:8:const jar = await cookies();\n");
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 预置档不拦收口：片全收口＋generated_at 落位（阶段 0 一次 + 切片两试）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(2).prompt())
                .contains("基座 lint 扫描未过")
                .contains("预置档");
        assertThat(segmentRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
        // 收尾卡如实标注：robustKitLint 带预置档余项与修轮事实、无强制档分节；
        // 阶段 0（未用会话件）passed 无标注
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceClosing = (Map<String, Object>) payloads.getAllValues()
                .get(1).get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> robustLint = (Map<String, Object>) sliceClosing
                .get(CoderRunAttempts.CLOSING_ROBUST_LINT_FIELD);
        assertThat(robustLint)
                .containsEntry("status", "violations")
                .containsEntry("retried", true)
                .doesNotContainKey("mandatory");
        assertThat((Map<String, Object>) robustLint.get("preset"))
                .containsEntry("total", 1);
        Map<String, Object> stage0Closing = (Map<String, Object>) payloads.getAllValues()
                .get(0).get(AgentEventTypes.CLOSING_FIELD);
        assertThat((Map<String, Object>) stage0Closing
                .get(CoderRunAttempts.CLOSING_ROBUST_LINT_FIELD))
                .containsEntry("status", "passed");
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
        // 一等断言（#296 验收⑤）：无规范项目派发——AGENTS.md 不带设计规范节、
        // 物化零写入（DESIGN.md／lint 配置皆不落，基座行为零改变）
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

    @Test
    @SuppressWarnings("unchecked")
    void given_lint_violations_when_close_then_fix_round_then_listed_with_retried() {
        // 灵魂用例（#296 验收④）：违规 → 自动重试一轮（违规清单经错误现场喂回
        // 执行体原地修）→ 仍违规 → 收口不被炸（软约束不当硬门）、收尾卡如实列
        // 违规项＋retried
        Long projectId = persistedProject("9822");
        givenSpecRow(projectId);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenPlatformWriteSucceeds();
        givenOxlintViolations();
        List<String> prompts = new ArrayList<>();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            prompts.add(command.prompt());
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "系统已生成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 阶段 0 与切片各一次违规修轮（首试＋修轮）＝4 次 converse；修轮 prompt
        // 携带违规现场（文件/规则/诊断原文）
        verify(agentClient, times(4)).converse(any(), any());
        assertThat(prompts.get(1))
                .contains("样式合规扫描未过")
                .contains("src/app/page.tsx:3 no-raw-colors")
                .contains("bg-red-500");

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing0 = (Map<String, Object>) payloads.getAllValues().get(0)
                .get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> lint = (Map<String, Object>) closing0.get("designLint");
        assertThat(lint)
                .containsEntry("status", "violations")
                .containsEntry("total", 1)
                .containsEntry("retried", true);
        List<Map<String, Object>> listedViolations = (List<Map<String, Object>>) lint.get("violations");
        assertThat(listedViolations).hasSize(1);
        assertThat(listedViolations.get(0))
                .containsEntry("file", "src/app/page.tsx")
                .containsEntry("rule", "no-raw-colors");
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_lint_violations_fixed_when_retry_then_closing_reports_passed_with_retried() {
        // 修一轮后清零：收尾卡如实叙事「曾发现违规、修一轮后通过」（passed＋
        // retried）；后继段干净收口＝passed 无 retried（修轮一场 run 至多一轮）
        Long projectId = persistedProject("9823");
        givenSpecRow(projectId);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenPlatformWriteSucceeds();
        AtomicInteger scans = new AtomicInteger();
        when(workspaceLifecycleAppService.exec(anyString(),
                argThat(command -> command != null && command.command().contains("oxlint"))))
                .thenAnswer(invocation -> scans.incrementAndGet() == 1
                        ? new ExecResultResponse(OXLINT_VIOLATIONS_JSON, "", 1)
                        : new ExecResultResponse("{}", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "系统已生成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 阶段 0：首试违规→修轮清零（2 次 converse）；切片：首试即净（1 次）
        verify(agentClient, times(3)).converse(any(), any());
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> stage0Lint = lintOf(payloads, 0);
        assertThat(stage0Lint).containsEntry("status", "passed").containsEntry("retried", true);
        Map<String, Object> sliceLint = lintOf(payloads, 1);
        assertThat(sliceLint).containsEntry("status", "passed").doesNotContainKey("retried");
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_violations_on_last_attempt_when_no_budget_then_accepted_not_failed() {
        // 软约束灵魂用例（ADR-0028 Considered「违规即 run 失败——拒绝」）：修轮
        // 无预算位（末次尝试才见违规）＝如实收口列违规、不 run 失败——重试耗尽
        // 的硬判据口径不因 lint 改变
        Long projectId = persistedProject("9824");
        givenSpecRow(projectId);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenPlatformWriteSucceeds();
        givenOxlintViolations();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("首次中断"))
                .thenThrow(new IllegalStateException("再次中断"))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    verifyRan(command);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                            Map.of(AgentEventTypes.FINISH_FIELD, "end")));
                    return new AgentReply(command.runId(), "系统已生成");
                });

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        // 阶段 0 三试（两硬败＋末次收口见违规无预算）→ 如实收口；切片照常起跑
        // 收口——run 整体成功（generated_at 落位），违规随收尾卡不随 run-failed
        assertThat(generatedAt(projectId)).isNotNull();
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing0 = (Map<String, Object>) payloads.getAllValues().get(0)
                .get(AgentEventTypes.CLOSING_FIELD);
        Map<String, Object> lint = (Map<String, Object>) closing0.get("designLint");
        assertThat(lint)
                .containsEntry("status", "violations")
                .doesNotContainKey("retried");
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
        // 重发 = 阶段 0 + 切片各一次 converse
        verify(agentClient, times(2)).converse(any(), any());
    }

    // ---------- 生成加门（#332）：确认触发首个 Demo run ----------

    @Test
    void given_confirmed_no_plan_when_dispatch_first_demo_then_redispatch_plan_then_runs() {
        // 灵魂例（#332 确认后派首个 Demo run）：确认时刻无既有计划——走补产链闭环
        // （补产轮产计划 → 收口自动派生成 → 阶段 0 + 逐片 → generated_at 落位）
        Long projectId = persistedProject("9880");
        seedConfirmedVersion(projectId);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        BuildPlan produced = new BuildPlan(List.of("用户能注册登录", "用户能下单支付"));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            if (command.sessionId().startsWith(MainAgentAppService.SESSION_PREFIX)) {
                buildPlanFacts.record(command.workspaceId(), produced);
                return new AgentReply(command.runId(), "已按 PRD 拟好实施计划");
            }
            return new AgentReply(command.runId(), "完成");
        });

        GenerationAppService.GenerationRun run = appService.dispatchFirstDemoOnConfirm(projectId);

        assertThat(run).isNotNull();
        assertThat(appService.planOf(projectId)).isEqualTo(produced);
        assertThat(appService.deepestClosedSegmentOf(projectId)).isEqualTo(2);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_generated_when_dispatch_first_demo_then_null_no_repeat() {
        // 已生成＝首个 Demo 已起，确认只负责首个——不重复派、零编码 run
        Long projectId = persistedGeneratedProject("9881");

        assertThat(appService.dispatchFirstDemoOnConfirm(projectId)).isNull();
        verify(agentClient, never()).converse(any(), any());
    }

    @Test
    void given_archived_when_dispatch_first_demo_then_null() {
        // 归档＝对话区关闭即全停——确认不派
        Long projectId = persistedArchivedProject("9882");

        assertThat(appService.dispatchFirstDemoOnConfirm(projectId)).isNull();
        verify(agentClient, never()).converse(any(), any());
    }

    @Test
    void given_first_demo_failed_when_reconfirm_then_redispatch_from_track() {
        // 失败重发同口径：首派失败（阶段 0 起服耗尽转终态）后项目仍「未生成」，用户
        // 重确认（「继续生成」同路）即再触发——计划已在轨道表，从断点续跑（不重产计划）
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
            if (command.sessionId().startsWith(MainAgentAppService.SESSION_PREFIX)) {
                buildPlanFacts.record(command.workspaceId(), SINGLE_SLICE_PLAN);
                return new AgentReply(command.runId(), "计划已定");
            }
            throw new IllegalStateException("起服失败"); // 审核段失败（重试耗尽转终态）
        });

        GenerationAppService.GenerationRun first = appService.dispatchFirstDemoOnConfirm(projectId);

        assertThat(first).isNotNull();
        assertThat(generatedAt(projectId)).isNull(); // 失败——仍「未生成」
        // 重确认（重发）：计划在轨道表，续跑即完成（重打桩的 matcher 空参调用触发旧
        // answer——旧桩对 null 命令直接过，同既有先例）
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            return command != null ? new AgentReply(command.runId(), "完成") : null;
        });
        GenerationAppService.GenerationRun again = appService.dispatchFirstDemoOnConfirm(projectId);

        assertThat(again).isNotNull();
        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- Demo 版本化（#333）：生成收口自动成版（锚起跑时确认版本） ----------

    @Test
    void given_confirmed_prd_when_generation_closes_then_demo_v1_persisted() {
        // 灵魂例（#333 AC①）：构建收口自动成版——首版 v1 随 generated_at 同刻落，
        // 锚＝起跑时已确认 PRD 版本、run 锚＝末片收口 run（「查看当时」接续缝）
        Long projectId = persistedProject("9884");
        seedConfirmedVersion(projectId);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            if (command.sessionId().startsWith(MainAgentAppService.SESSION_PREFIX)) {
                buildPlanFacts.record(command.workspaceId(), SINGLE_SLICE_PLAN);
                return new AgentReply(command.runId(), "计划已定");
            }
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchFirstDemoOnConfirm(projectId);

        verify(agentClient, atLeast(2)).converse(commands.capture(), any());
        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT version_no, prd_version_no, run_id FROM prj_demo_versions"
                        + " WHERE project_id = ?",
                projectId);
        assertThat(version.get("version_no")).isEqualTo(1);
        assertThat(version.get("prd_version_no")).isEqualTo(1);
        // run 锚＝末片（切片 1）收口 run——首 run 是阶段 0，末片收口才成版
        assertThat(version.get("run_id"))
                .isEqualTo(commands.getAllValues().get(commands.getAllValues().size() - 1).runId());
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_prd_v2_confirmed_mid_run_when_closure_then_version_anchors_v1() {
        // 在途 Demo 照跑完收口绑旧版本（#333 AC③）：起跑后、收口前 PRD 出新版
        // （converse 侧效插入确认版 v2）——成版仍锚起跑时的 v1，不随后续确认漂移
        Long projectId = persistedProject("9885");
        seedConfirmedVersion(projectId);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            verifyRan(command);
            if (command.sessionId().startsWith(MainAgentAppService.SESSION_PREFIX)) {
                buildPlanFacts.record(command.workspaceId(), SINGLE_SLICE_PLAN);
                return new AgentReply(command.runId(), "计划已定");
            }
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

        appService.dispatchFirstDemoOnConfirm(projectId);

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
        return tracks;
    }

    @Test
    void given_first_demo_in_flight_when_turn_opinions_arrive_then_notified_and_merged_fix_after_close()
            throws InterruptedException {
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
            if (command.sessionId().endsWith("-slice-1")) {
                // 切片 1（末段）在途挂住——制造首 Demo 生成在途窗口
                generating.countDown();
                release.await();
            }
            if (command.sessionId().contains("-fix-")) {
                // 排空补派的更新 run：以 finish_edit 事实收口（changed=true）
                finishEditFacts.record(command.workspaceId(), true, "已按意见更新系统");
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
        verify(agentClient, times(2)).converse(any(), any());
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
        verify(agentClient, times(3)).converse(commands.capture(), any());
        // 补派 prompt = 修正轨交接物拼装（逐轮配对：各轮「意见 → 修订说明」）
        assertThat(commands.getAllValues().get(2).prompt()).isEqualTo(
                IterationAppService.fixRunPrompt(new IterationAppService.FixHandoff(
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
        givenAgentsMdWriteSucceeds();
        List<Runnable> tracks = givenTrackCollected();
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch fixDone = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean(false);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            verifyRan(command);
            if (command.sessionId().endsWith("-slice-1") && !released.get()) {
                // 首 Demo 切片 1 在途挂住，等停止请求落位（Future.cancel 线程中断的
                // 现场等价物——停止检查点在中断侧，此处以中断形态异常收场）
                generating.countDown();
                while (!codingRunTrack.stopRequested(projectId)) {
                    Thread.sleep(50);
                }
                throw new RuntimeException("java.lang.InterruptedException");
            }
            if (command.sessionId().contains("-fix-")) {
                finishEditFacts.record(command.workspaceId(), true, "已按意见更新系统");
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
                .isEqualTo(IterationAppService.fixRunPrompt(new IterationAppService.FixHandoff(
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

    /** 已确认 PRD 版本在场（#332 生成加门：{@link
     * GenerationAppService#dispatchFirstDemoOnConfirm} 的调用前提——经确认缝触发时
     * 版本必已落库；收口观测判据只看存在性，内容不参与）。 */
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
        verify(agentClient, times(2)).converse(commands.capture(), any());
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
        // 失败升档（历史失败信号）：阶段 0 首试快档（枚举缺省）→ 重试升强档——
        // 换档不换会话（重试续本段会话，上下文连续）；下一段（切片）重新起跑
        // 回落快档（信号按段算，不跨段传染）
        Long projectId = persistedProject("9850");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseFailsThenSucceeds("起服失败");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "volcengine:deepseek-v4-flash-ga-260731", // 阶段 0 首试——无信号、快档
                "volcengine:deepseek-v4-pro-ga-260813",   // 阶段 0 重试——失败升档
                "volcengine:deepseek-v4-flash-ga-260731"); // 切片首试——新段重新判定、快档
        assertThat(commands.getAllValues().get(0).sessionId())
                .isEqualTo(commands.getAllValues().get(1).sessionId());

        // 收尾卡留档：阶段 0 收口带路由判定（升强档＋理由＋判定依据），切片收口
        // 带快档判定（无升档不携 reasons）
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
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
                .containsEntry("sizeLabel", null); // 阶段 0 无片标签——规模标签信号缺位
        Map<String, Object> sliceTier = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD))
                .get("modelTier");
        assertThat(sliceTier)
                .containsEntry("modelId", "flash")
                .doesNotContainKey("reasons");
        // 留档可查：modelTier 随收尾卡落对话史（JSONB 同载荷）——哪段用了哪档的
        // 持久留档面（失败段无收尾卡、以结构化日志为面）
        List<Map<String, Object>> closings = jdbcTemplate.queryForList(
                "SELECT closing->'modelTier'->>'modelId' AS tier_model_id FROM"
                        + " prj_conversation_entries WHERE project_id = ? AND kind = ?"
                        + " ORDER BY id",
                projectId, ConversationEntryKind.CLOSING.getCode());
        assertThat(closings).extracting(row -> row.get("tier_model_id")).containsExactly(
                "pro", // 阶段 0 收口——重试升档后的强档
                "flash"); // 切片收口——快档
    }

    @Test
    void given_large_file_surface_when_dispatch_then_strong_from_first_attempt() {
        // 文件面大小信号：应用文件面（探针输出已扣基座基数）≥ 阈值（代码缺省 30，
        // #426 排除基座基数后重校）——首试即升强档
        Long projectId = persistedProject("9851");
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
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "volcengine:deepseek-v4-pro-ga-260813", "volcengine:deepseek-v4-pro-ga-260813");

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceTier = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD))
                .get("modelTier");
        assertThat((List<String>) sliceTier.get("reasons")).contains("文件面超出阈值（87 ≥ 30）");
        assertThat((Map<String, Object>) sliceTier.get("signals")).containsEntry("fileCount", 87);
    }

    @Test
    void given_large_labeled_slice_when_dispatch_then_slice_escalates_but_stage0_stays_fast() {
        // 规模标签信号（#426）：计划自报「大」标签的片（描述短、功能重的锚案形态
        // ——枚举密度信号量不出来）升强档；阶段 0 无标签（信号缺位）仍快档。
        // 未设标签的存量计划不升档（回归见 ModelRoutingAppServiceTest）
        Long projectId = persistedProject("9852");
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
            return new AgentReply(command.runId(), "完成");
        });

        appService.dispatchGenerationOnTurnClose(projectId, new BuildPlan(
                List.of("用户能进行拼写题与听力题的测试"), null, null,
                List.of(SliceSizeLabel.LARGE)));

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "volcengine:deepseek-v4-flash-ga-260731",
                "volcengine:deepseek-v4-pro-ga-260813");
        // 留档快照：升档理由带规模标签判定快照（标签＋生效映射）
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> sliceTier = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD))
                .get("modelTier");
        assertThat((List<String>) sliceTier.get("reasons")).containsExactly("规模标签升档（大）");
        assertThat((Map<String, Object>) sliceTier.get("signals"))
                .containsEntry("sizeLabel", "大")
                .containsEntry("escalatingSizeLabels", List.of("大"));
    }

    @Test
    void given_manual_override_with_routing_on_when_retry_then_escalation_crosses_override() {
        // 路由判定优先、人工覆盖兜底：覆盖是基础档不是钉死——首试走人工档、
        // 重试升强档（升档越过覆盖）；下一段回落人工档
        Long projectId = persistedProject("9853");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, model_id)
                VALUES ('executor', 'doubao-flagship')
                """);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseFailsThenSucceeds("起服失败");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "volcengine:doubao-seed-2-1-pro-260915", // 首试——无信号、人工档兜底
                "volcengine:deepseek-v4-pro-ga-260813",   // 重试——失败升档（越过人工档）
                "volcengine:doubao-seed-2-1-pro-260915"); // 切片首试——新段回落人工档

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> tier = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD))
                .get("modelTier");
        assertThat(tier)
                .containsEntry("modelId", "doubao-flagship")
                .containsEntry("source", "routed")
                .doesNotContainKey("reasons"); // 兜底判定——只记为什么升
    }

    @Test
    void given_routing_off_with_override_when_retry_then_pinned_manual_tier() {
        // 钉死位＝路由关＋人工档：人工档恒生效——失败重试不升档（既有口径）
        Long projectId = persistedProject("9854");
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, model_id, routing_enabled)
                VALUES ('executor', 'pro', false)
                """);
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenConverseFailsThenSucceeds("起服失败");

        appService.dispatchGenerationOnTurnClose(projectId, SINGLE_SLICE_PLAN);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "volcengine:deepseek-v4-pro-ga-260813", "volcengine:deepseek-v4-pro-ga-260813", "volcengine:deepseek-v4-pro-ga-260813");

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> tier = (Map<String, Object>) ((Map<String, Object>) payloads
                .getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD))
                .get("modelTier");
        assertThat(tier)
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
        verify(agentClient, times(3)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::modelString).containsExactly(
                "volcengine:deepseek-v4-flash-ga-260731", "volcengine:deepseek-v4-flash-ga-260731", "volcengine:deepseek-v4-flash-ga-260731");
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
