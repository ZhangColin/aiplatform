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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import cn.hutool.core.collection.CollUtil;

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
import com.semgravio.aiplatform.base.agentscope.FileChange;
import com.semgravio.aiplatform.base.agentscope.AgentResume;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentSuspension;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.AgentscopeHarnessAgentFactory;
import com.semgravio.aiplatform.base.agentscope.KernelSystemPrompt;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeRetrieval;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.semgravio.aiplatform.base.skills.domain.enums.SkillSlot;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.billing.application.PoolTopupAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.project.domain.aggregate.ConversationEntry;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ForkState;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;
import com.semgravio.aiplatform.business.project.domain.repository.ConversationEntryRepository;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 主智能体单会话编排（#86 并轨，ADR 0006）：对话命令的会话寻址（projectId →
 * main-{projectId} 稳定绑定，追问 / 答询 / 受理意见每轮都续此会话——脚本化多轮
 * 验收「追问 → 咨询 → 提意见全部落在同一会话」）、计量归属（agentKind=main）、
 * 只读工作区姿态（workspaceReadOnly——写面结构性关闭的前提）、会话建立轮的知识
 * 命中注入（尾部 + 失败降级）、归档守卫、问答答复续跑（挂起事件载荷 + 答复 →
 * resume 从项目侧事实重建恢复私货）；<b>咨询零产物短路</b>（#47 语义随并轨保持：
 * 同会话直答、不锚意见、不派修正 run）；链必达收口（#43）——意见轮收口（无挂起
 * 问答）且项目已生成时平台自动派修正 run（模型不调派发工具也必达；未生成止于
 * 对话；在途排队合并；追问挂起待答复收口再派）；交接物补齐（#52）——savePrd 的
 * summary 终值与「本轮无修订」占位口径入修正 run prompt，本轮判定从零起算（上轮
 * 残留不进）；意见轮在途意见排队成轮（#54）——意见锚随会话任务落（在途窗口连发
 * 各自成轮各自派发、零丢失），converse 炸即清锚（重提即兜底）；排队合并逐轮配对
 * （#55）——交接物各轮「意见 → 修订说明」一一对应、未修订轮显式占位（配对由
 * 平台拼装锚定，不靠清单位置对齐）。
 */
@IntegrationTest
class MainAgentAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private MainAgentAppService appService;

    @Autowired
    private ConversationEntryRepository entries;

    @Autowired
    private ConversationHistoryAppService conversationHistory;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 命中留痕替身（端口 mock 上的进程内记录，#639）。 */
    private final Map<String, KnowledgeRetrieval> recordedRetrievals = new ConcurrentHashMap<>();

    @Autowired
    private PrdRevisionFacts prdRevisions;

    /** 快道事实登记口（#542：模拟轮内 savePrd 带 fastLane=true 的工具调用事实）。 */
    @Autowired
    private FastLaneFacts fastLaneFacts;

    @Autowired
    private BillingAppService billingAppService;

    @Autowired
    private PoolTopupAppService poolTopupAppService;

    @Autowired
    private CodingRunTrack codingRunTrack;

    @Autowired
    private MainAgentProperties mainAgentProperties;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private EventsAppService eventsAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private KnowledgePort knowledgePort;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM prj_agent_configs");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
        // 计费闸用例行清理（bil_ 弱引用直落，随测试项目一并清）
        jdbcTemplate.update("DELETE FROM bil_balance_holds");
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
    }

    @BeforeEach
    void defaultAgentReplies() {
        // 真解析链护卫（ADR-0031）：命令构建经 ModelConfigAppService 解析种子配置行
        // （flash → deepseek 通道，V79 收敛后种子），测试环境无 key 注入——幂等补测试 key
        ModelConfigSeed.ensureChannelKey(jdbcTemplate);
        // 轮落定点读 converse/resume 返回维护挂起问答会合锚——默认回复形状兜底
        // （个别用例自带脚本后打桩覆盖之，Mockito 后桩胜出）。同测重打桩时 when()
        // 的 matcher 空参调用会触发旧 answer——null 直接过，不当真调用（同
        // DispatchAppServiceTest.givenClassification 先例）
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return command != null ? new AgentReply(command.runId(), "好的") : null;
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation -> {
            AgentResume resume = invocation.getArgument(0);
            return resume != null ? new AgentReply(resume.runId(), "好的") : null;
        });
        // #639 命中留痕：端口 mock 上做进程内落痕替身——establish 写入的记录可被
        // 后续轮（sessionTailOf）读回，等价于真库「从最近记录重建」（默认无命中）
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(KnowledgeRetrieval.empty());
        doAnswer(invocation -> {
            recordedRetrievals.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(knowledgePort).recordRetrieval(anyString(), any());
        when(knowledgePort.latestRetrieval(anyString())).thenAnswer(invocation ->
                recordedRetrievals.getOrDefault(invocation.getArgument(0),
                        KnowledgeRetrieval.empty()));
        // #601 内核收尾判据双件（pnpm build＋8081 探活）默认成功——失败态用例后注册覆盖
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && (cmd.command().contains("pnpm build")
                        || cmd.command().contains("curl")))))
                .thenReturn(new ExecResultResponse("", "", 0));
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());
    }

    /** 同生产语义吞掉轨道异常（异步轨道失败经 error 事件表达，执行器只记日志）——
     * 炸轮用例的执行器形态。 */
    private void givenSessionExecutorSwallowsFailures() {
        doAnswer(invocation -> {
            try {
                ((Runnable) invocation.getArgument(1)).run();
            }
            catch (RuntimeException e) {
                // 生产执行器吞掉记日志（异步轨道），此处同语义
            }
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            try {
                ((Runnable) invocation.getArgument(1)).run();
            }
            catch (RuntimeException e) {
                // 同上：生成车道同吞语义
            }
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());
    }

    /** 脚本化智能体边界：主智能体正常回复；自动派发的修正 run 内核收口（平台机器
     * 判据双件——#604 起不再依赖结束工具自报）。 */
    private void givenConverseMainRepliesAndExecutorFinishes(String mainReply) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), mainReply);
        });
    }

    /** 已生成形态的项目（生成后发言走对话面的前提事实）。 */
    private Long persistedGeneratedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("迭代访谈项目", null, Long.parseLong(workspaceId), OWNER, null));
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    @Test
    void given_project_when_opinion_turn_then_command_bound_to_main_session() {
        Long projectId = persistedProject("9700");
        givenSessionExecutorRunsInline();

        MainAgentAppService.MainAgentRun run = appService.runOpinionTurn(projectId, "做一个官网");

        // 对话命令全要素：主智能体会话稳定绑定 + owner 寻址 + 配置模型/协议 + 计量归属
        // + 项目工作区 + 流关联 + 只读面（写面结构性关闭）。原轮命令＝首次 converse
        //（#445 起访谈悬空形另派纠偏轮——次数属链内细节，此处不断言；取首次捕获值，
        // getValue() 会拿到链尾纠偏轮命令、runId 对不上）
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        AgentCommand value = command.getAllValues().get(0);
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo("做一个官网");
        assertThat(value.sessionId()).isEqualTo("main-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).contains("ask_user");
        assertThat(value.modelString()).isEqualTo(ModelConfigSeed.FLASH_MODEL_STRING);
        assertThat(value.agentKey()).isEqualTo(AgentProfile.MAIN.key());
        assertThat(value.workspaceReadOnly()).isTrue();
        assertThat(value.workspaceId()).isEqualTo("9700");
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).isEqualTo(
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN), "main-" + projectId));
        // 后续轮不重注知识：systemPrompt 即配置原文＋#538 试用轨协议段
        assertThat(value.systemPrompt()).isEqualTo(AgentProfile.MAIN.systemPrompt()
                + MainAgentAppService.TRIAL_INTERVIEW_PROTOCOL_TAIL);
        // #371 对话轮活超时：命令携可配静默判死阈值（还在干活就不掐——慢工具轮
        // 不被 2 分钟总墙钟一次性掐死），替换原三 null 总墙钟口径
        assertThat(value.silenceTimeout()).isEqualTo(mainAgentProperties.getSilenceTimeout());
        assertThat(value.toolSilenceTimeout()).isEqualTo(mainAgentProperties.getToolSilenceTimeout());
    }

    @Test
    void given_main_config_override_when_opinion_turn_then_command_carries_library_values() {
        // #251 装配断言（真命令构建缝，ADR-0021 库值优先缺省回落）：设运营配置库行后
        // 对话命令实取库值（prompt 与模型配置两腿）；清空（行删即全回落）后下一轮
        // 命令回枚举默认——解析单点 AgentConfigAppService 经 mainCommand 真链路钉死。
        // #252 增强工具开关同批：生效开关编入 toolSpec 随命令透传（进工厂缓存键＋
        // 装配判据——开关变更下一轮命令构建即新装配）
        Long projectId = persistedProject("9740");
        givenSessionExecutorRunsInline();
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, system_prompt, model_config_key, web_search_enabled)
                VALUES ('main', ?, 'pro', false)
                """, "主智能体覆盖协议：每轮先复述目标。");

        appService.runOpinionTurn(projectId, "做一个官网");

        ArgumentCaptor<AgentCommand> overridden = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(overridden.capture(), any());
        // 库值优先全量覆盖本体协议；#538 访谈协议段仍随附（覆盖行管本体，管不到动态装配段）
        assertThat(overridden.getValue().systemPrompt()).isEqualTo("主智能体覆盖协议：每轮先复述目标。"
                + MainAgentAppService.TRIAL_INTERVIEW_PROTOCOL_TAIL);
        assertThat(overridden.getValue().modelString()).isEqualTo("deepseek:deepseek-v4-pro");
        // 开关库值编入规格串（ws 关、fu 缺省开；#545 gi 件随 #604 出图开关退役，不再
        // 编入规格串）
        assertThat(overridden.getValue().toolSpec()).isEqualTo("ws=false,fu=true");

        jdbcTemplate.update("DELETE FROM prj_agent_configs WHERE agent_key = 'main'");
        appService.runOpinionTurn(projectId, "再聊聊范围");
        // 两轮意见轮共 6 次 converse（#445：每轮悬空收口各派两轮纠偏）——回落断言取
        // 次轮原命令（第 4 次调用）
        ArgumentCaptor<AgentCommand> fallback = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(6)).converse(fallback.capture(), any());
        assertThat(fallback.getAllValues().get(3).systemPrompt())
                .isEqualTo(AgentProfile.MAIN.systemPrompt()
                        + MainAgentAppService.TRIAL_INTERVIEW_PROTOCOL_TAIL);
        assertThat(fallback.getAllValues().get(3).modelString())
                .isEqualTo(ModelConfigSeed.FLASH_MODEL_STRING);
        assertThat(fallback.getAllValues().get(3).toolSpec())
                .isEqualTo("ws=true,fu=true"); // gi 件随 #604 出图开关退役，不再编入规格串
    }

    @Test
    void given_trial_interview_when_opinion_turn_then_command_carries_trial_protocol_tail() {
        // #538 协议分轨（ADR-0048）：试用态访谈轮的 system prompt 尾附「访谈协议·试用轨」
        // 段（重要性排序纪律、不数数），付费轨段缺席——走既有每轮动态装配缝（知识尾注
        // 同缝），零新建机制
        Long projectId = persistedProject("9751");
        givenSessionExecutorRunsInline();
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);

        appService.runOpinionTurn(projectId, "做一个预约系统");

        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).systemPrompt())
                .contains("访谈协议·试用轨")
                .doesNotContain("访谈协议·付费轨")
                .doesNotContain("【收口指令】");
    }

    @Test
    void given_paid_interview_when_opinion_turn_then_command_carries_paid_protocol_tail() {
        // #538 付费翻转后回原协议（无上限、题量软目标）——协议段形态随付费状态翻转
        Long projectId = persistedProject("9752");
        poolTopupAppService.topup(null, projectId, 1, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.INTERCEPT);
        givenSessionExecutorRunsInline();
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);

        appService.runOpinionTurn(projectId, "做一个预约系统");

        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).systemPrompt())
                .contains("访谈协议·付费轨")
                .contains("软目标")
                .doesNotContain("【访谈协议·试用轨】") // 基础协议可引用段名；断言的是试用轨段未随附
                .doesNotContain("【收口指令】");
    }

    @Test
    void given_generated_project_when_opinion_turn_then_no_interview_protocol_tail() {
        // 非访谈期（已生成＝迭代期）：额度与协议段均不附着——访谈纪律不混入迭代轮
        Long projectId = persistedGeneratedProject("9753");
        givenSessionExecutorRunsInline();
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);

        appService.runOpinionTurn(projectId, "把按钮改大一点");

        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).systemPrompt())
                .doesNotContain("【访谈协议·")
                .doesNotContain("【收口指令】");
    }

    @Test
    void given_quota_full_when_answer_question_then_resume_carries_closure_instruction() {
        // #538 收口指令注入（ADR-0048「第 5 问答完由编排层注入收口指令」）：试用态已答
        // 满 5 个问答缺口后，答复续跑的 system prompt 尾附收口指令（剩余必答缺口按推荐
        // 补全＋立即梳理 PRD）——不再出现第 6 问的提示词面＋结构面双保险
        Long projectId = persistedProject("9754");
        seedAnsweredGaps(projectId, 5);
        givenSessionExecutorRunsInline();
        List<Map<String, Object>> pendingToolCalls = List.of(Map.of(
                "id", "tc-1", "name", "ask_user",
                "input", Map.of("question", "目标用户是谁？")));
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);

        appService.answerQuestion(projectId, "run-q", "reply-9", pendingToolCalls, "海外企业客户");

        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().systemPrompt())
                .contains("【收口指令】")
                .contains("访谈协议·试用轨");
    }

    @Test
    void given_quota_not_full_when_answer_question_then_resume_without_closure_instruction() {
        // 未答满不注入：额度内续跑维持原协议（试用轨段在场、收口指令缺席）
        Long projectId = persistedProject("9755");
        seedAnsweredGaps(projectId, 3);
        givenSessionExecutorRunsInline();
        List<Map<String, Object>> pendingToolCalls = List.of(Map.of(
                "id", "tc-1", "name", "ask_user",
                "input", Map.of("question", "目标用户是谁？")));
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);

        appService.answerQuestion(projectId, "run-q", "reply-9", pendingToolCalls, "海外企业客户");

        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().systemPrompt())
                .contains("访谈协议·试用轨")
                .doesNotContain("【收口指令】");
    }

    @Test
    void given_scripted_multi_turn_when_followup_inquiry_opinion_then_all_same_session() {
        // 灵魂用例（#86 验收①：脚本化多轮——追问 → 咨询 → 提意见全部落在同一会话，
        // 连续不换会话）：开场追问挂起（question-raised）→ 答复续跑（resume 同会话）
        // → 咨询直答（同会话 converse，零产物不派 run）→ 意见受理（同会话 converse；
        // #622/#625 起生成后发言走对话面，意见轮收口不再派修正 run）——全程
        // main-{projectId} 恒定，无 coder 会话
        Long projectId = persistedGeneratedProject("9705");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.prompt().contains("预约系统")) { // 开场轮：追问挂起
                Consumer<AgentEvent> sink = invocation.getArgument(1);
                sink.accept(new AgentEvent(AgentEventTypes.QUESTION_RAISED,
                        new java.util.LinkedHashMap<>(Map.of(
                                "runId", command.runId(),
                                AgentEventTypes.WAIT_SUMMARY_FIELD, "给谁用、谁来操作？"))));
            }
            return new AgentReply(command.runId(), "好的");
        });
        // 挂起事实脚本：开场提交放行 → 开场收口见挂起；其余收口均无挂起
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)
                .thenReturn(true)
                .thenReturn(false);

        appService.startConversation(projectId, "给宠物医院做预约系统");
        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "宠物主人自己操作");
        appService.answerInquiry(projectRepository.findById(projectId).orElseThrow(),
                "我后台的地址是什么？");
        appService.runOpinionTurn(projectId, "把系统的主色调改成绿色");

        // 会话恒定：主智能体的三轮 converse（开场 / 咨询 / 意见）与一次 resume 全在
        // main-{projectId}；不再派生任何编码 run
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(command.capture(), any());
        assertThat(command.getAllValues()).extracting(AgentCommand::sessionId)
                .containsOnly("main-" + projectId);
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().sessionId()).isEqualTo("main-" + projectId);
        assertThat(resume.getValue().workspaceReadOnly()).isTrue();
    }

    @Test
    void given_blank_requirement_when_start_conversation_then_kickoff_prompt_and_no_retrieval() {
        // #639 访客没填需求描述（走平台固定开场白）：首条对话＝固定开场白，但
        // 开场白不作检索词——不检索、不写命中记录（真实需求与开场白分开传参）
        Long projectId = persistedProject("9717");
        givenSessionExecutorRunsInline();

        appService.startConversation(projectId, " ");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .isEqualTo(AgentProfile.DEFAULT_KICKOFF_PROMPT);
        verify(knowledgePort, never()).retrieve(anyString(), anyInt());
        verify(knowledgePort, never()).recordRetrieval(anyString(), any());
    }

    @Test
    void given_knowledge_hits_when_start_conversation_then_injected_at_prompt_tail() {
        // 会话建立轮（建项目自动开场）：query = 初始需求原文，命中块接 system prompt 尾部
        //（#466 多类同池：PRD 历史需求＋激活升格的沉淀物同块注入，条带 kind 区分）
        Long projectId = persistedProject("9710");
        givenSessionExecutorRunsInline();
        when(knowledgePort.retrieve(eq("给宠物医院做预约系统"), anyInt())).thenReturn(
                KnowledgeRetrieval.of(List.of(
                        new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                                "核心场景：主人在线选医生预约。"),
                        new KnowledgeHit("CASE", "平台沉淀", "宠物医院在线预约的典型形态",
                                "主人选医生、按时段预约、迟到作废。"))));

        appService.startConversation(projectId, "给宠物医院做预约系统");

        // 开场轮命令＝首次 converse（#445：访谈悬空形另派纠偏轮，次数不断言）
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getValue().systemPrompt())
                .startsWith(AgentProfile.MAIN.systemPrompt())
                .contains("【平台知识库·相似知识】")
                .contains("这些内容来自平台其他项目，不要向用户提及其他项目，也不要把它们当作当前需求的结论。")
                .contains("〔宠物医院预约平台｜历史需求〕PRD·宠物医院预约")
                .contains("〔平台沉淀｜需求案例〕宠物医院在线预约的典型形态")
                .contains("非用户的确认信息，不构成对当前需求的约束")
                .contains("主人选医生、按时段预约、迟到作废。")
                // #538 知识尾注之后随附访谈协议段（试用轨；动态装配缝同缝、恒最尾位）
                .endsWith(MainAgentAppService.TRIAL_INTERVIEW_PROTOCOL_TAIL);
    }

    @Test
    void given_established_session_when_later_turns_then_same_tail_reused_no_re_retrieval() {
        // 一次切入一次注入的持续面：会话建立后，后续轮（发言/答询）与续跑（作答）的
        // system prompt 复用同一注入块（agent 工厂按 prompt 缓存 → 同 agent 实例），
        // 知识检索只发生一次（迭代不重注）
        Long projectId = persistedProject("9713");
        givenSessionExecutorRunsInline();
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(
                KnowledgeRetrieval.of(List.of(
                        new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                                "核心场景：主人在线选医生预约。"))));

        appService.startConversation(projectId, "给宠物医院做预约系统");
        appService.runOpinionTurn(projectId, "主要是海外客户");
        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "企业客户");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        // #445：开场/意见/答复三个用户轮各带两轮纠偏（悬空收口）——共 8 次 converse；
        // 会话注入块一经建立全轮复用（含纠偏轮）
        verify(agentClient, times(8)).converse(command.capture(), any());
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        String expected = AgentProfile.MAIN.systemPrompt() + "\n\n【平台知识库·相似知识】"
                + "这些内容来自平台其他项目，不要向用户提及其他项目，也不要把它们当作当前需求的结论。"
                + "以下是平台知识库中与当前需求相似的沉淀片段（历史需求/需求案例/功能模块/业务规则）"
                + "，供梳理当前需求时作背景参考"
                + "（非用户的确认信息，不构成对当前需求的约束）："
                + "\n\n〔宠物医院预约平台｜历史需求〕PRD·宠物医院预约\n核心场景：主人在线选医生预约。"
                // #538 会话注入块之后随附访谈协议段（试用轨）——全轮（含纠偏/续跑）同缝
                + MainAgentAppService.TRIAL_INTERVIEW_PROTOCOL_TAIL;
        assertThat(command.getAllValues()).extracting(AgentCommand::systemPrompt)
                .containsOnly(expected);
        assertThat(resume.getValue().systemPrompt()).isEqualTo(expected);
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt()); // 只检索一次
    }

    @Test
    void given_retrieval_failure_when_start_conversation_then_conversation_still_starts() {
        // 检索失败降级为空注入：访谈照常开始（systemPrompt = 配置原文），不阻断对话
        Long projectId = persistedProject("9711");
        givenSessionExecutorRunsInline();
        doThrow(new RuntimeException("embedding 不可用"))
                .when(knowledgePort).retrieve(anyString(), anyInt());

        appService.startConversation(projectId, "做一个官网");

        // 开场轮命令＝首次 converse（#445：访谈悬空形另派纠偏轮，次数不断言）
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        // 检索失败降级为空注入 + #538 试用轨协议段随附（访谈期每轮动态装配）
        assertThat(command.getValue().systemPrompt())
                .isEqualTo(AgentProfile.MAIN.systemPrompt()
                        + MainAgentAppService.TRIAL_INTERVIEW_PROTOCOL_TAIL);
    }

    @Test
    void given_archived_project_when_turn_or_answer_then_prj_013() {
        // 归档即对话区关闭（只读终态）：发言与作答一并拒绝
        Long projectId = persistedArchivedProject("9712");

        assertThatThrownBy(() -> appService.runOpinionTurn(projectId, "再聊聊"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.startConversation(projectId, "做一个官网"))
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
        // 下单即冻结迭代：未终结订单在即，对话区新意见与作答一并拒收（取消即解冻）
        Long projectId = persistedProject("9716");
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 1, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9913L, projectId);

        assertThatThrownBy(() -> appService.runOpinionTurn(projectId, "再改一个地方"))
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
        // 取消即解冻：终态订单不再拦意见，迭代继续
        Long projectId = persistedProject("9714");
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 5, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9914L, projectId);
        givenSessionExecutorRunsInline();

        appService.runOpinionTurn(projectId, "继续改");

        // 原轮命令即首次 converse（#445：访谈悬空形另派纠偏轮，次数不断言）
        verify(agentClient, atLeast(1)).converse(any(), any());
    }

    @Test
    void given_turn_when_run_then_no_role_assigned_emitted() {
        // 收缩验收（#82）：角色键已并入 run-start（converse 内发射），编排层不再
        // 前置 role-assigned——退役族零发射
        Long projectId = persistedProject("9701");
        givenSessionExecutorRunsInline();

        appService.runOpinionTurn(projectId, "梳理需求");

        // 原轮命令即首次 converse（#445：访谈悬空形另派纠偏轮，次数不断言）
        verify(agentClient, atLeast(1)).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq("role-assigned"), anyMap());
    }

    @Test
    void given_turn_frames_when_bridge_publishes_then_project_id_injected_per_frame() {
        // 流桥：编排注入的关联字段（projectId）逐事件并入 payload（事件序在前——寻址
        // 字段不覆盖事件本体字段）；发射失败不断流（护栏）
        Long projectId = persistedProject("9702");
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent> sink =
                    invocation.getArgument(1);
            sink.accept(new com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent(
                    "text", new java.util.LinkedHashMap<>(Map.of("runId", "run-x"))));
            return new AgentReply(((AgentCommand) invocation.getArgument(0)).runId(), "好的");
        }).when(agentClient).converse(any(), any());

        appService.runOpinionTurn(projectId, "做一个官网");

        // text 事件每次 converse 都经流桥注入 projectId（#445 起含纠偏轮）——断首帧
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, atLeast(1)).publishAgentEvent(org.mockito.ArgumentMatchers.eq("text"), payload.capture());
        assertThat(payload.getValue()).containsEntry("projectId", projectId.toString())
                .containsEntry("runId", "run-x");
    }

    @Test
    void given_paid_exhausted_when_answer_question_then_resume_not_blocked() {
        // 段中绝不检查绝不中断（#537 编排缝断言时序面）：问答答复＝在途段（挂起轮）
        // 的续跑——不是段起跑，段边界余额门不消费此缝：负余额下答复照 resume，
        // 零余额提醒事件、零 BILL_025
        Long projectId = persistedProject("9704");
        poolTopupAppService.topup(null, projectId, 1, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.INTERCEPT);
        jdbcTemplate.update("UPDATE bil_pools SET balance = -500 WHERE project_id = ?", projectId);
        givenSessionExecutorRunsInline();
        List<Map<String, Object>> pendingToolCalls = List.of(Map.of(
                "id", "tc-1", "name", "ask_user",
                "input", Map.of("question", "目标用户是谁？")));

        appService.answerQuestion(projectId, "run-q", "reply-9", pendingToolCalls, "海外企业客户");

        verify(agentClient).resume(any(), any()); // 负余额不阻断段内续跑
        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.BALANCE_REMINDER), anyMap());
    }

    @Test
    void given_question_answer_when_resume_then_rebuilt_from_project_facts() {        // 问答答复续跑：恢复私货从项目侧事实重建（会话/owner/工作区/配置/计量），
        // 待确认工具来自挂起事件载荷——不信前端回传的恢复私货
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
        assertThat(value.sessionId()).isEqualTo("main-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.workspaceId()).isEqualTo("9703");
        assertThat(value.modelString()).isEqualTo(ModelConfigSeed.FLASH_MODEL_STRING);
        // #538 续跑同缝随附试用轨协议段（额度内无收口指令）
        assertThat(value.systemPrompt()).isEqualTo(AgentProfile.MAIN.systemPrompt()
                + MainAgentAppService.TRIAL_INTERVIEW_PROTOCOL_TAIL);
        assertThat(value.agentKey()).isEqualTo(AgentProfile.MAIN.key());
        assertThat(value.workspaceReadOnly()).isTrue(); // 续跑同只读面（#86：不漂移成读写面）
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
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN), "main-" + projectId));
        // #371 续跑与 converse 同口径：续跑请求携与首轮同源的静默判死阈值
        assertThat(value.silenceTimeout()).isEqualTo(mainAgentProperties.getSilenceTimeout());
        assertThat(value.toolSilenceTimeout()).isEqualTo(mainAgentProperties.getToolSilenceTimeout());
    }

    @Test
    void given_missing_project_when_turn_then_prj_001() {
        assertThatThrownBy(() -> appService.runOpinionTurn(-1L, "梳理需求"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_pending_question_when_turn_then_prj_024_and_no_submission() {
        // 挂起问答守卫（#40 / ADR-0005）：问答待答期间意见新输入不盲提交 converse
        // ——引擎按 ASKING 态拒时 REST 已返 200、只见异步 error 事件；同步 409 指路
        // 作答。任何事件也不发（守卫先于提交）
        Long projectId = persistedProject("9715");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(true);

        assertThatThrownBy(() -> appService.runOpinionTurn(projectId, "测试：请继续"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());
        assertThatThrownBy(() -> appService.startConversation(projectId, "做一个官网"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());

        verify(agentClient, never()).converse(any(), any());
        verify(sessionExecutor, never()).submit(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(any(), any());
        verify(eventsAppService, never()).publishNotification(any(), any());
    }

    // ---------- 咨询车道（#47 零产物短路，#86 并轨单会话） ----------

    @Test
    void given_consultation_when_answered_then_same_main_session_zero_artifact() {
        // 咨询与意见同会话（#86）：答询轮绑同一 main-{projectId}、只读面、配置=MAIN；
        // 零产物——已生成项目也不派修正 run（无第二轮 coder converse）、无
        // document-updated、不触知识检索、状态位不动
        Long projectId = persistedGeneratedProject("9810");
        Project project = projectRepository.findById(projectId).orElseThrow();
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "系统访问地址是 http://localhost:32168/。");
        });

        MainAgentAppService.MainAgentRun run = appService.answerInquiry(project,
                "我后台的地址是什么？");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any()); // 唯一一条 = 答询轮本身
        AgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo("我后台的地址是什么？");
        assertThat(value.sessionId()).isEqualTo("main-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.modelString()).isEqualTo(ModelConfigSeed.FLASH_MODEL_STRING);
        assertThat(value.agentKey()).isEqualTo(AgentProfile.MAIN.key());
        assertThat(value.workspaceReadOnly()).isTrue();
        assertThat(value.usageContext().dims()).isEqualTo(
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN), "main-" + projectId));

        verify(eventsAppService, never()).publishNotification(
                eq(ProjectEventTypes.DOCUMENT_UPDATED), anyMap());
        verify(knowledgePort, never()).retrieve(anyString(), anyInt()); // 咨询不知识命中
        Project after = projectRepository.findById(projectId).orElseThrow();
        assertThat(after.getPrdProducedAt()).isEqualTo(project.getPrdProducedAt());
        assertThat(after.getGeneratedAt()).isEqualTo(project.getGeneratedAt());
        assertThat(after.getArchivedAt()).isNull();
    }

    @Test
    void given_suspended_question_when_inquiry_arrives_in_render_race_then_rerouted_as_answer_resume() {
        // 灵魂用例（#86 复审：渲染竞态收敛——错误气泡对对话体验不可接受）：意见轮
        // 以 ask_user 挂起（挂起事件已发、前端问答卡未及呈现的窗口）里经派发口到达
        // 的咨询不炸对话——转作答复续跑：同挂起 run 与 engineRef/工具面、咨询文本
        // 即答复文本，与作答通道完全同路；续跑收口（正常回复）后锚清，后续咨询回
        // 正常答询轮（新 converse）
        Long projectId = persistedGeneratedProject("9706");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) { // 意见轮：以 ask_user 挂起
                return new AgentReply(command.runId(), "先问一下", new AgentSuspension(
                        "reply-77", List.of(Map.of("id", "tc-9", "name", "ask_user"))));
            }
            return new AgentReply(command.runId(), "修正完成");
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation ->
                new AgentReply(((AgentResume) invocation.getArgument(0)).runId(), "进展顺利"));
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 意见轮提交守卫：放行
                .thenReturn(true)   // 意见轮收口观测：挂起在即 → 不派、锚保留
                .thenReturn(false); // 答复续跑收口观测：无挂起 → 派修正（锚含咨询文本并入）

        MainAgentAppService.MainAgentRun opinion = appService.runOpinionTurn(projectId,
                "把系统的主色调改成绿色");
        MainAgentAppService.MainAgentRun inquiry = appService.answerInquiry(
                projectRepository.findById(projectId).orElseThrow(), "现在进展如何？");
        assertThat(inquiry.runId()).isEqualTo(opinion.runId()); // 响应锚 = 挂起 run（续跑同 run 收口）

        // 转答而非新 converse：唯一 converse 是意见轮本身（#625 起生成后不再派修正 run）
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().runId()).isEqualTo(opinion.runId());
        assertThat(resume.getValue().sessionId()).isEqualTo("main-" + projectId);
        assertThat(resume.getValue().replyId()).isEqualTo("reply-77");
        assertThat(resume.getValue().resumeText()).isEqualTo("现在进展如何？");
        assertThat(resume.getValue().confirmResults()).hasSize(1);
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        assertThat(command.getAllValues()).extracting(AgentCommand::sessionId)
                .containsExactly("main-" + projectId);

        // 续跑收口（正常回复）清锚：后续咨询回正常答询轮（新 captor——capture() 跨
        // verify 累积，复用旧 captor 会读到重复段）
        MainAgentAppService.MainAgentRun next = appService.answerInquiry(
                projectRepository.findById(projectId).orElseThrow(), "谢谢，没事了");
        ArgumentCaptor<AgentCommand> third = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(third.capture(), any());
        assertThat(third.getAllValues().get(1).sessionId()).isEqualTo("main-" + projectId);
        assertThat(third.getAllValues().get(1).runId()).isEqualTo(next.runId());
    }

    @Test
    void given_pending_question_without_anchor_when_inquiry_then_prj_024_restart_edge() {
        // 丢锚重启边角：挂起仍在（状态库 ASKING）但本进程没有挂起事实（run 无表丢
        // runId，无法代答）——同步 409 指路作答，好过异步错误气泡；拒绝即零提交零事件
        Long projectId = persistedProject("9707");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(true);

        assertThatThrownBy(() -> appService.answerInquiry(
                projectRepository.findById(projectId).orElseThrow(), "现在进展如何？"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());
        verify(agentClient, never()).converse(any(), any());
        verify(agentClient, never()).resume(any(), any());
        verify(sessionExecutor, never()).submit(any(), any());
    }

    // ---------- 链必达收口（#43：主智能体无派发权，平台意见轮收口观测自动派修正） ----------

    // ---------- 受理动作卡（#87：受理轮过程呈现——迭代期意见轮开场受理事件） ----------

    // ---------- 交接物补齐（#52：需求侧判定结果入修正 run） ----------

    @Test
    void given_prd_not_produced_and_not_generated_when_opinion_turn_closes_then_stops_at_conversation() {
        // 守卫收紧（#101）：未生成且未产出 PRD 止于对话（访谈期收口是常态路径，
        // 静默不派——不是异常）；PRD 产出前不触发任何生成
        Long projectId = persistedProject("9721");
        givenSessionExecutorRunsInline();

        appService.runOpinionTurn(projectId, "把主色调改成绿色");

        // 未生成未出 PRD 仍止于对话（零生成派发不变，#445 起悬空收口先走两轮纠偏）
        verify(agentClient, times(3)).converse(any(), any());
    }

    @Test
    void given_prd_produced_natural_close_when_turn_closes_then_fork_pending_no_dispatch() {
        // 灵魂例（#542 访谈② AC①/AC③，ADR-0048/0049——两入口退役）：试用态自然收口
        // （无快道事实）不派发——出岔路口三选卡（状态位持久可回：关页回来卡仍在），
        // 编码 run 零起跑；「立即生成」才点火（见 BuildGateAppServiceTest/POST /generate）
        Long projectId = persistedPrdProject("9727");
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "已产出 PRD 与切片计划");
        });

        appService.runOpinionTurn(projectId, "做一个官网");

        // 自然收口不派：只有主智能体一轮 converse，无任何编码 run
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        assertThat(command.getAllValues())
                .noneMatch(c -> c.sessionId().startsWith(CodingRunDispatcher.SESSION_PREFIX));
        // 岔路口状态位落库（持久可回——重读在场；Integer code 落库）
        assertThat(jdbcTemplate.queryForMap(
                "SELECT fork_state, generation_requested_at FROM prj_projects WHERE id = ?",
                projectId).get("fork_state")).isEqualTo(ForkState.PENDING.getCode());
    }

    @Test
    void given_fast_lane_save_when_turn_closes_then_first_generation_dispatched_no_card() {
        // 灵魂例（#542 AC④，ADR-0048 快道）：savePrd 携 fastLane=true（收敛令/零必答
        // 短路/付费「直接开始做」视同确认）——收口观测直派首个构建 run，不出卡不确认
        Long projectId = persistedPrdProject("9732");
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                fastLaneFacts.record(command.workspaceId());
            }
            return new AgentReply(command.runId(), "已按收敛令直接产出 PRD 与切片计划");
        });

        appService.runOpinionTurn(projectId, "别问了直接做");

        // 快道直派：编码 run 在场；不出卡（岔路口位不置）
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(2)).converse(command.capture(), any());
        assertThat(command.getAllValues()).anyMatch(
                c -> c.sessionId().startsWith(CodingRunDispatcher.SESSION_PREFIX));
        assertThat(jdbcTemplate.queryForMap(
                "SELECT fork_state FROM prj_projects WHERE id = ?", projectId)
                .get("fork_state")).isNull();
    }

    @Test
    void given_prd_produced_with_version_rows_when_opinion_turn_closes_then_first_generation_dispatched() {
        // 首场编码 run 的会话寻址与配置形状（#104 生成轨道首 run）：
        // PRD 已产出（判据＝prdProducedAt，去门化 #462 后与版本行无关——版本行在场
        // 只是 legacy 记行形态，不构成任何门）。
        // #601 内核接线：首建直派新内核（权威提示＋generation-kernel 装配键＋
        // kernelForm＋无工具规格）；#602：切片计划交接面随切片机器整体出局
        Long projectId = persistedPrdProject("9731");
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                fastLaneFacts.record(command.workspaceId()); // #542 快道（收敛令形态）
            }
            return new AgentReply(command.runId(), "已产出 PRD 与切片计划");
        });

        appService.runOpinionTurn(projectId, "别问了直接做");

        // 快道收口直派生成：首条编码 run＝内核形态（#601——会话锚 run、收口判据双件）
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(2)).converse(command.capture(), any());
        AgentCommand generation = command.getAllValues().stream()
                .filter(c -> c.sessionId().contains("-kernel-"))
                .findFirst().orElseThrow();
        assertThat(generation.systemPrompt()).isEqualTo(KernelSystemPrompt.text());
        assertThat(generation.agentKey())
                .isEqualTo(AgentscopeHarnessAgentFactory.KERNEL_AGENT_NAME);
        assertThat(generation.kernelForm()).isTrue();
        assertThat(generation.toolSpec()).isNull();
        assertThat(generation.prompt()).isEqualTo(GenerationAppService.KERNEL_FIRST_RUN_PROMPT);
    }

    @Test
    void given_main_no_build_plan_when_turn_closes_then_no_auto_redispatch_fork_stays() {
        // 无计划路径（#220 删 minimalFallback；#542 两入口退役改判）：主智能体产出
        // PRD 但未产出切片计划——自然收口不再自动补产（补产链随轮收口兜一轮退役）：
        // 岔路口卡照旧在场（PRD 可看），「立即生成」触发时才点火——不兜假计划。
        // #602：切片计划机器整体出局，「计划缺失」概念随之退役（内核路径无计划判据）
        Long projectId = persistedPrdProject("9730");
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("run-x", "已了解需求"));

        appService.runOpinionTurn(projectId, "做一个官网");

        // 只有意见轮一轮 main 会话（无补产、无编码 run），岔路口在场待用户点火
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        assertThat(command.getAllValues())
                .noneMatch(c -> c.sessionId().startsWith(CodingRunDispatcher.SESSION_PREFIX));
        assertThat(jdbcTemplate.queryForMap(
                "SELECT fork_state FROM prj_projects WHERE id = ?", projectId)
                .get("fork_state")).isEqualTo(ForkState.PENDING.getCode());
    }

    @Test
    void given_generation_in_flight_when_opinion_turn_closes_then_dispatch_skipped() {
        // 场景矩阵（#101 在途守卫；去门化 #462 后取「PRD 已产出＋在途」形）：生成
        // 进行中，意见轮收口静默跳过（不派不报错）——已产出 PRD（版本行在场）、但
        // 已有生成在途（共用件在途标记 begin 已占位），收口不派
        // 第二场生成，用户新意见照旧随对话收口
        Long projectId = persistedPrdProject("9728");
        givenSessionExecutorRunsInline();
        codingRunTrack.begin(projectId); // 模拟生成在途
        try {
            appService.runOpinionTurn(projectId, "做一个官网");

            // 在途：只有主智能体一轮 converse，不派第二次生成
            verify(agentClient, times(1)).converse(any(), any());
        }
        finally {
            codingRunTrack.end(projectId);
        }
    }

    // ---------- 改需求回路去门化（#462，ADR-0039）：意见轮收口一律受理即派 ----------
    //（#334 大改小改分流的机械停派已随确认门退役：范围级新草稿照派修正 run，
    // 停驻两列留库不删、无人读写——以下用例钉死「不停派」与「停驻列恒空」）

    /** 范围级新草稿（工作区 PRD 修订后的形态——去门化后随修正 run 起跑自动记为
     * 下一构建基准，不再有「待确认」停留位）。 */
    private static final String SCOPE_DRAFT =
            "# PRD\n\n## 功能清单\n\n1. 首页\n2. 管理后台——管理员可管理商品与订单\n";

    /** 工作区 PRD 读取桩（PRD 读端点同形：stat 首行＋cat 余文）。 */
    private void givenWorkspacePrd(String prdMarkdown) {
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100000\n" + prdMarkdown, "", 0));
    }

    @Test
    void given_un_generated_with_scope_draft_when_turn_closes_then_first_demo_dispatched() {
        // 去门化（#462）：未生成形态的范围级新草稿不再停派待确认——收口观测照派
        // 首个构建 run（新草稿＝首个构建基准：版本记行随起跑自动落，无确认动作）；
        // 停驻两列留库不删、无人写
        Long projectId = persistedPrdProject("9764");
        givenWorkspacePrd(SCOPE_DRAFT);
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                fastLaneFacts.record(command.workspaceId()); // #542 快道（收敛令形态）
                return new AgentReply(command.runId(), "已按意见修订 PRD");
            }
            return new AgentReply(command.runId(), "修正完成");
        });

        appService.runOpinionTurn(projectId, "交付范围加一个小程序端，直接做");

        // 收口后派首个构建 run：main 轮＋编码 run（至少两轮）；无停驻写读
        verify(agentClient, atLeast(2)).converse(any(), any());
        Map<String, Object> parked = jdbcTemplate.queryForMap(
                "SELECT prd_draft_opinion FROM prj_projects WHERE id = ?", projectId);
        assertThat(parked.get("prd_draft_opinion")).isNull();
    }

    // ---------- 意见轮在途意见排队成轮（#54：锚随任务落 + 失败清锚） ----------

    @Test
    void given_turn_error_when_close_then_no_dispatch() {
        // 对话轮失败不派修正（意见未被处理；error 事件已表达，用户重提即兜底）——
        // 内联执行器同生产语义吞掉轨道异常
        Long projectId = persistedGeneratedProject("9724");
        givenSessionExecutorSwallowsFailures();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("对话轮失败"));

        appService.runOpinionTurn(projectId, "把主色调改成绿色");

        verify(agentClient, times(1)).converse(any(), any());
    }

    // ---------- 访谈轮收口硬契约与悬空接管（ADR-0032，#373） ----------

    @Test
    void given_interview_turn_closes_with_question_when_observe_then_no_remedy_and_no_dangling() {
        // 合法收口形一（硬契约）：访谈期意见轮以挂起问答收口（再问一问）——零补救
        // 消息、零悬空标记（接管不触发）
        Long projectId = persistedProject("9750");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "先问一下", new AgentSuspension(
                    "reply-1", List.of(Map.of("id", "tc-1", "name", "ask_user"))));
        });
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false) // 提交守卫：放行
                .thenReturn(true); // 收口观测：挂起问答在场（合法形——不悬空）

        appService.runOpinionTurn(projectId, "做一个官网");

        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
        assertThat(appService.interviewDangling(projectId)).isFalse();
    }

    @Test
    void given_quota_full_when_turn_closes_with_drift_question_then_platform_answers_it_and_resumes_to_closure() {
        // #538 合法形收窄（ADR-0032 两形契约的条件收窄）：第 5 答后挂起问答不再放行——
        // 模型漂移再提问（第 6 问）时平台以收口指令代答该问题、续跑同一 run（纠偏只
        // 导向收口不导向新问）；代答续跑产出 PRD 即合法收口形二（首个构建照派、零悬空）
        Long projectId = persistedProject("9756");
        seedAnsweredGaps(projectId, 5);
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) { // 意见轮：漂移再提问（第 6 问）
                Consumer<AgentEvent> sink = invocation.getArgument(1);
                sink.accept(new AgentEvent(AgentEventTypes.QUESTION_RAISED,
                        new java.util.LinkedHashMap<>(Map.of(
                                AgentEventTypes.RUN_FIELD, command.runId(),
                                AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-drift",
                                AgentEventTypes.WAIT_SUMMARY_FIELD, "还要问一个？",
                                AgentEventTypes.WAIT_DATA_FIELD, Map.of(
                                        "toolCalls", List.of(Map.of(
                                                "id", "tc-d", "name", "ask_user")),
                                        "questions", List.of(Map.of(
                                                "question", "还要问一个？")))))));
                return new AgentReply(command.runId(), "还有一个问题", new AgentSuspension(
                        "reply-drift", List.of(Map.of("id", "tc-d", "name", "ask_user"))));
            }
            return new AgentReply(command.runId(), "构建完成");
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation -> {
            AgentResume resume = invocation.getArgument(0);
            // 代答续跑：模型按收口指令产出 PRD（事实面置位）
            Project project = projectRepository.findById(projectId).orElseThrow();
            project.markPrdProduced();
            projectRepository.save(project);
            return new AgentReply(resume.runId(), "好的，按推荐补全并保存 PRD");
        });
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 提交守卫：放行
                .thenReturn(true)   // 收口观测：漂移第 6 问挂起在场（收窄——不视作合法形一）
                .thenReturn(false); // 代答续跑收口观测：无挂起、PRD 已产出

        appService.runOpinionTurn(projectId, "再补充一个需求");

        // 挂起问答不再放行：收口作答（resume 文本＝收口指令口径）并续跑同 run
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().resumeText()).contains("额度已用尽");
        assertThat(resume.getValue().systemPrompt()).contains("【收口指令】");
        // 漂移卡由收口作答置位、不另计缺口（同缺口重派不另计——计数仍 5）；
        // PRD 产出即收口形二：零悬空、零补救
        assertThat(conversationHistory.answeredGapCount(projectId)).isEqualTo(5);
        assertThat(appService.interviewDangling(projectId)).isFalse();
        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
    }

    @Test
    void given_quota_full_when_turn_dangles_then_corrective_round_only_directs_to_closure() {
        // #538 纠偏收口：第 5 问后悬空收口（无问无 PRD）的纠偏轮只导向收口——收口
        // 微指令（不再提问、缺口按推荐补全、立即 savePrd），不问任何问题；纠偏轮产出
        // PRD 即收口形二（照派首个构建、零悬空）
        Long projectId = persistedProject("9757");
        seedAnsweredGaps(projectId, 5);
        givenSessionExecutorRunsInline();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")
                    && command.prompt().contains("额度已用尽")) {
                // 纠偏收口轮：模型按收口微指令产出 PRD（事实面置位）
                Project project = projectRepository.findById(projectId).orElseThrow();
                project.markPrdProduced();
                projectRepository.save(project);
                return new AgentReply(command.runId(), "已按推荐补全并保存 PRD");
            }
            return new AgentReply(command.runId(), "我还没整理好");
        });

        appService.runOpinionTurn(projectId, "继续");

        // 纠偏轮命令＝收口微指令（只导向收口：不再 ask_user、立即 savePrd）；其后
        // PRD 产出即收口形二——首个构建 run 照派（coder 会话 2 次，本断言不数）
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(2)).converse(command.capture(), any());
        List<AgentCommand> mainRounds = command.getAllValues().stream()
                .filter(c -> c.sessionId().startsWith("main-"))
                .toList();
        assertThat(mainRounds).hasSize(2);
        AgentCommand corrective = mainRounds.get(1);
        assertThat(corrective.prompt())
                .contains("额度已用尽")
                .contains("savePrd")
                .doesNotContain("ask_user 重新提出");
        assertThat(appService.interviewDangling(projectId)).isFalse();
        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
    }

    @Test
    void given_quota_corrective_exhausts_when_drift_questions_persist_then_remedy_and_marker() {
        // #538 纠偏收口封顶两轮（同 ADR-0036 防烧 token 姿态）：代答续跑再挂起→再代答→
        // 再挂起＝双失格——置悬空标记＋额度文案补救（恒带「继续」指路；用户作答仍可推进，
        // 续跑随附收口指令）
        Long projectId = persistedProject("9758");
        seedAnsweredGaps(projectId, 5);
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "还有一个问题", new AgentSuspension(
                    "reply-drift-1", List.of(Map.of("id", "tc-d1", "name", "ask_user"))));
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation ->
                new AgentReply(((AgentResume) invocation.getArgument(0)).runId(),
                        "我还是要问", new AgentSuspension("reply-drift-2",
                                List.of(Map.of("id", "tc-d2", "name", "ask_user")))));
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 提交守卫：放行
                .thenReturn(true)   // 意见轮收口观测：漂移挂起在场（收窄）
                .thenReturn(true)   // 代答续跑①收口观测：再挂起（收窄）
                .thenReturn(true);  // 代答续跑②收口观测：再挂起（收窄）

        appService.runOpinionTurn(projectId, "再补充一个需求");

        // 两轮收口作答后双失格：悬空标记＋额度文案补救（恒带「继续」）
        verify(agentClient, times(2)).resume(any(), any());
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY),
                argThat(payload -> String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                        .contains("额度")
                        && String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                                .contains("继续")));
        assertThat(appService.interviewDangling(projectId)).isTrue();
    }

    @Test
    void given_paid_interview_when_turn_closes_with_question_then_suspension_admitted_as_usual() {
        // 付费翻转后五问额度解除：挂起问答照常＝合法收口形一（收窄只作用于试用态）——
        // 零代答、零纠偏收口
        Long projectId = persistedProject("9759");
        poolTopupAppService.topup(null, projectId, 1, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.INTERCEPT);
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "先问一下", new AgentSuspension(
                    "reply-1", List.of(Map.of("id", "tc-1", "name", "ask_user"))));
        });
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false) // 提交守卫：放行
                .thenReturn(true); // 收口观测：挂起问答在场（合法形——收窄不生效）

        appService.runOpinionTurn(projectId, "做一个官网");

        verify(agentClient, never()).resume(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
        assertThat(appService.interviewDangling(projectId)).isFalse();
    }

    @Test
    void given_interview_turn_closes_with_prd_when_observe_then_no_remedy_and_no_dangling() {
        // 合法收口形二（硬契约）：访谈期意见轮以 PRD 产出收口（含修订）——零补救、
        // 零悬空标记
        Long projectId = persistedPrdProject("9751");
        givenSessionExecutorRunsInline();

        appService.runOpinionTurn(projectId, "做一个官网");

        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
        assertThat(appService.interviewDangling(projectId)).isFalse();
    }

    @Test
    void given_interview_turn_dangles_with_narration_when_closes_then_generic_remedy_persisted_and_marker() {
        // 悬空形一（叙述型，#373 本地实录形态）：访谈期意见轮收口无挂起问答、无 PRD
        // ——智能体叙述「已完成」也改变不了平台事实。平台补一条如实状态（泛形＋「继续」
        // 指路）、落对话史、置悬空标记
        Long projectId = persistedProject("9752");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "需求梳理已完成，PRD 内容已在本地拟定完整");
        });

        MainAgentAppService.MainAgentRun run = appService.runOpinionTurn(projectId, "梳理好了吗");

        // 补救消息（平台自述面 guide-reply 同族，SSE 事件封闭集零新增）：锚本轮
        // runId、无 prompt 键（无新用户话语——不重复重建用户气泡）、泛形＋「继续」
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY),
                argThat(payload -> projectId.toString().equals(payload.get(EventsAppService.PROJECT_FIELD))
                        && run.runId().equals(payload.get(EventsAppService.RUN_FIELD))
                        && !payload.containsKey(AgentEventTypes.GUIDE_PROMPT_FIELD)
                        && DispatchAppService.GUIDE_LABEL.equals(payload.get(AgentEventTypes.GUIDE_LABEL_FIELD))
                        && String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                                .contains("上一轮没有正常完成")
                        && String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD)).contains("继续")));
        // 落对话史（guide 行挂本轮 runId——刷新回访补救不丢）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_conversation_entries WHERE project_id = ? AND kind = 6",
                Integer.class, projectId)).isEqualTo(1);
        assertThat(appService.interviewDangling(projectId)).isTrue();
    }

    @Test
    void given_interview_turn_killed_when_converse_fails_then_interrupted_remedy_and_marker() {
        // 悬空形二（判死收口，#371 同被不变量命中）：访谈期对话轮判死/失败收口——
        // 同为无问无 PRD 收口，平台补救点名「本轮被中断」并置标记
        Long projectId = persistedProject("9753");
        givenSessionExecutorSwallowsFailures();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("活超时判死：静默超阈值"));

        MainAgentAppService.MainAgentRun run = appService.runOpinionTurn(projectId, "继续梳理");

        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY),
                argThat(payload -> run.runId().equals(payload.get(EventsAppService.RUN_FIELD))
                        && String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                                .contains("本轮被中断")
                        && String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD)).contains("继续")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_conversation_entries WHERE project_id = ? AND kind = 6",
                Integer.class, projectId)).isEqualTo(1);
        assertThat(appService.interviewDangling(projectId)).isTrue();
    }

    @Test
    void given_interview_turn_save_prd_failed_when_closes_then_prd_failed_remedy_and_marker() {
        // 悬空形三（savePrd 工具失败）：轮内 savePrd 落盘失败（失败事实）＋收口无问
        // 无 PRD——补救点名「刚才写 PRD 一步失败了」
        Long projectId = persistedProject("9754");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                prdRevisions.recordFailure(command.workspaceId());
                return new AgentReply(command.runId(), "PRD 内容已拟定，工作区容器不存在保存失败");
            }
            return new AgentReply(command.runId(), "好的");
        });

        MainAgentAppService.MainAgentRun run = appService.runOpinionTurn(projectId, "收敛吧");

        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY),
                argThat(payload -> run.runId().equals(payload.get(EventsAppService.RUN_FIELD))
                        && String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                                .contains("写 PRD 一步失败了")
                        && String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD)).contains("继续")));
        assertThat(appService.interviewDangling(projectId)).isTrue();
    }

    @Test
    void given_dangling_then_recovery_turn_dangles_again_then_marker_kept_and_second_remedy() {
        // 恢复轮再悬空：标记保持（继续接管）＋再补一条如实状态（每轮悬空收口各补一条）
        Long projectId = persistedProject("9755");
        givenSessionExecutorRunsInline();

        appService.runOpinionTurn(projectId, "做一个官网");
        appService.runOpinionTurn(projectId, "继续");

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(2)).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), payloads.capture());
        assertThat(payloads.getAllValues()).allSatisfy(payload ->
                assertThat(String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD)))
                        .contains("继续"));
        assertThat(appService.interviewDangling(projectId)).isTrue();
    }

    // ---------- 收口纠偏链（ADR-0036，#445） ----------

    @Test
    void given_dangling_when_corrective_round_1_asks_then_rescued_without_remedy() {
        // 纠偏链①：悬空命中先派纠偏轮（主档 flash＋确定性微指令）——纠回（挂起问答
        // 在场）即不播报不置标记：用户只见一张带选项的问答卡
        Long projectId = persistedProject("9760");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.prompt().contains("平台纠正")) {
                return new AgentReply(command.runId(), "重新提问", new AgentSuspension(
                        "reply-c", List.of(Map.of("id", "tc-c", "name", "ask_user"))));
            }
            return new AgentReply(command.runId(), "需求梳理已完成");
        });
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false) // 提交守卫：放行
                .thenReturn(false) // 收口观测：无问无 PRD → 纠偏链
                .thenReturn(true); // 纠偏轮收口观测：挂起问答在场（救回）

        appService.runOpinionTurn(projectId, "做一个官网");

        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
        assertThat(appService.interviewDangling(projectId)).isFalse();
        // 纠偏微指令：教规则（ask_user）＋令当场重问；档位＝主档（库值优先缺省回落）
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(1).prompt())
                .contains("ask_user").contains("重新提出");
        assertThat(commands.getAllValues().get(1).modelString())
                .isEqualTo(ModelConfigSeed.FLASH_MODEL_STRING);
    }

    @Test
    void given_corrective_round_1_fails_when_round_2_same_tier_rescues() {
        // 纠偏链②：①（主档 flash）仍失格 → ②同档（升档随模型路由退役，#606）
        // ＋同款微指令——纠正只靠确定性微指令；救回即不播报
        Long projectId = persistedProject("9761");
        givenSessionExecutorRunsInline();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (calls.incrementAndGet() == 3) {
                return new AgentReply(command.runId(), "重新提问", new AgentSuspension(
                        "reply-c", List.of(Map.of("id", "tc-c", "name", "ask_user"))));
            }
            return new AgentReply(command.runId(), "好的");
        });
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false) // 提交守卫
                .thenReturn(false) // 收口观测：无问无 PRD → 纠偏链
                .thenReturn(false) // 纠偏①收口观测：失格
                .thenReturn(true); // 纠偏②收口观测：挂起问答在场（救回）

        appService.runOpinionTurn(projectId, "做一个官网");

        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
        assertThat(appService.interviewDangling(projectId)).isFalse();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(commands.capture(), any());
        assertThat(commands.getAllValues().get(2).modelString())
                .isEqualTo(ModelConfigSeed.FLASH_MODEL_STRING);
    }

    @Test
    void given_corrective_chain_exhausts_when_double_failure_then_remedy_and_marker() {
        // 纠偏链封顶（ADR-0036）：双失格才置悬空标记＋泛形播报（ADR-0032 兜底原样）——
        // 恰两轮纠偏（converse 共 3 次），不无限重试（防烧 token）
        Long projectId = persistedProject("9762");
        givenSessionExecutorRunsInline();

        MainAgentAppService.MainAgentRun run = appService.runOpinionTurn(projectId, "做一个官网");

        verify(agentClient, times(3)).converse(any(), any());
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY),
                argThat(payload -> run.runId().equals(payload.get(EventsAppService.RUN_FIELD))
                        && String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                                .contains("上一轮没有正常完成")));
        assertThat(appService.interviewDangling(projectId)).isTrue();
    }

    @Test
    void given_dangling_then_recovery_turn_raises_question_when_settles_then_marker_cleared() {
        // 标记生命周期＝合法收口即清（ADR-0032，经纠偏链后）：双失格置标记 → 恢复轮
        // （接管期输入直达意见轮）再问一问（挂起问答）——标记清除，入口恢复三分类
        Long projectId = persistedProject("9756");
        givenSessionExecutorRunsInline();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (calls.incrementAndGet() == 4) {
                return new AgentReply(command.runId(), "先问一下", new AgentSuspension(
                        "reply-1", List.of(Map.of("id", "tc-1", "name", "ask_user"))));
            }
            return new AgentReply(command.runId(), "好的"); // 首轮＋两轮纠偏：失格收口
        });
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false) // 首轮提交守卫：放行
                .thenReturn(false) // 首轮收口观测：无问无 PRD → 纠偏链
                .thenReturn(false) // 纠偏①收口观测：失格
                .thenReturn(false) // 纠偏②收口观测：失格（置标记＋播报）
                .thenReturn(false) // 恢复轮提交守卫：放行
                .thenReturn(true); // 恢复轮收口观测：挂起问答（合法形——清标记）

        appService.runOpinionTurn(projectId, "做一个官网");
        assertThat(appService.interviewDangling(projectId)).isTrue();

        appService.runOpinionTurn(projectId, "继续");

        assertThat(appService.interviewDangling(projectId)).isFalse();
    }

    @Test
    void given_dangling_then_prd_produced_before_kill_when_turn_fails_then_marker_cleared() {
        // 判死路径的标记生命周期闭合（评审追加，经纠偏链后）：双失格置标记 → 恢复轮内
        // savePrd 已成功（PRD 已产出）后判死——PRD 产出即合法收口事实，标记随清不滞留
        //（期间输入不再被接管），catch 侧不补第二条补救
        Long projectId = persistedProject("9759");
        givenSessionExecutorSwallowsFailures();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (calls.incrementAndGet() == 4) {
                // 恢复轮：savePrd 已成功（状态位已置）后判死
                Project project = projectRepository.findById(projectId).orElseThrow();
                project.markPrdProduced();
                projectRepository.save(project);
                throw new IllegalStateException("活超时判死：静默超阈值");
            }
            return new AgentReply(command.runId(), "好的"); // 首轮＋两轮纠偏：失格收口
        });

        appService.runOpinionTurn(projectId, "做一个官网");
        assertThat(appService.interviewDangling(projectId)).isTrue();

        appService.runOpinionTurn(projectId, "继续");

        assertThat(appService.interviewDangling(projectId)).isFalse();
        // 补救恰一条（首轮双失格）——PRD 已出的判死不补第二条
        verify(eventsAppService, times(1)).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
    }

    @Test
    void given_inquiry_during_interview_when_answered_then_no_remedy_and_no_dangling() {
        // 咨询轮豁免（答询零产物是常态）：访谈期答询不触发不变量——零补救零标记
        Long projectId = persistedProject("9757");
        givenSessionExecutorRunsInline();

        appService.answerInquiry(projectRepository.findById(projectId).orElseThrow(),
                "项目现在什么状态？");

        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
        assertThat(appService.interviewDangling(projectId)).isFalse();
    }

    @Test
    void given_iteration_turn_closes_without_question_when_observe_then_no_remedy() {
        // 迭代期豁免（PRD 已出）：意见轮无问收口是受理常态（受理即派），不变量不适用
        Long projectId = persistedGeneratedProject("9758");
        givenSessionExecutorRunsInline();
        givenConverseMainRepliesAndExecutorFinishes("好的，会处理的");

        appService.runOpinionTurn(projectId, "按钮改成蓝色");

        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.GUIDE_REPLY), anyMap());
        assertThat(appService.interviewDangling(projectId)).isFalse();
    }

    // ---------- 测试数据 ----------

    private Long persistedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("访谈项目", null, Long.parseLong(workspaceId), OWNER, null));
        return project.getId();
    }

    /** 已答问答缺口种子（#538 五问额度用例）：直接落 n 张已答问答卡（缺口标识＝问题
     *  文本；同 run 补作答行＝用户已答语义——收口作答收口的卡不计入计数）。 */
    private void seedAnsweredGaps(Long projectId, int count) {
        for (int i = 1; i <= count; i++) {
            entries.save(ConversationEntry.answer(projectId, "seed-run-" + i, "种子答复"));
            ConversationEntry card = ConversationEntry.question(projectId, "seed-run-" + i,
                    new java.util.LinkedHashMap<>(Map.of(
                            AgentEventTypes.RUN_FIELD, "seed-run-" + i,
                            AgentEventTypes.WAIT_ENGINE_REF_FIELD, "seed-reply-" + i,
                            AgentEventTypes.WAIT_DATA_FIELD, Map.of(
                                    "toolCalls", List.of(Map.of("id", "tc-" + i, "name", "ask_user")),
                                    "questions", List.of(Map.of(
                                            "header", "提问", "question", "种子问题" + i,
                                            "multiple", false, "custom", true, "options", List.of()))))));
            card.markAnswered();
            entries.save(card);
        }
    }

    /** PRD 已产出、未生成的项目（去门化 #462：收口观测派首个构建 run 的判据＝
     * {@code prdProducedAt}——与旧版本行无关，旧版本表已随 #630 退场）。 */
    private Long persistedPrdProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("访谈项目", null, Long.parseLong(workspaceId), OWNER, null));
        project.markPrdProduced();
        return projectRepository.save(project).getId();
    }

    private Long persistedArchivedProject(String workspaceId) {
        Project project = Project.create("归档访谈项目", null, Long.parseLong(workspaceId), OWNER, null);
        project.archive();
        return projectRepository.save(project).getId();
    }
}
