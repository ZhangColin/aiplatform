package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.FileChange;
import com.semgravio.aiplatform.base.agentscope.RunHeading;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.billing.application.BalanceGateAppService;
import com.semgravio.aiplatform.business.billing.application.BalanceReminderAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 迭代编排（#26 迭代环① → #43 链必达收口 → #46 结束工具收口）：需求侧判定内化
 * 主智能体，派发权归平台——意见轮收口（无追问挂起）由 {@link MainAgentAppService}
 * 观测并自动调用本服务派修正 run，不依赖模型自觉调用派发工具（startFixRun 已撤）。
 * 交接物三要素（#52，CONTEXT.md「交接物」）：意见原文清单 + 主智能体的需求侧判定
 * （PRD 改没改、改了什么——{@link PrdRevisionFacts} 从 savePrd 工具调用事实观测）
 * + PRD 路径引用（{@link ProjectArtifacts#PRD}，不注全文——执行体重读约定见
 * 配置），结构化拼装入 {@link FixHandoff} 随修正 run 下发（排队合并时逐轮
 * 配对全保留——各轮「意见 → 修订说明」一一对应，未修订轮显式占位，#55）。
 * 修正 run 与生成同机制（每次新会话（#114 更新 run 每次新会话）与同工作区——
 * run 执行体在迭代链上上下文有界；知识命中前置注入 / 单发派发 / 计量全走
 * 共用派发器 {@link CodingRunDispatcher}，#604 尝试环退役——失败＝明确展示
 * ＋重跑即恢复，不自动重试）。
 *
 * <p><b>收口判据＝平台侧机器双件</b>（#601 内核接线；#604 finish_edit 收口件退役；
 * #605 事实登记件清场）：修正 run 正常返回后过 pnpm build＋8081 探活双件，未过即
 * 该场 run 失败终态；系统改没改以平台可观测文件改动事实为准（不依赖模型自报）。
 * 判据逐件结果与变更清单的呈现归收口扩载权威化（#617 五字段收尾卡）。</p>
 *
 * <p><b>需求基准＝工作区 PRD 正本（#462 去确认门——#334「小迭代基准＝当前已确认
 * 版本」随停派整段退役，ADR-0039/#450 裁决 A）</b>：范围级新草稿不再停派待确认
 * ——意见轮收口一律受理即派（判定权归主智能体工作协议）；修正 run 以工作区
 * {@code docs/PRD.md} 正本为需求基准（路径引用、执行体重读约定见配置）。原
 * 漂移判定组件 {@code PrdDraftBaseline} 随两消费路径退役一并注销；Demo 类 run
 * 起跑自动记 PRD 版本行（{@link PrdVersionAppService#recordOnDemoRunStart}，
 * 版本号＝构建基准序——起跑时的新草稿即成新基准快照），Demo 版本锚捕获读
 * 「最新版」。</p>
 *
 * <p><b>排队合并</b>：修正 run 进行中再派的任务排队（主智能体回复用户「已排入下一轮」）；
 * 当前 run 收口后（无论成败）排空队列、合并为一场修正 run 续派——用户在 run 中
 * 连提多条意见不会被丢弃，也不会每条各烧一场 run。轨道状态（在途标记 + 队列）是
 * 进程内事实，重启即清（run 无表口径）：重启后进行中 run 标失败，用户重新提意见
 * 即重新起轨。</p>
 *
 * <p><b>终态失败恢复出口</b>（#48，#604 读法顺延）：修正 run 终态失败（run-failed）
 * 时，该场的交接
 * 任务记入 {@link #terminallyFailedHandoffs}（成功收工即清），恢复出口
 * {@link #restartFixRun} 据此重派——交接物沿用、新 runId 随响应回（与新 run 的
 * 链路锚）。仅终态可达：修正在途（进行中/排队中）拒绝 PRJ_025、无终态账（未派过/
 * 已成功/重启丢账）拒绝 PRJ_026——正常流程全自动，不出现任何手动触发，故障态留
 * 最后一条生路。终态收口事件 {@code run-failed} 与终态账同事实点发射（#56）：事件到
 * ⟺ 恢复出口可达——排队合并续派的中途超限不发（轨道仍在途，「重新修改」出口
 * 零闪现）。</p>
 *
 * <p><b>无次数上限</b>：迭代轮数不设界，意见发散时的收敛催促归主智能体协议（配置），
 * 平台不设门。</p>
 *
 * <p><b>内核接线（#601，ADR-0054/0055）</b>：迭代重建 run 按<b>生成内核装配形态</b>
 * 派发（{@link CodingRunDispatcher.RunSeat#kernel()}——系统提示权威版本、工具恰四件、
 * 循环语义中间件、装配键不带 executor 旧中间件）；收尾＝平台侧机器判据双件
 * （{@link KernelClosingCriteria}：{@code pnpm build} 过＋8081 探活），不再经
 * finish_edit 事实／验证在场／产出证明／终验复走旧验收链。</p>
 */
@Service
@Slf4j
public class IterationAppService {

    /**
     * 内核修正任务 prompt（#601 接线）：交接物三要素结构化拼装同 {@link
     * {@link #kernelFixRunPrompt}（意见原文＋逐轮需求侧判定＋PRD 正本引用），收口判据句换内核
     * 口径——pnpm build 过＋8081 探活有响应双件全过才算完成（无 finish_edit 尾句：
     * 内核工具面恰四件、收口以平台侧机器判据为准）。
     */
    static String kernelFixRunPrompt(FixHandoff handoff) {
        StringBuilder prompt = new StringBuilder(
                "系统修正：系统已生成并可操作，用户提出了如下修正意见（每条意见后紧跟"
                        + "该轮的需求侧判定），请在现有工作区内完成修正（系统的其余部分"
                        + "保持可用）：");
        for (int index = 0; index < handoff.rounds().size(); index++) {
            FixHandoff.Round round = handoff.rounds().get(index);
            prompt.append("\n").append(index + 1).append(". 意见原文：").append(round.opinion())
                    .append("\n   需求侧判定（主智能体已收口）：")
                    .append(round.prdRevisionSummary() != null
                            ? "PRD 已修订——" + round.prdRevisionSummary()
                            : "本轮无修订（未触发 PRD 变更）");
        }
        prompt.append("\n需求正本 = 工作区 ").append(ProjectArtifacts.PRD)
                .append("，以正本与上述意见为准。");
        prompt.append("\n完成后用 pnpm build 验证可构建、用 curl http://localhost:8081 "
                + "确认有响应——双件全过本轮修正才算完成。");
        return prompt.toString();
    }

    /** 修正轨会话寻址（#114 更新 run 每次新会话）：runId 逐场换新 → 会话逐场换新。 */
    static String fixSession(Long projectId, String runId) {
        return CodingRunDispatcher.SESSION_PREFIX + projectId + "-fix-" + runId;
    }

    /**
     * 更新 run 工作消息头部标题（#118）：平台生成（判定跟职责走——收口判定归平台
     * 机器判据双件、标题不做自由文本解析），用户语言「系统更新」（对应用户面
     * 「更新」词条），无切片进度。
     */
    static final String FIX_TITLE = "系统更新";

    private final ProjectRepository projectRepository;
    private final AgentSessionExecutor sessionExecutor;
    private final CodingRunDispatcher codingRunDispatcher;
    private final AgentEventBridge eventBridge;
    private final CodingRunTrack codingRunTrack;
    private final DemoVersionAppService demoVersionAppService;
    private final PrdVersionAppService prdVersionAppService;
    private final DemoSeedAppService demoSeed;
    private final BalanceGateAppService balanceGate;
    /** 内核收尾判据（#601 接线消费 #597 落位件）：build 过＋8081 探活双件。 */
    private final KernelClosingCriteria kernelClosingCriteria;

    /** 对话面在途入待办的轻提示落对话史（刷新/回访不丢，#89 闭史口径）。 */
    private final ConversationHistoryAppService conversationHistory;

    /** run 进行中排队的修正交接物（projectId → 待合并交接物清单）。 */
    private final Map<Long, List<FixHandoff>> queuedFixRuns = new ConcurrentHashMap<>();
    /**
     * 超限终态那场的交接物（projectId → 终态交接物）：恢复出口的重派依据，
     * 成功收工即清。进程内态与轨道状态同口径——重启丢账，恢复出口 409 指路重提
     * 意见（既有兜底不变）。
     */
    private final Map<Long, FixHandoff> terminallyFailedHandoffs = new ConcurrentHashMap<>();
    /**
     * 余额闸暂存的修正 intent（#537 段边界余额门，projectId → 被拦交接物）：段
     * 边界源池≤0 时派发/恢复出口被拦的交接物暂存于此，充值回正经
     * {@link #drainBalanceBlocked} 自动重派（不手动重试）。进程内锚形态（同
     * 轨道状态口径）——重启丢账属既有兜底哲学。多场被拦合并暂存（交接物
     * {@link FixHandoff#merge} 口径——不丢轮）。
     */
    private final Map<Long, FixHandoff> balanceBlockedFixRuns = new ConcurrentHashMap<>();

    /**
     * 对话面交指令的待办（#623，projectId → 按到达序待派的自足修改指令）：生成/迭代
     * run 在途（含首建在途）时先记在此并发轻提示「已记下，下一轮生效」；当前 run
     * <b>成功</b>收口后由 {@link #drainPendingModificationsAfterSuccess} 合并为恰好
     * 一场后续 run 补派。失败/停止不自动补派、待办保留，用户重说即触发下一轮。
     * 进程内态与轨道状态同口径——重启即清，用户重说即恢复。
     */
    private final Map<Long, List<ModificationHandoffFacts.Instruction>> pendingModifications =
            new ConcurrentHashMap<>();

    /**
     * 段边界余额闸暂存的对话面修改 intent（#537 计费③口径沿用，projectId → 被拦
     * 指令集）：源池≤0 时起跑被拦、暂存于此，充值回正经 {@link #drainBalanceBlocked}
     * 自动重派（不手动重试）。多场被拦按到达序合并（不丢条）。进程内态，重启即清。
     */
    private final Map<Long, List<ModificationHandoffFacts.Instruction>> balanceBlockedModifications =
            new ConcurrentHashMap<>();

    /** 对话面交指令在途入待办后的平台轻提示文案（#623，guide-reply 复用）。 */
    static final String MODIFICATION_QUEUED_NOTICE = "已记下，下一轮生效";

    public IterationAppService(ProjectRepository projectRepository,
            AgentSessionExecutor sessionExecutor, CodingRunDispatcher codingRunDispatcher,
            AgentEventBridge eventBridge,
            CodingRunTrack codingRunTrack,
            DemoVersionAppService demoVersionAppService,
            PrdVersionAppService prdVersionAppService,
            DemoSeedAppService demoSeed,
            BalanceGateAppService balanceGate,
            KernelClosingCriteria kernelClosingCriteria,
            ConversationHistoryAppService conversationHistory) {
        this.projectRepository = projectRepository;
        this.sessionExecutor = sessionExecutor;
        this.codingRunDispatcher = codingRunDispatcher;
        this.eventBridge = eventBridge;
        this.codingRunTrack = codingRunTrack;
        this.demoVersionAppService = demoVersionAppService;
        this.prdVersionAppService = prdVersionAppService;
        this.demoSeed = demoSeed;
        this.balanceGate = balanceGate;
        this.kernelClosingCriteria = kernelClosingCriteria;
        this.conversationHistory = conversationHistory;
    }

    /**
     * 派修正任务（意见轮收口的平台自动派发入口，#43 链必达）：修正 run 空闲即
     * 起跑（runId 随派发生成，过程事件经 SSE）；在途则排入队列、当前 run 收口后
     * 合并续派。交接物三要素中的需求侧判定随派发携带：{@code prdRevisionSummary}
     * = 主智能体轮 savePrd 的 summary 终值（null = 本轮未修订）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              PRJ_019 系统从未生成（迭代在首次生成完成后
     *                              才开始——意见收口侧对未生成项目静默止于对话，
     *                              此处守卫兜其余调用面）
     */
    public FixDispatch startFixRun(Long projectId, String task, String prdRevisionSummary) {
        Project project = requireFixableProject(projectId);
        return dispatch(project, new FixHandoff(
                List.of(new FixHandoff.Round(task.strip(), prdRevisionSummary))));
    }

    /**
     * 携完整交接物的修正派发（#360 首 Demo 在途意见的排空口）：与
     * {@link #startFixRun} 同守卫同轨道，差异只在交接物粒度——多轮意见由调用方
     * 逐轮配对成 {@link FixHandoff}（首 Demo 在途期间收口的各意见轮，生成收口后
     * 合并为一场更新 run 补派）；在途口径同款（修正 run 在途即排队进
     * {@link #queuedFixRuns}，那场收口排空时自然合并——竞态零丢失）。
     */
    FixDispatch startFixRun(Long projectId, FixHandoff handoff) {
        Project project = requireFixableProject(projectId);
        return dispatch(project, handoff);
    }

    /**
     * 对话面交指令（#623，ADR-0056 修改交接的唯一出口）：对话面把已说清楚的修改
     * 蒸馏成自足指令后交来本方法——<b>空闲即起一轮内核重跑</b>；有 run 在途
     * （含首建在途、或首建失败尚未生成的空闲态）<b>进平台内存待办</b>并发一条
     * 轻提示「已记下，下一轮生效」（guide-reply 复用），当前生成不打断。当前 run
     * <b>成功</b>收口后由 {@link #drainPendingModificationsAfterSuccess} 合并为一场
     * 后续 run 补派；失败/停止不自动补派、待办保留，用户重说即触发下一轮。
     *
     * <p><b>段边界余额门照旧</b>（#537 计费③，ADR-0047）：起跑前源池≤0 拦下暂存
     * 并推余额提醒卡，充值回正自动重派（同 {@link #drainBalanceBlocked}）；在途被
     * 排队的指令等到真正起跑时再查门。待办是进程内态，平台重启即清——用户重说
     * 即恢复（与旧队列同口径）。</p>
     *
     * @param dialogRunId 对话面本轮 runId（在途轻提示的对话面锚；空闲直接起跑时
     *                    仅为回执锚，不需提示）
     */
    public void handOffModification(Project project,
            List<ModificationHandoffFacts.Instruction> instructions, String dialogRunId) {
        if (instructions == null || instructions.isEmpty()) {
            return;
        }
        Long projectId = project.getId();
        boolean queued = false;
        synchronized (codingRunTrack) {
            // 同一临界区内取最新项目事实（对话轮可能跨过首建收口瞬间：陈旧快照会把
            // 「刚生成完、轨道已释放」误记为未生成而滞留待办——排空钩子已经跑过，
            // 无人再来取）。取舍：一次主键 SELECT 在轨道锁内，换零滞留窗口。
            Project current = projectRepository.findById(projectId).orElse(project);
            if (current.getArchivedAt() != null) {
                log.info("[modify] 项目 {} 已归档，对话面交指令不收（修改出口关闭）", projectId);
                return;
            }
            // 在途（含首建点火在途）或尚未生成（首建失败空闲态）——进待办：
            // 前者当前 run 不打断、成功收口后补派；后者待首建成功收口后作第一轮
            // 迭代补派（首建失败后待办保留，重新点火成功即补派）
            if (codingRunTrack.isInFlight(projectId) || current.getGeneratedAt() == null) {
                pendingModifications.computeIfAbsent(projectId, key -> new ArrayList<>())
                        .addAll(instructions);
                queued = true;
            }
        }
        if (queued) {
            log.info("[modify] 项目 {} run 在途（或未生成），修改指令入待办 {} 条（成功收口后"
                    + "合并补派，对话轮锚 {}）", projectId, instructions.size(), dialogRunId);
            notifyModificationQueued(project, dialogRunId);
            return;
        }
        dispatchModification(project, instructions, dialogRunId);
    }

    /**
     * 当前 run 成功收口后的待办排空（#623）：首建成功（{@code generated_at} 已落）
     * 或迭代 run 成功后由轨道调用——待办非空且当前无 run 在途，即合并为恰好一场
     * 后续 run 补派（多轮指令合并进同一场，不逐条烧 run）。失败/停止不调用本
     * 方法（待办保留，用户重说即触发下一轮）；未生成（首建失败/停止）直接返回。
     */
    public void drainPendingModificationsAfterSuccess(Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getArchivedAt() != null || project.getGeneratedAt() == null) {
            return; // 未成功收口（首建失败/停止）或已归档——待办保留
        }
        List<ModificationHandoffFacts.Instruction> pending;
        synchronized (codingRunTrack) {
            if (codingRunTrack.isInFlight(projectId)) {
                return; // 仍有 run 在途——等那场成功收口再排空（不并发起轨）
            }
            pending = pendingModifications.remove(projectId);
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }
        log.info("[modify] 项目 {} 前一场 run 成功收口，排空待办修改指令 {} 条合并补派一轮",
                projectId, pending.size());
        dispatchModification(project, pending, null);
    }

    /**
     * 修正 run 超限终态的恢复出口（#48）：重派终态那场的交接物（交接物沿用、
     * 每次新会话，#114），新 runId 随响应回——与新 run 的链路锚（同
     * {@code /generate} 口径），重派事实落日志可追溯。仅终态可达：修正在途（进行
     * 中/排队中）PRJ_025；无终态账（未派过修正/已成功收工/重启丢账）PRJ_026；
     * 归档/未生成守卫同 {@link #startFixRun}。
     */
    public FixDispatch restartFixRun(Long projectId) {
        Project project = requireFixableProject(projectId);
        FixHandoff handoff;
        String firstRunId;
        synchronized (codingRunTrack) {
            if (codingRunTrack.isInFlight(projectId)) {
                throw new ApplicationException(ProjectMessage.FIX_RESTART_IN_FLIGHT);
            }
            handoff = terminallyFailedHandoffs.get(projectId);
            if (handoff == null) {
                throw new ApplicationException(ProjectMessage.FIX_RESTART_UNAVAILABLE);
            }
            // 段边界余额门（#537 计费③）：恢复出口同守段间硬门——欠费不空翻终态账
            // （PRJ_026 账保留），交接物转余额闸暂存（充值回正 drainBalanceBlocked
            // 自动重派〔不手动重试〕），BILL_025 指路充值
            BalanceGateAppService.Verdict gate = balanceGate.assess(projectId);
            if (gate.blocked()) {
                terminallyFailedHandoffs.remove(projectId, handoff);
                balanceBlockedFixRuns.merge(projectId, handoff,
                        (oldHeld, newHeld) -> FixHandoff.merge(List.of(oldHeld, newHeld)));
                String blockedRunId = EventsAppService.newRunId();
                eventBridge.emitBalanceReminder(project,
                        BalanceReminderAppService.BalanceReminderCard.forRunBlock(
                                gate.blockedBalanceFen(), blockedRunId));
                log.info("[fix] 项目 {} 余额提醒（run 场）：恢复出口被拦，交接物已暂存，充值回正自动重派",
                        projectId);
                throw new ApplicationException(BillingMessage.BALANCE_BLOCKED);
            }
            terminallyFailedHandoffs.remove(projectId);
            codingRunTrack.begin(projectId);
            firstRunId = EventsAppService.newRunId();
        }
        log.info("[fix] 项目 {} 恢复出口重派修正 run（runId={}，交接 {} 轮，源自超限终态）",
                projectId, firstRunId, handoff.rounds().size());
        beginFixTrack(project, firstRunId, handoff);
        return new FixDispatch(firstRunId, false);
    }

    /** 修正派发结果（起跑 runId / 是否排入下一轮）。 */
    public record FixDispatch(String runId, boolean queued) {
    }

    /**
     * 修正 run 交接物（#52，CONTEXT.md「交接物」）：需求侧收口后交给修正的任务
     * 载体——逐轮配对的「意见原文 → 该轮判定结果」（{@link Round}，#55）：一轮
     * 派发一条意见一份判定，配对关系由平台代码在拼装时锚定，不依赖意见清单与
     * 说明清单的位置对齐——某轮零修订不再引起后续轮错位。summary null = 该轮
     * 无 savePrd 调用事实（即未修订；占位呈现归 {@link #kernelFixRunPrompt}）。PRD
     * 引用不随物携带（路径固定 {@link ProjectArtifacts#PRD}，prompt 拼装时引用）。
     */
    record FixHandoff(List<Round> rounds) {

        /** 一轮派发的一条交接：意见原文 + 该轮 PRD 修订说明（null = 该轮未修订）。 */
        record Round(String opinion, String prdRevisionSummary) {
        }

        /** 排队合并：各轮按派发序成对串联（#55 逐轮配对）——跨轮各轮的判定
         * 描述的是当前 PRD 上仍生效的增量（A 轮落盘 X、B 轮在其上再改 Y），只留
         * 最新会让修正侧漏掉早期轮的需求侧变更（#53）；未修订轮不丢不串位
         * （null 槽保留轮位，占位呈现归 prompt 拼装）——丢轮即后续全部错位。
         * 同轮多次 savePrd 的「取终值」在捕获层（{@link PrdRevisionFacts}
         * 后写胜出）处理，不在此层。 */
        static FixHandoff merge(List<FixHandoff> handoffs) {
            return new FixHandoff(handoffs.stream()
                    .flatMap(handoff -> handoff.rounds().stream())
                    .toList());
        }
    }

    // ---------- 内部 ----------

    /** 迭代态守卫：未归档 / 已生成（修正 run 的对象是已生成的系统）。 */
    private Project requireFixableProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        if (project.getGeneratedAt() == null) {
            throw new ApplicationException(ProjectMessage.FIX_RUN_NOT_GENERATED);
        }
        return project;
    }

    /** 迭代态守卫过后的派发：空闲即起跑，在途即排队。 */
    private FixDispatch dispatch(Project project, FixHandoff handoff) {
        Long projectId = project.getId();
        // 段边界余额门（#537 计费③，ADR-0047 段间硬门）：修正 run 起跑前查门——
        // 源池≤0 拦下暂存（多场被拦合并不丢轮）＋余额提醒卡直达对话面（显负额＋
        // 充值入口），充值回正经 drainBalanceBlocked 自动重派（不手动重试）；试用
        // 恒放行。段内绝不检查绝不中断——本门只在此（段起跑前）被消费。
        BalanceGateAppService.Verdict gate = balanceGate.assess(projectId);
        if (gate.blocked()) {
            balanceBlockedFixRuns.merge(projectId, handoff,
                    (oldHeld, newHeld) -> FixHandoff.merge(List.of(oldHeld, newHeld)));
            String runId = EventsAppService.newRunId();
            eventBridge.emitBalanceReminder(project,
                    BalanceReminderAppService.BalanceReminderCard.forRunBlock(
                            gate.blockedBalanceFen(), runId));
            log.info("[fix] 项目 {} 余额提醒（run 场）：修正 intent 已暂存，充值回正自动重派",
                    projectId);
            return new FixDispatch(runId, false);
        }
        String firstRunId;
        synchronized (codingRunTrack) {
            if (!codingRunTrack.begin(projectId)) {
                queuedFixRuns.computeIfAbsent(projectId, key -> new ArrayList<>()).add(handoff);
                return new FixDispatch(null, true);
            }
            firstRunId = EventsAppService.newRunId();
        }
        beginFixTrack(project, firstRunId, handoff);
        return new FixDispatch(firstRunId, false);
    }

    /**
     * 对话面修改批次的派发（空闲入口与成功排空共用）：段边界余额门 → 与残留待办
     * 合并 → 占轨起跑。占轨失败（收口窗口内别处已起跑）即连同已取出的残留待办一起
     * 回填待办，并给对话轮补轻提示——零丢失；那场 run 成功收口后再排空。
     */
    private void dispatchModification(Project project,
            List<ModificationHandoffFacts.Instruction> incoming, String dialogRunId) {
        Long projectId = project.getId();
        BalanceGateAppService.Verdict gate = balanceGate.assess(projectId);
        if (gate.blocked()) {
            balanceBlockedModifications.merge(projectId, new ArrayList<>(incoming),
                    (held, fresh) -> {
                        held.addAll(fresh);
                        return held;
                    });
            String blockedRunId = EventsAppService.newRunId();
            eventBridge.emitBalanceReminder(project,
                    BalanceReminderAppService.BalanceReminderCard.forRunBlock(
                            gate.blockedBalanceFen(), blockedRunId));
            log.info("[modify] 项目 {} 余额提醒（run 场）：对话面修改 intent 已暂存，充值回正自动重派",
                    projectId);
            return;
        }
        List<ModificationHandoffFacts.Instruction> batch = new ArrayList<>();
        String firstRunId = null;
        synchronized (codingRunTrack) {
            List<ModificationHandoffFacts.Instruction> retained =
                    pendingModifications.remove(projectId);
            if (retained != null) {
                batch.addAll(retained);
            }
            batch.addAll(incoming);
            if (!codingRunTrack.begin(projectId)) {
                pendingModifications.merge(projectId, batch, (existing, fresh) -> {
                    List<ModificationHandoffFacts.Instruction> merged = new ArrayList<>(existing);
                    merged.addAll(fresh); // 保持到达序：先前待办在前
                    return merged;
                });
            }
            else {
                firstRunId = EventsAppService.newRunId();
            }
        }
        if (firstRunId == null) {
            log.info("[modify] 项目 {} 轨道在收口窗口被占用，修改指令回填待办（成功收口后补派）",
                    projectId);
            notifyModificationQueued(project, dialogRunId);
            return;
        }
        log.info("[modify] 项目 {} 起一轮修改 run（{} 条指令合并，runId={}）", projectId,
                batch.size(), firstRunId);
        submitModificationTrack(project, firstRunId, batch);
    }

    /**
     * 在途入待办的平台轻提示（#623，guide-reply 复用型）：同步落对话史（刷新回访
     * 不丢）＋发 guide-reply 事件（锚对话面本轮 runId，prompt 不携带——用户气泡已
     * 自落，平台只补回执一条）。发射失败只记日志不反噬待办事实；无对话轮锚（成功
     * 排空路径）不提示。
     */
    private void notifyModificationQueued(Project project, String dialogRunId) {
        if (dialogRunId == null) {
            return;
        }
        try {
            conversationHistory.recordGuideNotice(project.getId(), dialogRunId,
                    MODIFICATION_QUEUED_NOTICE);
            eventBridge.emitGuideReply(project, dialogRunId, /* prompt= */ null,
                    DispatchAppService.GUIDE_LABEL, MODIFICATION_QUEUED_NOTICE);
        }
        catch (RuntimeException e) {
            log.warn("[modify] 项目 {} 在途修改回执发射失败（不反噬待办）：{}",
                    project.getId(), e.toString());
        }
    }

    /** 修改轨道提交（异步，与修正轨同执行器/同停止登记面）。 */
    private void submitModificationTrack(Project project, String firstRunId,
            List<ModificationHandoffFacts.Instruction> firstBatch) {
        Future<?> trackTask = sessionExecutor.submitGeneration(
                CodingRunDispatcher.SESSION_PREFIX + project.getId(),
                () -> {
                    try {
                        runModificationTrack(project, firstRunId, firstBatch);
                    }
                    catch (RunShutdownException e) {
                        // 关停收口（#477）：排队残局是进程内态（重启即清），在途场保持
                        // 待跑（中断可续跑态，不发终态事件）
                        log.info("[modify] 项目 {} 修改轨道因平台关停中断（runId={}）——待办保留",
                                project.getId(), e.runId());
                    }
                });
        codingRunTrack.registerTask(project.getId(), trackTask);
    }

    /**
     * 修改轨道（#623）：整场内核重跑至收尾判据双件全过（与修正轨同装配、同收口
     * 回调）。成功后若待办非空，合并为<b>恰好一场</b>后续 run 续派（多轮待办合并，
     * 不逐条烧 run）；失败/停止即终态——不自动补派、待办保留（用户重说即新一轮，
     * 不造第三层重试）。在途标记的释放与待办排空同临界区（同修正轨锁序）。
     */
    private void runModificationTrack(Project project, String firstRunId,
            List<ModificationHandoffFacts.Instruction> firstBatch) {
        Long projectId = project.getId();
        boolean released = false;
        try {
            List<ModificationHandoffFacts.Instruction> batch = firstBatch;
            String runId = firstRunId;
            while (true) {
                List<ModificationHandoffFacts.Instruction> current = batch;
                // Demo 类 run 起跑自动记行（#462 去门化口径沿用，随旧版本表归 #625 清场）
                try {
                    prdVersionAppService.recordOnDemoRunStart(projectId, runId);
                }
                catch (RuntimeException e) {
                    log.warn("[modify] 项目 {} run {} 起跑记行失败（不反噬本场）：{}",
                            projectId, runId, e.toString());
                }
                Integer prdVersionNo = demoVersionAppService.latestPrdVersionNo(projectId);
                CodingRunDispatcher.RunResult result;
                try {
                    result = codingRunDispatcher.run(project, runId,
                            fixSession(projectId, runId),
                            modificationRunPrompt(current),
                            (attemptRunId, attemptChanges) -> closeKernelFix(project,
                                    attemptRunId, prdVersionNo, attemptChanges),
                            "fix", true, CodingRunDispatcher.RunSeat.kernel(),
                            RunHeading.titled(FIX_TITLE), SegmentType.GENERATION_RUN,
                            /* includePatch= */ true);
                }
                catch (RunStoppedException e) {
                    synchronized (codingRunTrack) {
                        codingRunTrack.end(projectId);
                        released = true;
                    }
                    eventBridge.emitRunStopped(project, e.runId(), e.failure());
                    return;
                }
                if (!result.succeeded()) {
                    synchronized (codingRunTrack) {
                        codingRunTrack.end(projectId);
                        released = true;
                    }
                    eventBridge.emitRunFailed(project, runId, result.failure());
                    return;
                }
                // 成功收口：待办（含收口窗口内新到的交指令）合并为恰好一场后续 run
                List<ModificationHandoffFacts.Instruction> pending;
                boolean done;
                synchronized (codingRunTrack) {
                    pending = pendingModifications.remove(projectId);
                    done = pending == null || pending.isEmpty();
                    if (done) {
                        codingRunTrack.end(projectId);
                        released = true;
                    }
                }
                if (done) {
                    return;
                }
                log.info("[modify] 项目 {} 修改 run 成功收口，合并待办 {} 条续派一场", projectId,
                        pending.size());
                batch = pending;
                runId = EventsAppService.newRunId();
            }
        }
        finally {
            if (!released) {
                synchronized (codingRunTrack) {
                    codingRunTrack.end(projectId);
                }
            }
        }
    }

    /**
     * 修改 run 任务提示（#623）：逐条携<b>用户原话＋平台整理的自足修改指令</b>（附件
     * 定位说明有则附），要求内核在现有工作区完成修改、其余部分保持可用；需求正本
     * 引用工作区 PRD、收口判据句同内核口径（{@code pnpm build}＋8081 探活双件）。
     */
    static String modificationRunPrompt(List<ModificationHandoffFacts.Instruction> instructions) {
        StringBuilder prompt = new StringBuilder(
                "系统修改：系统已生成并可操作，用户提出了如下修改要求（每条含用户的原始说法"
                        + "与平台整理出的自足修改指令），请在现有工作区内完成修改（系统的其余"
                        + "部分保持可用）：");
        for (int index = 0; index < instructions.size(); index++) {
            ModificationHandoffFacts.Instruction instruction = instructions.get(index);
            prompt.append("\n").append(index + 1).append(". 用户原话：")
                    .append(instruction.userWords())
                    .append("\n   修改指令：").append(instruction.instruction());
            if (instruction.location() != null && !instruction.location().isBlank()) {
                prompt.append("\n   定位说明：").append(instruction.location());
            }
        }
        prompt.append("\n需求正本 = 工作区 ").append(ProjectArtifacts.PRD)
                .append("，以正本与上述修改要求为准。");
        prompt.append("\n完成后用 pnpm build 验证可构建、用 curl http://localhost:8081 "
                + "确认有响应——双件全过本轮修改才算完成。");
        return prompt.toString();
    }

    /**
     * 充值回正排空（#537——「充值到账后下一段自动放行〔不手动重试〕」run 场面）：
     * 余额闸暂存的修正 intent 逐项目重派（触发时门已过〔入账事件〕，重派自然再守
     * 全守卫与在途口径）。重派失败（项目已归档等）intent 丢弃并记日志——用户重提
     * 即新段（段边界门判定自然接管）。入账事件（AFTER_COMMIT 监听）触发。
     */
    public void drainBalanceBlocked() {
        for (Map.Entry<Long, FixHandoff> entry : balanceBlockedFixRuns.entrySet()) {
            Long projectId = entry.getKey();
            if (balanceGate.assess(projectId).blocked()) {
                continue; // 未回正——暂存保持待下一次入账（入账的可能是别项目的池）
            }
            if (!balanceBlockedFixRuns.remove(projectId, entry.getValue())) {
                continue; // 并发已被排空——不重复重派
            }
            try {
                Project project = projectRepository.findById(projectId)
                        .orElseThrow(() -> new ApplicationException(
                                ProjectMessage.PROJECT_NOT_FOUND));
                FixHandoff handoff = entry.getValue();
                log.info("[fix] 项目 {} 余额回正：暂存修正 intent 自动重派（交接 {} 轮）",
                        projectId, handoff.rounds().size());
                dispatch(project, handoff);
            }
            catch (RuntimeException e) {
                log.warn("[fix] 项目 {} 余额回正排空重派失败（intent 丢弃，重提即新段）：{}",
                        projectId, e.toString());
            }
        }
        // 对话面修改 intent 的余额暂存同面排空（#623；同口径——回正即重派，失败丢弃）
        for (Map.Entry<Long, List<ModificationHandoffFacts.Instruction>> entry
                : balanceBlockedModifications.entrySet()) {
            Long projectId = entry.getKey();
            if (balanceGate.assess(projectId).blocked()) {
                continue;
            }
            if (!balanceBlockedModifications.remove(projectId, entry.getValue())) {
                continue;
            }
            try {
                Project project = projectRepository.findById(projectId)
                        .orElseThrow(() -> new ApplicationException(
                                ProjectMessage.PROJECT_NOT_FOUND));
                if (project.getArchivedAt() != null) {
                    continue;
                }
                log.info("[modify] 项目 {} 余额回正：暂存对话面修改 intent 自动重派（{} 条）",
                        projectId, entry.getValue().size());
                dispatchModification(project, entry.getValue(), null);
            }
            catch (RuntimeException e) {
                log.warn("[modify] 项目 {} 余额回正排空重派失败（intent 丢弃，重提即新段）：{}",
                        projectId, e.toString());
            }
        }
    }

    /**
     * 修正轨道（异步轨道内，#604 单发）：当前任务跑完（含终态失败）后排空队列——有排队任务
     * 即合并为一场修正 run 续跑，队列空即收工。收工时按末场成败结算终态账（#48）：
     * 超限终态即记下该场交接物（恢复出口 {@link #restartFixRun} 的重派依据），
     * 成功即清账（正常态无恢复面）。
     *
     * <p><b>在途标记的释放在两处，各自唯一负责一种收场</b>：正常收工在排空块
     * 内——「查队列空 + 清标记」同一临界区，起跑侧的 add 也在同锁内分岔，任务
     * 要么赶在本轨道收工前并入合并、要么在收工后自起新轨，不滞留；异常收场
     * （轨道任务本身炸，会话执行器吞掉记日志）在 finally——此时标记自 add 起一
     * 直属于本轨道，直接清不会误伤（{@code released} 防的正是「正常收工后继任
     * 轨道已 add、旧 finally 再盲清一次」的双释放）。</p>
     */
    private void runFixTrack(Project project, String firstRunId, FixHandoff firstHandoff) {
        Long projectId = project.getId();
        boolean released = false;
        // 成功收工后排空对话面待办修改指令（#623）——失败/停止不排（待办保留）
        boolean drainModificationsAfterSuccess = false;
        try {
            FixHandoff handoff = firstHandoff;
            String runId = firstRunId;
            while (true) {
                // 循环内再赋值的局部不进 lambda：本场交接物取逐轮快照（判定行随场）
                FixHandoff currentHandoff = handoff;
                // Demo 类 run 起跑自动记行（#462 去门化，ADR-0039）：快照当时工作区
                // PRD 全文为构建基准（同文幂等跳过）——记行先行，随后锚捕获读到的
                // 「最新版」即本场基准；best-effort：失败只记日志不反噬本场
                try {
                    prdVersionAppService.recordOnDemoRunStart(projectId, runId);
                }
                catch (RuntimeException e) {
                    log.warn("[fix] 项目 {} run {} 起跑记行失败（不反噬本场）：{}",
                            projectId, runId, e.toString());
                }
                // Demo 版本锚捕获（#333；#462 读法顺延）：本场起跑时最新 PRD 版本——
                // 在途场照跑完收口绑起跑时版本
                Integer prdVersionNo = demoVersionAppService.latestPrdVersionNo(projectId);
                CodingRunDispatcher.RunResult result;
                try {
                    // 内核装配形态（#601 接线，ADR-0054/0055）：系统提示权威版本＋工具
                    // 恰四件＋循环语义中间件＋装配键不带 executor（决策提示／verify 自决
                    // 旧中间件结构性挂不上）；收尾＝平台侧机器判据双件（build＋探活），
                    // 无 finish_edit／验证在场／产出证明／终验复走旧验收链（段四技术债）
                    result = codingRunDispatcher.run(project, runId,
                            fixSession(projectId, runId),
                            // 单发提示（#604 失败即终态、不自动重试）
                            kernelFixRunPrompt(handoff),
                            (attemptRunId, attemptChanges) -> closeKernelFix(project, attemptRunId,
                                    prdVersionNo, attemptChanges),
                            "fix", true, CodingRunDispatcher.RunSeat.kernel(),
                            RunHeading.titled(FIX_TITLE), SegmentType.GENERATION_RUN,
                            /* includePatch= */ true); // 迭代清单携单文件只读 diff（#619）
                }
                catch (RunStoppedException e) {
                    // 停止收口（#359 生成取消）：失败账口径——本场（＋排队残队的意见
                    // 合并，意见零丢失）记终态账，「继续更新」恢复出口照常可达；释放
                    // 与账同临界区（同正常收工口径，不出现「已释放无账」窗口）；
                    // run-failed(stopped) 与终态账同事实点发射
                    synchronized (codingRunTrack) {
                        List<FixHandoff> pending = queuedFixRuns.remove(projectId);
                        FixHandoff stopped = pending == null || pending.isEmpty()
                                ? currentHandoff
                                : FixHandoff.merge(Stream.concat(
                                        Stream.of(currentHandoff), pending.stream()).toList());
                        terminallyFailedHandoffs.put(projectId, stopped);
                        codingRunTrack.end(projectId);
                        released = true;
                    }
                    eventBridge.emitRunStopped(project, e.runId(), e.failure());
                    return;
                }
                // #477 重派上限：通道全死（整场 run 全尝试 llmMs=0——未发生任何真实
                // 模型调用）的秒败终态不续派排队残局——并入终态账（恢复出口
                // 「继续更新」一次带走，意见零丢失），不形成「转终态→重派→秒败」
                // 循环；有真实模型工作的失败不受影响（排队合并续派口径不变）
                if (!result.succeeded() && CodingRunDispatcher.instantTerminalFailure(result.failure())) {
                    synchronized (codingRunTrack) {
                        List<FixHandoff> pending = queuedFixRuns.remove(projectId);
                        FixHandoff merged = pending == null || pending.isEmpty()
                                ? currentHandoff
                                : FixHandoff.merge(Stream.concat(
                                        Stream.of(currentHandoff), pending.stream()).toList());
                        terminallyFailedHandoffs.put(projectId, merged);
                        codingRunTrack.end(projectId);
                        released = true;
                    }
                    log.info("[fix] 项目 {} 修正 run 通道级秒败终态（全尝试无真实模型调用）——"
                            + "排队残局并入终态账停派，恢复出口「继续更新」一次带走", projectId);
                    eventBridge.emitRunFailed(project, runId, result.failure());
                    return;
                }
                List<FixHandoff> queued;
                boolean terminalFailure = false;
                synchronized (codingRunTrack) {
                    List<FixHandoff> pending = queuedFixRuns.remove(projectId);
                    queued = pending != null ? pending : List.of();
                    if (queued.isEmpty()) {
                        codingRunTrack.end(projectId);
                        released = true;
                        // 终态账与释放同临界区结算：恢复出口（同锁内「查在途+取账+占位」）
                        // 要么见释放前已落的账、要么在收工后自起新轨——不出现「已释放
                        // 无账」的假 PRJ_026 窗口，也不被先收工的旧轨覆盖
                        if (result.succeeded()) {
                            terminallyFailedHandoffs.remove(projectId);
                            drainModificationsAfterSuccess = true; // 成功收工才排空对话面待办
                        }
                        else {
                            terminallyFailedHandoffs.put(projectId, handoff);
                            terminalFailure = true;
                        }
                    }
                }
                if (queued.isEmpty()) {
                    if (terminalFailure) {
                        // 终态收口事件（#56）：与终态账同事实点发射——事件到 ⟺ 恢复出口
                        // 可达（点击不被 PRJ_025/026 挡回）；排队合并续派的中途超限
                        // 不发（轨道仍在途，「重新修改」零闪现）。锚 = 该场 run 的
                        // 用户面标识（该场 runId，#604 单发：run 身份全程不变）
                        eventBridge.emitRunFailed(project, runId, result.failure());
                    }
                    return;
                }
                int mergedRounds = queued.stream()
                        .mapToInt(next -> next.rounds().size()).sum();
                log.info("[fix] 项目 {} 修正轨道续派（合并 {} 轮排队意见）",
                        projectId, mergedRounds);
                handoff = FixHandoff.merge(queued);
                runId = EventsAppService.newRunId();
            }
        }
        finally {
            if (!released) {
                synchronized (codingRunTrack) {
                    codingRunTrack.end(projectId);
                }
            }
            if (drainModificationsAfterSuccess) {
                try {
                    drainPendingModificationsAfterSuccess(projectId);
                }
                catch (RuntimeException e) {
                    log.warn("[modify] 项目 {} 成功收工后排空修改待办失败（不反噬收工）：{}",
                            projectId, e.toString());
                }
            }
        }
    }

    /**
     * 内核修正收口判据回调（#601 接线，ADR-0054）：run 正常返回后的平台侧机器核验
     * ——{@code pnpm build} 过＋8081 探活双件全过才判完成（{@link KernelClosingCriteria}）；
     * 未过即抛——该场 run 失败转终态（#604 单发：不自动重跑，恢复出口重派即续）。
     * 系统改没改以派发器的平台可观测文件改动事实为准（{@code attemptChanges} 非空——
     * 不依赖模型自报）；实动即 Demo 迭代轮成版（#333 同口径）＋演示种子补位（#340）。
     * 逐件判据结果（#617）随收口判据回给派发器，进收尾卡「判据结果」。
     */
    private CodingRunDispatcher.ClosingJudgment closeKernelFix(Project project, String attemptRunId,
            Integer prdVersionNo, List<FileChange> attemptChanges) {
        KernelClosingCriteria.KernelClosingResult closing =
                kernelClosingCriteria.assess(project.getWorkspaceId());
        if (!closing.complete()) {
            throw new IllegalStateException("收尾判据未过：pnpm build "
                    + (closing.buildPassed() ? "通过" : "未通过") + "、8081 探活"
                    + (closing.probePassed() ? "有响应" : "无响应")
                    + "——按完成判据继续修复后再收口");
        }
        boolean systemChanged = attemptChanges != null && !attemptChanges.isEmpty();
        if (systemChanged) {
            try {
                demoVersionAppService.closeOnIteration(project.getId(), attemptRunId, prdVersionNo);
            }
            catch (RuntimeException e) {
                log.warn("[fix] 项目 {} Demo 版本成版失败（不反噬收口，下轮补位）：{}",
                        project.getId(), e.toString());
            }
        }
        // 演示种子预置（#340）：迭代收口补位——系统实动后可能有新空表（幂等引擎
        // 只补空表）；失败不阻断收工。
        demoSeed.seedSandbox(project);
        return new CodingRunDispatcher.ClosingJudgment(
                closing.buildPassed(), closing.probePassed());
    }

    /**
     * 修正轨道起手（异步提交，{@link #dispatch} 与 {@link #restartFixRun} 共用）：
     * #607 起不再写工作区 AGENTS.md（平台约定退场、生产约束并入内核提示词）。
     */
    private void beginFixTrack(Project project, String firstRunId, FixHandoff handoff) {
        submitFixTrack(project, firstRunId, handoff);
    }

    private void submitFixTrack(Project project, String firstRunId, FixHandoff firstHandoff) {
        // 主轨任务登记停止面（#359）：requestStop 的 cancel(true) 经此中断在途执行
        // 线程——修正轨停止同样是真断流（不只标志位）。#603 起执行形态＝直接执行
        // （生成车道排队/错峰/排空已拆），登记面不变
        Future<?> trackTask = sessionExecutor.submitGeneration(CodingRunDispatcher.SESSION_PREFIX + project.getId(),
                () -> {
                    try {
                        runFixTrack(project, firstRunId, firstHandoff);
                    }
                    catch (RunShutdownException e) {
                        // 关停收口（#477）：宽限窗内不拉起排队意见——排队残局是进程内
                        // 态（重启即清，用户重提即兜底——既有口径），在途场保持待跑
                        //（中断可续跑态，不发终态事件）
                        log.info("[fix] 项目 {} 修正轨道因平台关停中断（runId={}）——在途场保持待跑",
                                project.getId(), e.runId());
                    }
                });
        codingRunTrack.registerTask(project.getId(), trackTask);
    }
}
