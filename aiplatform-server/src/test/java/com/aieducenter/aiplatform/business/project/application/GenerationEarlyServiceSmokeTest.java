package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

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
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.business.project.application.GenerationAppService.GenerationRun;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 尽早起服真模型冒烟（#44 渐进预览前提；DEEPSEEK_API_KEY 未设或 docker daemon
 * 不在整类跳过）：真编码智能体从 PRD 生成系统的过程中，应用端口（8081）在 run
 * 早期即可访问——而不是临近收口才第一次起服务。仅 SSE 发射边 mock 收口观测。
 *
 * <p>机械判据四条：① run 成功收口且 generated_at 落位、收口后 8081 仍可达
 * （收口判据不因「尽早起」放松——converse 无异常不构成成功，8081 可达才落已生成）；
 * ② 8081 首次探活可达发生在 run 进行中（早于 run-finish 帧）；③ 首达时点不晚于
 * run 时长的九成——防旧形态回归的粗线（写完全部代码才起服的旧行为典型落在
 * 95%+ 时点；短 run 分母小、探活节流 ±2s 抖动，不贴更紧的线）；④ 首达之后至少
 * 还有两个直播步骤帧——实质判据（一步≈一次完整修改，起服后仍有两次完整修改
 * 在跑，「起服→curl 验证→收口」的假渐进过不了）。</p>
 *
 * <p>PRD 直接预置到工作区（等价 savePrd 的写文件 + 置已产出两步——冒烟聚焦编码
 * run 行为，BA 访谈链路另有 IterationChainSmokeTest 覆盖）。生成有自动重试：单次
 * 尝试的 error 帧后跟 run-retrying 续试不算失败，退出条件只认 run-finish，超时红
 * 并附帧序诊断。</p>
 */
@SpringBootTest
class GenerationEarlyServiceSmokeTest {

    /** 真模型从 PRD 生成的期限（含装依赖/建库/起服全流程）。 */
    private static final Duration GEN_DEADLINE = Duration.ofSeconds(1800);

    /** 探活节流：生成长 run，秒级一探足够（每探一次 docker exec）。 */
    private static final long PROBE_INTERVAL_MS = 2000;

    /** 冒烟用 PRD（极简单页展示 + 留言板——带库有落点、工作量可控）。 */
    private static final String PRD_CONTENT = """
            # 展示系统 PRD

            ## 需求背景
            用户需要一个简洁的单页展示系统，用于展示内容并收集访客留言。

            ## 目标用户
            普通访客，无需登录。

            ## 核心场景
            访客打开首页看到标题与介绍；在留言板输入留言提交后，留言出现在列表中。

            ## 范围边界
            仅一个首页；无后台管理、无登录、无多语言。

            ## 关键约束
            页面简洁，中文界面。

            ## 功能清单
            1. 首页展示：显示页面标题与一段介绍文字；验收：打开首页可见标题与介绍。
            2. 留言板：访客输入留言提交后显示在页面的留言列表中；验收：提交一条留言后刷新页面，留言仍在（数据落库）。

            ## 待定项
            暂无。
            """;

    @Autowired
    private GenerationAppService appService;

    @Autowired
    private ProjectKnowledgeAppService knowledgeAppService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    /** SSE 发射边收口：捕获全帧带时戳（真实链路无订阅者，发射本身是观测缝）。 */
    @MockitoBean
    private AgentStreamAppService streamAppService;

    private final ConcurrentLinkedQueue<Frame> frames = new ConcurrentLinkedQueue<>();

    private String workspaceId;
    private Long projectId;
    private String coderSessionId;

    private record Frame(String type, Map<String, Object> payload, long atNanos) {
    }

    @BeforeAll
    static void requireEnvironment() {
        Assumptions.assumeTrue(
                System.getenv("DEEPSEEK_API_KEY") != null
                        && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过尽早起服真模型冒烟");
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
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
    @Timeout(2400)
    void given_prd_when_generate_then_app_port_reachable_early_in_run() {
        // 0) 真实 dev 容器 + 工作区记录 + 项目落库；帧捕获就位；PRD 预置（写文件 +
        //    置已产出——等价 savePrd，不走 BA 访谈）
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        doAnswer(invocation -> {
            frames.add(new Frame(invocation.getArgument(0), invocation.getArgument(1),
                    System.nanoTime()));
            return null;
        }).when(streamAppService).publish(any(), any());
        Project project = projectRepository.save(Project.create("渐进起服冒烟", null,
                Long.parseLong(workspaceId), null));
        projectId = project.getId();
        coderSessionId = CoderRunAttempts.SESSION_PREFIX + projectId;
        placePrd();
        projectRepository.findById(projectId).ifPresent(p -> {
            p.markPrdProduced();
            projectRepository.save(p);
        });

        // 1) 起跑生成（异步轨道），轮询探活直到 run 收口：首达记录在 run 进行中
        //    （run-finish 尚未到达时探通才算），退出只认 run-finish（error 帧可能
        //    属于会续试的中间尝试，不当退出条件）
        long startNanos = System.nanoTime();
        GenerationRun run = appService.startGeneration(projectId);
        assertThat(run.runId()).isNotEmpty();
        Long firstReachNanos = null;
        int framesAtReach = 0;
        Frame finish = null;
        while (finish == null) {
            finish = frames.stream()
                    .filter(f -> AgentEventTypes.RUN_FINISH.equals(f.type()))
                    .findFirst().orElse(null);
            if (finish == null) {
                if (firstReachNanos == null && appPortReachable()) {
                    firstReachNanos = System.nanoTime();
                    framesAtReach = frames.size();
                }
                if (System.nanoTime() - startNanos >= GEN_DEADLINE.toNanos()) {
                    throw new AssertionError("生成 run 未在期限内收口（" + GEN_DEADLINE
                            + "），已捕获帧序：" + frameDigest());
                }
                sleepQuietly();
            }
        }

        // 2) 判据①后半：收口后 8081 仍可达（服务常驻）+ generated_at 落位
        //    （8081 可达才落已生成——收口判据不因尽早起服放松）
        assertThat(awaitUntil(Duration.ofSeconds(30), () -> appPortReachable()))
                .as("收口后应用端口应仍可达").isTrue();
        assertThat(awaitUntil(Duration.ofSeconds(30), () -> jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                Timestamp.class, projectId)))
                .as("run 收口且 8081 可达后 generated_at 应落位").isNotNull();

        // 3) 判据②：首达发生在 run 进行中（早于 run-finish 帧）
        assertThat(firstReachNanos).as("应用端口应在 run 进行中即可访问（而非收口后）").isNotNull();

        // 帧时间线落盘（诊断面：起服前后各步骤在干嘛，成败都打）
        System.out.println("[early-service-smoke] 首达 " + msBetween(startNanos, firstReachNanos)
                + "ms / run " + msBetween(startNanos, finish.atNanos()) + "ms");
        System.out.println(frameTimeline(startNanos, firstReachNanos));

        // 4) 判据③：首达时点不晚于 run 时长九成——防旧形态回归的粗线（写完全部
        //    代码才起服的旧行为典型落在 95%+ 时点）；不贴更紧的线（短 run 分母
        //    小，探活节流 ±2s 的抖动会翻脸）
        long runNanos = finish.atNanos() - startNanos;
        assertThat(firstReachNanos - startNanos)
                .as("首达时点应在 run 时长九成前（首达 %dms / run %dms）",
                        msBetween(startNanos, firstReachNanos), msBetween(startNanos, finish.atNanos()))
                .isLessThanOrEqualTo(runNanos * 9 / 10);

        // 5) 判据④：首达之后至少还有两个直播步骤帧（一步≈一次完整修改——起服
        //    后仍有两次完整修改在跑，排除「起服→curl 验证→收口」的假渐进）
        assertThat(frames.stream().skip(framesAtReach)
                .filter(f -> AgentEventTypes.LIVE_STEP.equals(f.type())).count())
                .as("首达之后应仍有至少两个步骤帧到达（增量演进在发生）").isGreaterThanOrEqualTo(2);
    }

    // ---------- 编排件 ----------

    /** PRD 预置（heredoc 单引号定界不展开，正文无定界符冲突）。 */
    private void placePrd() {
        exec("mkdir -p /workspace/docs && cat > /workspace/docs/PRD.md <<'SMOKE_PRD_EOF'\n"
                + PRD_CONTENT + "SMOKE_PRD_EOF");
    }

    /** 8081 探活（容器内 curl；exitCode 0 = 端口有 HTTP 应答）。 */
    private boolean appPortReachable() {
        ExecResultResponse result = exec("curl -s -o /dev/null -w '%{http_code}' http://localhost:8081");
        return result.exitCode() == 0 && !result.stdout().isBlank()
                && !"000".equals(result.stdout().trim());
    }

    private ExecResultResponse exec(String command) {
        return workspaceLifecycleAppService.exec(workspaceId, new WorkspaceExecCommand(command));
    }

    /** 帧序诊断（超时红时附帧类型序列，长度封顶防刷屏）。 */
    private String frameDigest() {
        return frames.stream().map(Frame::type).limit(200).toList().toString();
    }

    /**
     * 帧时间线（诊断正本）：run 生命周期 + 直播步骤/动作帧逐条带相对时戳，
     * 首达时点插标记行——起服前后各步骤在干嘛一目了然；live-text/引擎透传
     * 逐段太密不进时间线。
     */
    private String frameTimeline(long startNanos, Long firstReachNanos) {
        StringBuilder timeline = new StringBuilder("[early-service-smoke] 帧时间线：\n");
        boolean reachMarked = false;
        for (Frame f : frames) {
            if (!reachMarked && firstReachNanos != null && f.atNanos() >= firstReachNanos) {
                timeline.append(String.format("%8.1fs == 首达（8081 首次可达） ==%n",
                        (firstReachNanos - startNanos) / 1e9));
                reachMarked = true;
            }
            if (AgentEventTypes.RUN_START.equals(f.type()) || AgentEventTypes.RUN_FINISH.equals(f.type())
                    || AgentEventTypes.ERROR.equals(f.type()) || AgentEventTypes.RUN_RETRYING.equals(f.type())
                    || AgentEventTypes.LIVE_STEP.equals(f.type())
                    || AgentEventTypes.LIVE_ACTION.equals(f.type())) {
                timeline.append(String.format("%8.1fs %s %s%n",
                        (f.atNanos() - startNanos) / 1e9, f.type(),
                        f.payload().getOrDefault(AgentEventTypes.LIVE_ACTION_FIELD, "")));
            }
        }
        return timeline.toString();
    }

    /** 两时点间隔的毫秒数（断言描述与时间线共用）。 */
    private static long msBetween(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    /** 有界轮询直到条件成立（null / false / 空串 = 未达成继续等；超时红）。 */
    private <T> T awaitUntil(Duration deadline, Supplier<T> condition) {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            T value = condition.get();
            if (satisfied(value)) {
                return value;
            }
            sleepQuietly();
        }
        throw new AssertionError("等待超时（" + deadline + "）");
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
            Thread.sleep(PROBE_INTERVAL_MS);
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
