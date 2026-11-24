package com.semgravio.aiplatform.business.project.application;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.KernelSystemPrompt;
import com.semgravio.aiplatform.base.agentscope.RunHeading;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.business.billing.application.BalanceGateAppService;
import com.semgravio.aiplatform.business.billing.application.BalanceReminderAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 生成编排（#22 片2-1；#101 生成无门自动发起→#332 生成加门→#462 去确认门
 * （ADR-0039）→#542 首建派发门（ADR-0048/0049）——照产自动派发两入口整体
 * 退役，点火归用户→<b>#601 接线（ADR-0054/0055）：首建＋迭代接管走新内核
 * →<b>#602 拆切片与轨道机器：旧生成轨道（切片计划、单会话/并行波两形态、
 * fork/wave/merge、断点续跑）整体退场</b>，本服务只余内核派发路径）：
 * 岔路口「立即生成」/付费轨 PRD 确认/快道/设计定稿四触发面经同一派发编排
 * （{@link #dispatchGeneration}）提交<b>内核装配形态</b>的一场 run——
 * 权威系统提示（{@link KernelSystemPrompt}）＋工具四件＋循环语义中间件＋
 * 装配键不带 executor（决策提示／verify 自决旧中间件结构性挂不上），单会话
 * 连续驱动直至收尾判据双件全过（{@link KernelClosingCriteria}）。
 *
 * <p><b>旧轨道清场（#602，ADR-0055 生成执行栈组）</b>：切片与切片计划
 * （{@code BuildPlan}、轨道表/核心旅程/推迟清单三张整表、saveBuildPlan 工具、
 * REST 片清单读面、规模标签落库）、单会话态／切片机器态两开关、并行波次机器
 * （fork/wave/merge、波次遥测）、段收口/段失败与失败回滚、切片交接物落盘、
 * 整轨终验（载荷轴＝计划核心旅程，随表一并退场）——全部移除；主智能体侧的
 * 计划补产链（requestBuildPlan/saveBuildPlan 事实）同步退役。<b>#604 拆
 * run 尝试体旧机制</b>：尝试环（静默续试/错误现场续作/通道级免预算重试/
 * lint 修轮/段回滚重跑）与续跑机器（terminalFailureScenes＋续作 prompt）
 * 整体退役——失败语义＝<b>明确展示＋重跑即恢复</b>（run-failed 失败面落库
 * ＋SSE 扩载；用户在点火入口重跑即续——已落盘成果保留、不重做已对的工作，
 * 内核自见工作区现状，不携错误现场，ADR-0055 #580-13：不造第三层重试）。</p>
 *
 * <p><b>首建派发门（#542 访谈②，ADR-0048/0049——两入口退役）</b>：首个构建
 * run 的点火权归用户。触发面四条：试用态岔路口三选卡「立即生成」（
 * {@link #startGeneration} 既有口）、付费轨 PRD 确认按钮（{@link
 * BuildGateAppService#confirmFirstBuild}，确认即派发、不写版本行）、快道
 * （试用态＝用户明确收敛令；付费态＝零必答短路/「直接开始做」视同确认——
 * savePrd 的 fastLane 工具调用事实，收口观测直派
 * {@link #dispatchGenerationOnTurnClose}，不出卡不确认）、设计类轨迹全部定稿
 * （设计轨触发律不改）。自然收口不派发：试用态出岔路口卡（状态位持久可回）、
 * 付费态确认门在场。点火时点落 {@code generation_requested_at}
 * （{@link #markFirstBuildRequested}——卡/门「首建未派发」呈现条件的事实源）；
 * 终态失败/停止收口清点火位（{@link #reopenFirstBuildRequest}，卡与门复现），
 * 用户重走点火入口重跑即恢复、不重复确认。小迭代照旧无门（受理即派）；
 * 重复触发（已生成或生成在途）按钮路径拒绝 PRJ_017。Demo 类 run 起跑自动记
 * PRD 版本行（{@link PrdVersionAppService#recordOnDemoRunStart}，构建基准序
 * ——确认不写版本行，记行归起跑单点）、Demo 版本锚捕获读「最新版」。</p>
 *
 * <p><b>首 Demo 在途的意见排队补派（#360）</b>：首个构建 run 在途期间（generated_at
 * 未落位）到达的意见轮，收口派发不再静默跳过——意见（原文＋需求侧修订说明）入队
 * （{@link #firstDemoTurnOpinions}，修正轨 queuedFixRuns 同构的进程内态）＋对话面
 * 平台明示（guide-reply 复用型，{@link #IN_FLIGHT_OPINION_NOTICE}）；生成<b>成功
 * 收口</b>后排空队列，多条意见合并为一场更新 run 补派（{@link IterationAppService}
 * 的逐轮配对交接物——同构修正轨排队合并，不逐条烧 run）。失败/停止收口不排空、
 * 队列保留：重跑成功收口时再消化（不自锁）；补派起跑撞修正轨在途即
 * 排进修正轨队列，那场收口排空时自然合并（竞态零丢失）。意见零丢失之外仍留兜底：
 * 进程内队列重启即清（同修正轨口径），用户重提即恢复。</p>
 *
 * <p><b>过程事件恒挂</b>：消息部件（解说段 + 动作卡）随全部智能体
 * 事件流产出（parts 契约，前端长成工作消息）。</p>
 *
 * <p><b>知识命中前置注入</b>（#24 生成环③）：下发前以起跑任务 prompt 检索知识库
 * （query 截 2000 字、topK=5），命中块拼在任务 prompt <b>前</b>（知识是背景非
 * 指令）；检索失败降级空注入、run 照旧下发。一场 run 一次注入（#114 生成链首片
 * 口径沿用到内核跑）——重跑是新的一场 run，照常重新切入注入。</p>
 *
 * <p><b>起手文件资产</b>：PRD（docs/PRD.md）由主智能体先前写出，run 执行体自读、
 * 平台不搬运；#607 起平台不再向工作区写 AGENTS.md 平台约定（仍成立的生产约束
 * 并入内核提示词单一权威，见 {@link KernelSystemPrompt#WORKSPACE_CONSTRAINTS}）。</p>
 */
@Service
@Slf4j
public class GenerationAppService {

    /**
     * 内核首跑 prompt（#601 接线，ADR-0054/0055）：「开始」——权威系统提示
     * （{@link KernelSystemPrompt}）自带完整指令（PRD 正本阅读＋任务＋完成判据），
     * 首句只点火不复述任务；与 #598 一次性真模型验收驱动形态一致。
     */
    static final String KERNEL_FIRST_RUN_PROMPT = "开始";

    /**
     * 内核会话寻址（#601 接线）：单会话连续驱动至收尾判据过；run 维度新 run 换
     * 会话（同 #114 口径的会话有界）。
     */
    static String kernelSession(Long projectId, String runId) {
        return CodingRunDispatcher.SESSION_PREFIX + projectId + "-kernel-" + runId;
    }

    /**
     * 内核 run 工作消息标题（#601 接线沿用「系统初始化」呈现值；#602 起不再区分
     * 阶段 0/切片——整场生成一场 run，标题即内核轨道的固定水平工序名）。
     */
    static final String STAGE0_TITLE = "系统初始化";

    /**
     * 任务级解说约定（#225 叙说密度放宽的拼装单点；执行体 systemPrompt 协议条
     * 同向引用）。
     */
    static final String NARRATION_CONVENTION =
            "过程解说只在关键节点（开工、重大转向、失败、收口）用一两句平实中文说明，"
                    + "不必每组动作都配解说。";

    /**
     * 收口判据核验探针（#35；#602 起本服务内无消费面，正本留此供
     * {@link ClosingProbes} 引用——8081 探活＝预览转可用的门，保留腿）：
     * {@code -s} 静默、{@code -o /dev/null} 弃正文，exitCode 0 = 端口有 HTTP 应答
     * （连接拒绝即非 0）；{@code --max-time} 上限（#183）——收口线程不被无响应对端
     * 挂死。入口文件完整检查已随审查与收口组退役（#605）。
     */
    static final String CLOSING_PROBE = "curl -s --max-time 2 -o /dev/null http://localhost:8081";

    private final ProjectRepository projectRepository;
    private final AgentSessionExecutor sessionExecutor;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final CodingRunDispatcher codingRunDispatcher;
    private final AgentEventBridge eventBridge;
    private final EventsAppService eventsAppService;
    private final CodingRunTrack codingRunTrack;
    private final TransactionTemplate transactionTemplate;
    private final ConversationHistoryAppService conversationHistory;
    private final BuildDispatchPolicy buildDispatchPolicy;
    private final DemoVersionAppService demoVersionAppService;
    private final PrdVersionAppService prdVersionAppService;
    private final DemoSeedAppService demoSeed;

    /** 更新 run 派发口（#360 排空补派）：生成成功收口后消化在途意见队列。 */
    private final IterationAppService iterationAppService;

    private final BalanceGateAppService balanceGate;

    /** 内核收尾判据（#601 接线消费 #597 落位件）：build 过＋8081 探活双件。 */
    private final KernelClosingCriteria kernelClosingCriteria;

    /** 内核起服守卫（#601 接线消费 #597 落位件）：开发服务器平台起、常驻。 */
    private final KernelServingGuard kernelServingGuard;

    /**
     * 首 Demo 在途期间到达的意见轮（#360，projectId → 按收口序排队）：生成成功
     * 收口后由 {@link #dispatchFirstDemoTurnOpinions} 排空合并补派一场更新 run；
     * 失败/停止收口保留（重跑成功收口时消化）。进程内态与轨道状态
     * 同口径——重启即清，用户重提即兜底（修正轨 queuedFixRuns 同构）。
     */
    private final Map<Long, List<TurnOpinion>> firstDemoTurnOpinions = new ConcurrentHashMap<>();

    /**
     * 一条在途意见轮的排队载荷（#360）：意见原文＋该轮需求侧判定（savePrd 修订
     * 说明，null = 未修订）＋意见轮 runId（明示回执的对话面锚）——排空时逐轮
     * 配对成修正轨交接物（{@link IterationAppService.FixHandoff.Round} 同形）。
     */
    record TurnOpinion(String opinion, String prdRevisionSummary, String turnRunId) {
    }

    /**
     * 余额闸暂存的生成派发 intent（#537 段边界余额门，projectId 集）：余额门
     * 拦下时生成派发被暂存于此，充值回正经 {@link #drainBalanceBlocked} 自动重派
     * （被拦意见轮已并入 {@link #firstDemoTurnOpinions} 排队面——按既有在途
     * 口径消化：生成成功收口后合并补派更新 run）。进程内锚形态（同排队态口径）
     * ——重启丢账属既有兜底哲学。
     */
    private final Set<Long> balanceBlockedGeneration = ConcurrentHashMap.newKeySet();

    public GenerationAppService(ProjectRepository projectRepository,
            AgentSessionExecutor sessionExecutor,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            CodingRunDispatcher codingRunDispatcher, AgentEventBridge eventBridge,
            EventsAppService eventsAppService, CodingRunTrack codingRunTrack,
            TransactionTemplate transactionTemplate,
            ConversationHistoryAppService conversationHistory,
            BuildDispatchPolicy buildDispatchPolicy,
            DemoVersionAppService demoVersionAppService,
            PrdVersionAppService prdVersionAppService,
            DemoSeedAppService demoSeed,
            IterationAppService iterationAppService,
            BalanceGateAppService balanceGate,
            KernelClosingCriteria kernelClosingCriteria,
            KernelServingGuard kernelServingGuard) {
        this.projectRepository = projectRepository;
        this.sessionExecutor = sessionExecutor;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.codingRunDispatcher = codingRunDispatcher;
        this.eventBridge = eventBridge;
        this.eventsAppService = eventsAppService;
        this.codingRunTrack = codingRunTrack;
        this.transactionTemplate = transactionTemplate;
        this.conversationHistory = conversationHistory;
        this.buildDispatchPolicy = buildDispatchPolicy;
        this.demoVersionAppService = demoVersionAppService;
        this.prdVersionAppService = prdVersionAppService;
        this.demoSeed = demoSeed;
        this.iterationAppService = iterationAppService;
        this.balanceGate = balanceGate;
        this.kernelClosingCriteria = kernelClosingCriteria;
        this.kernelServingGuard = kernelServingGuard;
    }

    /**
     * 生成点火入口（POST /generate——岔路口「立即生成」与前端「继续生成」恢复出口
     * 共用；#604 起重发即在既有工作区重跑：服务端续跑机器已退役，不带断点、不携
     * 错误现场）：守卫 → 异步提交编码 run（runId 随响应回，
     * 过程事件经 SSE）。失败不自动重试——终态经 run-failed 明确展示，由用户重跑。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              PRJ_017 已生成或生成在途（重复发起）；
     *                              PRJ_018 PRD 从未产出（编码 run 的任务就是读
     *                              PRD 实现，无 PRD 起跑只会空烧一场 run）
     */
    public GenerationRun startGeneration(Long projectId) {
        Project project = requireGeneratableProject(projectId);
        GenerationRun run = dispatchGeneration(project, /* rejectInFlight= */ true,
                /* turnOpinion= */ null);
        if (run == null) {
            // 收口自动路径的 null 静默跳过不经过本入口——按钮路径在途已由守卫拒绝，
            // 此处 null 只剩环境性早退（余额门等已各自抛错），按重复触发口径兜住
            throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
        }
        return run;
    }

    /**
     * 停止在途编码 run（#359 生成取消）：不挑轨道——生成/更新/设计任一在途即置停
     * ＋中断执行任务（模型流订阅随之取消，不再烧 token）。停止＝中断口径非判死：
     * 已收口成果保留、终态按失败账口径 run-failed(stopped=true) 收口（修正轨记终态
     * 账——「继续更新」可达）。幂等：无在途返回 false（与自然收口的竞态如实回，
     * 不 409——连点/迟到点击无害）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public boolean stopInFlightRun(Long projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        return codingRunTrack.requestStop(projectId);
    }

    /**
     * 意见轮收口观测派首个构建 run（#101 生成无门自动发起→#332 生成加门→#462
     * 去确认门→<b>#542 两入口退役</b>，ADR-0048/0049）：自动派发两入口（首产
     * 回调＋轮收口兜一轮）整体退役、全域无无条件自动路径——本入口只在<b>用户
     * 触发事实</b>在场时被调用：①快道（savePrd 的 fastLane 工具调用事实——试用态
     * 用户明确收敛令、付费态零必答短路/「直接开始做」视同确认，{@link
     * MainAgentAppService} 收口消费分岔直调本入口）；②设计类轨迹的首个构建（设计
     * 全部定稿后经此起构建，设计轨触发律不改——定稿是用户动作）。<b>自然收口不走
     * 本入口</b>：试用态出岔路口卡（{@link BuildGateAppService#ensureForkPending}）、
     * 付费态确认门读面在场（PRD 文档面按钮）——点火归用户。守卫沿用
     * {@link #startGeneration}（存在 / 未归档 / 未生成过 / PRD 已产出——
     * {@link #requireGeneratableProject} 单点），在途口径：生成进行中意见轮
     * （{@code turnOpinion} 在场）入队＋明示、无意见载荷的调用面（设计定稿
     * 构建）静默跳过（不派不报错）。
     *
     * @return 派发的 run 标识；在途（生成进行中）静默跳过返回 null——调用方
     *         据此区分「已派」与「未派」，不误报派发事实
     * @throws ApplicationException 守卫组同 {@link #startGeneration}（收口观测处
     *                              已判定过未归档 / 未生成 / PRD 已产出，此处守卫
     *                              兜其余竞态调用面）
     */
    public GenerationRun dispatchGenerationOnTurnClose(Long projectId) {
        return dispatchGenerationOnTurnClose(projectId, /* turnOpinion= */ null);
    }

    /**
     * 携意见轮载荷的收口派发（#360 首 Demo 在途的反馈不静默丢）：{@code turnOpinion}
     * 在场且生成在途时意见入队＋对话面平台明示，返回 null（调用方不误报派发事实，
     * 排空补派是生成收口后的次生事实）；其余路径同两参形。
     */
    public GenerationRun dispatchGenerationOnTurnClose(Long projectId, TurnOpinion turnOpinion) {
        Project project = requireGeneratableProject(projectId);
        return dispatchGeneration(project, /* rejectInFlight= */ false, turnOpinion);
    }

    /**
     * 在途意见排队（#360 面，#542 两入口退役后的<b>非快道</b>收口观测）：首个
     * 构建 run 在途期间到达的意见轮——意见入队＋对话面平台明示（生成成功收口后
     * 合并补派更新 run，不静默丢），<b>不派发</b>（自然收口无自动路径——点火归
     * 用户：岔路口「立即生成」/确认按钮/快道）。不在途（首建未点火/已收口）静默
     * 跳过：意见由 PRD 修订承载，无需排队。
     *
     * <p>返回 void：排队是次生事实而非派发——恒 null 的返回值只会引诱调用方误读
     * 为派发回执。
     */
    public void queueFirstDemoTurnOpinion(Long projectId, TurnOpinion turnOpinion) {
        if (turnOpinion == null || turnOpinion.opinion() == null
                || turnOpinion.opinion().isBlank()) {
            return; // 无意见载荷（平台轮等）——无事可排
        }
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return;
        }
        synchronized (codingRunTrack) { // 与派发侧同锁（begin 失败 ⟹ end 未发生的锁序）
            if (!codingRunTrack.isInFlight(projectId)) {
                return; // 不在途——不排（意见由 PRD 承载，点火归用户动作）
            }
            firstDemoTurnOpinions.computeIfAbsent(projectId, key -> new ArrayList<>())
                    .add(turnOpinion);
        }
        log.info("[generate] 项目 {} 首 Demo 在途，意见轮收口入队（成功收口后"
                + "合并补派，轮锚 {}）", projectId, turnOpinion.turnRunId());
        notifyTurnOpinionQueued(project, turnOpinion); // 锁外：回执不占轨道锁
    }

    /**
     * 首次生成派发（显式与收口自动两入口共用，#601 接线）：起手资产就位 → 异步
     * 提交编码 run（<b>内核装配形态</b>，{@link #runKernelTrack}——旧切片轨已随
     * #602 拆除）。在途口径按调用方分岔——按钮路径拒绝 PRJ_017、收口自动路径
     * 静默跳过（返回 null 即「未派」，调用方不关心）。资产就位失败如实上抛并
     * 释放在途标记（环境故障口径，生成不起跑）。
     */
    private GenerationRun dispatchGeneration(Project project, boolean rejectInFlight,
            TurnOpinion turnOpinion) {
        Long projectId = project.getId();
        boolean hasTurnOpinion = turnOpinion != null && turnOpinion.opinion() != null
                && !turnOpinion.opinion().isBlank();
        // 段边界余额门（#537 计费③，ADR-0047 段间硬门）：生成 run 起跑前查门——
        // 源池≤0 拦下：项目入暂存集（充值回正经 drainBalanceBlocked 自动重派〔不手动
        // 重试〕）＋余额提醒卡直达对话面（显负额＋充值入口）；意见轮载荷按在途口径
        // 入队 firstDemoTurnOpinions＋明示回执（同 #360 不静默丢——重派收口后合并
        // 补派更新 run）。按钮路径（rejectInFlight）同步抛 BILL_025 指路充值；
        // 收口自动路径静默返回 null（同静默跳过口径）。试用恒放行。run 内绝不检查
        // 绝不中断——本门只在此（run 起跑前）被消费。
        BalanceGateAppService.Verdict gate = balanceGate.assess(projectId);
        if (gate.blocked()) {
            if (hasTurnOpinion) {
                // 入队与 begin 同临界区口径（#360 锁序——此处不占轨道，仅队列锁序）
                synchronized (codingRunTrack) {
                    firstDemoTurnOpinions.computeIfAbsent(projectId, key -> new ArrayList<>())
                            .add(turnOpinion);
                }
                notifyTurnOpinionQueued(project, turnOpinion);
            }
            balanceBlockedGeneration.add(projectId);
            eventBridge.emitBalanceReminder(project,
                    BalanceReminderAppService.BalanceReminderCard.forRunBlock(
                            gate.blockedBalanceFen(), EventsAppService.newRunId()));
            log.info("[generate] 项目 {} 余额提醒（run 场）：生成派发 intent 已暂存，充值回正自动重派",
                    projectId);
            if (rejectInFlight) {
                throw new ApplicationException(BillingMessage.BALANCE_BLOCKED);
            }
            return null;
        }
        if (hasTurnOpinion) {
            // #360 首 Demo 在途的反馈不静默丢：入队判定与 begin 同临界区（修正轨排队
            // 锁序同款——begin 失败 ⟹ end 未发生 ⟹ 排空 remove 未发生，add 不落在
            // 排空之后，零丢失；remove 同锁见 {@link #dispatchFirstDemoTurnOpinions}）
            boolean queued = false;
            synchronized (codingRunTrack) {
                if (!codingRunTrack.begin(projectId)) {
                    firstDemoTurnOpinions.computeIfAbsent(projectId, key -> new ArrayList<>())
                            .add(turnOpinion);
                    queued = true;
                }
            }
            if (queued) {
                log.info("[generate] 项目 {} 首 Demo 在途，意见轮收口入队（成功收口后"
                        + "合并补派，轮锚 {}）", projectId, turnOpinion.turnRunId());
                notifyTurnOpinionQueued(project, turnOpinion); // 锁外：回执不占轨道锁
                return null;
            }
        }
        else if (!codingRunTrack.begin(projectId)) {
            if (rejectInFlight) {
                throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
            }
            log.info("[generate] 项目 {} 生成在途，收口自动派发静默跳过（无意见载荷调用面）",
                    projectId);
            return null;
        }

        // 额度/收费通用缝（#332）：构建任务派发前「规则说明位＋执行前确认位」两挂点
        // ——v1 缺省免费不限次（说明位空、确认位不激活＝直接派发，行为无感）。结构在
        // 位：说明位激活后拼入起跑上下文/呈现面；确认位激活后此处拦下待用户确认。
        buildDispatchPolicy.ruleNotice(projectId);
        if (buildDispatchPolicy.requiresPreExecutionConfirmation(projectId)) {
            log.info("[generate] 项目 {} 构建派发前需执行前确认（规则缝激活），本次不派", projectId);
            codingRunTrack.end(projectId);
            return null;
        }

        // 首建点火时点落位（#542 派发门，ADR-0049「两轨唯一差异＝触发源」）：岔路口
        // 「立即生成」/付费轨 PRD 确认/快道直生成/重跑（重发）共用——单向置位
        // 不刷新；岔路口卡与确认按钮「首建未派发」呈现条件的事实源（BuildGateAppService）
        markFirstBuildRequested(projectId);
        String firstRunId = EventsAppService.newRunId();
        // Demo 类 run 起跑自动记行（#462 去门化，ADR-0039）：快照当时工作区 PRD 全文
        // 为构建基准（版本号＝构建基准序，同文幂等跳过）——记行先行，随后锚捕获
        // 读到的「最新版」即本场基准；best-effort：失败只记日志不反噬 run
        try {
            prdVersionAppService.recordOnDemoRunStart(projectId, firstRunId);
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} run {} 起跑记行失败（不反噬派发）：{}",
                    projectId, firstRunId, e.toString());
        }
        // Demo 版本锚捕获（#333；#462 读法顺延）：起跑时最新 PRD 版本——在途 Demo 照
        // 跑完收口绑本锚（此后新版记行不漂移旧版关联）；无版本记行（legacy/记行失败）
        // 为 null
        Integer prdVersionNo = demoVersionAppService.latestPrdVersionNo(projectId);
        Future<?> trackTask = submitTrack(project, firstRunId, prdVersionNo);
        codingRunTrack.registerTask(projectId, trackTask);
        return new GenerationRun(firstRunId);
    }

    /**
     * 轨道任务提交（派发尾单点）：生成轨道任务提交会话执行器（#603 起直接执行——
     * 原生成车道的排队/错峰/排空已拆，即交即跑）＋停止/关停收口面装配——内核
     * 轨道正常返回后排空在途意见队列（#360）。
     */
    private Future<?> submitTrack(Project project, String firstRunId, Integer prdVersionNo) {
        Long projectId = project.getId();
        return sessionExecutor.submitGeneration(
                CodingRunDispatcher.SESSION_PREFIX + projectId,
                () -> {
                    boolean trackReturned = false;
                    try {
                        runKernelTrack(project, firstRunId, prdVersionNo);
                        trackReturned = true;
                    }
                    catch (RunStoppedException e) {
                        // 停止收口（#359 生成取消）：中断口径非判死——已完成进度保留；
                        // 终态按失败账口径 run-failed(stopped=true) 收口；#360 在途
                        // 意见队列保留（不排空）——重跑成功收口时消化，不自锁
                        reopenFirstBuildRequest(projectId);
                        eventBridge.emitRunStopped(project, e.runId(), e.failure());
                    }
                    catch (RunShutdownException e) {
                        // 关停收口（#477）：宽限窗内中断——不落失败、不发终态事件
                        // （进程正死，留痕只日志，本处不做落库）；重启后由重跑入口
                        // 恢复（POST /generate——点火位未清，入口为前端「继续生成」
                        // 恢复出口，#604 起无断点续跑）
                        log.info("[generate] 项目 {} 生成轨道因平台关停中断（runId={}，本场累计与逐尝试账见 failure 面）",
                                projectId, e.runId());
                    }
                    finally {
                        codingRunTrack.end(projectId);
                    }
                    if (trackReturned) {
                        // #360 排空只在轨道正常返回后（停止/异常收场不动队列）；
                        // 在途标记已释放（排空补派的更新 run 要占同一轨道）——补派
                        // 是否起跑由排空内按 generated_at 事实判定
                        dispatchFirstDemoTurnOpinions(projectId);
                    }
                });
    }

    /**
     * 在途意见入队后的平台明示（#360）：guide-reply 复用型（prompt 不携带——用户
     * 气泡已由意见轮自落，平台只补回执一条），同步落对话史（#89 闭史，刷新回访
     * 不丢；落库内部 quietly 自吞失败）。事件发射失败只记日志不反噬排队事实。
     */
    private void notifyTurnOpinionQueued(Project project, TurnOpinion turnOpinion) {
        conversationHistory.recordGuideNotice(project.getId(), turnOpinion.turnRunId(),
                IN_FLIGHT_OPINION_NOTICE);
        try {
            eventBridge.emitGuideReply(project, turnOpinion.turnRunId(), /* prompt= */ null,
                    DispatchAppService.GUIDE_LABEL, IN_FLIGHT_OPINION_NOTICE);
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} 在途意见回执事件发射失败（不反噬排队）：{}",
                    project.getId(), e.toString());
        }
    }

    /**
     * 首 Demo 在途意见队列的排空（#360，生成轨道任务尾部调）：合并为一场更新 run
     * 补派（逐轮配对交接物——同构修正轨排队合并，不逐条烧 run）。只在生成
     * <b>成功收口</b>后排空——以 {@code generated_at} 落位事实为闸（失败收口轨道
     * 正常返回的场景同走本方法、闸不过即原队保留，重跑成功时再来
     * 消化——不自锁、不重复派）。补派撞修正轨在途（用户恰在收口窗口提意见起跑
     * 了更新 run）即排进修正轨队列，那场收口排空时自然合并（竞态零丢失）。补派
     * 失败不炸轨道任务（#51 口径）——记日志，用户重提即兜底。
     */
    private void dispatchFirstDemoTurnOpinions(Long projectId) {
        Project current = projectRepository.findById(projectId).orElse(null);
        if (current == null || current.getGeneratedAt() == null) {
            return; // 未成功收口（失败/停止/收口落位失败）——队列保留待重跑成功
        }
        List<TurnOpinion> queued;
        synchronized (codingRunTrack) { // 与入队侧同锁（begin 失败 ⟹ end 未发生的锁序）
            queued = firstDemoTurnOpinions.remove(projectId);
        }
        if (queued == null || queued.isEmpty()) {
            return;
        }
        IterationAppService.FixHandoff handoff = new IterationAppService.FixHandoff(
                queued.stream().map(opinion -> new IterationAppService.FixHandoff.Round(
                        opinion.opinion(), opinion.prdRevisionSummary())).toList());
        try {
            IterationAppService.FixDispatch dispatch =
                    iterationAppService.startFixRun(projectId, handoff);
            log.info("[generate] 项目 {} 首 Demo 收口，排空在途意见 {} 轮合并补派更新 run（{}）",
                    projectId, queued.size(), dispatch.queued() ? "撞在途转修正轨排队" : "起跑");
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} 在途意见排空补派失败（用户重提即兜底）：{}",
                    projectId, e.toString());
        }
    }

    /**
     * 充值回正排空（#537——「充值到账后下一段自动放行〔不手动重试〕」run 场面）：
     * 余额闸暂存的生成派发 intent 逐项目重派（未回正的项目保持暂存待下一次入账）。
     * 已生成（生成在拦下窗口外竞态收口）的项目不重演生成——只在途意见队列排空
     * （合并补派更新 run）；未生成的重派生成（被拦意见轮随 firstDemoTurnOpinions
     * 既有口径消化）。重派失败记日志——用户重提即兜底。入账事件（AFTER_COMMIT
     * 监听）触发。
     */
    public void drainBalanceBlocked() {
        for (Long projectId : Set.copyOf(balanceBlockedGeneration)) {
            if (balanceGate.assess(projectId).blocked()) {
                continue; // 未回正——暂存保持待下一次入账
            }
            if (!balanceBlockedGeneration.remove(projectId)) {
                continue; // 并发已被排空——不重复重派
            }
            try {
                log.info("[generate] 项目 {} 余额回正：暂存生成派发 intent 自动重派", projectId);
                dispatchFirstDemoTurnOpinions(projectId); // 已生成：在途意见合并补派更新 run
                Project current = projectRepository.findById(projectId).orElse(null);
                if (current != null && current.getArchivedAt() == null
                        && current.getGeneratedAt() == null) {
                    dispatchGeneration(current, /* rejectInFlight= */ false,
                            /* turnOpinion= */ null);
                }
            }
            catch (RuntimeException e) {
                log.warn("[generate] 项目 {} 余额回正排空重派失败（intent 丢弃，重提即兜底）：{}",
                        projectId, e.toString());
            }
        }
    }

    /** 一场生成的运行标识 = 用户面 run 身份（前端挂智能体事件 ?runId= 的锚；#604 起单发无重试，全程同值）。 */
    public record GenerationRun(String runId) {
    }

    /**
     * 首建点火时点落位（#542）：run 提交在即时短事务置位（单向——已置不刷新；
     * 余额拦下/资产失败等早退路径不落位＝「未派发」如实）。读面消费归
     * {@link BuildGateAppService}（岔路口卡/确认按钮的呈现条件），本服务只保证
     * 落位语义。
     */
    private void markFirstBuildRequested(Long projectId) {
        transactionTemplate.executeWithoutResult(status -> projectRepository.findById(projectId)
                .ifPresent(current -> {
                    current.markFirstBuildRequested();
                    projectRepository.save(current);
                }));
    }

    /**
     * 生成内核轨道（#601 接线，ADR-0054/0055）：首建点火后整轨<b>一场 run</b>——内核
     * 装配形态（{@link CodingRunDispatcher.RunSeat#kernel()}：系统提示＝{@link
     * KernelSystemPrompt} 权威版本、工具恰四件、循环语义中间件、装配键不带 executor
     * 〔决策提示／verify 自决旧中间件结构性挂不上〕）单会话连续驱动，直至收尾判据
     * 双件全过（{@link KernelClosingCriteria}：{@code pnpm build} 过＋8081 探活有
     * 响应——与内核完成判据句同口径）。起服先行由 {@link KernelServingGuard} 尽力
     * 确保（收敛不成不杀 run——探活仍是收口硬门）。成功：{@code generated_at}/Demo
     * 首版成版＋preview-ready；失败/停止：终态 run-failed＋清点火位（岔路口卡/确认
     * 按钮复现——用户在点火入口重跑即恢复，#604 失败新语义＝明确展示＋重跑即续，
     * ADR-0055 #580-13：不自动重跑、不携错误现场）。
     */
    private void runKernelTrack(Project project, String firstRunId, Integer prdVersionNo) {
        Long projectId = project.getId();
        // 起手起服（#597 起服守卫，尽力而为）：开发服务器平台起、常驻——内核工具面
        // 恰四件、无杀/起服务路径，探活收口前起服须在
        try {
            kernelServingGuard.ensureServing(project.getWorkspaceId());
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} 内核轨道起手起服未成（探活仍为收口硬门）：{}",
                    projectId, e.toString());
        }
        CodingRunDispatcher.RunResult result = codingRunDispatcher.run(project, firstRunId,
                kernelSession(projectId, firstRunId),
                // 单发提示（#604 失败即终态、不自动重试）
                KERNEL_FIRST_RUN_PROMPT,
                (attemptRunId, attemptChanges) -> closeKernelGeneration(project),
                CodingRunDispatcher.GENERATE_LABEL, true, CodingRunDispatcher.RunSeat.kernel(),
                RunHeading.titled(STAGE0_TITLE), SegmentType.GENERATION_RUN);
        if (!result.succeeded()) {
            reopenFirstBuildRequest(projectId);
            eventBridge.emitRunFailed(project, firstRunId, result.failure());
            return;
        }
        markGenerated(projectId, firstRunId, prdVersionNo);
        // 演示种子预置（#340 口径延续：Demo 将可见前一刻——幂等，失败不阻断收工）
        demoSeed.seedSandbox(project);
        emitPreviewReady(project);
    }

    /**
     * 内核收口判据回调（#601 接线，ADR-0054）：run 正常返回（一轮无工具调用）后的
     * 平台侧机器核验——{@code pnpm build} 过＋8081 探活双件全过才判完成
     * （{@link KernelClosingCriteria}，#597 落位件）；未过即抛——该场 run 失败转
     * 终态（#604 单发：不自动重跑，用户在点火入口重跑即恢复）。逐件结果（#617）
     * 随收口判据回给派发器，进收尾卡「判据结果」。
     */
    private CodingRunDispatcher.ClosingJudgment closeKernelGeneration(Project project) {
        KernelClosingCriteria.KernelClosingResult closing =
                kernelClosingCriteria.assess(project.getWorkspaceId());
        if (!closing.complete()) {
            throw new IllegalStateException("收尾判据未过：pnpm build "
                    + (closing.buildPassed() ? "通过" : "未通过") + "、8081 探活"
                    + (closing.probePassed() ? "有响应" : "无响应")
                    + "——按完成判据继续修复后再收口");
        }
        return new CodingRunDispatcher.ClosingJudgment(
                closing.buildPassed(), closing.probePassed());
    }

    /**
     * 首建点火位复位（#604 失败新语义）：终态失败/停止收口时清
     * {@code generation_requested_at}——岔路口卡/确认按钮「首建未派发」呈现条件
     * 复现，用户在点火入口重跑即恢复（成功收口不清：已生成，入口本就不在场）。
     */
    private void reopenFirstBuildRequest(Long projectId) {
        try {
            transactionTemplate.executeWithoutResult(status -> projectRepository.findById(projectId)
                    .ifPresent(current -> {
                        current.reopenFirstBuildRequest();
                        projectRepository.save(current);
                    }));
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} 点火位复位失败（不反噬终态收口）：{}", projectId,
                    e.toString());
        }
    }

    /**
     * 首 Demo 在途时意见轮的平台明示文案（#360）：入队即回执——用户面「说了没反应」
     * 补上平台事实（意见已记录、生成完成后自动落实），兑现靠排空补派而非空话。
     */
    static final String IN_FLIGHT_OPINION_NOTICE =
            "首次生成进行中，本条意见已记录，生成完成后将自动落实";

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
                    ProjectEventTypes.URL_FIELD, url.toString(),
                    EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId()),
                        EventsAppService.OWNER_GUEST_FIELD,
                        EventsAppService.ownerPayload(project.getOwnerGuestId())));
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} preview-ready 推送失败（UI 面，不断流）：{}",
                    project.getId(), e.getMessage());
        }
    }

    /**
     * 首次生成时点落位＋Demo 首版成版（#333 同刻原子——{@link
     * DemoVersionAppService#closeOnGeneration} 短事务）：失败记日志不炸异步轨道
     * （run 已成功；两落 either-or，下次派发收口自愈）。
     */
    private void markGenerated(Long projectId, String runId, Integer prdVersionNo) {
        try {
            demoVersionAppService.closeOnGeneration(projectId, runId, prdVersionNo);
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} generated_at/Demo 首版落位失败：{}", projectId,
                    e.toString());
        }
    }

    // ---------- 内部 ----------

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
