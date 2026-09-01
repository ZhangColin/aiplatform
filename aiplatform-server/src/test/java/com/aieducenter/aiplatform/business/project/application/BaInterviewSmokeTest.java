package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * BA 访谈循环真模型冒烟（DEEPSEEK_API_KEY 未设或 docker daemon 不在整类跳过）：
 * 编排全真链（真模型 + 真 dev 容器 + 真 PG 会话状态/问答答复续跑），仅 SSE 发射边
 * （无订阅者的广播口）一处 mock 收口观测。
 *
 * <p>验收口径：一句话开场 → 至少两轮实质提问（QUESTION 载荷带前端问答卡形状，
 * 经 JSON 往返）→ 答复续跑（answerQuestion 从项目侧事实重建恢复私货）→ 催促收敛
 * （BA 停止提问）→ savePrd 产出 PRD（工作区文件 + 状态位 + document-updated，
 * 修订再执行三更新）→ 同会话上下文延续；计量落 UsageEvent（dims.agentKind=ba）。</p>
 */
@SpringBootTest
class BaInterviewSmokeTest {

    private static final Duration TURN_DEADLINE = Duration.ofSeconds(150);

    @Autowired
    private BaInterviewAppService appService;

    @Autowired
    private ProjectQueryAppService queryAppService;

    @Autowired
    private ProjectKnowledgeAppService knowledgeAppService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 工作区走真实建链（容器 + wsp_workspaces 行——PRD 读端点按记录寻址）。 */
    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    /** SSE 发射边收口：捕获全帧（真实链路无订阅者，发射本身是观测缝）。 */
    @MockitoBean
    private AgentStreamAppService streamAppService;

    /** 通知通道发射边收口（document-updated 观测；BA 访谈链路无其余通知方）。 */
    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    private final ConcurrentLinkedQueue<Frame> frames = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Notify> notifies = new ConcurrentLinkedQueue<>();

    private String workspaceId;
    private Long projectId;
    private String sessionId;

    private record Frame(String type, Map<String, Object> payload) {
        String runId() {
            Object runId = payload.get("runId");
            return runId != null ? runId.toString() : "";
        }
    }

    private record Notify(String type, Map<String, Object> payload) {
    }

    @BeforeAll
    static void requireEnvironment() {
        Assumptions.assumeTrue(
                System.getenv("DEEPSEEK_API_KEY") != null
                        && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过真模型 BA 访谈冒烟");
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            jdbcTemplate.update("DELETE FROM met_usage_events WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM cat_agent_state WHERE session_id = ?", sessionId);
        }
        if (projectId != null) {
            knowledgeAppService.purgeByProject(projectId);
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
        if (workspaceId != null) {
            try {
                workspaceLifecycleAppService.destroy(workspaceId);
            } catch (RuntimeException e) {
                // 物理清理尽力而为（记录已随 destroy 删；Docker 残留可重试销毁）
            }
        }
    }

    @Test
    @Timeout(700)
    void given_one_liner_when_interview_then_question_loop_until_prd_produced() {
        // 真实 dev 容器 + 工作区记录 + 项目落库（编排面全真——PRD 读端点按
        // wsp_workspaces 行寻址，照实走 docker exec）
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        doAnswer(invocation -> {
            frames.add(new Frame(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(streamAppService).publish(any(), any());
        doAnswer(invocation -> {
            notifies.add(new Notify(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(notificationAppService).publish(any(), any());
        Project project = projectRepository.save(Project.create("冒烟官网", null,
                Long.parseLong(workspaceId), null));
        projectId = project.getId();
        sessionId = BaInterviewAppService.SESSION_PREFIX + projectId;

        // 1) 一句话开场 → BA 至少一轮实质提问（QUESTION，前端问答卡形状经 JSON 往返）
        BaInterviewAppService.InterviewRun first = appService.runInterviewTurn(projectId,
                "做一个企业官网");
        Frame question1 = awaitQuestionWaitOf(first.runId());
        Map<String, Object> body1 = waitBody(question1);
        assertThat(framesOf(AgentEventTypes.ROLE_ASSIGNED)).isNotEmpty();
        Map<String, Object> asked = firstQuestionOf(body1);
        assertThat(String.valueOf(asked.get("question"))).as("问题载荷：%s", asked).isNotBlank();
        // 问答卡形状齐备即可——单选/多选由模型按问题性质定，不锁死（曾断 multiple=false
        // 被模型正当的多选题打爆）
        assertThat(asked).containsKeys("header", "multiple", "custom", "options");

        // 2) 答复 → 续跑 → 第二轮提问（答复循环 ≥2 轮：续跑在同一 run 上再挂起，
        // 新挂起新 engineRef；答复刻意只覆盖目标用户——范围/约束仍缺，访谈必续）
        settle(question1, "目标用户是海外企业客户，主要看公司与产品介绍；其余方面我也不确定，你继续问");
        Frame question2 = awaitNextQuestionWaitOf(first.runId(), engineRefOf(question1));
        settle(question2, "要有中英文两个语言版本，范围就官网本体不要商城，风格简洁专业");

        // 3) 催促收敛：第二问答复后续跑要么再挂新问（逐问以催促文本答复再催）、要么
        // 收口（task-finish）——上限 4 轮必收敛。不设「无在悬问开新轮」回退：续跑
        // 出结果（新挂起/收口/异常）前开新轮会与内核挂起态相撞（ASKING 未批复即
        // 报错），而 awaitResumeOutcome 本就阻塞到出结果，回退是死重兼竞态源
        List<String> seenRefs = new ArrayList<>(
                List.of(engineRefOf(question1), engineRefOf(question2)));
        String outcome = awaitResumeOutcome(allRunIds(), seenRefs);
        for (int i = 0; i < 4 && !"finished".equals(outcome); i++) {
            Frame pending = waitFrameByRef(outcome);
            seenRefs.add(outcome);
            settle(pending, "不要再继续提问了，现在就结束访谈，直接产出 PRD");
            outcome = awaitResumeOutcome(allRunIds(), seenRefs);
        }
        assertThat(outcome).as("催促收敛未在限轮内收口（已捕获帧序：%s）",
                frames.stream().map(f -> f.type() + "@" + engineRefOrEmpty(f)).toList())
                .isEqualTo("finished");

        // #34 回归守卫：多轮答复续跑后，会话状态任何 tool_use 的 input 不得含
        // answer 键——答复经 block metadata（模型不可见；input 持久化且模型可见，
        // 带 answer 会教模型「ask_user 可自带答案」自答后续提问）
        assertNoAnswerKeyInToolUseInputs();

        // 4) 判定明确/催促收敛 → savePrd：工作区文件 + 状态位 + document-updated
        //    （PRD 读端点直读工作区文件——编码智能体同视图）
        awaitPrdProduced(1);
        assertThat(prdBitOf(projectId)).isNotNull();
        PrdResponse prd = queryAppService.prd(projectId);
        assertThat(prd.content()).as("PRD 正文（真模型产出）").isNotBlank();
        assertThat(prd.updatedAt()).isNotNull();

        // 5) PRD 修订（savePrd 再次执行）：三更新——文件/状态位/事件
        LocalDateTime firstBit = prdBitOf(projectId);
        BaInterviewAppService.InterviewRun revision = appService.runInterviewTurn(projectId,
                "需求有更新：官网要增加一个博客板块，请修订并重新保存 PRD");
        awaitRunEnd(revision.runId());
        awaitPrdProduced(2);
        assertThat(prdBitOf(projectId)).isAfterOrEqualTo(firstBit);
        assertThat(queryAppService.prd(projectId).content()).isNotBlank();

        // 6) 同会话上下文延续：访谈答复在上下文中可回溯（自由补充不丢）
        BaInterviewAppService.InterviewRun recall = appService.runInterviewTurn(projectId,
                "回顾访谈：我把目标用户答成了什么？只回答「海外企业客户」相关的答案要点，不要再提问");
        awaitRunEnd(recall.runId());
        assertThat(textOf(recall.runId())).contains("海外");

        // 7) 计量：BA 对话用量落 UsageEvent（dims 终态口径：agentKind=ba、归属项目）
        Integer usageRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE session_id = ?", Integer.class,
                sessionId);
        assertThat(usageRows).isPositive();
        Map<String, Object> usage = jdbcTemplate.queryForMap(
                "SELECT subject, dims->>'agentKind' AS agent_kind FROM met_usage_events"
                        + " WHERE session_id = ? LIMIT 1", sessionId);
        assertThat(String.valueOf(usage.get("subject"))).isEqualTo(projectId.toString());
        assertThat(String.valueOf(usage.get("agent_kind"))).isEqualTo("ba");
    }

    // ---------- 内部 ----------

    private Set<String> allRunIds() {
        Set<String> runs = new HashSet<>();
        frames.forEach(f -> runs.add(f.runId()));
        return runs;
    }

    /** 有界等待 document-updated 触达 ≥ n 次（savePrd 每次执行必发；工具在 run
     *  收口前执行，收敛轮结束即可等，留界兜底层间延迟）。 */
    private void awaitPrdProduced(int atLeast) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (documentUpdatedCount() >= atLeast && prdBitOf(projectId) != null) {
                return;
            }
            sleepQuietly();
        }
        assertThat(documentUpdatedCount()).as("document-updated 触达（已捕获：%s）",
                notifies.stream().map(n -> n.type()).toList()).isGreaterThanOrEqualTo(atLeast);
        assertThat(prdBitOf(projectId)).as("PRD 状态位").isNotNull();
    }

    private long documentUpdatedCount() {
        return notifies.stream()
                .filter(n -> ProjectEventTypes.DOCUMENT_UPDATED.equals(n.type())).count();
    }

    /** 项目「PRD 已产出」状态位（savePrd 落盘回调写入）。 */
    private LocalDateTime prdBitOf(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT prd_produced_at FROM prj_projects WHERE id = ?",
                LocalDateTime.class, projectId);
    }

    /** BA 会话状态正文（#34 守卫的判读源：tool_use input 不含 answer 键）。 */
    private String sessionStateJson() {
        return jdbcTemplate.queryForObject(
                "SELECT state_data FROM cat_agent_state WHERE session_id = ?",
                String.class, sessionId);
    }

    /** #34 守卫判读：遍历会话状态 tool_use 块，input 含 answer 键即违规（答复正道 =
     *  block metadata，模型不可见）。 */
    private void assertNoAnswerKeyInToolUseInputs() {
        List<String> offenders = new ArrayList<>();
        try {
            for (var msg : new ObjectMapper().readTree(sessionStateJson()).path("context")) {
                for (var block : msg.path("content")) {
                    if ("tool_use".equals(block.path("type").asText())
                            && block.path("input").has("answer")) {
                        offenders.add(block.path("id").asText());
                    }
                }
            }
        }
        catch (java.io.IOException e) {
            throw new AssertionError("会话状态解析失败", e);
        }
        assertThat(offenders).as("#34：tool_use input 不应含 answer 键（违规件 id）").isEmpty();
    }

    /** 等待该 run 的 QUESTION 挂起帧（问答卡呈现源）。 */
    private Frame awaitQuestionWaitOf(String runId) {
        Frame wait = awaitFrame(runId, AgentEventTypes.WAIT_RAISED);
        assertThat(wait.payload()).containsEntry(AgentEventTypes.WAIT_KIND_FIELD, "QUESTION");
        return wait;
    }

    /** 挂起帧的引擎侧请求 id（答复续跑的锚——一轮一值）。 */
    private static String engineRefOf(Frame wait) {
        return String.valueOf(wait.payload().get(AgentEventTypes.WAIT_ENGINE_REF_FIELD));
    }

    /** 帧的 engineRef（非挂起帧为空串——帧序诊断用）。 */
    private static String engineRefOrEmpty(Frame frame) {
        Object ref = frame.payload().get(AgentEventTypes.WAIT_ENGINE_REF_FIELD);
        return ref != null ? String.valueOf(ref) : "";
    }

    /** 等待该 run 的下一个 QUESTION（续跑同 run 再挂起——按排除已见 engineRef 区分）。 */
    private Frame awaitNextQuestionWaitOf(String runId, String excludeRef) {
        long deadline = System.nanoTime() + TURN_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            for (Frame frame : frames) {
                if (!runId.equals(frame.runId())
                        || !AgentEventTypes.WAIT_RAISED.equals(frame.type())) {
                    continue;
                }
                if (!excludeRef.equals(engineRefOf(frame))) {
                    assertThat(frame.payload())
                            .containsEntry(AgentEventTypes.WAIT_KIND_FIELD, "QUESTION");
                    return frame;
                }
            }
            sleepQuietly();
        }
        throw new AssertionError("答复续跑未再挂起提问（run=" + runId + "）——访谈在第二轮前收敛");
    }

    /** 按 engineRef 取挂起帧（催促循环锚定 awaitResumeOutcome 返回的新挂起）。 */
    private Frame waitFrameByRef(String engineRef) {
        return frames.stream()
                .filter(f -> AgentEventTypes.WAIT_RAISED.equals(f.type())
                        && engineRef.equals(engineRefOf(f)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("engineRef 无对应挂起帧: " + engineRef));
    }

    /** 等待该 run 收口（task-finish；error 视为失败）。 */
    private void awaitRunEnd(String runId) {
        Frame end = awaitFrame(runId, AgentEventTypes.TASK_FINISH, AgentEventTypes.ERROR);
        assertThat(end.type()).as("run %s 异常收口：%s", runId, end.payload())
                .isEqualTo(AgentEventTypes.TASK_FINISH);
    }

    /** 续跑出结果（跨候选 run 锚集）：再挂起（返回新 engineRef，排除已见）或收口
     *  （"finished"；error 直接失败）。 */
    private String awaitResumeOutcome(Set<String> runIds, List<String> seenRefs) {
        long deadline = System.nanoTime() + TURN_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            for (Frame frame : frames) {
                if (!runIds.contains(frame.runId())) {
                    continue;
                }
                if (AgentEventTypes.ERROR.equals(frame.type())) {
                    throw new AssertionError("run 异常收口：" + frame.payload()
                            + "；已捕获帧序：" + frames.stream()
                                    .map(f -> f.type() + "@" + engineRefOrEmpty(f))
                                    .toList());
                }
                if (AgentEventTypes.TASK_FINISH.equals(frame.type())) {
                    return "finished";
                }
                if (AgentEventTypes.WAIT_RAISED.equals(frame.type())) {
                    String ref = engineRefOf(frame);
                    if (!seenRefs.contains(ref)) {
                        return ref;
                    }
                }
            }
            sleepQuietly();
        }
        throw new AssertionError("续跑无结果超时（runs=" + runIds + "）；已捕获帧序："
                + frames.stream().map(f -> f.type() + "@" + engineRefOrEmpty(f)).toList());
    }

    private Frame awaitFrame(String runId, String... types) {
        long deadline = System.nanoTime() + TURN_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            for (Frame frame : frames) {
                if (runId.equals(frame.runId()) && List.of(types).contains(frame.type())) {
                    return frame;
                }
            }
            sleepQuietly();
        }
        throw new AssertionError("等待帧超时（" + List.of(types) + " run=" + runId
                + "），已捕获：" + frames.stream().map(f -> f.type() + "@" + f.runId()).toList());
    }

    /** 该 run 文本增量拼接（对话可见面）。 */
    private String textOf(String runId) {
        StringBuilder text = new StringBuilder();
        for (Frame frame : frames) {
            if (runId.equals(frame.runId()) && "text".equals(frame.type())) {
                Object data = frame.payload().get("data");
                if (data instanceof Map<?, ?> map && map.get("delta") != null) {
                    text.append(map.get("delta"));
                }
            }
        }
        return text.toString();
    }

    private List<Frame> framesOf(String type) {
        return frames.stream().filter(f -> type.equals(f.type())).toList();
    }

    /** 答复挂起问（问答作答通道的编排入口调用——同 #19 端点的服务端路径）。 */
    @SuppressWarnings("unchecked")
    private void settle(Frame wait, String answer) {
        Map<String, Object> body = waitBody(wait);
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) body.get("toolCalls");
        assertThat(toolCalls).as("挂起帧 data 应带待确认工具清单").isNotEmpty();
        appService.answerQuestion(projectId, wait.runId(), engineRefOf(wait), toolCalls, answer);
    }

    /** 挂起帧载荷（wait-raised 的 data）：JSON 往返后的前端问答卡/续跑载荷形状。 */
    private Map<String, Object> waitBody(Frame wait) {
        return parseBody(wait.payload().get(AgentEventTypes.WAIT_DATA_FIELD));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(Object raw) {
        try {
            if (raw instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return new ObjectMapper().readValue(String.valueOf(raw), Map.class);
        } catch (java.io.IOException e) {
            throw new AssertionError("挂起帧 body 解析失败: " + raw, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstQuestionOf(Map<String, Object> body) {
        Object questions = body.get("questions");
        assertThat(questions).as("QUESTION body 应带 questions 数组（前端问答卡形状）").isNotNull();
        return ((List<Map<String, Object>>) questions).get(0);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断", e);
        }
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
