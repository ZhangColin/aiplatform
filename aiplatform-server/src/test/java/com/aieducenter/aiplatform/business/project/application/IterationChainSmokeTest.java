package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.function.Supplier;

import cn.hutool.core.collection.CollUtil;

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
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 迭代链必达真模型冒烟（#43；DEEPSEEK_API_KEY 未设或 docker daemon 不在整类
 * 跳过）：「改主色调」场景全链绿——真 BA（真模型 + 真 dev 容器 + 真会话状态）修订
 * PRD 后，平台在 BA 回合收口<b>自动</b>派修正 run（不依赖任何派发工具调用——BA
 * 也没有派发工具），真编码智能体把系统改掉。仅 SSE / 通知发射边（无订阅者的
 * 广播口）mock 收口观测。
 *
 * <p>链路编排：压缩访谈（一句话 → 追问即催促收敛 → savePrd，BA 会话内有自己写的
 * PRD 上下文）→ 预置 8081 常驻小系统（主色 #3b82f6 蓝）+ 置已生成 → 意见轮「把
 * 系统的主色调改成绿色」→ 断言三件事：PRD 更新（含绿）、CODER run 自动起跑并
 * 收口、服务页面换色（蓝主色字面量消失）。</p>
 */
@SpringBootTest
class IterationChainSmokeTest {

    private static final Duration TURN_DEADLINE = Duration.ofSeconds(180);
    private static final Duration FIX_DEADLINE = Duration.ofSeconds(600);

    /** 预置系统的主色字面量（修正后应被换掉——「系统跟着改」的机械判据）。 */
    private static final String BLUE_MAIN_COLOR = "#3b82f6";

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

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    /** SSE 发射边收口：捕获全帧（真实链路无订阅者，发射本身是观测缝）。 */
    @MockitoBean
    private AgentStreamAppService streamAppService;

    /** 通知通道发射边收口（document-updated 观测）。 */
    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    private final ConcurrentLinkedQueue<Frame> frames = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Notify> notifies = new ConcurrentLinkedQueue<>();
    /** 本轮已见的挂起 engineRef（再挂起新 ref 才是新结果——同轮多次挂起的区分）。 */
    private final List<String> seenRefs = CollUtil.newArrayList();

    private String workspaceId;
    private Long projectId;
    private String baSessionId;
    private String coderSessionId;
    private String pageBefore;

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
                "DEEPSEEK_API_KEY 未设置，跳过链必达真模型冒烟");
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        if (baSessionId != null) {
            jdbcTemplate.update("DELETE FROM met_usage_events WHERE session_id = ?", baSessionId);
            jdbcTemplate.update("DELETE FROM cat_agent_state WHERE session_id = ?", baSessionId);
        }
        if (coderSessionId != null) {
            jdbcTemplate.update("DELETE FROM met_usage_events WHERE session_id = ?", coderSessionId);
            jdbcTemplate.update("DELETE FROM cat_agent_state WHERE session_id = ?", coderSessionId);
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
    @Timeout(1200)
    void given_generated_system_when_color_opinion_then_prd_updated_and_fix_auto_dispatched() {
        // 0) 真实 dev 容器 + 工作区记录 + 项目落库；帧/通知捕获就位
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
        Project project = projectRepository.save(Project.create("链必达冒烟", null,
                Long.parseLong(workspaceId), null));
        projectId = project.getId();
        baSessionId = BaInterviewAppService.SESSION_PREFIX + projectId;
        coderSessionId = CoderRunAttempts.SESSION_PREFIX + projectId;

        // 1) 压缩访谈：一句话 → 追问即催促收敛 → savePrd 产出 PRD（BA 会话内有
        //    自己写的 PRD 上下文——后续修订才有正本可依）
        convergeToInterviewPrd("做一个单页展示系统，主色调用蓝色，页面简洁");
        assertThat(prdContent()).as("访谈收敛后 PRD 应已产出且含主色调约定").contains("蓝");

        // 2) 预置已生成形态：8081 常驻小系统（主色蓝）+ generated_at 落位
        placeRunningSystem();
        pageBefore = servedPage();
        assertThat(pageBefore).as("预置系统应在 8081 可访问").contains(BLUE_MAIN_COLOR);
        projectRepository.findById(projectId).ifPresent(p -> {
            p.markGenerated();
            projectRepository.save(p);
        });

        // 3) 意见轮（真 BA）：改主色调——按新角色卡，BA 只判需求侧（此为需求变更
        //    → savePrd 修订），不调任何派发工具（也没有）
        settleOpinion("系统做得很好。现在提一条修改意见：请把系统的主色调改成绿色。");

        // 4a) PRD 更新：PRD 正文含绿（需求侧已落）
        awaitUntil(FIX_DEADLINE, () -> prdContent().contains("绿"));

        // 4b) 平台自动派修正 run：role-assigned(CODER) 帧到达——链的收口在平台
        //     代码，模型漏调任何工具都不断链
        Frame coderAssigned = awaitFrame(FIX_DEADLINE, frame ->
                AgentEventTypes.ROLE_ASSIGNED.equals(frame.type())
                        && "CODER".equals(frame.payload().get(AgentEventTypes.ROLE_FIELD)));
        assertThat(coderAssigned.runId()).isNotEmpty();

        // 4c) 修正 run 真跑完（真模型改真系统；error 帧即链路失败，如实红）
        Frame end = awaitUntil(FIX_DEADLINE, () -> frames.stream()
                .filter(f -> coderAssigned.runId().equals(f.runId()))
                .filter(f -> AgentEventTypes.RUN_FINISH.equals(f.type())
                        || AgentEventTypes.ERROR.equals(f.type()))
                .findFirst().orElse(null));
        assertThat(end.type()).as("修正 run 异常收口（帧序诊断见断言信息）")
                .isEqualTo(AgentEventTypes.RUN_FINISH);

        // 4d) 系统自动跟着改：页面仍可访问、内容已变、蓝色主色字面量被换掉
        awaitUntil(FIX_DEADLINE, () -> {
            String page = servedPage();
            return !page.isBlank() && (page.contains("绿")
                    || page.toLowerCase().contains("green")
                    || !page.contains(BLUE_MAIN_COLOR));
        });
        String pageAfter = servedPage();
        assertThat(pageAfter).as("系统应跟着意见变化（页面与改前不同）").isNotEqualTo(pageBefore);
        assertThat(pageAfter).as("主色蓝应被换掉（改后页面：%s）", pageAfter)
                .doesNotContain(BLUE_MAIN_COLOR);

        // 4e) 编码智能体工具面收紧的行为事实（真链路）：修正 run 全程无问答挂起、
        //     无问答/存 PRD/派发类工具面痕迹——这些工具不在 CODER 的工具面，模型
        //     无从调用
        List<Frame> coderFrames = frames.stream()
                .filter(f -> coderAssigned.runId().equals(f.runId())).toList();
        assertThat(coderFrames.stream().map(Frame::type))
                .doesNotContain(AgentEventTypes.QUESTION_RAISED);
        String coderPayloads = coderFrames.stream()
                .map(f -> String.valueOf(f.payload())).toList().toString();
        assertThat(coderPayloads)
                .doesNotContain("ask_user")
                .doesNotContain("savePrd")
                .doesNotContain("startFixRun");
        // 4f) 结束工具收口（#46）：真模型以 finish_fix 收口（工具调用帧可见——未
        //     调用即 run 未正常收口，本断言红），且动了系统（changed=true）不出
        //     「未动系统」帧——收口以工具事实观测，changed=true 现有收口行为不回归
        assertThat(coderPayloads).contains("finish_fix");
        assertThat(coderFrames.stream().map(Frame::type))
                .doesNotContain(AgentEventTypes.FIX_UNCHANGED);
    }

    // ---------- 编排步骤 ----------

    /** 压缩访谈：开场 → 每逢追问即催促收敛 → 收口 + PRD 产出（有界轮数）。 */
    private void convergeToInterviewPrd(String kickoff) {
        String outcome = runBaTurn(kickoff);
        for (int i = 0; i < 4 && !"finished".equals(outcome); i++) {
            outcome = settlePending(outcome,
                    "不要再继续提问了，现在就结束访谈，直接产出 PRD");
        }
        assertThat(outcome).as("访谈未在限轮内收敛（帧序见日志）").isEqualTo("finished");
        awaitUntil(TURN_DEADLINE, () -> !prdContent().isBlank());
    }

    /** 意见轮：每逢追问即以意见口径作答（不引入新需求），收口即回。 */
    private void settleOpinion(String opinion) {
        String outcome = runBaTurn(opinion);
        for (int i = 0; i < 2 && !"finished".equals(outcome); i++) {
            outcome = settlePending(outcome,
                    "不用再问了：就按这条意见处理——把系统的主色调改成绿色，请直接修订 PRD");
        }
        assertThat(outcome).as("意见轮未在限轮内收口（帧序见日志）").isEqualTo("finished");
    }

    /** 跑一轮 BA（意见/开场文本进指令区口径），返回首个结果（engineRef / finished）。 */
    private String runBaTurn(String text) {
        frames.clear();
        seenRefs.clear();
        appService.runInterviewTurn(projectId, text);
        return awaitOutcome();
    }

    /** 答复当前挂起（问答作答通道的编排入口调用——同 #19 端点的服务端路径）。 */
    @SuppressWarnings("unchecked")
    private String settlePending(String engineRef, String answer) {
        Frame question = frames.stream()
                .filter(f -> AgentEventTypes.QUESTION_RAISED.equals(f.type())
                        && engineRef.equals(String.valueOf(
                                f.payload().get(AgentEventTypes.WAIT_ENGINE_REF_FIELD))))
                .findFirst().orElseThrow(() -> new AssertionError("engineRef 无挂起帧: " + engineRef));
        Map<String, Object> body = parseBody(question.payload().get(AgentEventTypes.WAIT_DATA_FIELD));
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) body.get("toolCalls");
        assertThat(toolCalls).as("挂起帧 data 应带待确认工具清单").isNotEmpty();
        appService.answerQuestion(projectId, question.runId(), engineRef, toolCalls, answer);
        return awaitOutcome();
    }

    /** 等当前 BA 轮出结果：再挂起（返回新 engineRef）或收口（"finished"；error 红）。 */
    private String awaitOutcome() {
        return awaitUntil(TURN_DEADLINE, () -> {
            for (Frame frame : frames) {
                if (AgentEventTypes.ERROR.equals(frame.type())) {
                    throw new AssertionError("BA 轮异常收口：" + frame.payload());
                }
            }
            for (Frame frame : frames) {
                if (AgentEventTypes.RUN_FINISH.equals(frame.type())) {
                    return "finished";
                }
            }
            for (Frame frame : frames) {
                if (AgentEventTypes.QUESTION_RAISED.equals(frame.type())) {
                    String ref = String.valueOf(
                            frame.payload().get(AgentEventTypes.WAIT_ENGINE_REF_FIELD));
                    if (!seenRefs.contains(ref)) {
                        seenRefs.add(ref);
                        return ref;
                    }
                }
            }
            return null;
        });
    }

    // ---------- 预置已生成形态 ----------

    /** 预置可跑小系统：index.html（主色蓝）+ 8081 常驻 node 静态服务。 */
    private void placeRunningSystem() {
        exec("mkdir -p /workspace && cat > /workspace/index.html <<'SMOKE_EOF'\n"
                + "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<title>展示系统</title></head>"
                + "<body style=\"font-family: sans-serif; margin: 40px\">"
                + "<h1 style=\"color: " + BLUE_MAIN_COLOR + "\">展示系统</h1>"
                + "<p>主页内容。</p></body></html>\nSMOKE_EOF");
        exec("cat > /workspace/server.js <<'SMOKE_EOF'\n"
                + "const http = require('http'); const fs = require('fs');\n"
                + "http.createServer((req, res) => {\n"
                + "  res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});\n"
                + "  res.end(fs.readFileSync('/workspace/index.html'));\n"
                + "}).listen(8081, '0.0.0.0');\nSMOKE_EOF");
        exec("sh -c 'cd /workspace && nohup node server.js >/dev/null 2>&1 & echo started'");
        awaitUntil(TURN_DEADLINE, () -> servedPage().contains(BLUE_MAIN_COLOR));
    }

    // ---------- 观测件 ----------

    private String prdContent() {
        try {
            PrdResponse prd = queryAppService.prd(projectId);
            return prd != null && prd.content() != null ? prd.content() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 8081 页面正文（容器内 curl；空串 = 不可访问）。 */
    private String servedPage() {
        ExecResultResponse result = exec("curl -s http://localhost:8081");
        return result.exitCode() == 0 ? result.stdout() : "";
    }

    private ExecResultResponse exec(String command) {
        return workspaceLifecycleAppService.exec(workspaceId, new WorkspaceExecCommand(command));
    }

    private Frame awaitFrame(Duration deadline, Predicate<Frame> where) {
        return awaitUntil(deadline, () -> frames.stream().filter(where).findFirst().orElse(null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(Object raw) {
        try {
            if (raw instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return new ObjectMapper().readValue(String.valueOf(raw), Map.class);
        } catch (IOException e) {
            throw new AssertionError("挂起帧 body 解析失败: " + raw, e);
        }
    }

    /**
     * 有界轮询直到条件成立（null / false / 空串 = 未达成继续等；超时红，附已
     * 捕获帧序诊断）。条件抛 AssertionError 即刻失败（BA 轮 error 帧口径）。
     */
    private <T> T awaitUntil(Duration deadline, Supplier<T> condition) {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            T value = condition.get();
            if (satisfied(value)) {
                return value;
            }
            sleepQuietly();
        }
        throw new AssertionError("等待超时（" + deadline + "），已捕获帧序："
                + frames.stream().map(f -> f.type() + "@" + f.runId()).toList());
    }

    private static boolean satisfied(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        return true;
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
