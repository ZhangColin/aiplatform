package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.DesignScope;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 设计过程编排（#289 验收缝；#291 改稿分代与在途排队＋定稿机制——SSE 事件序列
 * 「出现什么/不出现什么」是一等断言，spec Testing Decisions）：设计类终点 PRD
 * 收口自动起设计（轨内无门）、按清单序逐件串行、每设计物一个设计会话
 * （designer-{projectId}-item-{ord}）；改 PRD 清单序→推进序随改（已收口件对照
 * 保留不重做）；designer 座命令全要素（agentKey/无 shell 工作区标记/计量 dims/
 * 设计物标题 heading/运营配置覆盖）；首产收口判据＝design/ 落稿（无稿重试、超限
 * run-failed 不跳下一件）；收尾卡扩载稿清单与去向、对话史落库同载荷；事件封闭集
 * 零新增。#291：改稿同会话继续（产新代、旧代保留——relaxed 判据 0 稿合法收口）、
 * 在途插话排队（当前稿代收口后受理、失败残留不丢失）、作用域改稿直达任意件
 * （跨件回溯的会话路由）、已生成＋设计类终点的中途切换承接、定稿＝显式动作收口
 * （git commit 成版 Run-Id 对偶锚定收尾卡＋后续三分岔：按稿对齐更新 run／全部
 * 定稿起首个构建携稿引用／设计主线开放下单）。
 */
@IntegrationTest
class DesignProcessAppServiceTest {

    private static final long OWNER = 3897654321098765402L;

    /**
     * 平台侧事件封闭集（SSE 事件清单正本的代码镜像——生命周期注册制 + 部件契约；
     * 引擎透传族开放集合不在此列，编排层结构性不产新顶层 type）。
     */
    private static final Set<String> CLOSED_EVENT_TYPES = Set.of(
            AgentEventTypes.RUN_START, AgentEventTypes.ERROR, AgentEventTypes.RUN_FINISH,
            AgentEventTypes.QUESTION_RAISED, AgentEventTypes.RUN_FAILED,
            AgentEventTypes.GUIDE_REPLY, AgentEventTypes.ACCEPTANCE_START,
            AgentEventTypes.PART_TEXT, AgentEventTypes.PART_SIGNAL, AgentEventTypes.PART_ACTION,
            AgentEventTypes.PART_CHECK, AgentEventTypes.PART_PLAN);

    /** 设计主线 PRD（设计物清单两件——推进序断言面）。 */
    private static final String DESIGN_PRD = """
            # 毛巾品牌设计需求

            ## 设计物清单

            1. 品牌主 logo——方形构图，扁平风格
            2. 电商海报——竖版，验收：主体清晰

            ## 待定项
            暂无
            """;

    @Autowired
    private DesignProcessAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CodingRunTrack codingRunTrack;

    @Autowired
    private ConversationHistoryAppService conversationHistory;

    @Autowired
    private FinishEditFacts finishFixFacts;

    @Autowired
    private BuildPlanFacts buildPlanFacts;

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

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_design_items");
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

    /** 延迟执行器（轨道提交不即跑——在途窗口的排队用例形态）：手动 run 捕获件。 */
    private List<Runnable> givenSessionExecutorDefers() {
        List<Runnable> submitted = new ArrayList<>();
        doAnswer(invocation -> {
            submitted.add(invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());
        return submitted;
    }

    /** PRD 读取桩（清单源＝工作区 PRD 正本）。 */
    private void givenPrdContent(String prdMarkdown) {
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse(prdMarkdown, "", 0));
    }

    /**
     * 按命令分岔的工作区桩（定稿用例形态）：PRD 读取（cat）回 markdown、git 成版
     * 提交回固定 hash、其余（存在性检查 / git init / AGENTS.md 写入 / 8081 探活）
     * 成功零输出。
     */
    private void givenWorkspaceCommands(String prdMarkdown) {
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.<WorkspaceExecCommand>getArgument(1).command();
                    if (command.startsWith("cat ")) {
                        return new ExecResultResponse(prdMarkdown, "", 0);
                    }
                    if (command.contains("git commit")) {
                        return new ExecResultResponse("abc123def456\n", "", 0);
                    }
                    return new ExecResultResponse("", "", 0);
                });
    }

    /** 脚本化智能体边界（定稿后续分岔用）：designer 会话落稿、coder 更新 run 以
     * finish_edit 收口（changed=true）、主智能体补产轮产出切片计划（saveBuildPlan
     * 事实登记——计划补产链的脚本侧）。 */
    private void givenScriptedDesignAndFixAndPlanSessions(String draftPath) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command == null) {
                return null;
            }
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            if (command.sessionId().startsWith("coder-")) {
                finishFixFacts.record(command.workspaceId(), true, "已按定稿稿对齐");
                return new AgentReply(command.runId(), "已对齐", null, List.of());
            }
            if (command.sessionId().startsWith("main-")
                    && command.prompt().contains("还没有切片计划")) {
                buildPlanFacts.record(command.workspaceId(),
                        new BuildPlan(List.of("用户能使用全部功能")));
            }
            sink.accept(AgentEventScripts.scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(AgentEventScripts.scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "已出稿", null,
                    List.of(new FileChange(draftPath, 60, 0)));
        });
    }

    /** 脚本化设计会话（run-start + 写稿动作 + run-finish 经 sink 吐出，携 design/ 落稿）。
     * 同测重打桩时 when() 的空参调用会触发旧 answer——null 直接过（MainAgentAppServiceTest
     * 先例）。 */
    private void givenScriptedDesignSession(FileChange... changes) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command == null) {
                return null;
            }
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(AgentEventScripts.scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(AgentEventScripts.scripted(AgentEventTypes.PART_ACTION, command.runId(),
                    Map.of(AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "write_file")));
            sink.accept(AgentEventScripts.scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "已出 3 稿", null, List.of(changes));
        });
    }

    private Long persistedDesignProject(ProjectEndpointType endpointType, DesignScope scope) {
        Project project = Project.create("设计项目", null, 971900L, OWNER);
        project.switchEndpoint(endpointType, scope);
        project.markPrdProduced();
        return projectRepository.save(project).getId();
    }

    /** 轨道件行读口（ord / title / status（1=待跑 2=已收口 3=失败）/ run_id）。 */
    private List<Map<String, Object>> itemRows(Long projectId) {
        return jdbcTemplate.queryForList(
                "SELECT ord, title, status, run_id FROM prj_design_items"
                        + " WHERE project_id = ? ORDER BY ord",
                projectId);
    }

    // ---------- 首产编排：PRD 收口自动起设计、每设计物一会话、按清单序逐件 ----------

    @Test
    void given_design_prd_when_dispatch_then_one_session_per_item_in_checklist_order() {
        // 灵魂用例（#289 验收①②）：设计主线 PRD 收口 → 自动起设计（无确认门）；
        // 每设计物一个设计会话（designer-{projectId}-item-{ord}）、按清单序逐件串行；
        // designer 座命令全要素——agentKey=designer（run-start.agent 扩值的编发侧）、
        // 无 shell 工作区标记（ProjectDesign 面）、计量 dims agentKind=designer、
        // heading＝设计物标题＋进度（直播卡计划区的编发侧）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));

        DesignProcessAppService.DesignRun run = appService.dispatchDesignOnTurnClose(projectId, null);

        assertThat(run).isNotNull();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        assertThat(all).extracting(AgentCommand::sessionId).containsExactly(
                DesignProcessAppService.itemSession(projectId, 1),
                DesignProcessAppService.itemSession(projectId, 2));
        assertThat(all).extracting(AgentCommand::agentKey)
                .containsOnly(AgentProfile.DESIGNER.key());
        assertThat(all).extracting(AgentCommand::workspaceNoShell).containsOnly(true);
        assertThat(all).extracting(AgentCommand::workspaceReadOnly).containsOnly(false);
        assertThat(all).extracting(AgentCommand::heading).containsExactly(
                RunHeading.slice("品牌主 logo——方形构图，扁平风格", 1, 2),
                RunHeading.slice("电商海报——竖版，验收：主体清晰", 2, 2));
        assertThat(all).extracting(AgentCommand::usageContext)
                .allSatisfy(usage -> assertThat(usage.dims())
                        .containsEntry(UsageDims.KEY_AGENT_KIND, UsageDims.AGENT_KIND_DESIGNER));
        // 任务 prompt：清单条目锚＋PRD 重读＋多稿纪律＋稿落 design/；设计轨不做知识
        // 命中注入（注入面＝主智能体会话与生成轨，清单源即 PRD）
        assertThat(all.get(0).prompt())
                .contains("品牌主 logo——方形构图，扁平风格")
                .contains("docs/PRD.md")
                .contains("3 稿")
                .contains("design/")
                .doesNotContain("平台知识库");
        // 轨道表：两件均已收口（脚本会话都落了稿）、按清单序
        assertThat(itemRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
    }

    @Test
    void given_scripted_sessions_when_close_then_closing_carries_drafts_and_history_records() {
        // #289 验收⑤⑦：收尾卡带稿清单与去向（closing.drafts——判定以平台可观察的
        // 文件变更事实为准：本场落进 design/ 的稿、media 按扩展名派生、path＝去向）；
        // PRD 未动、系统未动（设计稿不是系统）；设计候选不成版（无 version 键，
        // ADR-0025 候选与版本不打通）；对话史落库同载荷（写口唯一、回访完整）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent(DESIGN_PRD);
        givenScriptedDesignSession(
                new FileChange("/design/logo-1.html", 80, 0),
                new FileChange("/design/logo-2.html", 82, 0),
                new FileChange("/design/logo.png", 1, 0),
                new FileChange("/materials/ref.png", 10, 0));

        appService.dispatchDesignOnTurnClose(projectId, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing = (Map<String, Object>) payloads.getAllValues().get(0)
                .get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing)
                .containsEntry(CoderRunAttempts.CLOSING_SUMMARY_FIELD,
                        DesignProcessAppService.itemClosingSummary("品牌主 logo——方形构图，扁平风格"))
                .containsEntry("prdChanged", false)
                .containsEntry("systemChanged", false)
                .doesNotContainKey("prdNote")
                .doesNotContainKey(CoderRunAttempts.CLOSING_VERSION_FIELD);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> drafts =
                (List<Map<String, Object>>) closing.get(CoderRunAttempts.CLOSING_DRAFTS_FIELD);
        assertThat(drafts).containsExactly(
                Map.of("item", "品牌主 logo——方形构图，扁平风格", "media", "html",
                        "path", "/design/logo-1.html"),
                Map.of("item", "品牌主 logo——方形构图，扁平风格", "media", "html",
                        "path", "/design/logo-2.html"),
                Map.of("item", "品牌主 logo——方形构图，扁平风格", "media", "image",
                        "path", "/design/logo.png"));
        // 对话史回访完整：收尾卡按写入序落库、载荷同 SSE 扩载（水合重建的读面）
        assertThat(conversationHistory.read(projectId))
                .anySatisfy(entry -> {
                    assertThat(entry.kindName()).isEqualTo("收尾卡");
                    assertThat(entry.closing()).containsKey(
                            CoderRunAttempts.CLOSING_DRAFTS_FIELD);
                });
    }

    @Test
    void given_no_drafts_when_session_closes_then_retries_then_run_failed_no_next_item() {
        // 收口判据（converse 无异常不构成成功——本场 design/ 无新稿即判据未过）：
        // 重试续本件会话、超限转终态 run-failed、不自动跳下一件（完整性优先，同
        // 生成轨道）；件状态落失败
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/src/elsewhere.js", 10, 0)); // design/ 外不算稿

        appService.dispatchDesignOnTurnClose(projectId, null);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::sessionId)
                .containsOnly(DesignProcessAppService.itemSession(projectId, 1));
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                anyMap());
        assertThat(itemRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(3, 1); // 件 1 失败、件 2 未被自动推进
    }

    @Test
    void given_prd_revised_when_dispatch_again_then_order_follows_new_checklist_closed_kept() {
        // #289 验收②后半：改 PRD 清单序→推进序随改——PRD 演进（锚漂）即清单重产：
        // 推进序随新清单、已收口件按标题精确对照保留（不重做；措辞漂移即对照不上、
        // 按待跑重做——降级方向安全）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));
        appService.dispatchDesignOnTurnClose(projectId, null);

        // PRD 修订：清单重排＋新增一件（锚随 markPrdProduced 换新）
        String revised = """
                # 毛巾品牌设计需求

                ## 设计物清单

                1. 电商海报——竖版，验收：主体清晰
                2. 品牌主 logo——方形构图，扁平风格
                3. 包装盒展开图——横版

                ## 待定项
                暂无
                """;
        givenPrdContent(revised);
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markPrdProduced();
        projectRepository.save(project);
        clearInvocations(agentClient);

        appService.dispatchDesignOnTurnClose(projectId, null);

        // 已收口件（两件标题对照保留）不重做；待跑件＝新清单序的首件（海报——标题
        // 已收口即跳过）、新件照跑：本轨只跑「包装盒展开图」一件
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getValue().prompt()).contains("包装盒展开图");
        assertThat(commands.getValue().sessionId())
                .isEqualTo(DesignProcessAppService.itemSession(projectId, 3));
        assertThat(itemRows(projectId)).extracting(row -> row.get("title")).containsExactly(
                "电商海报——竖版，验收：主体清晰", "品牌主 logo——方形构图，扁平风格", "包装盒展开图——横版");
        assertThat(itemRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2, 2); // 对照保留的两件仍是已收口
    }

    // ---------- 系统＋设计：设计先行、清单＝功能清单按范围圈定 ----------

    @Test
    void given_system_design_selected_scope_when_dispatch_then_feature_items_anchored() {
        // ADR-0025 设计先行：系统＋设计 PRD 收口同律自动起设计；清单＝功能清单按
        // 设计范围圈定（勾选形标签精确匹配）；任务 prompt 携系统＋设计语境句
        Long projectId = persistedDesignProject(ProjectEndpointType.SYSTEM_DESIGN,
                DesignScope.selected(List.of("用户能浏览商品下单")));
        givenSessionExecutorRunsInline();
        givenPrdContent("""
                # 门店系统 PRD

                ## 功能清单

                1. 用户能注册登录
                2. 用户能浏览商品下单

                ## 待定项
                暂无
                """);
        givenScriptedDesignSession(new FileChange("/design/home-1.html", 50, 0));

        appService.dispatchDesignOnTurnClose(projectId, null);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getValue().prompt())
                .contains("用户能浏览商品下单")
                .contains("系统＋设计")
                .doesNotContain("用户能注册登录");
    }

    // ---------- 触发守卫与在途口径 ----------

    @Test
    void given_design_in_flight_when_dispatch_again_then_silent_skip() {
        // 设计在途的新派发静默跳过（在途插话排队归 #291；同生成轨道收口自动口径）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenPrdContent(DESIGN_PRD);
        assertThat(codingRunTrack.begin(projectId)).isTrue();

        assertThat(appService.dispatchDesignOnTurnClose(projectId, null)).isNull();

        verify(agentClient, never()).converse(any(), any());
        codingRunTrack.end(projectId);
    }

    @Test
    void given_prd_without_checklist_when_dispatch_then_no_run_no_fake_track() {
        // PRD 形态偏离（无清单章/无编号条目）：如实不派（不造假清单）、在途标记
        // 释放（重提意见即兜底）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent("# 设计需求\n\n## 需求背景\n尚无清单\n");

        assertThat(appService.dispatchDesignOnTurnClose(projectId, null)).isNull();

        verify(agentClient, never()).converse(any(), any());
        assertThat(itemRows(projectId)).isEmpty();
        assertThat(codingRunTrack.isInFlight(projectId)).isFalse();
    }

    @Test
    void given_generated_system_endpoint_when_dispatch_then_rejected() {
        // 系统终点已生成不可达设计轨（路由层已分岔——防御位；设计类终点的已生成
        // 承接是 #291 中途切换路径，另有用例钉）
        Long projectId = persistedDesignProject(ProjectEndpointType.SYSTEM, null);
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markGenerated();
        projectRepository.save(project);

        assertThatThrownBy(() -> appService.dispatchDesignOnTurnClose(projectId, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
    }

    @Test
    void given_generated_design_endpoint_when_dispatch_then_design_track_runs() {
        // #291 系统中途切换的设计轨承接：已生成＋设计类终点（设置 tab 切换终点后的
        // 设计意见轮收口）——设计照起（清单＝功能清单按范围圈定，requireDesignableProject
        // 放行），系统迭代让位设计先行
        Long projectId = persistedDesignProject(ProjectEndpointType.SYSTEM_DESIGN, null);
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markGenerated();
        projectRepository.save(project);
        givenSessionExecutorRunsInline();
        givenPrdContent("""
                # 门店系统 PRD

                ## 功能清单

                1. 用户能注册登录
                2. 用户能浏览商品下单

                ## 待定项
                暂无
                """);
        givenScriptedDesignSession(new FileChange("/design/home-1.html", 50, 0));

        DesignProcessAppService.DesignRun run = appService.dispatchDesignOnTurnClose(projectId, null);

        assertThat(run).isNotNull();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(AgentCommand::sessionId).containsExactly(
                DesignProcessAppService.itemSession(projectId, 1),
                DesignProcessAppService.itemSession(projectId, 2));
    }

    // ---------- #291 改稿：同会话继续、产新代、旧代全保留 ----------

    @Test
    void given_all_items_closed_when_opinion_dispatched_then_revision_continues_same_session() {
        // 灵魂用例（#291 验收①）：清单全部收口后的意见即改稿——同件设计会话继续
        //（审美上下文连续，designer-{projectId}-item-{ord} 不换）、prompt 携意见原文
        // ＋新代纪律（新一代候选、文件名不重、旧稿不改写不覆盖——旧代全保留、横向
        // 分代可辨）；件状态不翻（首产收口位保持）；收尾卡＝改稿叙事＋本轮稿清单
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));
        appService.dispatchDesignOnTurnClose(projectId, null);

        clearInvocations(agentClient, eventsAppService);
        givenScriptedDesignSession(new FileChange("/design/logo-4.html", 90, 0));

        DesignProcessAppService.DesignRun run =
                appService.dispatchDesignOnTurnClose(projectId, "刚才那稿的颜色再亮一点");

        assertThat(run).isNotNull();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        AgentCommand revision = commands.getValue();
        // 改稿同会话：目标＝最近活跃件（清单末件刚收口）
        assertThat(revision.sessionId()).isEqualTo(DesignProcessAppService.itemSession(projectId, 2));
        assertThat(revision.agentKey()).isEqualTo(AgentProfile.DESIGNER.key());
        assertThat(revision.prompt())
                .contains("刚才那稿的颜色再亮一点")
                .contains("新一代候选")
                .contains("不改写不覆盖")
                .contains("design/");
        // 收尾卡：改稿叙事＋本轮（新代）稿清单——旧代不在本场变更里、不在清单里
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing = (Map<String, Object>) payloads.getValue()
                .get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).containsEntry(CoderRunAttempts.CLOSING_SUMMARY_FIELD,
                DesignProcessAppService.revisionClosingSummary("电商海报——竖版，验收：主体清晰"));
        assertThat(closing.toString()).contains("/design/logo-4.html");
        assertThat(closing.toString()).doesNotContain("/design/logo-1.html");
        assertThat(closing).doesNotContainKey(CoderRunAttempts.CLOSING_VERSION_FIELD);
        // 件状态不翻：两件仍是已收口（改稿是探索、不是首产收口位变化）
        assertThat(itemRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
    }

    @Test
    void given_revision_without_new_drafts_when_session_closes_then_zero_drafts_close_cleanly() {
        // 改稿收口判据＝对话收口（relaxed）：意见不涉改稿（确认/提问）也是合法收口
        //——drafts 如实空清单（「本轮 0 稿」）、不重试不 run-failed（对偶修正
        // finish_edit(changed=false)），件状态保持
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));
        appService.dispatchDesignOnTurnClose(projectId, null);

        clearInvocations(agentClient, eventsAppService);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command == null) {
                return null;
            }
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(AgentEventScripts.scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(AgentEventScripts.scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "好的，第 2 稿方向已定，随时可定稿", null, List.of());
        });

        appService.dispatchDesignOnTurnClose(projectId, "第2稿不错，就它了");

        verify(agentClient, times(1)).converse(any(), any()); // 无稿也一次收口——不重试
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED),
                anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        Map<String, Object> closing = (Map<String, Object>) payloads.getValue()
                .get(AgentEventTypes.CLOSING_FIELD);
        assertThat((List<?>) closing.get(CoderRunAttempts.CLOSING_DRAFTS_FIELD)).isEmpty();
        assertThat(itemRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(2, 2);
    }

    @Test
    void given_track_in_flight_when_opinion_arrives_then_queued_and_consumed_after_current_item() {
        // 灵魂用例（#291 验收② 在途插话）：设计轨在途的新意见排队（返回 null 不误报
        // 派发、不打断在途 run）、当前稿代收口后受理——无作用域意见派向刚收口件，
        // 随后轨道继续推进剩余件（受理不夺轨）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        List<Runnable> submitted = givenSessionExecutorDefers();
        givenPrdContent(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));

        assertThat(appService.dispatchDesignOnTurnClose(projectId, null)).isNotNull();
        // 在途窗口（轨道已提交未跑完）插话：排队、返回 null
        assertThat(appService.dispatchDesignOnTurnClose(projectId, "海报的红色调亮"))
                .isNull();

        submitted.get(0).run(); // 轨道执行：件 1 首产收口 → 排队意见受理（改稿件 1）→ 件 2 首产

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        List<AgentCommand> all = commands.getAllValues();
        assertThat(all).extracting(AgentCommand::sessionId).containsExactly(
                DesignProcessAppService.itemSession(projectId, 1),
                DesignProcessAppService.itemSession(projectId, 1), // 改稿同会话继续
                DesignProcessAppService.itemSession(projectId, 2));
        assertThat(all.get(1).prompt())
                .contains("海报的红色调亮")
                .contains("新一代候选");
        assertThat(codingRunTrack.isInFlight(projectId)).isFalse();
    }

    @Test
    void given_failed_track_with_queued_opinion_when_redispatched_then_stale_opinion_consumed() {
        // 排队不丢失（#291 验收② 后半）：失败收场不排空队列——残队随下次派发在
        // 首件成功收口后合并受理（重提意见续跑的既有口径之上，排队意见不蒸发）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        List<Runnable> submitted = givenSessionExecutorDefers();
        givenPrdContent("""
                # 毛巾品牌设计需求

                ## 设计物清单

                1. 品牌主 logo——方形构图，扁平风格

                ## 待定项
                暂无
                """);
        givenScriptedDesignSession(new FileChange("/src/elsewhere.js", 10, 0)); // 无稿 → 判据未过

        assertThat(appService.dispatchDesignOnTurnClose(projectId, null)).isNotNull();
        assertThat(appService.dispatchDesignOnTurnClose(projectId, "logo 圆角再柔和些"))
                .isNull(); // 在途排队
        submitted.get(0).run(); // 轨道：件 1 三次尝试无稿 → run-failed，残队不排空
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());
        assertThat(itemRows(projectId)).extracting(row -> row.get("status"))
                .containsExactly(3);

        clearInvocations(agentClient, eventsAppService);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));

        // 用户重提续跑：失败件重做 → 收口后排空残队（「logo 圆角再柔和些」不丢失）
        assertThat(appService.dispatchDesignOnTurnClose(projectId, "继续")).isNotNull();
        submitted.get(1).run(); // 延迟执行器形态：重派轨道手动执行

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(1).prompt())
                .contains("logo 圆角再柔和些");
        assertThat(commands.getAllValues().get(1).sessionId())
                .isEqualTo(DesignProcessAppService.itemSession(projectId, 1));
    }

    @Test
    void given_scoped_revision_when_dispatched_then_direct_to_item_session() {
        // 跨件回溯的会话路由能力（#291——画布点选随到、画布侧接线归 #294）：作用域
        // 改稿直达任意件会话（不派最近活跃件）；在途即排队（目标件锚定）、轨道
        // 空闲后的派发排空受理
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));
        appService.dispatchDesignOnTurnClose(projectId, null); // 两件收口，最近活跃＝件 2

        clearInvocations(agentClient);
        givenScriptedDesignSession(new FileChange("/design/logo-5.html", 70, 0));

        // 空闲直达：作用域指件 1（跨件回溯——不是最近活跃的件 2）
        DesignProcessAppService.DesignRun run =
                appService.reviseDesignItem(projectId, 1, "跨件回溯：logo 重出一版");
        assertThat(run).isNotNull();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getValue().sessionId())
                .isEqualTo(DesignProcessAppService.itemSession(projectId, 1));
        assertThat(commands.getValue().prompt()).contains("跨件回溯：logo 重出一版");

        // 在途排队：手工占位轨道 → 作用域改稿排队（null）→ 释放后随派发受理
        clearInvocations(agentClient);
        assertThat(codingRunTrack.begin(projectId)).isTrue();
        assertThat(appService.reviseDesignItem(projectId, 2, "海报文字加大"))
                .isNull();
        codingRunTrack.end(projectId);
        givenScriptedDesignSession(new FileChange("/design/poster-2.html", 70, 0));

        appService.dispatchDesignOnTurnClose(projectId, null);

        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getValue().sessionId())
                .isEqualTo(DesignProcessAppService.itemSession(projectId, 2));
        assertThat(commands.getValue().prompt()).contains("海报文字加大");
    }

    @Test
    void given_finalized_item_when_prd_revised_then_checklist_reproduction_keeps_finalization() {
        // 定稿锚续承（#291 修正 #289 对照保留只保已收口）：清单重产（PRD 演进锚漂）
        // 时已定稿件按标题对照保留——状态、定稿锚与选定稿路径续承（定稿不因 PRD
        // 演进蒸发重做），已收口件照旧保留，只跑待跑/新件
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenWorkspaceCommands(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));
        appService.dispatchDesignOnTurnClose(projectId, null);
        DesignProcessAppService.DesignFinalization finalization =
                appService.finalizeDesignItem(projectId, 1, "/design/logo-1.html");

        String revised = """
                # 毛巾品牌设计需求

                ## 设计物清单

                1. 品牌主 logo——方形构图，扁平风格
                2. 电商海报——竖版，验收：主体清晰
                3. 包装盒展开图——横版

                ## 待定项
                暂无
                """;
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markPrdProduced();
        projectRepository.save(project);
        givenPrdContent(revised);
        clearInvocations(agentClient);

        appService.dispatchDesignOnTurnClose(projectId, null);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ord, status, run_id, finalized_path FROM prj_design_items"
                        + " WHERE project_id = ? ORDER BY ord", projectId);
        assertThat(rows.get(0))
                .containsEntry("status", 4)
                .containsEntry("run_id", finalization.runId())
                .containsEntry("finalized_path", "/design/logo-1.html");
        assertThat(rows.get(1)).containsEntry("status", 2);
        assertThat(rows.get(2)).containsEntry("status", 2); // 新件本轨跑完收口
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        assertThat(commands.getValue().sessionId())
                .isEqualTo(DesignProcessAppService.itemSession(projectId, 3));
    }

    // ---------- #291 定稿：git commit 成版＋收尾卡＋后续三分岔 ----------

    @Test
    void given_closed_item_when_finalized_then_version_committed_and_closing_card_anchored() {
        // 灵魂用例（#291 验收③）：定稿＝显式动作收口——git commit 成版（Run-Id
        // trailer 对偶锚定定稿收尾卡）＋件状态转已定稿（finalized_path 记选定稿）
        //＋定稿收尾卡入对话流（run-finish 载 closing：version 锚＋稿清单单条带
        // triggers）＋对话史落库同载荷（「查看当时」经版本详情联接本卡）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenWorkspaceCommands(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));
        appService.dispatchDesignOnTurnClose(projectId, null);

        clearInvocations(eventsAppService);
        DesignProcessAppService.DesignFinalization finalization =
                appService.finalizeDesignItem(projectId, 1, "/design/logo-1.html");

        assertThat(finalization.runId()).isNotBlank();
        assertThat(finalization.versionHash()).isEqualTo("abc123def456");
        // 成版提交携 Run-Id trailer 对偶锚定收尾卡（版本正本＝git log 的联接键）
        ArgumentCaptor<WorkspaceExecCommand> execCommands =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeastOnce()).exec(any(), execCommands.capture());
        assertThat(execCommands.getAllValues().stream()
                .map(WorkspaceExecCommand::command))
                .anySatisfy(command -> assertThat(command)
                        .contains("git commit")
                        .contains("Run-Id: " + finalization.runId())
                        .contains(DesignProcessAppService.finalizeClosingSummary(
                                "品牌主 logo——方形构图，扁平风格")));
        // 件行：已定稿＋选定稿路径＋run 锚＝定稿锚
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ord, status, run_id, finalized_path FROM prj_design_items"
                        + " WHERE project_id = ? ORDER BY ord", projectId);
        assertThat(rows.get(0)).containsEntry("status", 4)
                .containsEntry("finalized_path", "/design/logo-1.html")
                .containsEntry("run_id", finalization.runId());
        assertThat(rows.get(1)).containsEntry("status", 2); // 未定稿件不受影响
        // 定稿收尾卡：run-finish 载 closing 直达对话流（判定行恒未动、version 锚、
        // 稿清单单条、triggers 空清单如实）——事件封闭集零新增
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        assertThat(payloads.getValue())
                .containsEntry(EventsAppService.RUN_FIELD, finalization.runId());
        Map<String, Object> closing = (Map<String, Object>) payloads.getValue()
                .get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing)
                .containsEntry(CoderRunAttempts.CLOSING_SUMMARY_FIELD,
                        DesignProcessAppService.finalizeClosingSummary(
                                "品牌主 logo——方形构图，扁平风格"))
                .containsEntry("prdChanged", false)
                .containsEntry("systemChanged", false)
                .containsEntry(CoderRunAttempts.CLOSING_VERSION_FIELD, "abc123def456");
        assertThat(closing.toString())
                .contains("/design/logo-1.html")
                .contains("triggers=[]");
        // 对话史回访完整（版本详情经 Run-Id 联接本卡——「查看当时」可见定稿稿）
        assertThat(conversationHistory.read(projectId))
                .anySatisfy(entry -> {
                    assertThat(entry.kindName()).isEqualTo("收尾卡");
                    assertThat(entry.closing()).containsEntry(
                            CoderRunAttempts.CLOSING_VERSION_FIELD, "abc123def456");
                });
    }

    @Test
    void given_design_endpoint_when_all_finalized_then_order_open_triggered() {
        // #291 验收④第三岔：设计主线全部定稿——开放下单位（trigger 事实入收尾卡；
        // 确认下单可见性随全部定稿，下单面归交易票）；未全部定稿不触发
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenWorkspaceCommands(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));
        appService.dispatchDesignOnTurnClose(projectId, null);
        clearInvocations(eventsAppService);

        DesignProcessAppService.DesignFinalization first =
                appService.finalizeDesignItem(projectId, 1, "/design/logo-1.html"); // 首件：未全部定稿
        appService.finalizeDesignItem(projectId, 2, "/design/logo-1.html"); // 末件：全部定稿

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        assertThat(payloads.getAllValues().get(0))
                .containsEntry(EventsAppService.RUN_FIELD, first.runId());
        assertThat(payloads.getAllValues().get(0).get(AgentEventTypes.CLOSING_FIELD).toString())
                .contains("triggers=[]")
                .doesNotContain(DesignProcessAppService.TRIGGER_ORDER_OPEN);
        assertThat(payloads.getAllValues().get(1).get(AgentEventTypes.CLOSING_FIELD).toString())
                .contains(DesignProcessAppService.TRIGGER_ORDER_OPEN);
    }

    @Test
    void given_generated_system_design_when_finalized_then_fix_run_with_draft_reference() {
        // #291 验收④第一岔：系统在途（已生成＋系统＋设计＝中途切换）——定稿自动起
        // 更新 run 按稿对齐：交接物携定稿稿引用（CONTEXT「交接物」）、执行体座
        //（executor）、trigger 事实入收尾卡
        Long projectId = persistedDesignProject(ProjectEndpointType.SYSTEM_DESIGN, null);
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markGenerated();
        projectRepository.save(project);
        givenSessionExecutorRunsInline();
        givenWorkspaceCommands("""
                # 门店系统 PRD

                ## 功能清单

                1. 用户能注册登录
                2. 用户能浏览商品下单

                ## 待定项
                暂无
                """);
        givenScriptedDesignAndFixAndPlanSessions("/design/home-1.html");
        appService.dispatchDesignOnTurnClose(projectId, null);

        clearInvocations(agentClient);
        DesignProcessAppService.DesignFinalization finalization =
                appService.finalizeDesignItem(projectId, 1, "/design/home-1.html");

        assertThat(finalization).isNotNull();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any());
        AgentCommand fixRun = commands.getValue();
        assertThat(fixRun.sessionId()).isEqualTo(
                IterationAppService.fixSession(projectId, fixRun.runId()));
        assertThat(fixRun.agentKey()).isEqualTo(AgentProfile.EXECUTOR.key());
        assertThat(fixRun.prompt())
                .contains("/design/home-1.html")
                .contains("按稿对齐");
    }

    @Test
    void given_system_design_not_generated_when_all_finalized_then_first_build_with_drafts() {
        // 灵魂用例（#291 验收④第二岔＋验收⑤）：系统＋设计未生成、全部定稿——定稿
        // 自动起首个构建（经计划补产链自然恢复：主智能体补产计划收口→生成派发对
        // 全部定稿放行），起跑上下文携定稿稿引用块（稿入起跑上下文——执行体先读
        // 稿再动手）；转系统开发同链（PRD 重写系统形后同序，归 MainAgentAppService
        // 终点切换既有链）
        Long projectId = persistedDesignProject(ProjectEndpointType.SYSTEM_DESIGN, null);
        givenSessionExecutorRunsInline();
        givenWorkspaceCommands("""
                # 门店系统 PRD

                ## 功能清单

                1. 用户能注册登录

                ## 待定项
                暂无
                """);
        givenScriptedDesignAndFixAndPlanSessions("/design/home-1.html");
        appService.dispatchDesignOnTurnClose(projectId, null); // 单件收口

        clearInvocations(agentClient, eventsAppService);
        DesignProcessAppService.DesignFinalization finalization =
                appService.finalizeDesignItem(projectId, 1, "/design/home-1.html");

        assertThat(finalization).isNotNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, atLeast(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payloads.capture());
        assertThat(payloads.getAllValues().stream()
                .filter(payload -> finalization.runId()
                        .equals(payload.get(EventsAppService.RUN_FIELD)))
                .findFirst().orElseThrow()
                .get(AgentEventTypes.CLOSING_FIELD).toString())
                .contains(DesignProcessAppService.TRIGGER_BUILD_STARTED);
        // 计划补产链（主智能体轮）→ 生成轨道首 run（阶段 0）——prompt 前置定稿稿
        // 引用块（设计先行：构建从定稿设计稿长出）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(2)).converse(commands.capture(), any());
        AgentCommand stage0 = commands.getAllValues().stream()
                .filter(command -> command.sessionId()
                        .equals(GenerationAppService.sliceSession(projectId, 0)))
                .findFirst().orElseThrow();
        assertThat(stage0.agentKey()).isEqualTo(AgentProfile.EXECUTOR.key());
        assertThat(stage0.prompt())
                .contains("已定稿的设计稿")
                .contains("/design/home-1.html")
                .contains(GenerationAppService.STAGE0_RUN_PROMPT);
        assertThat(projectRepository.findById(projectId).orElseThrow().getGeneratedAt())
                .isNotNull();
    }

    @Test
    void given_track_in_flight_or_missing_draft_when_finalize_then_rejected() {
        // 定稿守卫（#291）：设计/更新轨在途（成版全量提交会卷入在途改动）409
        // PRJ_046；件不存在 404 PRJ_047；件未产出稿 409 PRJ_048；选定的稿不在
        // 工作区 404 PRJ_049（候选可被悬卡删除——定稿对象以容器事实为准）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenWorkspaceCommands(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));
        appService.dispatchDesignOnTurnClose(projectId, null);

        assertThat(codingRunTrack.begin(projectId)).isTrue();
        assertThatThrownBy(() -> appService.finalizeDesignItem(projectId, 1, "/design/logo-1.html"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.DESIGN_FINALIZE_IN_FLIGHT.message());
        codingRunTrack.end(projectId);

        assertThatThrownBy(() -> appService.finalizeDesignItem(projectId, 9, "/design/logo-1.html"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.DESIGN_ITEM_NOT_FOUND.message());

        assertThatThrownBy(() -> appService.finalizeDesignItem(projectId, 1, "/outside/evil.html"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FILE_PATH_INVALID.message());

        // 稿不存在：存在性检查退出码非 0（悬卡删除形态）
        when(workspaceLifecycleAppService.exec(any(),
                argThat(command -> command != null && command.command().contains("test -f"))))
                .thenReturn(new ExecResultResponse("", "not found", 1));
        assertThatThrownBy(() -> appService.finalizeDesignItem(projectId, 1, "/design/gone.html"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.DESIGN_DRAFT_NOT_FOUND.message());

        // 件未产出稿：待跑件（清单重产的初始态——无稿可选）不可定稿
        jdbcTemplate.update("UPDATE prj_design_items SET status = 1 WHERE project_id = ?",
                projectId);
        assertThatThrownBy(() -> appService.finalizeDesignItem(projectId, 1, "/design/logo-1.html"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.DESIGN_ITEM_NOT_READY.message());
    }

    // ---------- designer 座运营配置（三座齐） ----------

    @Test
    void given_designer_config_override_when_dispatch_then_command_uses_override() {
        // #289 验收③：designer 座可经智能体运营配置覆盖提示词与档位（库值优先、
        // 缺省回落枚举默认——ADR-0021 解析单点对三座通用）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent(DESIGN_PRD);
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));
        jdbcTemplate.update(
                "INSERT INTO prj_agent_configs (agent_key, system_prompt, model_id) VALUES (?, ?, ?)",
                "designer", "运营覆盖的设计协议", "deepseek-v4-flash");

        appService.dispatchDesignOnTurnClose(projectId, null);

        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(0).systemPrompt()).isEqualTo("运营覆盖的设计协议");
        assertThat(commands.getAllValues().get(0).modelString())
                .isEqualTo(AgentProfile.chatModelStringOf("deepseek-v4-flash"));
    }

    // ---------- 事件封闭集零新增（一等断言：出现什么/不出现什么） ----------

    @Test
    void given_scripted_design_track_when_events_published_then_closed_set_only() {
        // #289 验收⑥：SSE 事件封闭集零新增——设计轨道全流程（含收口判据未过的重试
        // 与终态）发射的顶层事件类型 ⊆ 平台封闭集（生命周期注册制 + part-* 契约）；
        // 不出现：任何新顶层 type（治理：eventhub 唯一管道、新增必须先进正本）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        givenSessionExecutorRunsInline();
        givenPrdContent(DESIGN_PRD);
        List<String> published = new ArrayList<>();
        doAnswer(invocation -> {
            published.add(invocation.getArgument(0, String.class));
            return null;
        }).when(eventsAppService).publishAgentEvent(anyString(), anyMap());
        givenScriptedDesignSession(new FileChange("/design/logo-1.html", 80, 0));

        appService.dispatchDesignOnTurnClose(projectId, null);

        assertThat(published).isNotEmpty();
        assertThat(published).allSatisfy(type -> assertThat(CLOSED_EVENT_TYPES)
                .as("设计轨道事件 %s 不在平台封闭集内", type).contains(type));
        // 出现什么：两件设计会话各一场收口（run-finish 携 closing 扩载）
        assertThat(published.stream()
                .filter(AgentEventTypes.RUN_FINISH::equals).count()).isEqualTo(2);
        assertThat(published.stream().collect(Collectors.toSet()))
                .contains(AgentEventTypes.RUN_START, AgentEventTypes.PART_CHECK);
        // 不出现什么：无失败终态、无 error（脚本会话全收口）、无 question-raised
        assertThat(published).doesNotContain(AgentEventTypes.RUN_FAILED,
                AgentEventTypes.ERROR, AgentEventTypes.QUESTION_RAISED);
    }
}
