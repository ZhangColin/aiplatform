package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.RunHeading;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.business.project.application.CoderRunAttempts.ClosingJudgment;
import com.semgravio.aiplatform.business.project.application.CoderRunAttempts.Prompts;
import com.semgravio.aiplatform.business.project.application.CoderRunAttempts.RunResult;
import com.semgravio.aiplatform.business.project.application.CoderRunAttempts.RunSeat;
import com.semgravio.aiplatform.business.project.application.rehearsal.RehearsalCurveAnalyzer;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 单长会话 60 分钟连跑排练（#434，#432 Step 2 落地前的 go/no-go 依据）：用现有
 * run 执行体（{@link RunSeat#executor()}）以<b>恒定会话寻址</b>把锚需求「背词记忆
 * 管家」全旅程（阶段 0＋9 片，同 dev 库锚项目切片计划）在同一执行会话内连续追加
 * ——镜像 Step 2「分段任务同会话连续追加」的目标语义，量三个数：
 *
 * <ul>
 * <li><b>上下文/token 增长曲线</b>：{@code met_usage_events} 按会话拉逐调用
 *     input＋cache_read，{@link RehearsalCurveAnalyzer} 锯齿判读压缩触发；</li>
 * <li><b>早期需求遗忘</b>：连跑收尾后在工作区内对早期片（录词/单词本/学词/
 *     艾宾浩斯间隔/题型）做探针 grep，早片交付物在会话末段仍完好＝无遗忘；</li>
 * <li><b>失败止损</b>：逐段记录尝试环消耗（重试次数/终态错误），暴露段级止损表现。</li>
 * </ul>
 *
 * <p>防 #379 复发：offload 关闭（平台装配硬编码）下连跑，收尾断言宿主
 * {@code C:\workspace} 无本会话孤儿转录。</p>
 *
 * <p><b>运行口径</b>：live 车道手跑——
 * {@code REHEARSAL_434_GO=yes REHEARSAL_434_VOLCENGINE_API_KEY=<真key> mvn test -P live
 * -Dtest=SingleSessionLongRunRehearsalLiveTest}。
 * 环境变量闸门防误触发 60 分钟真模型连跑（烧 volcengine 平台通道，不烧本地
 * 智能体 token）；真 key 经 env 注入测试库、跑完还原（测试库种子是占位 key）。
 * 三开关（收口硬门）保持缺省＝生产现行为。</p>
 */
@IntegrationTest
@Tag("live")
class SingleSessionLongRunRehearsalLiveTest {

    /**
     * 连跑墙钟上限：到点不再派下一段（在跑段跑完为止）。可用
     * {@code REHEARSAL_434_CAP_MINUTES} 缩短（冒烟试跑用——注意只拦新派段，在跑
     * 段自然跑完，故冒烟最低成本＝一段的自然时长）。
     */
    private static final Duration RUN_CAP = capFromEnv();

    /**
     * 真模型 key 注入（env 闸门）：测试库种子是占位 key（sk-test-ark），连跑需真
     * volcengine key——经环境变量 {@code REHEARSAL_434_VOLCENGINE_API_KEY} 注入
     * （key 不落仓、不进代码；缺省跳过本类，防占位 key 中途烧一轮失败）。key 取法：
     * dev 库（docker aiplatform-pg 容器）{@code prj_model_providers} 行。
     */
    private static final String VOLCENGINE_KEY_ENV = "REHEARSAL_434_VOLCENGINE_API_KEY";
    /** 全局安全闸（60 分钟连跑＋收口与测量余量）。 */
    private static final int TIMEOUT_SECONDS = 5400;

    /** 恒定会话寻址＝单会话连续追加的承载（#114 后执行体侧无此形态，排练即验它）。 */
    private static final String SESSION_ID = "coder-rehearsal434-single";

    /** 锚项目切片计划（dev 库 377311333753763207「背词助手」＝背词记忆管家 PRD）。 */
    private static final List<String> SLICES = List.of(
            "用户能先看到可点的界面骨架（孩子端+家长端主要页面与导航一次搭出，可交互、可预览，暂不接数据与后端逻辑）",
            "用户能注册登录（孩子与家长双角色账号体系）",
            "孩子能录入新单词（拍照OCR识别与手动打字输入，系统自动补全释义与发音，可修正）",
            "孩子能查看单词本并按状态分组筛选、管理单词",
            "孩子能学单词（看卡片、听标准发音）",
            "孩子能按记忆曲线参与测试（选择题/拼写题/听力题随熟练度切换，答对推进答错回退，有结果页）",
            "家长能绑定孩子并查看学习进度与掌握情况",
            "家长能查看错词分析与学习时长/活跃度统计",
            "家长能收到孩子逾期未复习的提醒");

    /** 规模标签（锚项目自报，dev 库 size_label 列）：路由升档信号，生产同口径保留。 */
    private static final List<SliceSizeLabel> SIZE_LABELS = List.of(
            SliceSizeLabel.LARGE, SliceSizeLabel.MEDIUM, SliceSizeLabel.LARGE,
            SliceSizeLabel.MEDIUM, SliceSizeLabel.SMALL, SliceSizeLabel.LARGE,
            SliceSizeLabel.MEDIUM, SliceSizeLabel.MEDIUM, SliceSizeLabel.SMALL);

    private static final String ARTIFACT_DIR = "target/rehearsal-434";

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycle;

    @Autowired
    private CoderRunAttempts coderRunAttempts;

    @Autowired
    private ClosingProbes closingProbes;

    @Autowired
    private ProofOfOutputGate proofGate;

    @Autowired
    private VerificationPresenceGate verificationPresence;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String workspaceId;
    private final List<Map<String, Object>> providerKeyBackup = new ArrayList<>();
    private final List<SegmentLog> segmentLogs = new ArrayList<>();

    @BeforeAll
    static void requireDockerAndConsent() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
        Assumptions.assumeTrue("yes".equals(System.getenv("REHEARSAL_434_GO")),
                "未设 REHEARSAL_434_GO=yes——60 分钟真模型连跑需显式 consent");
        Assumptions.assumeTrue(
                System.getenv(VOLCENGINE_KEY_ENV) != null
                        && !System.getenv(VOLCENGINE_KEY_ENV).isBlank(),
                "未设 " + VOLCENGINE_KEY_ENV + " ——真模型 key 需显式注入（占位 key 烧失败）");
    }

    @AfterEach
    void tearDown() {
        restoreProviderKeys();
        if (workspaceId != null && !"yes".equals(System.getenv("REHEARSAL_434_KEEP_WS"))) {
            try {
                workspaceLifecycle.destroy(workspaceId);
            }
            catch (RuntimeException e) {
                // 物理清理尽力而为（Docker 残留可重试销毁）
            }
        }
        else if (workspaceId != null) {
            logLine("保留工作区供事后检视：%s", workspaceId);
        }
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void given_single_constant_session_when_anchor_journey_appended_then_measure_growth_forgetting_stoploss()
            throws Exception {
        syncProviderKeysFromDevDb();
        WorkspaceResponse workspace = workspaceLifecycle.create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        long workspaceKey = Long.parseLong(workspaceId);

        seedPrdAndGitBaseline();

        Project project = projectRepository.save(Project.create(
                "排练-单长会话-背词记忆管家", null, null, workspaceKey, 1L, null));
        BuildPlan plan = new BuildPlan(SLICES, null, null, SIZE_LABELS);

        Instant journeyStartedAt = Instant.now();
        String previousHandoff = null;
        for (int ord = 0; ord <= SLICES.size(); ord++) {
            if (Duration.between(journeyStartedAt, Instant.now()).compareTo(RUN_CAP) > 0) {
                logLine("连跑墙钟 %d 分钟到——不再派后续段（ord=%d）", RUN_CAP.toMinutes(), ord);
                break;
            }
            boolean stage0 = ord == 0;
            String slice = stage0 ? null : SLICES.get(ord - 1);
            String runId = EventsAppService.newRunId();
            AtomicInteger retryInvocations = new AtomicInteger();
            String segmentDesc = stage0 ? GenerationAppService.STAGE0_RETRY_DESC
                    : "实现切片「" + slice + "」";
            String handoff = previousHandoff;
            ProofOfOutputGate.Checking proof = proofGate.forRun(workspaceKey);

            logLine("派段 ord=%d runId=%s（已连跑 %d 分钟）", ord, runId,
                    Duration.between(journeyStartedAt, Instant.now()).toMinutes());
            Instant segmentStartedAt = Instant.now();
            RunResult result = coderRunAttempts.run(project, workspaceKey, runId, SESSION_ID,
                    new Prompts(
                            stage0 ? GenerationAppService.stage0Prompt(plan)
                                    : GenerationAppService.slicePrompt(plan, ord - 1, handoff),
                            errorScene -> {
                                retryInvocations.incrementAndGet();
                                return GenerationAppService.generationRetryPrompt(plan,
                                        segmentDesc, handoff, errorScene);
                            }),
                    (attemptRunId, changes) -> closeSegmentReplica(workspaceKey, proof, stage0),
                    "rehearsal-434", stage0, RunSeat.executor(),
                    stage0 ? ModelRoutingAppService.SegmentSignals.none()
                            : ModelRoutingAppService.SegmentSignals.slice(SIZE_LABELS.get(ord - 1)),
                    stage0 ? RunHeading.titled(GenerationAppService.STAGE0_TITLE)
                            : RunHeading.slice(slice, ord, SLICES.size()),
                    null);
            long segmentWallMs = Duration.between(segmentStartedAt, Instant.now()).toMillis();
            int attempts = 1 + retryInvocations.get();
            segmentLogs.add(new SegmentLog(ord, runId, segmentWallMs, attempts,
                    result.succeeded(), result.succeeded() ? null : result.lastError()));
            logLine("段 ord=%d 收场：成功=%s 尝试=%d 墙钟=%dms %s", ord, result.succeeded(),
                    attempts, segmentWallMs,
                    result.succeeded() ? "" : ("error=" + result.lastError()));
            if (!result.succeeded()) {
                // #429 同口径：片失败不拖垮整轨——继续派下一段（止损表现的观测面之一）
                continue;
            }
            previousHandoff = result.closingText();
        }

        writeArtifactsAndAssert(journeyStartedAt, workspaceKey);
    }

    /**
     * 收口复刻（对偶 {@code GenerationAppService.closeGenerationStage} 的判据件，
     * 不动段状态表/预览推送/版本成版——那些是编排面，排练只验执行体单会话面）：
     * 8081 探活＋入口文件完整＋产出证明闸＋验证在场闸（开关缺省＝生产现行为）。
     */
    private ClosingJudgment closeSegmentReplica(long workspaceKey,
            ProofOfOutputGate.Checking proof, boolean stage0) {
        closingProbes.requireReachable(workspaceKey);
        closingProbes.requireEntryFileComplete(workspaceKey);
        Map<String, Object> proofAnnotation = proof.proof();
        verificationPresence.requirePresence(Long.toString(workspaceKey));
        return ClosingJudgment
                .generation(stage0 ? GenerationAppService.STAGE0_CLOSING_SUMMARY
                        : "rehearsal-434 段收口")
                .withProofOfOutput(proofAnnotation);
    }

    // ---------- 现场置备 ----------

    private void seedPrdAndGitBaseline() {
        String prd = readAnchorPrd();
        // ①族纪律：进容器命令一律单引号形态。PRD 正文含 ASCII 双引号（Windows argv
        // 链剥不掉的安全形态不存在）——base64 单引号包裹传输、容器内解码，字节级保真
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(prd.getBytes(StandardCharsets.UTF_8));
        exec("echo '" + encoded + "' | base64 -d > /workspace/docs/PRD.md");
        exec("cd /workspace && git init -q -b main ."
                + " && git config user.name rehearsal && git config user.email rehearsal@local"
                + " && git add -A && git commit -qm baseline");
    }

    private static String readAnchorPrd() {
        try {
            return new String(new ClassPathResource("rehearsal/434-anchor-prd.md")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- 测量与 artifact ----------

    private void writeArtifactsAndAssert(Instant journeyStartedAt, long workspaceKey)
            throws IOException {
        Path dir = Path.of(ARTIFACT_DIR);
        Files.createDirectories(dir);

        List<RehearsalCurveAnalyzer.UsagePoint> points = jdbcTemplate.query("""
                SELECT model, input, cache_read, output, ts
                FROM met_usage_events WHERE session_id = ? ORDER BY ts
                """, (rs, n) -> new RehearsalCurveAnalyzer.UsagePoint(
                rs.getString("model"), rs.getLong("input"), rs.getLong("cache_read"),
                rs.getLong("output"), rs.getTimestamp("ts").toInstant()), SESSION_ID);
        RehearsalCurveAnalyzer.CurveReport curve = RehearsalCurveAnalyzer.analyze(points);

        StringBuilder csv = new StringBuilder("seq,model,input,cache_read,output,context\n");
        for (int i = 0; i < points.size(); i++) {
            RehearsalCurveAnalyzer.UsagePoint p = points.get(i);
            csv.append(i).append(',').append(p.model()).append(',').append(p.inputTokens())
                    .append(',').append(p.cacheReadTokens()).append(',').append(p.outputTokens())
                    .append(',').append(p.contextTokens()).append('\n');
        }
        Files.writeString(dir.resolve("usage-curve.csv"), csv.toString());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessionId", SESSION_ID);
        summary.put("journeyWallMs", Duration.between(journeyStartedAt, Instant.now()).toMillis());
        summary.put("curve", Map.of(
                "callCount", curve.callCount(),
                "firstContextTokens", curve.firstContextTokens(),
                "peakContextTokens", curve.peakContextTokens(),
                "finalContextTokens", curve.finalContextTokens(),
                "compactions", curve.compactions().stream()
                        .map(c -> Map.of("atIndex", c.atIndex(), "before", c.contextBeforeTokens(),
                                "after", c.contextAfterTokens(), "at", c.at().toString()))
                        .toList()));
        summary.put("segments", segmentLogs.stream()
                .map(s -> Map.of("ord", s.ord(), "runId", s.runId(), "wallMs", s.wallMs(),
                        "attempts", s.attempts(), "succeeded", s.succeeded(),
                        "error", s.error() == null ? "" : s.error()))
                .toList());
        summary.put("agentStateBytes", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(LENGTH(state_data::text)), 0) FROM cat_agent_state"
                        + " WHERE session_id = ?", Long.class, SESSION_ID));
        summary.put("forgettingProbes", runForgettingProbes());
        Files.writeString(dir.resolve("summary.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(summary));

        logLine("==== 排练测量摘要（artifact: %s）====", dir.toAbsolutePath());
        logLine("曲线：调用=%d 首=%d 峰=%d 末=%d 压缩事件=%d",
                curve.callCount(), curve.firstContextTokens(), curve.peakContextTokens(),
                curve.finalContextTokens(), curve.compactions().size());
        logLine("遗忘探针：%s", summary.get("forgettingProbes"));

        // 防 #379 复发：宿主 /workspace（Windows 即 C:\workspace）不得出现本会话孤儿转录
        Path hostWorkspace = Path.of("C:/workspace");
        if (Files.isDirectory(hostWorkspace)) {
            try (var stream = Files.list(hostWorkspace)) {
                List<String> orphans = stream
                        .filter(p -> p.getFileName().toString().contains("rehearsal434"))
                        .map(p -> p.getFileName().toString()).toList();
                assertThat(orphans)
                        .as("#379 防复发：宿主 C:\\workspace 不得有本会话孤儿转录目录")
                        .isEmpty();
            }
        }

        assertThat(points).as("连跑应产生 executor 计量事件").isNotEmpty();
        assertThat(segmentLogs).as("至少派过一段").isNotEmpty();
    }

    /**
     * 早期需求遗忘探针：在容器内对早片交付物做存活 grep。返回每项名→通过与否
     * （true＝交付物仍在）。早片（ord1-5）探针不过＝遗忘事件候选；晚片探针同时
     * 记录旅程进度（60 分钟到点未派到的段自然不过，报告内区分口径）。探针命令
     * 一律单引号形态（①族纪律：禁内嵌双引号与 <code>$()</code>——存在性判走
     * <code>grep -q</code> 退出码，不取输出）；单探针异常只记不过，不炸测量。
     */
    private Map<String, Boolean> runForgettingProbes() {
        record Probe(String name, String command) {
        }
        List<Probe> probes = List.of(
                new Probe("界面骨架-孩子端页面在位",
                        "find src/app -iname '*page*' | grep -q ."
                                + " && grep -riql '孩子\\|学生\\|单词' src/app"),
                new Probe("注册登录-双角色",
                        "grep -riql '登录\\|注册' src/app && grep -riql '家长' src/app"),
                new Probe("录词-拍照OCR入口", "grep -riql '拍照\\|OCR\\|ocr' src"),
                new Probe("单词本-状态分组管理", "grep -riql '单词本\\|已掌握\\|易错' src"),
                new Probe("学词-发音播放", "grep -riql '发音\\|播放\\|audio' src"),
                new Probe("测试-艾宾浩斯间隔表", "grep -riqlE '15.?天|12.?小时' src"),
                new Probe("测试-题型覆盖(选择/拼写/听力)",
                        "grep -riql '拼写' src && grep -riql '听力' src"),
                new Probe("家长-错词分析", "grep -riql '错词\\|错题' src"),
                new Probe("家长-时长活跃统计", "grep -riql '时长\\|活跃\\|连续' src"));
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (Probe probe : probes) {
            try {
                ExecResultResponse result = workspaceLifecycle.exec(workspaceId,
                        new WorkspaceExecCommand(probe.command()));
                results.put(probe.name(), result.exitCode() == 0);
            }
            catch (RuntimeException e) {
                results.put(probe.name(), false);
            }
        }
        return results;
    }

    // ---------- 供应商 key 注入（env → 测试库，跑完还原） ----------

    /**
     * 注入真实 volcengine key（env → 测试库，跑完还原）。注意：注入窗内共享测试库的
     * 其他 live  run 也会用到这把 key——本排练独占 dev 栈期间方可开跑（并行会话须
     * 先协调，见并行会话共享 infra 排练资源口径）。
     */
    private void syncProviderKeysFromDevDb() {
        providerKeyBackup.addAll(jdbcTemplate.queryForList(
                "SELECT provider_key, api_key, base_url FROM prj_model_providers"));
        jdbcTemplate.update("UPDATE prj_model_providers SET api_key = ? WHERE provider_key = 'volcengine'",
                System.getenv(VOLCENGINE_KEY_ENV));
    }

    private void restoreProviderKeys() {
        for (Map<String, Object> row : providerKeyBackup) {
            jdbcTemplate.update(
                    "UPDATE prj_model_providers SET api_key = ?, base_url = ? WHERE provider_key = ?",
                    row.get("api_key"), row.get("base_url"), row.get("provider_key"));
        }
        providerKeyBackup.clear();
    }

    // ---------- 小件 ----------

    private record SegmentLog(int ord, String runId, long wallMs, int attempts,
            boolean succeeded, String error) {
    }

    private String exec(String command) {
        ExecResultResponse result = workspaceLifecycle.exec(workspaceId,
                new WorkspaceExecCommand(command));
        assertThat(result.exitCode()).as("置备命令应成功: %s", command).isZero();
        return result.stdout() == null ? "" : result.stdout();
    }

    private static void logLine(String format, Object... args) {
        System.out.printf("[rehearsal-434] " + format + "%n", args);
    }

    private static Duration capFromEnv() {
        String override = System.getenv("REHEARSAL_434_CAP_MINUTES");
        if (override != null && !override.isBlank()) {
            return Duration.ofMinutes(Long.parseLong(override.trim()));
        }
        return Duration.ofMinutes(60);
    }

    private static boolean dockerAvailable() {
        try {
            // ②族纪律：不要输出就 DISCARD 直落 NUL
            return new ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        }
        catch (Exception e) {
            return false;
        }
    }
}
