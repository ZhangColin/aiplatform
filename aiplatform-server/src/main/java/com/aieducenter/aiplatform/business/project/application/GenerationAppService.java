package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 生成编排（#22 片2-1；#101 生成无门自动发起——「开始做系统」按钮退役，生成
 * 触发权归平台；#104 生成轨道——单 run 换「阶段 0 先起服 + 纵向切片逐段」多 run）：
 * 主智能体产出 PRD 后意见轮收口即自动派首次生成轨
 * （{@link #dispatchGenerationOnTurnClose}），显式端点（POST /generate）与失败
 * 「重新发起」兜底走同一编排（{@link #startGeneration}）。run 执行体与主智能体
 * 同构（AgentScope HarnessAgent 经 {@link AgentscopeAgentClient} 直调——编排缝
 * 极薄），仅资产与工具不同：会话每片/每 run 换新（#114 会话有界——每片新会话
 * {@code coder-{projectId}-slice-{n}}，重试续本片会话）、配置 = 平台技术约定
 * + 实现协议（{@link AgentProfile#EXECUTOR}）、无业务工具（编码工具由 harness
 * 内核自带）。
 *
 * <p><b>生成轨道（#104）</b>：按切片计划（{@link BuildPlan}，主智能体产出的有序
 * 纵向切片）顺序多 run——阶段 0（先起服白底页）固定前置，此后逐片长出。每片收口
 * 判据 = 8081 可达（平台侧检查点） + 成版 + run-finish（收口扩载；「端到端可操作」
 * 由片内 self-test 兜）。失败语义：某片超限转终态即发 {@code run-failed} 收口、
 * 不自动跳下一片；{@code generated_at} 落最后一片收口（口径不变——阶段 0 / 中间
 * 片不落位）。</p>
 *
 * <p><b>纯动作无门</b>：待定项未清也可发起（守卫只有项目存在 / 未归档 /
 * 未生成过 / PRD 已产出）；重复触发（已生成或生成在途）拒绝 PRJ_017。</p>
 *
 * <p><b>过程事件恒挂</b>：消息部件（解说段 + 动作卡）随全部智能体
 * 事件流产出（parts 契约，前端长成工作消息）。</p>
 *
 * <p><b>知识命中前置注入</b>（#24 生成环③）：下发前以首试任务 prompt 检索知识库
 * （query 截 2000 字、topK=5），命中块拼在任务 prompt <b>前</b>（知识是背景非
 * 指令）；检索失败降级空注入、run 照旧下发。一次切入一次注入（#114 生成链首片）——
 * 只在阶段 0 注入、切片不重注入（注入口径不膨胀）；单 run 内重试续本片会话（注入
 * 块已在会话历史），不重检索不重注入。</p>
 *
 * <p><b>工作区布局资产就位</b>：下发前把平台约定写入工作区 AGENTS.md
 * （幂等覆写，内容平台所有）——run 执行体经 harness 工作区上下文自读；
 * PRD（docs/PRD.md）由主智能体先前写出，同样是智能体自读，平台不搬运。</p>
 *
 * <p><b>失败自动静默重试有限次</b>（同工作区不丢数据——重试续本片会话，
 * 已落盘成果保留）：中间失败不出用户面事件；每片超限转终态失败即发 {@code run-failed}
 * 收口事件（#56，run 失败为唯一失败终态），由用户重新发起兜底（generated_at
 * 不落位 = 按钮口径仍在）。最后一片
 * 成功收口才落 {@code generated_at}（首次生成时点，单向置位——「确认下单」
 * 可见性口径）。</p>
 */
@Service
@Slf4j
public class GenerationAppService {

    /**
     * 阶段 0 prompt（先起服，#104 生成轨道首段；#113 收敛为「模板就位 + 起服 +
     * curl 确认」）：读 PRD 了解整体目标，工作区已内置基座工程（依赖预装），直接
     * 在基座上把应用以最小可运行形态跑上 8081（白底骨架页即可），收口即白底页——
     * 先于任何切片，解决「全程 503」的早可见；免选型、免初始化，总时长下压。
     */
    static final String STAGE0_RUN_PROMPT =
            "系统初始化（先起服）：请完整阅读工作区 docs/PRD.md（需求正本）了解整体目标。"
                    + "工作区已内置可运行的基座工程（TypeScript / Next.js / pnpm，依赖已预装），"
                    + "无需选型或初始化——直接在基座上把应用以最小可运行形态跑上 8081 端口"
                    + "（后台常驻，白底骨架页即可，暂不实现业务功能），收口前用 curl 确认 8081 可访问。";

    /**
     * 重试续作 prompt（#104 分段口径）：同工作区不丢数据——已落盘成果保留，从中断处
     * 续完<b>本段</b>任务（阶段 0 / 某片），不越段——切片逐段由平台顺序派 run 决定，
     * 重试不替执行体跨到下一片（「做一点展示一点」的完整性优先）。segmentDesc 即本段
     * 的任务描述（与首试 prompt 同口径）。
     */
    static String retryRunPrompt(String segmentDesc) {
        return "上一次尝试中断了，工作区内已完成的成果仍然有效。请先检查现状"
                + "（代码、依赖、数据、8081 端口服务是否在跑），从中断处继续完成本段任务"
                + "（" + segmentDesc + "），收口前确认 8081 端口服务在跑、curl 可访问。";
    }

    /**
     * 切片 prompt（#104 生成轨道逐段长出）：第 {@code index}/{@code total} 片，在
     * 现有系统上增量实现「用户能 X」这一纵向切片（前端→后端→落库端到端走通、用户
     * 可操作），系统其余部分保持可用。切片计划由主智能体产出（每片一句用户语言），
     * 平台只顺序执行、不解析自由文本（ADR 0009）。
     */
    static String sliceRunPrompt(int index, int total, String slice) {
        return "系统增量（切片 " + index + "/" + total + "）：请在现有系统上增量实现"
                + "这一纵向切片——「" + slice + "」（前端到后端、数据落库端到端走通，"
                + "用户可操作），保持系统其余部分可用，收口前确认 8081 端口服务在跑、"
                + "curl 可访问。";
    }

    /**
     * 交接摘要产出约定（#114 片间交接）：每片/阶段 0 收口前，执行体自产交接摘要
     * 作为最后一段话——平台取其收口终文（{@link CoderRunAttempts.RunResult#closingText}）
     * 落 .platform/ 并注入下一片 prompt。三要素（做了什么/关键文件/下一片须知）由
     * prompt 约定，平台不解析自由文本（不自造摘要机制，ADR 0012）。
     */
    static final String HANDOFF_PRODUCTION =
            "\n收口前，用你最后一段话输出本片交接摘要（供下一片执行体接手），包含三要素："
                    + "① 本片做了什么；② 关键文件（新增或修改的关键文件路径）；③ 下一片须知"
                    + "（下一片执行体需要知道的关键上下文、未完成事项或注意事项）。";

    /** 切片计划轨迹（#114 每片新会话后执行体自见全局）：全量有序清单，本片位置由切片任务自述。 */
    static String planTrajectory(BuildPlan plan) {
        StringBuilder trajectory = new StringBuilder("整体切片计划（按实现顺序）：");
        for (int i = 0; i < plan.slices().size(); i++) {
            trajectory.append('\n').append(i + 1).append(". ").append(plan.slices().get(i));
        }
        return trajectory.toString();
    }

    /** 前片交接摘要注入块（#114 片间交接）：下一片 prompt 直接携带前片终文。 */
    static String previousHandoffBlock(String previousHandoff) {
        return "上一片交接摘要（前片执行体所留，供你接手）：\n" + previousHandoff;
    }

    /**
     * 片级上下文（#114 每片新会话）：首试与重试共用——重试可能落在空会话（首试
     * converse 在引擎建会话前就失败，#84 静默重试恰覆盖此路径），故重试不能依赖
     * 「会话历史自持前片摘要」，须自足携带同一份上下文。拼装 = PRD 引用 + 切片
     * 计划轨迹 + 前片交接摘要 + 交接摘要产出约定。
     */
    static String segmentContext(BuildPlan plan, String previousHandoff) {
        StringBuilder context = new StringBuilder("\n\n")
                .append("先重读工作区 docs/PRD.md（需求正本）了解整体目标。")
                .append("\n").append(planTrajectory(plan));
        if (previousHandoff != null) {
            context.append("\n\n").append(previousHandoffBlock(previousHandoff));
        }
        return context.append(HANDOFF_PRODUCTION).toString();
    }

    /** 阶段 0 首试 prompt（#114）：起服任务 + 片级上下文。 */
    static String stage0Prompt(BuildPlan plan) {
        return STAGE0_RUN_PROMPT + segmentContext(plan, null);
    }

    /** 切片首试 prompt（#114）：切片任务 + 片级上下文。 */
    static String slicePrompt(BuildPlan plan, int index, String previousHandoff) {
        String slice = plan.slices().get(index);
        return sliceRunPrompt(index + 1, plan.slices().size(), slice)
                + segmentContext(plan, previousHandoff);
    }

    /** 生成轨重试续作 prompt（#114）：续作任务 + 片级上下文（与首试同上下文）。 */
    static String generationRetryPrompt(BuildPlan plan, String segmentDesc, String previousHandoff) {
        return retryRunPrompt(segmentDesc) + segmentContext(plan, previousHandoff);
    }

    /** 生成轨会话寻址（#114 每片新会话）：段号 0 = 阶段 0，1..N = 切片，重试续本段会话。 */
    static String sliceSession(Long projectId, int segment) {
        return CoderRunAttempts.SESSION_PREFIX + projectId + "-slice-" + segment;
    }

    /** 交接摘要落盘路径（#114 .platform/ 平台产物目录，不进用户 git 成版）：段号对应切片序。 */
    static String handoffFile(int segment) {
        return WorkspaceLayout.PLATFORM_DIR + "/slice-handoff-" + segment + ".md";
    }

    /**
     * AGENTS.md 平台约定正文（工作区布局资产，#22 就位）：工作区物理约定的正本
     * ——harness 工作区上下文自动注入 run 执行体，也是后续迭代 run / 引擎资产的
     * 演进载体。内容平台所有（无用户可控片段），幂等覆写。
     */
    static final String AGENTS_MD_CONTENT = """
            # 工作区平台约定

            本工作区是平台的单容器沙箱：Node.js 运行时与 PostgreSQL、Redis 同容器，/workspace 是唯一持久卷——容器可随时销毁重建，卷内数据不丢、卷外皆可弃。

            ## 基座技术栈

            工作区开箱即是一个可运行的基座工程（镜像内置、依赖已预装），技术栈固定：TypeScript / Next.js / React 19 / pnpm / shadcn（Tailwind 4）/ PostgreSQL / Redis。在基座上增量长出系统——不换栈、不重选型、不重新搭骨架；基座覆盖不了的依赖才在工作区现场安装（pnpm add），不要引入替代性框架。

            - 应用代码放工作区根目录；docs/ 放文档（docs/PRD.md 是需求正本，只读）。
            - 数据库连接串读 .env 的 DATABASE_URL（平台生成、唯一注入通道，勿手改）；需要缓存用 .env 的 REDIS_URL。
            - 应用自用的文件数据必须落 data/ 目录（卷内才持久）。
            - 应用服务必须监听 0.0.0.0:8081——平台预览从该端口取流量。
            - 应用服务一开工就跑起来：先把可运行形态（最小骨架或空壳页面即可）跑上 8081 后台常驻，此后增量长出页面与功能、随改随生效——不要写完全部代码才起服务。
            - 基座依赖已在镜像预装；新增依赖才在工作区现场安装（node_modules 属可重建物，打包交付时排除）。
            - .platform/ 是平台产物目录，不要写入；本文件（AGENTS.md）由平台维护，不要修改。
            - 每轮生成或修正收尾前，确认 8081 端口的服务在后台常驻运行——预览呈现的是活的服务。
            """;

    /**
     * 收口判据核验探针（#35）：converse 无异常不构成成功——run 执行体可能道歉式
     * 放弃或被 maxIters 掐断而照常返回。8081 可达才算收口（与 EXECUTOR systemPrompt
     * 的收口判据对齐）。{@code -s} 静默、{@code -o /dev/null} 弃正文，exitCode 0 =
     * 端口有 HTTP 应答（连接拒绝即非 0）。
     */
    static final String CLOSING_PROBE = "curl -s -o /dev/null http://localhost:8081";

    private final ProjectRepository projectRepository;
    private final AgentSessionExecutor sessionExecutor;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final CoderRunAttempts coderRunAttempts;
    private final AgentEventBridge eventBridge;
    private final EventsAppService eventsAppService;
    private final CodingRunTrack codingRunTrack;

    /**
     * 生成交接物的切片计划（projectId → 构建计划）：收口派发时落定（显式计划优先，
     * 无则退化为最小两段），生成轨道（#104 先起服 + 逐片多 run）据此顺序执行。进程
     * 内事实（run 无表口径）：重启即清。不沿用旧计划——主智能体重提意见会修订 PRD，
     * 旧切片计划相对已修订的 PRD 是过期结构（退化为最小两段，由 #104 决定是否及如何
     * 跨重新发起保留计划）。
     */
    private final Map<Long, BuildPlan> generationPlans = new ConcurrentHashMap<>();

    public GenerationAppService(ProjectRepository projectRepository,
            AgentSessionExecutor sessionExecutor,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            CoderRunAttempts coderRunAttempts, AgentEventBridge eventBridge,
            EventsAppService eventsAppService, CodingRunTrack codingRunTrack) {
        this.projectRepository = projectRepository;
        this.sessionExecutor = sessionExecutor;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.coderRunAttempts = coderRunAttempts;
        this.eventBridge = eventBridge;
        this.eventsAppService = eventsAppService;
        this.codingRunTrack = codingRunTrack;
    }

    /**
     * 触发首次生成的显式入口（#101 生成无门后为失败「重新发起」兜底：POST /generate
     * 端点与收口自动派发共用同一编排）：守卫 → AGENTS.md 资产就位 → 异步提交编码
     * run（首试 runId 随响应回，过程事件经 SSE；失败重试与超限兜底在异步轨道内）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              PRJ_017 已生成或生成在途（重复发起）；
     *                              PRJ_018 PRD 从未产出（编码 run 的任务就是读
     *                              PRD 实现，无 PRD 起跑只会空烧重试）
     */
    public GenerationRun startGeneration(Long projectId) {
        Project project = requireGeneratableProject(projectId);
        return dispatchGeneration(project, /* rejectInFlight= */ true, /* plan= */ null);
    }

    /**
     * 意见轮收口自动派首次生成（#101 生成无门自动发起）：主智能体产出 PRD 后，
     * 平台在意见轮收口处观测「未生成 && 已产出 PRD」即自动派首次生成 run，用户
     * 无需确认或点击。守卫沿用 {@link #startGeneration}（存在 / 未归档 / 未生成过
     * / PRD 已产出——{@link #requireGeneratableProject} 单点），差异只在在途口径：
     * 生成进行中静默跳过（不派不报错——此时用户新意见照旧随对话收口，不触发
     * 重复生成）；按钮路径在途拒绝 PRJ_017。run-failed 后项目仍「未生成」，用户
     * 重提一句即经本入口再触发，不自动重试（防空烧 token）。
     *
     * @param plan 切片计划交接物（saveBuildPlan 事实终值；null = 主智能体未产出
     *             切片计划，退化为最小两段——{@link BuildPlan#minimalFallback}）
     * @return 派发的 run 标识；在途（生成进行中）静默跳过返回 null——调用方据此
     *         区分「已派」与「跳过」，不误报派发事实
     * @throws ApplicationException 守卫组同 {@link #startGeneration}（收口观测处
     *                              已判定过未归档 / 未生成 / PRD 已产出，此处守卫
     *                              兜其余竞态调用面）
     */
    public GenerationRun dispatchGenerationOnTurnClose(Long projectId, BuildPlan plan) {
        Project project = requireGeneratableProject(projectId);
        return dispatchGeneration(project, /* rejectInFlight= */ false, plan);
    }

    /**
     * 首次生成派发（显式与收口自动两入口共用）：AGENTS.md 资产就位 → 异步提交
     * 编码 run。在途口径按调用方分岔——按钮路径拒绝 PRJ_017、收口自动路径静默
     * 跳过（返回 null 即「未派」，调用方不关心）。资产就位失败如实上抛并释放在途
     * 标记（环境故障口径，生成不起跑）。
     */
    private GenerationRun dispatchGeneration(Project project, boolean rejectInFlight, BuildPlan plan) {
        Long projectId = project.getId();
        if (!codingRunTrack.begin(projectId)) {
            if (rejectInFlight) {
                throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
            }
            log.info("[generate] 项目 {} 生成在途，收口自动派发静默跳过", projectId);
            return null;
        }
        try {
            placeConventionsAsset(project);
        } catch (RuntimeException e) {
            codingRunTrack.end(projectId);
            throw e;
        }
        // 切片计划交接物（ADR 0009）：显式传入的计划优先（收口派发），无计划退化为
        // 最小两段（阶段 0 先起服由平台固定前置，本计划含一段全量切片——守卫不派会
        // 倒退 #101 生成无门）。交接物落定供生成轨道（#104）读取。
        BuildPlan resolved = plan != null ? plan : BuildPlan.minimalFallback();
        generationPlans.put(projectId, resolved);

        String firstRunId = EventsAppService.newRunId();
        sessionExecutor.submit(CoderRunAttempts.SESSION_PREFIX + projectId, () -> {
            try {
                runGenerationTrack(project, firstRunId);
            }
            finally {
                codingRunTrack.end(projectId);
            }
        });
        return new GenerationRun(firstRunId);
    }

    /** 生成交接物的切片计划探针（#104 生成轨道消费；测试断言交接物落定）。 */
    BuildPlan planOf(Long projectId) {
        return generationPlans.get(projectId);
    }

    /** 一场生成的运行标识 = 用户面 run 身份（前端挂智能体事件 ?runId= 的锚；#84 静默重试——重试不换新锚，全程同值）。 */
    public record GenerationRun(String runId) {
    }

    // ---------- 内部 ----------

    /**
     * 生成轨道（异步轨道内，#104 先起服 + 纵向切片逐段）：复用 {@link CoderRunAttempts}
     * 的「轨道顺序多 run」模式（同 {@link IterationAppService#runFixTrack}）——阶段 0
     * （先起服白底页）固定前置（首试 runId = 用户面首 run 身份，随响应回），此后按切片
     * 计划顺序逐片派 run（每片新 runId）。每片收口判据 = 8081 可达（{@link #requireReachable}
     * 核验）+ 成版 + run-finish（收口扩载，端到端可操作由片内 self-test 兜）；最后一片
     * 收口才落 {@code generated_at}。失败语义：某片超限转终态即发 {@code run-failed}
     * 收口（锚该片 runId）、不自动跳下一片——「做一点展示一点」的完整性优先。
     */
    private void runGenerationTrack(Project project, String firstRunId) {
        Long projectId = project.getId();
        BuildPlan plan = planOf(projectId);
        List<String> slices = plan.slices();
        // 阶段 0（先起服）：最小可运行形态上 8081，收口即白底页，先于任何切片；会话
        // = slice-0（#114 每片新会话）。知识命中前置注入只在生成链首片（阶段 0）——
        // 一次切入一次注入，切片不重注入（注入口径不膨胀）
        CoderRunAttempts.RunResult stage0 = coderRunAttempts.run(project, firstRunId,
                sliceSession(projectId, 0),
                new CoderRunAttempts.Prompts(stage0Prompt(plan),
                        generationRetryPrompt(plan, "先起服：应用以最小可运行形态跑上 8081", null)),
                runId -> closeGenerationStage(project, false, "起服了系统骨架"),
                CoderRunAttempts.GENERATE_LABEL, true);
        if (!stage0.succeeded()) {
            eventBridge.emitRunFailed(projectId, firstRunId);
            return;
        }
        // 前片交接摘要（#114 片间交接）：本片收口终文即交接，落 .platform/ 并注入下一片
        String previousHandoff = stage0.closingText();
        placeSliceHandoff(project, 0, previousHandoff);
        // 切片逐段：按切片计划顺序派 run（每片新会话 slice-{index+1}），每片收口 =
        // 8081 可达 + 成版 + run-finish；某片失败不自动跳下一片（完整性优先，失败片
        // 要可见地修复——用户重提兜底）。重试续本片会话（同 sliceSession 不换）
        for (int index = 0; index < slices.size(); index++) {
            String slice = slices.get(index);
            String runId = EventsAppService.newRunId();
            boolean last = index == slices.size() - 1;
            CoderRunAttempts.RunResult result = coderRunAttempts.run(project, runId,
                    sliceSession(projectId, index + 1),
                    new CoderRunAttempts.Prompts(
                            slicePrompt(plan, index, previousHandoff),
                            generationRetryPrompt(plan, "实现切片「" + slice + "」", previousHandoff)),
                    attemptRunId -> closeGenerationStage(project, last,
                            "完成切片：" + slice),
                    CoderRunAttempts.GENERATE_LABEL, false);
            if (!result.succeeded()) {
                eventBridge.emitRunFailed(projectId, runId);
                return;
            }
            previousHandoff = result.closingText();
            placeSliceHandoff(project, index + 1, previousHandoff);
        }
    }

    /**
     * 交接摘要落盘（#114 片间交接）：前片收口终文写工作区 .platform/slice-handoff-{n}.md
     * （平台产物目录，不进用户 git 成版，与 #107 同向）。正文经 base64 传参防 shell
     * 元字符（执行体终文非平台常量）；失败只记日志不断流——交接摘要的注入走内存终文
     * （{@link CoderRunAttempts.RunResult#closingText}），落盘是透明面不承担正确性。
     */
    private void placeSliceHandoff(Project project, int segment, String content) {
        try {
            String encoded = Base64.getEncoder()
                    .encodeToString(content.getBytes(StandardCharsets.UTF_8));
            String command = "printf '%s' '" + encoded + "' | base64 -d > '"
                    + WorkspaceLayout.absolute(handoffFile(segment)) + "'";
            ExecResultResponse result = workspaceLifecycleAppService.exec(
                    Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(command));
            if (result.exitCode() != 0) {
                log.warn("[generate] 项目 {} 交接摘要落盘失败（透明面，不断流）：{}",
                        project.getId(), result.stderr());
            }
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} 交接摘要落盘失败（透明面，不断流）：{}",
                    project.getId(), e.getMessage());
        }
    }

    /**
     * 单段收口判据（#104 生成轨道的平台侧检查点）：8081 可达才收口——converse 无异常
     * 不构成成功（智能体可能道歉式放弃 / 被 maxIters 掐断）。核验不过抛异常，被共用件
     * 尝试环当作该次尝试失败（走重试/终态路径）；「端到端可操作」由片内 self-test 兜
     * （收口扩载 selfTest 统计），平台侧不新增探针。最后一片收口才落 {@code generated_at}
     * （口径不变——阶段 0 / 中间片不落位 = 拆片不漂移「确认下单」可见性）。
     */
    private CoderRunAttempts.ClosingJudgment closeGenerationStage(Project project,
            boolean markGenerated, String summary) {
        requireReachable(project);
        emitPreviewReady(project);
        if (markGenerated) {
            markGenerated(project.getId());
        }
        // 生成轮判定（#88 判定行）：PRD 未动（生成不改 PRD——正本由主智能体先行写出）、
        // 系统产出（8081 探活收口事实）；summary = 本段叙事（阶段 0 / 切片完成）
        return CoderRunAttempts.ClosingJudgment.generation(summary);
    }

    /** 8081 可达核验（#35 收口判据探针）：不可达即抛异常（驱动尝试环重试/终态）。 */
    private void requireReachable(Project project) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(CLOSING_PROBE));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("8081 不可达（curl 退出码 " + result.exitCode() + "）");
        }
    }

    /**
     * 切片收口推 URL（#105 URL 事件驱动推送）：8081 已在容器内探活通过（执行体已起服），
     * 此处经 exposePreview 取平台侧可访问的预览 URL 并推 {@code preview-ready}（载荷
     * projectId + url）——前端 bridge 消费写预览查询缓存，免 3s 轮询拿 URL。整个推送
     * 是「让 UI 活」的面、不承担正确性：URL 取不到或通知发射失败只记日志不断流——
     * 收口判据仍只有容器内 8081 探活（#104），URL 推送失败不反噬 run；前端兜底靠
     * run-finish 失效重拉 REST preview 补 URL。
     */
    private void emitPreviewReady(Project project) {
        try {
            URI url = workspaceLifecycleAppService.exposePreview(
                    Long.toString(project.getWorkspaceId()));
            eventsAppService.publishNotification(ProjectEventTypes.PREVIEW_READY, Map.of(
                    ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                    ProjectEventTypes.URL_FIELD, url.toString()));
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} preview-ready 推送失败（UI 面，不断流）：{}",
                    project.getId(), e.getMessage());
        }
    }

    /** 首次生成时点落位：重载置位（单向），失败记日志不炸异步轨道（run 已成功）。 */
    private void markGenerated(Long projectId) {
        try {
            projectRepository.findById(projectId).ifPresentOrElse(project -> {
                project.markGenerated();
                projectRepository.save(project);
            }, () -> log.warn("[generate] 项目 {} 生成成功但记录已不存在（generated_at 无处落）",
                    projectId));
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} generated_at 落位失败：{}", projectId, e.toString());
        }
    }

    /**
     * 工作区布局资产就位：AGENTS.md 平台约定写入（幂等覆写）。heredoc 单引号定界
     * 不做展开，正文为平台常量（无用户可控片段、无单引号）；退出码非 0 即写入
     * 失败（环境故障口径如实上抛，生成不起跑）。
     */
    private void placeConventionsAsset(Project project) {
        String command = "cat > '" + WorkspaceLayout.absolute(WorkspaceLayout.AGENTS_MD)
                + "' <<'PLATFORM_EOF'\n" + AGENTS_MD_CONTENT + "\nPLATFORM_EOF";
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(command));
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "AGENTS.md 平台约定写入失败: " + result.stderr());
        }
    }

    /**
     * 可生成守卫：存在 / 未归档 / 未生成过（已生成项目的调整走对话区意见，迭代环）/
     * PRD 已产出（「无门」指待定项不设门；编码 run 的任务就是读 PRD，无 PRD 起跑
     * 只会空烧重试——守的是动作成立的前置事实，不是流程门）。
     */
    private Project requireGeneratableProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        if (project.getGeneratedAt() != null) {
            throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
        }
        if (project.getPrdProducedAt() == null) {
            throw new ApplicationException(ProjectMessage.GENERATION_PRD_NOT_PRODUCED);
        }
        return project;
    }
}
