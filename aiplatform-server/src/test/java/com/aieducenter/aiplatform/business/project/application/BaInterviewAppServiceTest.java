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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import cn.hutool.core.collection.CollUtil;

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
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * BA 访谈编排：对话命令的会话寻址（projectId → ba-{projectId} 稳定绑定，每轮
 * 都续此会话）、计量归属（role=BA 维度）、role-assigned 帧序（engine=agentscope）、
 * 会话建立轮的知识命中注入（尾部 + 失败降级）、归档守卫、问答答复续跑（挂起帧
 * 载荷 + 答复 → resume 从项目侧事实重建恢复私货）；链必达收口（#43）——BA 回合
 * 收口（无挂起问答）且项目已生成时平台自动派修正 run（模型不调派发工具也必达；
 * 未生成止于 BA；在途排队合并；追问挂起待答复收口再派）；交接物补齐（#52）——
 * savePrd 的 summary 终值与「PRD 未修订」口径入修正 run prompt，本轮判定从零
 * 起算（上轮残留不进）。
 */
@SpringBootTest
class BaInterviewAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private BaInterviewAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FinishFixFacts finishFixFacts;

    @Autowired
    private PrdRevisionFacts prdRevisions;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private AgentStreamAppService streamAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private KnowledgePort knowledgePort;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
    }

    /** 脚本化智能体边界（#46 收口以 finish_fix 事实为准）：BA 正常回复；自动派发
     * 的修正 run 收口即调 finish_fix（changed=true——coder 会话才记，忠实于工具面）。 */
    private void givenConverseBaRepliesAndCoderFinishes(String baReply) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("coder-")) {
                finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            }
            return new AgentReply(command.runId(), baReply);
        });
    }

    /** 脚本化智能体边界（#52 修订事实观测）：BA 轮内调 savePrd(content, summary)
     * 的工具执行事实（真引擎内工具执行在此 mock 收口——登记即调用事实），一轮
     * 多次调用按序落多条（终值胜出）；修正 run 收口脚本沿用。 */
    private void givenConverseBaSavesPrdAndCoderFinishes(String... summaries) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("ba-")) {
                for (String summary : summaries) {
                    prdRevisions.record(command.workspaceId(), summary);
                }
                return new AgentReply(command.runId(), "已按意见修订 PRD");
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });
    }

    /** 已生成形态的项目（迭代期收口派修正的前提事实）。 */
    private Long persistedGeneratedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("迭代访谈项目", null,
                Long.parseLong(workspaceId), OWNER));
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    @Test
    void given_project_when_interview_turn_then_command_bound_to_ba_session() {
        Long projectId = persistedProject("9700");
        givenSessionExecutorRunsInline();

        BaInterviewAppService.InterviewRun run = appService.runInterviewTurn(projectId, "做一个官网");

        // 对话命令全要素：BA 会话稳定绑定 + owner 寻址 + 角色卡模型/人格 + 计量归属 + 项目工作区 + 流关联
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        AgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo("做一个官网");
        assertThat(value.sessionId()).isEqualTo("ba-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).contains("ask_user");
        assertThat(value.modelString()).isEqualTo(RolePreset.BA.chatModelString());
        assertThat(value.workspaceId()).isEqualTo("9700");
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).isEqualTo(
                UsageDims.of(projectId, UsageDims.kindOf(RolePreset.BA), "ba-" + projectId));
        // 后续轮不重注知识：systemPrompt 即角色卡原文
        assertThat(value.systemPrompt()).isEqualTo(RolePreset.BA.systemPrompt());
    }

    @Test
    void given_knowledge_hits_when_start_interview_then_injected_at_prompt_tail() {
        // 会话建立轮（建项目自动开场）：query = 初始需求原文，命中块接 system prompt 尾部
        Long projectId = persistedProject("9710");
        givenSessionExecutorRunsInline();
        when(knowledgePort.retrieve(eq("给宠物医院做预约系统"), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。")));

        appService.startInterview(projectId, "给宠物医院做预约系统");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().systemPrompt())
                .startsWith(RolePreset.BA.systemPrompt())
                .endsWith("核心场景：主人在线选医生预约。")
                .contains("【平台知识库·相似历史需求】");
    }

    @Test
    void given_established_session_when_later_turns_then_same_tail_reused_no_re_retrieval() {
        // 一次切入一次注入的持续面：会话建立后，后续轮（发言）与续跑（作答）的
        // system prompt 复用同一注入块（agent 工厂按 prompt 缓存 → 同 agent 实例），
        // 知识检索只发生一次（迭代不重注）
        Long projectId = persistedProject("9713");
        givenSessionExecutorRunsInline();
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。")));

        appService.startInterview(projectId, "给宠物医院做预约系统");
        appService.runInterviewTurn(projectId, "主要是海外客户");
        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "企业客户");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        String expected = RolePreset.BA.systemPrompt() + "\n\n【平台知识库·相似历史需求】"
                + "以下是平台沉淀的历史成交需求片段，供梳理当前需求时作背景参考"
                + "（非用户的确认信息，不构成对当前需求的约束）："
                + "\n\n〔宠物医院预约平台〕PRD·宠物医院预约\n核心场景：主人在线选医生预约。";
        assertThat(command.getAllValues()).extracting(AgentCommand::systemPrompt)
                .containsExactly(expected, expected);
        assertThat(resume.getValue().systemPrompt()).isEqualTo(expected);
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt()); // 只检索一次
    }

    @Test
    void given_retrieval_failure_when_start_interview_then_interview_still_starts() {
        // 检索失败降级为空注入：访谈照常开始（systemPrompt = 角色卡原文），不阻断对话
        Long projectId = persistedProject("9711");
        givenSessionExecutorRunsInline();
        doThrow(new RuntimeException("embedding 不可用"))
                .when(knowledgePort).retrieve(anyString(), anyInt());

        appService.startInterview(projectId, "做一个官网");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().systemPrompt()).isEqualTo(RolePreset.BA.systemPrompt());
    }

    @Test
    void given_archived_project_when_turn_or_answer_then_prj_013() {
        // 归档即指令区关闭（只读终态）：发言与作答一并拒绝
        Long projectId = persistedArchivedProject("9712");

        assertThatThrownBy(() -> appService.runInterviewTurn(projectId, "再聊聊"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.startInterview(projectId, "做一个官网"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "有"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        verify(agentClient, never()).converse(any(), any());
        verify(agentClient, never()).resume(any(), any());
    }

    @Test
    void given_active_order_when_turn_or_answer_then_ord_006_frozen() {
        // 下单即冻结迭代：未终结订单在即，指令区新发言与作答一并拒收（取消即解冻）
        Long projectId = persistedProject("9713");
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 1, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9913L, projectId);

        assertThatThrownBy(() -> appService.runInterviewTurn(projectId, "再改一个地方"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_FROZEN.message());
        assertThatThrownBy(() -> appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "有"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_FROZEN.message());
        verify(agentClient, never()).converse(any(), any());
        verify(agentClient, never()).resume(any(), any());
    }

    @Test
    void given_cancelled_order_when_turn_then_unfrozen() {
        // 取消即解冻：终态订单不再拦发言，迭代继续
        Long projectId = persistedProject("9714");
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 5, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9914L, projectId);
        givenSessionExecutorRunsInline();

        appService.runInterviewTurn(projectId, "继续改");

        verify(agentClient).converse(any(), any());
    }

    @Test
    void given_turn_when_run_then_role_assigned_first_then_converse() {
        Long projectId = persistedProject("9701");
        givenSessionExecutorRunsInline();

        appService.runInterviewTurn(projectId, "梳理需求");

        // 帧序 role-assigned（engine=agentscope）→ run-start（converse 内）
        InOrder order = inOrder(streamAppService, agentClient);
        order.verify(streamAppService).publish(org.mockito.ArgumentMatchers.eq(
                        AgentEventTypes.ROLE_ASSIGNED),
                org.mockito.ArgumentMatchers.anyMap());
        order.verify(agentClient).converse(any(), any());
    }

    @Test
    void given_turn_frames_when_bridge_publishes_then_project_id_injected_per_frame() {
        // 流桥：编排注入的关联字段（projectId）逐帧并入 payload（帧序在前——寻址
        // 字段不覆盖帧本体字段）；发射失败不断流（护栏）
        Long projectId = persistedProject("9702");
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent> sink =
                    invocation.getArgument(1);
            sink.accept(new com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent(
                    "text", new java.util.LinkedHashMap<>(Map.of("runId", "run-x"))));
            return null;
        }).when(agentClient).converse(any(), any());

        appService.runInterviewTurn(projectId, "做一个官网");

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(org.mockito.ArgumentMatchers.eq("text"), payload.capture());
        assertThat(payload.getValue()).containsEntry("projectId", projectId.toString())
                .containsEntry("runId", "run-x");
    }

    @Test
    void given_question_answer_when_resume_then_rebuilt_from_project_facts() {
        // 问答答复续跑：恢复私货从项目侧事实重建（会话/owner/工作区/角色卡/计量），
        // 待确认工具来自挂起帧载荷——不信前端回传的恢复私货
        Long projectId = persistedProject("9703");
        givenSessionExecutorRunsInline();
        List<Map<String, Object>> pendingToolCalls = List.of(Map.of(
                "id", "tc-1", "name", "ask_user",
                "input", Map.of("question", "目标用户是谁？")));

        appService.answerQuestion(projectId, "run-q", "reply-9", pendingToolCalls, "海外企业客户");

        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        AgentResume value = resume.getValue();
        assertThat(value.runId()).isEqualTo("run-q");
        assertThat(value.sessionId()).isEqualTo("ba-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.workspaceId()).isEqualTo("9703");
        assertThat(value.modelString()).isEqualTo(RolePreset.BA.chatModelString());
        assertThat(value.systemPrompt()).isEqualTo(RolePreset.BA.systemPrompt());
        assertThat(value.replyId()).isEqualTo("reply-9");
        assertThat(value.resumeText()).isEqualTo("海外企业客户");
        assertThat(value.confirmResults()).hasSize(1);
        // #34：答复走 block metadata（模型不可见通道），不进 input——input 只留原问题
        assertThat(value.confirmResults().get(0).getToolCall().getInput())
                .containsEntry("question", "目标用户是谁？")
                .doesNotContainKey("answer");
        assertThat(value.confirmResults().get(0).getToolCall().getMetadata())
                .containsEntry(AgentscopeAgentClient.ANSWER_METADATA_KEY, "海外企业客户");
        assertThat(value.usageContext().dims()).isEqualTo(
                UsageDims.of(projectId, UsageDims.kindOf(RolePreset.BA), "ba-" + projectId));
    }

    @Test
    void given_missing_project_when_turn_then_prj_001() {
        assertThatThrownBy(() -> appService.runInterviewTurn(-1L, "梳理需求"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_pending_question_when_turn_then_prj_024_and_no_submission() {
        // 挂起问答守卫（#40 / ADR-0005）：问答待答期间指令区新输入不盲提交 converse
        // ——引擎按 ASKING 态拒时 REST 已返 200、只见异步 error 帧；同步 409 指路
        // 作答。role-assigned 帧也不发（守卫先于任何帧与提交）
        Long projectId = persistedProject("9715");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "ba-" + projectId))
                .thenReturn(true);

        assertThatThrownBy(() -> appService.runInterviewTurn(projectId, "测试：请继续"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());
        assertThatThrownBy(() -> appService.startInterview(projectId, "做一个官网"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());

        verify(agentClient, never()).converse(any(), any());
        verify(sessionExecutor, never()).submit(any(), any());
        verify(streamAppService, never()).publish(any(), any());
    }

    // ---------- 链必达收口（#43：BA 无派发权，平台回合收口观测自动派修正） ----------

    @Test
    void given_generated_project_when_ba_turn_closes_without_dispatch_tool_then_fix_run_dispatched() {
        // 灵魂用例（#43 缺陷的行为化验证）：脚本化智能体边界——模型只收口（哪怕
        // 只存了 PRD、不调任何派发工具，BA 也没有派发工具），平台在回合收口时
        // 自动派修正 run：意见原文为任务，coder 会话 + CODER 角色卡 + 直播开
        Long projectId = persistedGeneratedProject("9720");
        givenSessionExecutorRunsInline();
        givenConverseBaRepliesAndCoderFinishes("好的，会把主色调改成绿色");

        appService.runInterviewTurn(projectId, "把系统的主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand fix = command.getAllValues().get(1);
        assertThat(fix.sessionId()).isEqualTo("coder-" + projectId);
        assertThat(fix.systemPrompt()).isEqualTo(RolePreset.CODER.systemPrompt());
        assertThat(fix.prompt()).isEqualTo(IterationAppService.fixRunPrompt(
                new IterationAppService.FixHandoff(List.of("把系统的主色调改成绿色"), null)));
        assertThat(fix.live()).isTrue();
        // 修正 run 的 role-assigned 前置（CODER）
        verify(streamAppService).publish(eq(AgentEventTypes.ROLE_ASSIGNED),
                argThat(payload -> "CODER".equals(payload.get(AgentEventTypes.ROLE_FIELD))));
    }

    // ---------- 交接物补齐（#52：BA 判定结果入修正 run） ----------

    @Test
    void given_ba_saves_prd_with_summary_when_turn_closes_then_handoff_carries_summary_and_prd_path() {
        // 灵魂用例（#41 Testing Decisions / #52）：脚本化 BA 流调
        // savePrd(content, summary="…") → 修正 run prompt（交接物）含 summary
        // 文本与 PRD 路径引用（不注全文）
        Long projectId = persistedGeneratedProject("9740");
        givenSessionExecutorRunsInline();
        givenConverseBaSavesPrdAndCoderFinishes("按意见把主色调相关约定从蓝改为绿（覆盖本条意见）");

        appService.runInterviewTurn(projectId, "把系统的主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .contains("PRD 已修订")
                .contains("按意见把主色调相关约定从蓝改为绿（覆盖本条意见）")
                .contains(ProjectArtifacts.PRD);
    }

    @Test
    void given_ba_without_save_prd_when_turn_closes_then_handoff_states_prd_not_revised() {
        // BA 流不调 savePrd：交接物如实含「PRD 未修订」口径，修正 run 照派
        Long projectId = persistedGeneratedProject("9741");
        givenSessionExecutorRunsInline();
        givenConverseBaRepliesAndCoderFinishes("好的，会处理的");

        appService.runInterviewTurn(projectId, "把系统的主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .contains("PRD 未修订")
                .contains("把系统的主色调改成绿色")
                .doesNotContain("修订说明：");
    }

    @Test
    void given_multiple_save_prd_in_one_turn_when_consumed_then_final_summary_only() {
        // 一轮多次 savePrd：交接物取终值不混杂（终版说明进 prompt，被覆盖的旧说明不进）
        Long projectId = persistedGeneratedProject("9742");
        givenSessionExecutorRunsInline();
        givenConverseBaSavesPrdAndCoderFinishes("第一次修订说明（将被覆盖）",
                "终版修订说明：主色调章节改为绿");

        appService.runInterviewTurn(projectId, "把系统的主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        String prompt = command.getAllValues().get(1).prompt();
        assertThat(prompt).contains("终版修订说明：主色调章节改为绿");
        assertThat(prompt).doesNotContain("第一次修订说明");
    }

    @Test
    void given_stale_revision_fact_from_aborted_turn_when_next_turn_settles_then_not_carried() {
        // 本轮判定从零起算：上一轮 BA 调了 savePrd 但轮炸（收口不跑、事实滞留）
        // → 下一轮不带残留（任务首行清残——滞留的修订事实不冒充本轮「已修订」）。
        // 单一脚本分轮：首轮 record 后抛，次轮正常回复（Mockito 重打桩会以 null
        // 参调旧 answer，故不分设）
        Long projectId = persistedGeneratedProject("9743");
        doAnswer(invocation -> {
            try {
                ((Runnable) invocation.getArgument(1)).run();
            }
            catch (RuntimeException e) {
                // 生产执行器吞掉记日志（异步轨道），此处同语义（首轮 BA 炸）
            }
            return null;
        }).when(sessionExecutor).submit(any(), any());
        AtomicBoolean firstBaTurn = new AtomicBoolean(true);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("ba-")) {
                if (firstBaTurn.getAndSet(false)) {
                    prdRevisions.record(command.workspaceId(), "上一轮的修订说明");
                    throw new IllegalStateException("BA 轮失败");
                }
                return new AgentReply(command.runId(), "好的");
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });

        appService.runInterviewTurn(projectId, "上一条意见（本轮将炸）");
        appService.runInterviewTurn(projectId, "这一条意见不用改 PRD");

        // 首轮炸不派（既有口径）；次轮收口派修正：prompt 如实「未修订」无残留
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(2).prompt())
                .contains("PRD 未修订")
                .doesNotContain("上一轮的修订说明");
    }

    @Test
    void given_not_generated_project_when_ba_turn_closes_then_stops_at_ba() {
        // 守卫沿用：未生成止于 BA（访谈期收口是常态路径，静默不派——不是异常）
        Long projectId = persistedProject("9721");
        givenSessionExecutorRunsInline();

        appService.runInterviewTurn(projectId, "把主色调改成绿色");

        verify(agentClient, times(1)).converse(any(), any());
    }

    @Test
    void given_pending_question_after_turn_when_close_then_no_dispatch_until_answer_settles() {
        // 追问挂起 = 本轮未收口：意见不派；答复续跑再挂起（多轮追问）也不派；
        // 最终收口后派——交接任务锚定意见原文 + 全部追问答复（逐条累积）
        Long projectId = persistedGeneratedProject("9722");
        givenSessionExecutorRunsInline();
        givenConverseBaRepliesAndCoderFinishes("先问一下");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "ba-" + projectId))
                .thenReturn(false)  // 提交守卫：无挂起（放行本轮）
                .thenReturn(true)   // 本轮收口观测：挂起在即 → 不派
                .thenReturn(true)   // 答复①续跑收口观测：再挂起 → 不派
                .thenReturn(false); // 答复②续跑收口观测：无挂起 → 派

        appService.runInterviewTurn(projectId, "把系统的主色调改成绿色");
        verify(agentClient, times(1)).converse(any(), any());

        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "要薄荷绿");
        appService.answerQuestion(projectId, "run-q", "reply-2",
                List.of(Map.of("id", "tc-2", "name", "ask_user")), "偏冷一点的");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(new IterationAppService.FixHandoff(List.of(
                        "把系统的主色调改成绿色；用户对追问的答复：要薄荷绿"
                                + "；用户对追问的答复：偏冷一点的"), null)));
    }

    @Test
    void given_fix_in_flight_when_next_opinion_closes_then_queued_and_merged() {
        // 守卫沿用：在途 run 排队下一轮合并（BA 收口路径的连续两条意见不丢、
        // 不逐条烧 run）
        Long projectId = persistedGeneratedProject("9723");
        List<Runnable> tracks = CollUtil.newArrayList();
        doAnswer(invocation -> {
            Runnable task = (Runnable) invocation.getArgument(1);
            if (("coder-" + projectId).equals(invocation.getArgument(0))) {
                tracks.add(task); // 修正轨道挂起不跑（模拟在途）
                return null;
            }
            task.run(); // BA 轨道直通
            return null;
        }).when(sessionExecutor).submit(any(), any());
        givenConverseBaRepliesAndCoderFinishes("好的");

        appService.runInterviewTurn(projectId, "意见一：加导出");
        appService.runInterviewTurn(projectId, "意见二：改蓝色");
        // 两条 BA 轮已跑、修正只起了一条轨道（第二条意见未即派新 run）
        verify(agentClient, times(2)).converse(any(), any());

        tracks.remove(0).run(); // 修正轨道起跑：第一条跑完即合并第二条续派

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(2).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(new IterationAppService.FixHandoff(List.of("意见一：加导出"), null)));
        assertThat(command.getAllValues().get(3).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(new IterationAppService.FixHandoff(List.of("意见二：改蓝色"), null)));
    }

    @Test
    void given_turn_error_when_close_then_no_dispatch() {
        // BA 轮失败不派修正（意见未被处理；error 帧已表达，用户重提即兜底）——
        // 内联执行器同生产语义吞掉轨道异常
        Long projectId = persistedGeneratedProject("9724");
        doAnswer(invocation -> {
            try {
                ((Runnable) invocation.getArgument(1)).run();
            }
            catch (RuntimeException e) {
                // 生产执行器吞掉记日志（异步轨道），此处同语义
            }
            return null;
        }).when(sessionExecutor).submit(any(), any());
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("BA 轮失败"));

        appService.runInterviewTurn(projectId, "把主色调改成绿色");

        verify(agentClient, times(1)).converse(any(), any());
    }

    // ---------- 派发阶段帧（#50 阶段状态条：意见链全程帧序） ----------

    /** 阶段帧捕获（只收 dispatch-stage，发射序即阶段序）。 */
    private List<String> givenStageCapture() {
        List<String> stages = CollUtil.newArrayList();
        doAnswer(invocation -> {
            if (AgentEventTypes.DISPATCH_STAGE.equals(invocation.getArgument(0))) {
                stages.add(String.valueOf(
                        invocation.<Map<String, Object>>getArgument(1)
                                .get(AgentEventTypes.DISPATCH_STAGE_FIELD)));
            }
            return null;
        }).when(streamAppService).publish(any(), any());
        return stages;
    }

    @Test
    void given_generated_project_when_zero_action_opinion_then_stage_frames_walk_full_chain() {
        // #50：BA 零动作的意见（不追问、不改 PRD）状态条完整走完——analyzing →
        // dispatching → fixing → done(changed=true)，不经 clarifying / updating-prd
        //（可选阶段缺席不是跳变，是如实）；帧序先于 role-assigned（状态条先开口）
        Long projectId = persistedGeneratedProject("9730");
        givenSessionExecutorRunsInline();
        List<String> stages = givenStageCapture();
        givenConverseBaRepliesAndCoderFinishes("好的，会处理的");
        String turnRunId = appService.runInterviewTurn(projectId, "把主色调改成绿色").runId();

        assertThat(stages).containsExactly("analyzing", "dispatching", "fixing", "done");
        // 首帧锚 BA 轮 runId 且先于 role-assigned；完成态区分：changed=true（脚本
        // finish_fix(changed=true)）——「已修改」与「未动系统」以 changed 分档
        InOrder order = inOrder(streamAppService);
        order.verify(streamAppService).publish(eq(AgentEventTypes.DISPATCH_STAGE),
                argThat(payload -> "analyzing".equals(payload.get(AgentEventTypes.DISPATCH_STAGE_FIELD))
                        && turnRunId.equals(payload.get(AgentStreamAppService.RUN_FIELD))));
        order.verify(streamAppService).publish(eq(AgentEventTypes.ROLE_ASSIGNED), anyMap());
        verify(streamAppService).publish(eq(AgentEventTypes.DISPATCH_STAGE), argThat(payload ->
                "done".equals(payload.get(AgentEventTypes.DISPATCH_STAGE_FIELD))
                        && Boolean.TRUE.equals(payload.get(AgentEventTypes.DISPATCH_CHANGED_FIELD))));
    }

    @Test
    void given_not_generated_project_when_turn_then_no_stage_frames() {
        // 生成前意见链止于 BA：无隐藏处理段，不发阶段帧（访谈期以对话面本身呈现）——
        // 状态条不空转、不悬停
        Long projectId = persistedProject("9731");
        givenSessionExecutorRunsInline();
        List<String> stages = givenStageCapture();

        appService.runInterviewTurn(projectId, "做一个官网");

        assertThat(stages).isEmpty();
    }

    @Test
    void given_question_raised_when_answer_settles_then_clarifying_then_chain_resumes() {
        // #50 挂起边界：追问挂起停在 clarifying（不发后续），答复续跑回 analyzing
        // 再走完链——阶段与链路实际状态一致
        Long projectId = persistedGeneratedProject("9732");
        givenSessionExecutorRunsInline();
        List<String> stages = givenStageCapture();
        // BA 轮流上挂起帧（kind=QUESTION）；修正 run 收口脚本沿用（changed=true）
        doAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("ba-")) {
                Consumer<AgentEvent> sink = invocation.getArgument(1);
                sink.accept(new AgentEvent(AgentEventTypes.QUESTION_RAISED,
                        new java.util.LinkedHashMap<>(Map.of(
                                "runId", command.runId(),
                                AgentEventTypes.WAIT_KIND_FIELD, "QUESTION",
                                AgentEventTypes.WAIT_SUMMARY_FIELD, "主色调想要哪种绿？"))));
                return new AgentReply(command.runId(), "先问一下");
            }
            finishFixFacts.record(command.workspaceId(), true, "已修正");
            return new AgentReply(command.runId(), "修正完成");
        }).when(agentClient).converse(any(), any());
        // 提交守卫放行 → 本轮收口观测见挂起（不派）→ 答复续跑收口观测无挂起（派）
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "ba-" + projectId))
                .thenReturn(false)
                .thenReturn(true)
                .thenReturn(false);

        appService.runInterviewTurn(projectId, "把主色调改成绿色");
        assertThat(stages).containsExactly("analyzing", "clarifying"); // 停在追问中

        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "要薄荷绿");
        assertThat(stages).containsExactly("analyzing", "clarifying",
                "analyzing", "dispatching", "fixing", "done"); // 答复后续跑走完
    }

    @Test
    void given_ba_calls_save_prd_when_opinion_then_updating_prd_stage() {
        // #50：BA 判定需求变更调 savePrd（tool 帧 start 边界）→ updating-prd 阶段
        Long projectId = persistedGeneratedProject("9733");
        givenSessionExecutorRunsInline();
        List<String> stages = givenStageCapture();
        doAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("ba-")) {
                Consumer<AgentEvent> sink = invocation.getArgument(1);
                sink.accept(new AgentEvent("tool", new java.util.LinkedHashMap<>(Map.of(
                        "runId", command.runId(),
                        "data", Map.of("toolCallId", "tc-1", "toolName", "savePrd",
                                "phase", "start")))));
                return new AgentReply(command.runId(), "已按意见更新 PRD");
            }
            finishFixFacts.record(command.workspaceId(), true, "已修正");
            return new AgentReply(command.runId(), "修正完成");
        }).when(agentClient).converse(any(), any());

        appService.runInterviewTurn(projectId, "把主色调改成绿色");

        assertThat(stages).containsExactly("analyzing", "updating-prd", "dispatching",
                "fixing", "done");
    }

    @Test
    void given_fix_in_flight_when_opinion_closes_then_queued_stage() {
        // #50 排队边界：修正 run 在途时新意见收口 → queued（如实呈现排队，锚本条
        // 意见的 BA 轮），不起第二条轨道
        Long projectId = persistedGeneratedProject("9734");
        List<Runnable> tracks = CollUtil.newArrayList();
        doAnswer(invocation -> {
            Runnable task = (Runnable) invocation.getArgument(1);
            if (("coder-" + projectId).equals(invocation.getArgument(0))) {
                tracks.add(task); // 修正轨道挂起不跑（模拟在途）
                return null;
            }
            task.run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
        givenConverseBaRepliesAndCoderFinishes("好的");
        List<String> stages = givenStageCapture();

        appService.runInterviewTurn(projectId, "意见一：加导出");
        appService.runInterviewTurn(projectId, "意见二：改蓝色");

        assertThat(stages).containsExactly("analyzing", "dispatching", "analyzing",
                "dispatching", "queued");
    }

    @Test
    void given_start_fix_run_failure_when_close_then_dispatch_failed_terminal_stage() {
        // #51 派发失败终态：收口派修正 run 炸 → dispatch-failed 失败终态帧锚本条
        // 意见的 BA 轮（状态条不悬死在「派发中」）；意见锚已消费、不自动重试
        //——重提即兜底（此处脚本化修正轨道提交失败 = startFixRun 抛出的代表路径）
        Long projectId = persistedGeneratedProject("9735");
        List<String> stages = givenStageCapture();
        givenConverseBaRepliesAndCoderFinishes("好的，会处理的");
        doAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            Runnable task = invocation.getArgument(1);
            if (sessionId.startsWith("coder-")) {
                throw new IllegalStateException("修正轨道提交失败");
            }
            task.run(); // BA 轨道直通
            return null;
        }).when(sessionExecutor).submit(any(), any());

        String turnRunId = appService.runInterviewTurn(projectId, "把主色调改成绿色").runId();

        // analyzing → dispatching → dispatch-failed（如实告知重提，不悬死不静默）
        assertThat(stages).containsExactly("analyzing", "dispatching", "dispatch-failed");
        verify(streamAppService).publish(eq(AgentEventTypes.DISPATCH_STAGE), argThat(payload ->
                "dispatch-failed".equals(payload.get(AgentEventTypes.DISPATCH_STAGE_FIELD))
                        && turnRunId.equals(payload.get(AgentStreamAppService.RUN_FIELD))));
        // 修正 run 未起跑：无 coder converse，BA 轮失败不炸轨道（会话执行器吞掉）
        verify(agentClient, times(1)).converse(any(), any());
    }

    // ---------- 测试数据 ----------

    private Long persistedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("访谈项目", null,
                Long.parseLong(workspaceId), OWNER));
        return project.getId();
    }

    private Long persistedArchivedProject(String workspaceId) {
        Project project = Project.create("归档访谈项目", null,
                Long.parseLong(workspaceId), OWNER);
        project.archive();
        return projectRepository.save(project).getId();
    }
}
