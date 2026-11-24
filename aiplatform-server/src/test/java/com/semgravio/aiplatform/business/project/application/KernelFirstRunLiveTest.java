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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.agentscope.AgentscopeHarnessAgentFactory;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 生成内核首建一次性验收（#598，真容器真模型）：照实验脚本 {@code v0-harness4.py}
 * 的 helpdesk 工单题，把阶段一四票的成果按顺序组装起来真跑一次，验「机制层面的
 * 变动有没有达到目标」——
 *
 * <ul>
 *   <li>#594 工具四件：真模型在真容器里读写文件、跑命令；</li>
 *   <li>#595 装配＋提示词：真模型照权威提示词与四个工具把 PRD 做成系统；</li>
 *   <li>#596 循环语义：跑到「一轮不调工具」自然停下，三种保护事件（截断／空回复／
 *       重复调用提醒）是否触发；</li>
 *   <li>#597 护栏＋输出上限（不触发）、开发服务器由平台侧命令起、收尾判据
 *       build✓＋探活✓。</li>
 * </ul>
 *
 * <p><b>两种沙箱形态</b>（环境变量选）：</p>
 * <ul>
 *   <li><b>独立容器</b>（设 {@value #CONTAINER_ENV}，照实验脚本的做法）：本类自建
 *       一个非平台命名的容器并把 8081 映射到宿主 {@value #PORT_ENV}，建法、环境
 *       文件、起服命令照 {@code DockerEnvironmentBackend} 原文。<b>跑完容器留着</b>
 *       （{@code http://localhost:<port>} 可直接打开，看完 {@code docker rm -f} 即可）。
 *       dev 栈在跑时必须用这一形态：平台工作区容器（{@code ws-编号}）启动满 10 分钟
 *       即被 dev 栈的运行期孤儿清扫当「库无记录」删掉（#598 首跑实证：内核第 10 分
 *       20 秒失联，收尾判据无从核验）。此形态下 #597 的起服守卫与收尾判据两个
 *       平台工作区件无法原样调用，由本类以同款命令代跑。</li>
 *   <li><b>平台工作区</b>（不设上述变量）：经工作区生命周期建沙箱，
 *       {@link KernelServingGuard} 起服、{@link KernelClosingCriteria} 判据——仅限
 *       dev 栈已停的机器上跑；跑完销毁（置 {@value #KEEP_WORKSPACE_ENV}=yes 保留）。</li>
 * </ul>
 *
 * <p><b>本类不验</b>（#598 施工时用户裁定不在本票范围）：执行通道对内核跑次的接线
 * （平台尚无该通道，装配经 {@link AgentscopeHarnessAgentFactory#obtainKernel} 直取）
 * 与计量落账。模型经配置 {@value #MODEL_CONFIG_KEY} 解析（候选队列→通道行）；测试库
 * 的通道 key 是占位值，跑前把真 key 注入该行、跑完还原——上下文压缩模型同走这一行
 * （首跑未注入时压缩调用 401）。通过标准＝{@code pnpm build} 过＋8081 探活有响应；
 * 时长、轮次、工具调用分布、三种事件触发情况写 artifact（{@value #ARTIFACT_DIR}）
 * 供验收记录，超出如实记录、不现场硬修。</p>
 *
 * <p><b>运行口径</b>：live 车道手跑，默认车道（{@code mvn test}／CI）不执行本类；
 * 即使 {@code -P live} 也须显式同意——
 * {@code KERNEL_FIRST_RUN_GO=yes KERNEL_FIRST_RUN_DEEPSEEK_API_KEY=<真key>
 * KERNEL_FIRST_RUN_CONTAINER=kernel-firstrun-598 KERNEL_FIRST_RUN_PORT=18098
 * mvn test -P live -Dtest=KernelFirstRunLiveTest}。真 key 只经环境变量注入，不落仓、
 * 不进代码。</p>
 */
@IntegrationTest
@Tag("live")
class KernelFirstRunLiveTest {

    /** 显式同意闸门：真模型首建一次，防误触发。 */
    private static final String GO_ENV = "KERNEL_FIRST_RUN_GO";
    /** 真 key 经环境变量注入（取法同实验脚本：aiplatform-server/.env.local 的 DEEPSEEK_API_KEY）。 */
    private static final String KEY_ENV = "KERNEL_FIRST_RUN_DEEPSEEK_API_KEY";
    /** 独立容器名（设了即走独立容器形态；不得是平台工作区命名 ws-编号）。 */
    private static final String CONTAINER_ENV = "KERNEL_FIRST_RUN_CONTAINER";
    /** 独立容器的宿主端口（映射容器内 8081，只绑 127.0.0.1）。 */
    private static final String PORT_ENV = "KERNEL_FIRST_RUN_PORT";
    /** 平台工作区形态：置 yes 保留工作区供事后检视（缺省跑完即销毁）。 */
    private static final String KEEP_WORKSPACE_ENV = "KERNEL_FIRST_RUN_KEEP_WS";

    /** 基准模型配置键（ADR-0054 速度与成本基准口径：deepseek-flash）。 */
    private static final String MODEL_CONFIG_KEY = "flash";
    private static final String PROVIDER_KEY = "deepseek";

    private static final String SESSION_ID = "kernel-first-run-acceptance";
    private static final String USER_ID = "kernel-first-run";
    /** 会话状态键（内核把整个 AgentState 含消息 context 存此键，同 AgentscopeAgentClient）。 */
    private static final String AGENT_STATE_KEY = "agent_state";
    private static final String ARTIFACT_DIR = "target/kernel-first-run";

    // 独立容器形态：建法与起服命令照 DockerEnvironmentBackend 原文（startDevContainer／
    // settleMiddleware／appStartCommand），差别只有容器名与端口映射
    private static final String DEV_IMAGE = "aiplatform/dev:0.19";
    private static final String STANDALONE_DB = "kernelfirstrun";
    private static final String STANDALONE_WORKSPACE_ID = "kernel-first-run-standalone";
    private static final String STANDALONE_ENV = "DATABASE_URL=postgresql://" + STANDALONE_DB
            + "@localhost:5432/" + STANDALONE_DB + "\nREDIS_URL=redis://localhost:6379\n";
    private static final String APP_START_COMMAND = "cd /workspace"
            + " && if test -f package.json && ! test -d node_modules; then pnpm install; fi"
            + " && sh /opt/seed/apply.sh"
            + " && if test -f server.js; then APP='node server.js';"
            + " elif test -f package.json; then APP='npm start';"
            + " else exit 0; fi"
            + " && { nohup $APP > .app.log 2>&1 & }"
            + " && echo started";
    /** 收尾判据 build 腿（同 KernelClosingCriteria.BUILD_PROBE）与探活腿（同平台 CLOSING_PROBE）。 */
    private static final String BUILD_PROBE = "cd /workspace && pnpm build";
    private static final String REACH_PROBE = "curl -s --max-time 2 -o /dev/null http://localhost:8081";

    // 循环事件与守卫的识别片段：正本在 KernelLoopPrompts／DockerExecFilesystem（包内可见），
    // 此处按原文片段识别，跑完从会话消息里数
    private static final String TRUNCATED_MARK = "本轮输出被截断";
    private static final String EMPTY_REPLY_MARK = "模型本轮返回空回复";
    private static final String MAX_STEPS_MARK = "CRITICAL - MAXIMUM STEPS REACHED";
    private static final String GUARD_CAP_MARK = "CRITICAL - PLATFORM GUARD LIMIT REACHED";
    private static final String REPEAT_REMINDER_MARK = "You are repeating the exact same tool call";
    private static final String REPEAT_REMINDER_DETAILED_MARK = "Repeated tool call detected";
    /** ①族守卫拒命文案（Windows 宿主限定——本机真跑的环境性摩擦，非内核问题）。 */
    private static final String GUARD_REJECT_MARK = "命令被平台拒绝执行";

    @Autowired
    private AgentscopeHarnessAgentFactory factory;

    @Autowired
    private AgentStateStore stateStore;

    @Autowired
    private ModelConfigAppService modelConfigs;

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycle;

    @Autowired
    private KernelServingGuard servingGuard;

    @Autowired
    private KernelClosingCriteria closingCriteria;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String workspaceId;
    private String providerKeyBackup;

    @BeforeAll
    static void requireDockerAndConsent() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
        Assumptions.assumeTrue("yes".equals(System.getenv(GO_ENV)),
                "未设 " + GO_ENV + "=yes——真模型首建需显式 consent");
        Assumptions.assumeTrue(
                System.getenv(KEY_ENV) != null && !System.getenv(KEY_ENV).isBlank(),
                "未设 " + KEY_ENV + "——真模型 key 需显式注入");
    }

    @AfterEach
    void tearDown() {
        restoreProviderKey();
        // 独立容器形态：容器留着供事后访问，不清
        if (workspaceId == null || "yes".equals(System.getenv(KEEP_WORKSPACE_ENV))) {
            return;
        }
        try {
            workspaceLifecycle.destroy(workspaceId);
        }
        catch (RuntimeException e) {
            // 物理清理尽力而为（Docker 残留可重试销毁）
        }
    }

    @Test
    @Timeout(value = 65, unit = TimeUnit.MINUTES)
    void given_real_prd_when_kernel_runs_once_then_build_passes_and_dev_server_answers()
            throws Exception {
        StandaloneSandbox sandbox = standaloneSandboxFromEnv();
        injectRealProviderKey();
        Long workspaceKey = null;
        String containerName;
        if (sandbox == null) {
            WorkspaceResponse workspace = workspaceLifecycle.create(new CreateWorkspaceCommand(EnvKind.DEV));
            workspaceId = workspace.workspaceId();
            workspaceKey = Long.parseLong(workspaceId);
            containerName = workspaceLifecycle.handleOf(workspaceId).containerName();
            execInWorkspace(prdSeedCommand());
            servingGuard.ensureServing(workspaceKey);
        }
        else {
            containerName = sandbox.container();
            sandbox.create(prdSeedCommand());
        }
        // 同一 (userId, sessionId) 的会话状态先清——重跑不得接上上一轮的对话
        stateStore.delete(USER_ID, SESSION_ID);

        ModelCandidateQueue queue = modelConfigs.resolveQueue(MODEL_CONFIG_KEY);
        HarnessAgent agent = factory.obtainKernel(queue.modelString(), queue.channel(), queue,
                new AgentWorkspace.ProjectKernel(
                        sandbox == null ? workspaceId : STANDALONE_WORKSPACE_ID, containerName),
                /* agentKey= */ null);

        RunLog run = new RunLog();
        run.startedAt = Instant.now();
        logLine("起跑：沙箱=%s（%s），模型=%s", containerName,
                sandbox == null ? "平台工作区" : "独立容器", queue.modelString());
        agent.streamEvents(List.of(new UserMessage("开始")),
                RuntimeContext.builder().sessionId(SESSION_ID).userId(USER_ID).build())
                .doOnNext(run::onEvent)
                .blockLast();
        Duration elapsed = Duration.between(run.startedAt, Instant.now());
        logLine("内核自然终止：用时 %d 秒，模型调用 %d 次", elapsed.toSeconds(), run.modelCalls.get());

        ConversationFacts facts = readConversationFacts();
        KernelClosingCriteria.KernelClosingResult closing = sandbox == null
                ? closingCriteria.assess(workspaceKey)
                : sandbox.assess();

        writeArtifacts(elapsed, run, facts, closing, queue.modelString());
        logLine("==== 验收摘要（artifact: %s）====", Path.of(ARTIFACT_DIR).toAbsolutePath());
        logLine("时长 %d 秒｜模型调用 %d 次｜工具调用 %s｜工具失败 %d 次", elapsed.toSeconds(),
                run.modelCalls.get(), run.toolCalls, run.toolFailures.get());
        logLine("循环事件：截断 %d｜空回复 %d｜重复提醒 %d｜轮次护栏 %d｜时长/用量护栏 %d｜守卫拒命 %d",
                facts.truncatedClosings(), facts.emptyReplyClosings(), facts.repeatReminders(),
                facts.maxStepsPrompts(), facts.guardCapPrompts(), facts.guardRejections());
        logLine("会话消息用量：输入 %d｜输出 %d｜缓存命中 %d（消息 %d 条）", facts.inputTokens(),
                facts.outputTokens(), facts.cachedTokens(), facts.messages());
        logLine("收尾判据：build=%s｜探活=%s", closing.buildPassed(), closing.probePassed());
        if (sandbox != null) {
            logLine("访问地址：%s（容器跑完保留，看完 docker rm -f %s）", sandbox.accessUrl(),
                    sandbox.container());
        }

        assertThat(closing.complete())
                .as("收尾判据：pnpm build 过＋8081 探活有响应（build=%s，probe=%s）",
                        closing.buildPassed(), closing.probePassed())
                .isTrue();
    }

    // ---------- 供应商 key 注入（env → 测试库，跑完还原） ----------

    /**
     * 把真 key 注入测试库的 deepseek 通道行（种子是占位值）——主模型与上下文压缩模型
     * 都经配置面解析到这一行。只动这一行的 key，其余不动；注入窗内共享测试库的
     * 其他 live 调用也会用到这把 key。
     */
    private void injectRealProviderKey() {
        providerKeyBackup = jdbcTemplate.queryForObject(
                "SELECT api_key FROM prj_model_providers WHERE provider_key = ?", String.class,
                PROVIDER_KEY);
        jdbcTemplate.update("UPDATE prj_model_providers SET api_key = ? WHERE provider_key = ?",
                System.getenv(KEY_ENV), PROVIDER_KEY);
    }

    private void restoreProviderKey() {
        if (providerKeyBackup != null) {
            jdbcTemplate.update("UPDATE prj_model_providers SET api_key = ? WHERE provider_key = ?",
                    providerKeyBackup, PROVIDER_KEY);
            providerKeyBackup = null;
        }
    }

    // ---------- 现场置备 ----------

    private static String prdSeedCommand() throws IOException {
        // ①族纪律：进容器命令一律单引号形态——PRD 正文含 ASCII 双引号，base64 单引号
        // 包裹传输、容器内解码，字节级保真（同 SingleSessionLongRunRehearsalLiveTest）
        String prd = new String(new ClassPathResource("kernel-acceptance/prd-run1-helpdesk.md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(prd.getBytes(StandardCharsets.UTF_8));
        return "mkdir -p /workspace/docs && echo '" + encoded + "' | base64 -d > /workspace/docs/PRD.md";
    }

    private void execInWorkspace(String command) {
        ExecResultResponse result = workspaceLifecycle.exec(workspaceId, new WorkspaceExecCommand(command));
        assertThat(result.exitCode()).as("置备命令应成功: %s", command).isZero();
    }

    private static StandaloneSandbox standaloneSandboxFromEnv() {
        String container = System.getenv(CONTAINER_ENV);
        if (container == null || container.isBlank()) {
            return null;
        }
        String port = System.getenv(PORT_ENV);
        assertThat(port).as("独立容器形态须设 %s（宿主端口）", PORT_ENV).isNotBlank();
        return new StandaloneSandbox(container, Integer.parseInt(port.trim()));
    }

    // ---------- 会话消息里的事实 ----------

    /**
     * 跑完从会话状态（cat_agent_state）读消息列：三种保护事件（收尾句／注入的用户
     * 消息）、守卫拒命次数、逐条消息携带的用量。发生上下文压缩时早段消息被摘要
     * 取代——用量按保留的消息累计，偏小时以压缩为准。
     */
    private ConversationFacts readConversationFacts() {
        List<Msg> context = stateStore.get(USER_ID, SESSION_ID, AGENT_STATE_KEY, AgentState.class)
                .map(AgentState::getContext)
                .orElseGet(List::of);
        int truncated = 0;
        int empty = 0;
        int repeat = 0;
        int maxSteps = 0;
        int guardCap = 0;
        int guardRejections = 0;
        long input = 0;
        long output = 0;
        long cached = 0;
        for (Msg message : context) {
            String text = textOf(message);
            truncated += countOccurrences(text, TRUNCATED_MARK);
            empty += countOccurrences(text, EMPTY_REPLY_MARK);
            repeat += countOccurrences(text, REPEAT_REMINDER_MARK)
                    + countOccurrences(text, REPEAT_REMINDER_DETAILED_MARK);
            maxSteps += countOccurrences(text, MAX_STEPS_MARK);
            guardCap += countOccurrences(text, GUARD_CAP_MARK);
            guardRejections += countOccurrences(text, GUARD_REJECT_MARK);
            ChatUsage usage = message.getUsage();
            if (usage != null) {
                input += usage.getInputTokens();
                output += usage.getOutputTokens();
                cached += usage.getCachedTokens();
            }
        }
        return new ConversationFacts(truncated, empty, repeat, maxSteps, guardCap, guardRejections,
                input, output, cached, context.size());
    }

    /** 消息里的全部文本：正文块＋工具结果块（工具结果不在正文块里）。 */
    private static String textOf(Msg message) {
        StringBuilder text = new StringBuilder();
        message.getContentBlocks(TextBlock.class)
                .forEach(block -> text.append(block.getText()).append('\n'));
        message.getContentBlocks(ToolResultBlock.class).stream()
                .map(ToolResultBlock::getOutput)
                .flatMap(List::stream)
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .forEach(block -> text.append(block.getText()).append('\n'));
        return text.toString();
    }

    private static int countOccurrences(String text, String mark) {
        int count = 0;
        for (int at = text.indexOf(mark); at >= 0; at = text.indexOf(mark, at + mark.length())) {
            count++;
        }
        return count;
    }

    // ---------- artifact ----------

    private void writeArtifacts(Duration elapsed, RunLog run, ConversationFacts facts,
            KernelClosingCriteria.KernelClosingResult closing, String model) throws IOException {
        Path dir = Path.of(ARTIFACT_DIR);
        Files.createDirectories(dir);
        Map<String, Integer> toolCalls = new LinkedHashMap<>();
        run.toolCalls.forEach((name, count) -> toolCalls.put(name, count.get()));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("topic", "helpdesk 工单（实验脚本三题之一，同题面 prd-run1-helpdesk.md）");
        summary.put("model", model);
        summary.put("workspaceId", workspaceId);
        summary.put("elapsedSeconds", elapsed.toSeconds());
        summary.put("modelCalls", run.modelCalls.get());
        summary.put("toolCalls", toolCalls);
        summary.put("toolFailures", run.toolFailures.get());
        summary.put("loopEvents", Map.of(
                "truncationClosings", facts.truncatedClosings(),
                "emptyReplyClosings", facts.emptyReplyClosings(),
                "repeatReminders", facts.repeatReminders(),
                "maxStepsPrompts", facts.maxStepsPrompts(),
                "guardCapPrompts", facts.guardCapPrompts()));
        summary.put("windowsArgvGuardRejections", facts.guardRejections());
        summary.put("usageFromSessionMessages", Map.of(
                "input", facts.inputTokens(), "output", facts.outputTokens(),
                "cached", facts.cachedTokens(), "messages", facts.messages()));
        summary.put("closing", Map.of("buildPassed", closing.buildPassed(),
                "probePassed", closing.probePassed()));
        Files.writeString(dir.resolve("summary.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("trace.txt"), String.join("\n", run.trace) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("final-text.md"), run.text.toString(), StandardCharsets.UTF_8);
    }

    private static void logLine(String format, Object... args) {
        System.out.printf("[kernel-first-run] " + format + "%n", args);
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

    /**
     * 容器内执行一次命令并取回全量输出：先读完输出再等退出（②族纪律：Windows 匿名
     * 管道约 4KB，不读完即 waitFor 会死锁）；超时即杀 docker 客户端、退出码记 -1。
     */
    private static DockerRun containerExec(String container, Duration timeout, String script)
            throws Exception {
        Process process = new ProcessBuilder("docker", "exec", container, "sh", "-c", script)
                .redirectErrorStream(true)
                .start();
        try (ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> output = reader.submit(() -> new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor();
            }
            return new DockerRun(finished ? process.exitValue() : -1, output.get(10, TimeUnit.SECONDS));
        }
    }

    private record DockerRun(int exitCode, String output) {
    }

    /**
     * 独立容器沙箱（照实验脚本的做法：非平台命名的容器＋宿主端口映射）。建法、
     * 环境文件、起服命令照平台原文——内核看到的 substrate 与平台工作区一致。
     */
    private static final class StandaloneSandbox {

        private final String container;
        private final int port;

        StandaloneSandbox(String container, int port) {
            this.container = container;
            this.port = port;
        }

        String container() {
            return container;
        }

        String accessUrl() {
            return "http://localhost:" + port;
        }

        /** 建容器 → 等中间件与基线就位 → 环境文件 → 需求文档 → 起开发服务器。 */
        void create(String prdSeedCommand) throws Exception {
            DockerRun started = docker(Duration.ofMinutes(2), "docker", "run", "-d", "--name", container,
                    "-p", "127.0.0.1:" + port + ":8081",
                    "-w", "/workspace",
                    "-e", "PGDATA=/workspace/data/pg",
                    "-e", "WORKSPACE_DB=" + STANDALONE_DB,
                    DEV_IMAGE, "sleep", "infinity");
            assertThat(started.exitCode()).as("独立容器应建成：%s", started.output()).isZero();
            awaitMiddlewareAndBaseline();
            require(containerExec(container, Duration.ofSeconds(30),
                    "printf '%s' '" + STANDALONE_ENV + "' > /workspace/.env"), ".env 写入");
            require(containerExec(container, Duration.ofSeconds(30), prdSeedCommand), "需求文档落盘");
            DockerRun app = containerExec(container, Duration.ofMinutes(5), APP_START_COMMAND);
            assertThat(app.output()).as("开发服务器应拉起：%s", app.output()).contains("started");
        }

        /** 收尾判据双腿（同平台 KernelClosingCriteria）：build 过＋8081 探活有响应。 */
        KernelClosingCriteria.KernelClosingResult assess() throws Exception {
            DockerRun build = containerExec(container, Duration.ofMinutes(10), BUILD_PROBE);
            Files.createDirectories(Path.of(ARTIFACT_DIR));
            Files.writeString(Path.of(ARTIFACT_DIR, "closing-build.log"), build.output(),
                    StandardCharsets.UTF_8);
            DockerRun probe = containerExec(container, Duration.ofSeconds(30), REACH_PROBE);
            return new KernelClosingCriteria.KernelClosingResult(
                    build.exitCode() == 0, probe.exitCode() == 0);
        }

        /** 平台 settleMiddleware 同款就绪等待（另等基线工程 package.json 落位）。 */
        private void awaitMiddlewareAndBaseline() throws Exception {
            long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            while (System.nanoTime() < deadline) {
                DockerRun pg = containerExec(container, Duration.ofSeconds(20),
                        "pg_isready -h localhost -U postgres");
                DockerRun redis = containerExec(container, Duration.ofSeconds(20),
                        "redis-cli -h localhost ping");
                DockerRun baseline = containerExec(container, Duration.ofSeconds(20),
                        "test -f /workspace/package.json");
                if (pg.exitCode() == 0 && redis.exitCode() == 0 && baseline.exitCode() == 0) {
                    return;
                }
                Thread.sleep(500);
            }
            throw new IllegalStateException("独立容器内 PostgreSQL／Redis／基线工程就绪超时：" + container);
        }

        private static void require(DockerRun run, String what) {
            assertThat(run.exitCode()).as("%s 应成功：%s", what, run.output()).isZero();
        }

        private static DockerRun docker(Duration timeout, String... command) throws Exception {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new DockerRun(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    ? process.exitValue() : -1, output);
        }
    }

    /** 事件流观察面：模型调用次数、工具调用分布与失败数、工具调用流水、末次文本。 */
    private static final class RunLog {

        private final AtomicInteger modelCalls = new AtomicInteger();
        private final Map<String, AtomicInteger> toolCalls = new TreeMap<>();
        private final AtomicInteger toolFailures = new AtomicInteger();
        private final List<String> trace = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private Instant startedAt;

        void onEvent(AgentEvent event) {
            if (event instanceof ModelCallStartEvent) {
                modelCalls.incrementAndGet();
            }
            else if (event instanceof ToolCallEndEvent end) {
                toolCalls.computeIfAbsent(String.valueOf(end.getToolCallName()), name -> new AtomicInteger())
                        .incrementAndGet();
                trace.add(String.format("+%ds %s", Duration.between(startedAt, Instant.now()).toSeconds(),
                        end.getToolCallName()));
            }
            else if (event instanceof ToolResultEndEvent end && end.getState() != ToolResultState.SUCCESS) {
                toolFailures.incrementAndGet();
            }
            else if (event instanceof TextBlockDeltaEvent delta) {
                text.append(delta.getDelta());
            }
        }
    }

    /** 会话消息里数出的事实。 */
    private record ConversationFacts(int truncatedClosings, int emptyReplyClosings, int repeatReminders,
            int maxStepsPrompts, int guardCapPrompts, int guardRejections, long inputTokens,
            long outputTokens, long cachedTokens, int messages) {
    }
}
