package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.FileChange;
import com.semgravio.aiplatform.base.agentscope.KernelSystemPrompt;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 迭代编排（#623 对话面交指令 → 内核重跑；#625 旧迭代受理链拆除后只剩这一条从
 * 对话面到内核的路径）：空闲即起恰好一场内核 run；在途入待办、成功收口后合并为
 * 恰好一场补派；失败/停止不自动补派、待办保留（用户重说即新一轮）。收口判据双件
 * （build＋8081 探活）与收尾卡扩载、Demo 迭代成版、PRD 变化通知同生成轨口径。
 */
@IntegrationTest
class IterationAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private IterationAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 停止面（#359 生成取消）——停止请求的注入口（测试经公开 requestStop 触发）。 */
    @Autowired
    private CodingRunTrack codingRunTrack;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private EventsAppService eventsAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    /** 工作区 exec（#619 快照/diff 与 #624 PRD 读的脚本化缝）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @BeforeEach
    void stubWorkspace() {
        // #601 内核收尾判据双件（#597：pnpm build 过＋8081 探活）默认成功——失败态
        // 用例在测试体内后注册桩覆盖（Mockito 后桩优先）
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && (cmd.command().contains("pnpm build")
                        || cmd.command().contains("curl")))))
                .thenReturn(new ExecResultResponse("", "", 0));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_demo_versions");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
        jdbcTemplate.update("DELETE FROM bil_balance_holds");
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
    }

    /** 轨道任务排队不跑（起跑侧只登记在途）；手动 run 模拟异步轨道执行。 */
    private List<Runnable> givenTrackQueued() {
        List<Runnable> tracks = new ArrayList<>();
        doAnswer(invocation -> {
            tracks.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            tracks.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());
        return tracks;
    }

    /** 脚本化智能体边界：run 正常返回即收口（内核座单发——收口判定归平台机器判据双件）。 */
    private void givenConverseFinishing() {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "修改完成");
        });
    }

    private static AgentEvent scripted(String type, String runId, Map<String, Object> extra) {
        return AgentEventScripts.scripted(type, runId, extra);
    }

    // ---------- 对话面交指令（#623，ADR-0056 修改交接） ----------

    private static ModificationHandoffFacts.Instruction instruction(String userWords,
            String instruction) {
        return new ModificationHandoffFacts.Instruction(instruction, userWords, null);
    }

    /** 一条对话面交指令（自足指令＝用户原话的整理形）。 */
    private void handOff(Long projectId, String text) {
        appService.handOffModification(projectRepository.findById(projectId).orElseThrow(),
                List.of(instruction(text, text)), "dialog-run");
    }

    @Test
    void given_idle_generated_when_handoff_then_one_kernel_run_carries_instruction_and_words() {
        // 说清一句修改 → 空闲即起恰好一场内核重跑；任务提示携自足指令与用户原话；
        // 空闲直派无在途轻提示、零派发第二场
        Long projectId = persistedGeneratedProject("9930");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing();
        Project project = projectRepository.findById(projectId).orElseThrow();

        appService.handOffModification(project,
                List.of(instruction("列表加分页", "给预约列表加分页")), "dialog-run");

        assertThat(tracks).hasSize(1); // 异步轨道已提交未跑
        verify(agentClient, never()).converse(any(), any());
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .contains("用户原话：列表加分页")
                .contains("修改指令：给预约列表加分页");
        assertThat(command.getValue().systemPrompt()).isEqualTo(KernelSystemPrompt.text());
        assertThat(command.getValue().kernelForm()).isTrue();
        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
    }

    @Test
    void given_handoffs_while_in_flight_when_run_succeeds_then_merged_into_one_follow_up()
            throws InterruptedException {
        // 当前 run 在途再交两条修改 → 各自入待办并轻提示；当前 run 成功收口后合并且仅
        // 合并为一场后续 run 补派（不逐条烧 run、不漏派）
        Long projectId = persistedGeneratedProject("9931");
        List<Runnable> tracks = givenTrackQueued();
        CountDownLatch runInFlight = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            runInFlight.countDown();
            releaseRun.await();
            return new AgentReply(command.runId(), "完成");
        });
        Project project = projectRepository.findById(projectId).orElseThrow();

        appService.handOffModification(project,
                List.of(instruction("列表加筛选", "给列表加筛选功能")), "dialog-1");
        Thread worker = new Thread(tracks.remove(0));
        worker.start();
        assertThat(runInFlight.await(5, TimeUnit.SECONDS)).isTrue();

        appService.handOffModification(project,
                List.of(instruction("按钮改蓝色", "把主按钮改成蓝色")), "dialog-2");
        appService.handOffModification(project,
                List.of(instruction("加导出", "增加导出功能")), "dialog-3");
        verify(eventsAppService, times(2)).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), argThat(payload -> IterationAppService
                        .MODIFICATION_QUEUED_NOTICE.equals(payload.get("text"))));

        releaseRun.countDown();
        worker.join(5000);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any()); // 当前一场 + 合并一场
        assertThat(command.getAllValues().get(1).prompt())
                .contains("用户原话：按钮改蓝色")
                .contains("修改指令：把主按钮改成蓝色")
                .contains("用户原话：加导出")
                .contains("修改指令：增加导出功能");
    }

    @Test
    void given_run_fails_when_handoff_queued_then_no_auto_follow_up_and_user_resay_dispatches() {
        // 当前 run 失败 → 不自动补派、待办保留；用户重说即起新一轮（待办并入本轮，
        // 失败那场不重放——失败语义不造第三层重试）
        Long projectId = persistedGeneratedProject("9932");
        List<Runnable> tracks = givenTrackQueued();
        AtomicInteger calls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("第一场失败");
            }
            return new AgentReply(command.runId(), "完成");
        });
        Project project = projectRepository.findById(projectId).orElseThrow();

        appService.handOffModification(project,
                List.of(instruction("第一件事", "做第一件事")), "dialog-1");
        // 当前 run 在途（已占轨未跑）：第二条入待办
        appService.handOffModification(project,
                List.of(instruction("第二件事", "做第二件事")), "dialog-2");
        tracks.remove(0).run(); // 第一场失败收口

        verify(agentClient, times(1)).converse(any(), any()); // 失败不自动补派

        // 用户重说第三条 → 空闲直派，待办（第二条）并入本轮
        appService.handOffModification(project,
                List.of(instruction("第三件事", "做第三件事")), "dialog-3");
        assertThat(tracks).hasSize(1);
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .contains("用户原话：第二件事")
                .contains("修改指令：做第二件事")
                .contains("用户原话：第三件事")
                .contains("修改指令：做第三件事")
                .doesNotContain("第一件事"); // 失败那场不自动重放
    }

    @Test
    void given_first_build_in_flight_when_handoff_then_after_success_dispatched_as_first_iteration() {
        // 首建在途提的意见 → 待办保留；首建成功收口后作为第一轮迭代补派（恰好一场）
        Long projectId = projectRepository.save(Project.create("首建在途项目", null,
                9933L, OWNER, null)).getId();
        assertThat(codingRunTrack.begin(projectId)).isTrue();
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing();

        appService.handOffModification(projectRepository.findById(projectId).orElseThrow(),
                List.of(instruction("首建时提的", "首建完成后做这件事")), "dialog-1");
        assertThat(tracks).isEmpty(); // 在途不打断：待办不派
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY),
                argThat(payload -> IterationAppService.MODIFICATION_QUEUED_NOTICE
                        .equals(payload.get("text"))));

        // 首建成功收口（generated_at 落位）→ 待办作为第一轮迭代补派
        jdbcTemplate.update("UPDATE prj_projects SET generated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", projectId);
        codingRunTrack.end(projectId);
        appService.drainPendingModificationsAfterSuccess(projectId);

        assertThat(tracks).hasSize(1);
        tracks.remove(0).run();
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .contains("用户原话：首建时提的")
                .contains("修改指令：首建完成后做这件事");
    }

    // ---------- 收口判据（平台机器双件）与收尾卡扩载（#617/#619） ----------

    @Test
    void given_fix_run_when_closes_then_demo_seed_applied_and_failure_not_blocking() {
        // #340 演示种子预置：迭代收口（系统实动后可能有新空表）施用种子——施用失败
        // 也不阻断收工（轨道释放、下一场可派）
        Long projectId = persistedGeneratedProject("9916");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing();
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("/opt/seed/apply.sh"))))
                .thenThrow(new IllegalStateException("容器 exec 失败"));

        handOff(projectId, "列表页加一列状态");
        tracks.remove(0).run();

        verify(workspaceLifecycleAppService, times(1)).exec(eq("9916"),
                argThat(cmd -> cmd != null && cmd.command().contains("/opt/seed/apply.sh")));
        handOff(projectId, "下一场");
        assertThat(tracks).hasSize(1); // 轨道已释放，下一场可起跑
    }

    /**
     * 脚本化收口轮（#88 验收缝）：真实事件序（run-start → 过程部件 → run-finish）
     * + 回复携带文件变更观察（真实客户端的 AgentReply.changes 面）。
     */
    private void givenConverseClosing(List<FileChange> changes) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.PART_SIGNAL, command.runId(),
                    Map.of(AgentEventTypes.PART_SIGNAL_SIGNAL_FIELD,
                            AgentEventTypes.PART_SIGNAL_DERAILED)));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "修正完成", null, changes);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_update_round_when_fix_closes_then_run_finish_carries_five_field_closing() {
        // 灵魂用例（#617/#619）：脚本化更新轮收口——五字段骨架齐（用时/判据逐件/
        // 内核自述/变更清单/预览入口由前端查询提供）；判定键与平台句式摘要退役；
        // 变更清单来源 = 工作区真实 diff（跑前快照基线 + 收口 numstat），迭代另携
        // 单文件只读 patch
        Long projectId = persistedGeneratedProject("9915");
        List<Runnable> tracks = givenTrackQueued();
        givenSnapshotAndDiff(
                "12\t3\tsrc/pages/Orders.jsx\n4\t0\tsrc/App.jsx\n",
                "diff --git a/src/App.jsx b/src/App.jsx\n"
                        + "--- a/src/App.jsx\n+++ b/src/App.jsx\n@@ -1 +1,4 @@\n+新增\n"
                        + "diff --git a/src/pages/Orders.jsx b/src/pages/Orders.jsx\n"
                        + "--- a/src/pages/Orders.jsx\n+++ b/src/pages/Orders.jsx\n"
                        + "@@ -1,3 +1,12 @@\n-旧\n+新\n");
        // 工具参数观察与真实 diff 刻意不同——收尾卡必须取 diff
        givenConverseClosing(List.of(new FileChange("/src/pages/Orders.jsx", 99, 99)));

        handOff(projectId, "下单页加配送范围说明");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).containsEntry("narrative", "修正完成");
        assertThat((Map<String, Object>) closing.get("criteria"))
                .containsEntry("buildPassed", true)
                .containsEntry("previewPassed", true);
        assertThat(closing).doesNotContainKeys(
                "summary", "prdChanged", "prdNote", "systemChanged", "systemNote");
        List<Map<String, Object>> files = (List<Map<String, Object>>) closing.get("files");
        assertThat(files).extracting(file -> file.get("path"))
                .containsExactly("/src/App.jsx", "/src/pages/Orders.jsx");
        assertThat(files.get(0)).containsEntry("added", 4).containsEntry("removed", 0);
        assertThat(files.get(1)).containsEntry("added", 12).containsEntry("removed", 3);
        assertThat((String) files.get(0).get("patch")).contains("+新增");
        assertThat((String) files.get(1).get("patch")).contains("-旧").contains("+新");
        assertThat(files).allSatisfy(file -> assertThat(file).doesNotContainKey("patchOmitted"));
        assertThat(closing).doesNotContainKey("filesUnavailable");
        assertThat(closing).doesNotContainKey("directories"); // 迭代维持文件级清单
        assertThat(closing.get("durationMs")).isInstanceOf(Long.class);
        assertThat((Long) closing.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_patch_over_limit_when_fix_closes_then_patch_omitted_but_lines_kept() {
        Long projectId = persistedGeneratedProject("9921");
        List<Runnable> tracks = givenTrackQueued();
        StringBuilder huge = new StringBuilder("diff --git a/src/Big.jsx b/src/Big.jsx");
        for (int index = 0; index < 301; index++) {
            huge.append("\n+line ").append(index);
        }
        givenSnapshotAndDiff("500\t0\tsrc/Big.jsx\n", huge.toString());
        givenConverseClosing(List.of());

        handOff(projectId, "大改一页");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat((List<Map<String, Object>>) closing.get("files")).singleElement()
                .satisfies(file -> assertThat(file).containsEntry("path", "/src/Big.jsx")
                        .containsEntry("added", 500)
                        .containsEntry("removed", 0)
                        .containsEntry("patchOmitted", true)
                        .doesNotContainKey("patch"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_update_round_when_fix_closes_then_closing_has_no_version_anchor() {
        // 收尾卡不携 version 键；跑前快照会提交 git（平台标识、无 trailer）——快照不是
        // 用户面版本，版本序列成员判据（Rollback-From）天然过滤
        Long projectId = persistedGeneratedProject("9917");
        List<Runnable> tracks = givenTrackQueued();
        givenSnapshotAndDiff("12\t3\tsrc/pages/Orders.jsx\n", "");
        givenConverseClosing(List.of());

        handOff(projectId, "下单页加配送范围说明");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).doesNotContainKey("version");
        ArgumentCaptor<WorkspaceExecCommand> execs =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(0)).exec(any(), execs.capture());
        assertThat(execs.getAllValues().stream().map(WorkspaceExecCommand::command))
                .noneSatisfy(command -> assertThat(command).contains("Rollback-From"));
        assertThat(execs.getAllValues().stream().map(WorkspaceExecCommand::command))
                .anySatisfy(command -> assertThat(command).contains("-m 'snapshot "));
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_no_file_changes_when_fix_closes_then_closing_has_empty_change_list() {
        Long projectId = persistedGeneratedProject("9916");
        List<Runnable> tracks = givenTrackQueued();
        givenSnapshotAndDiff("", "");
        givenConverseClosing(List.of());

        handOff(projectId, "把配送范围改成全国");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).containsEntry("narrative", "修正完成");
        assertThat((Map<String, Object>) closing.get("criteria"))
                .containsEntry("buildPassed", true)
                .containsEntry("previewPassed", true);
        assertThat((List<Map<String, Object>>) closing.get("files")).isEmpty();
        assertThat(closing).doesNotContainKey("filesUnavailable"); // 空清单不是降级
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_diff_fails_when_fix_closes_then_run_succeeds_and_changes_unavailable() {
        Long projectId = persistedGeneratedProject("9922");
        List<Runnable> tracks = givenTrackQueued();
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("-m 'snapshot "))))
                .thenReturn(new ExecResultResponse("a1b2c3d", "", 0));
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("--numstat"))))
                .thenReturn(new ExecResultResponse("", "fatal: bad revision", 128));
        givenConverseClosing(List.of());

        handOff(projectId, "列表页加一列状态");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).containsEntry("filesUnavailable", true)
                .containsEntry("narrative", "修正完成");
        assertThat((List<Map<String, Object>>) closing.get("files")).isEmpty();
        assertThat((Map<String, Object>) closing.get("criteria"))
                .containsEntry("buildPassed", true);
    }

    /** 跑前快照成功（回读 hash）＋收口 numstat 与完整 patch 脚本（#619 diff 缝）。 */
    private void givenSnapshotAndDiff(String numstat, String patch) {
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("-m 'snapshot "))))
                .thenReturn(new ExecResultResponse("a1b2c3d", "", 0));
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("--numstat"))))
                .thenReturn(new ExecResultResponse(numstat, "", 0));
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("diff --cached --no-renames '"))))
                .thenReturn(new ExecResultResponse(patch, "", 0));
    }

    // ---------- 迭代需求基准（#462 去门化）：恒工作区 PRD 正本（单形） ----------

    private static final String PRD_V1_ROW = "# PRD v1 基准\n\n## 功能清单\n\n1. 首页\n";

    private static final String SCOPE_DRAFT =
            "# PRD v1 基准\n\n## 功能清单\n\n1. 首页\n2. 管理后台\n";

    /** 工作区 PRD 读取桩（PRD 读端点同形：stat 首行＋cat 余文）。 */
    private void givenWorkspacePrd(String prdMarkdown) {
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100000\n" + prdMarkdown, "", 0));
    }

    private void givenConverseSucceeds() {
        givenConverseFinishing();
    }

    @Test
    void given_pending_draft_when_small_iteration_runs_then_prompt_baselines_workspace_draft() {
        Long projectId = persistedGeneratedProject("9960");
        seedPrdVersionRowContent(projectId, 1, PRD_V1_ROW);
        givenWorkspacePrd(SCOPE_DRAFT);
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        handOff(projectId, "按钮改成蓝色");
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .contains("需求正本 = 工作区")
                .doesNotContain("本轮需求基准");
    }

    @Test
    void given_draft_equals_latest_row_when_iteration_runs_then_prompt_reads_workspace() {
        Long projectId = persistedGeneratedProject("9961");
        seedPrdVersionRowContent(projectId, 1, PRD_V1_ROW);
        givenWorkspacePrd(PRD_V1_ROW);
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        handOff(projectId, "按钮改成蓝色");
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .contains("需求正本 = 工作区")
                .doesNotContain("本轮需求基准");
    }

    @Test
    void given_no_version_rows_when_iteration_runs_then_prompt_reads_workspace() {
        Long projectId = persistedGeneratedProject("9962");
        givenWorkspacePrd("# PRD\n\n## 功能清单\n\n1. 首页\n");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        handOff(projectId, "按钮改成蓝色");
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .contains("需求正本 = 工作区")
                .doesNotContain("本轮需求基准");
    }

    // ---------- 迭代后 PRD 仍是完整文档（#624） ----------

    /** 工作区 PRD 读取桩（按调用序返回——起跑快照在前、收口比对在后）。 */
    private void givenWorkspacePrdReads(String... prdMarkdowns) {
        ExecResultResponse[] responses = new ExecResultResponse[prdMarkdowns.length];
        for (int index = 0; index < prdMarkdowns.length; index++) {
            responses[index] = new ExecResultResponse("1756100000\n" + prdMarkdowns[index], "", 0);
        }
        org.mockito.stubbing.OngoingStubbing<ExecResultResponse> stub =
                when(workspaceLifecycleAppService.exec(any(),
                        argThat(cmd -> cmd != null && cmd.command().contains("stat -c %Y"))));
        if (responses.length == 1) {
            stub.thenReturn(responses[0]);
        }
        else {
            stub.thenReturn(responses[0],
                    java.util.Arrays.copyOfRange(responses, 1, responses.length));
        }
    }

    @Test
    void given_modification_instructions_when_prompt_then_requires_merge_requirement_into_prd_sections() {
        // 对话面交指令的迭代 run 同口径：内核把需求整合进 PRD 对应章节
        String prompt = IterationAppService.modificationRunPrompt(
                List.of(instruction("列表加导出", "把订单列表增加导出按钮")));

        assertThat(prompt)
                .contains("docs/PRD.md")
                .contains("对应章节")
                .contains("完整文档")
                .contains("留痕")
                .doesNotContain("迭代节");
    }

    @Test
    void given_prd_changed_after_fix_when_closes_then_document_updated_emitted() {
        // 迭代收口对比工作区 PRD 与起跑快照：变了才发既有 document-updated
        Long projectId = persistedGeneratedProject("9975");
        List<Runnable> tracks = givenTrackQueued();
        givenWorkspacePrdReads("# PRD\n\n## 功能清单\n\n1. 首页\n",
                "# PRD\n\n## 功能清单\n\n1. 首页\n2. 导出\n");
        givenConverseClosing(List.of(new FileChange("/src/App.jsx", 1, 0)));

        handOff(projectId, "列表加导出");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishNotification(
                eq(ProjectEventTypes.DOCUMENT_UPDATED), payload.capture());
        assertThat(payload.getValue())
                .containsEntry(ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString())
                .containsEntry(ProjectEventTypes.DOCUMENT_TYPE_FIELD,
                        ProjectEventTypes.DOCUMENT_TYPE_PRD);
    }

    @Test
    void given_prd_unchanged_after_fix_when_closes_then_no_document_updated() {
        // 无变化不发（避免右栏无意义重拉）
        Long projectId = persistedGeneratedProject("9976");
        List<Runnable> tracks = givenTrackQueued();
        String same = "# PRD\n\n## 功能清单\n\n1. 首页\n";
        givenWorkspacePrdReads(same, same);
        givenConverseClosing(List.of(new FileChange("/src/App.jsx", 1, 0)));

        handOff(projectId, "列表加导出");
        tracks.remove(0).run();

        verify(eventsAppService, never()).publishNotification(
                eq(ProjectEventTypes.DOCUMENT_UPDATED), anyMap());
    }

    @Test
    void given_prd_changed_after_modification_when_closes_then_document_updated_emitted() {
        // 对话面交指令触发的迭代 run 同口径
        Long projectId = persistedGeneratedProject("9977");
        List<Runnable> tracks = givenTrackQueued();
        givenWorkspacePrdReads("# PRD\n\n## 功能清单\n\n1. 首页\n",
                "# PRD\n\n## 功能清单\n\n1. 首页\n2. 导出\n");
        givenConverseClosing(List.of(new FileChange("/src/App.jsx", 1, 0)));

        appService.handOffModification(projectRepository.findById(projectId).orElseThrow(),
                List.of(instruction("列表加导出", "把订单列表增加导出按钮")), "dialog-run-1");
        tracks.remove(0).run();

        verify(eventsAppService).publishNotification(
                eq(ProjectEventTypes.DOCUMENT_UPDATED), anyMap());
    }

    // ---------- Demo 版本化（#333）：迭代轮收口自动成版 ----------

    @Test
    void given_changed_fix_runs_when_close_then_demo_versions_increment_with_prd_anchor() {
        // 迭代每轮自动成版：changed=true 收口即递增成版、锚本场起跑时最新 PRD 版本
        Long projectId = persistedGeneratedProject("9950");
        seedPrdVersionRow(projectId, 1);
        givenWorkspacePrd("# PRD");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseClosing(List.of(new FileChange("/src/App.jsx", 1, 0)));

        handOff(projectId, "列表按时间倒序");
        tracks.remove(0).run();
        // 需求侧修订后的新基准记行——下一场锚 v2
        seedPrdVersionRow(projectId, 2);
        handOff(projectId, "加筛选");
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version_no, prd_version_no, run_id FROM prj_demo_versions"
                        + " WHERE project_id = ? ORDER BY version_no",
                projectId);
        assertThat(rows).extracting(row -> row.get("version_no")).containsExactly(1, 2);
        assertThat(rows.get(0)).containsEntry("prd_version_no", 1)
                .containsEntry("run_id", commands.getAllValues().get(0).runId());
        assertThat(rows.get(1)).containsEntry("prd_version_no", 2)
                .containsEntry("run_id", commands.getAllValues().get(1).runId());
    }

    @Test
    void given_unchanged_fix_run_when_close_then_no_demo_version() {
        // 系统无改动（平台可观测文件改动为空）＝照常收口、无新版本
        Long projectId = persistedGeneratedProject("9951");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing();

        handOff(projectId, "列表按时间倒序");
        tracks.remove(0).run();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_demo_versions WHERE project_id = ?", Integer.class,
                projectId);
        assertThat(count).isZero();
    }

    // ---------- 测试数据 ----------

    /** 已生成形态的项目（迭代的前提事实）。 */
    private Long persistedGeneratedProject(String workspaceId) {
        Project project = Project.create("迭代项目", null, Long.parseLong(workspaceId), OWNER, null);
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    private void seedPrdVersionRow(Long projectId, int versionNo) {
        seedPrdVersionRowContent(projectId, versionNo, "# PRD");
    }

    private void seedPrdVersionRowContent(Long projectId, int versionNo, String content) {
        jdbcTemplate.update("""
                INSERT INTO prj_prd_versions (id, project_id, version_no, content, confirmed_at,
                    prd_produced_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, System.nanoTime(), projectId, versionNo, content);
    }
}
