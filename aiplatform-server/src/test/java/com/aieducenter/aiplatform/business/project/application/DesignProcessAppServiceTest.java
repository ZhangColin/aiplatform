package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * 设计过程编排（#289 验收缝——SSE 事件序列「出现什么/不出现什么」是一等断言，
 * spec Testing Decisions）：设计类终点 PRD 收口自动起设计（轨内无门）、按清单序
 * 逐件串行、每设计物一个设计会话（designer-{projectId}-item-{ord}）；改 PRD
 * 清单序→推进序随改（已收口件对照保留不重做）；designer 座命令全要素（agentKey/
 * 无 shell 工作区标记/计量 dims/设计物标题 heading/运营配置覆盖）；收口判据＝
 * design/ 落稿（无稿重试、超限 run-failed 不跳下一件）；收尾卡扩载稿清单与去向、
 * 对话史落库同载荷；事件封闭集零新增。
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

    /** PRD 读取桩（清单源＝工作区 PRD 正本）。 */
    private void givenPrdContent(String prdMarkdown) {
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse(prdMarkdown, "", 0));
    }

    /** 脚本化设计会话（run-start + 写稿动作 + run-finish 经 sink 吐出，携 design/ 落稿）。 */
    private void givenScriptedDesignSession(FileChange... changes) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
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

        DesignProcessAppService.DesignRun run = appService.dispatchDesignOnTurnClose(projectId);

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

        appService.dispatchDesignOnTurnClose(projectId);

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

        appService.dispatchDesignOnTurnClose(projectId);

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
        appService.dispatchDesignOnTurnClose(projectId);

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

        appService.dispatchDesignOnTurnClose(projectId);

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

        appService.dispatchDesignOnTurnClose(projectId);

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

        assertThat(appService.dispatchDesignOnTurnClose(projectId)).isNull();

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

        assertThat(appService.dispatchDesignOnTurnClose(projectId)).isNull();

        verify(agentClient, never()).converse(any(), any());
        assertThat(itemRows(projectId)).isEmpty();
        assertThat(codingRunTrack.isInFlight(projectId)).isFalse();
    }

    @Test
    void given_generated_project_when_dispatch_then_rejected() {
        // 已生成项目不走设计轨（系统中途切换的设计过程启动接线归后续票——防御位）
        Long projectId = persistedDesignProject(ProjectEndpointType.DESIGN, null);
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markGenerated();
        projectRepository.save(project);

        assertThatThrownBy(() -> appService.dispatchDesignOnTurnClose(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
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

        appService.dispatchDesignOnTurnClose(projectId);

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

        appService.dispatchDesignOnTurnClose(projectId);

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
