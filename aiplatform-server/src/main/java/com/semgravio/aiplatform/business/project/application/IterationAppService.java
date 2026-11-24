package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.FileChange;
import com.semgravio.aiplatform.base.agentscope.RunHeading;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.billing.application.BalanceGateAppService;
import com.semgravio.aiplatform.business.billing.application.BalanceReminderAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 迭代编排（#623 对话面交指令 → 内核重跑；#625 旧迭代受理链拆除后只剩这一条从
 * 对话面到内核的路径）：对话面把用户说清楚的修改蒸馏成自足指令，经
 * {@link #handOffModification} 交来——空闲即起一轮内核重跑，在途（含首建在途）
 * 进平台内存待办、成功收口后合并补派恰好一场；失败/停止不自动补派、待办保留，
 * 用户重说即触发下一轮（不造第三层重试）。
 *
 * <p><b>内核装配形态</b>（#601，ADR-0054/0055）：迭代 run 按生成内核装配形态派发
 * （{@link CodingRunDispatcher.RunSeat#kernel()}——系统提示权威版本、工具恰四件、
 * 循环语义中间件、装配键不带 executor 旧中间件）；收尾＝平台侧机器判据双件
 * （{@link KernelClosingCriteria}：{@code pnpm build} 过＋8081 探活）。</p>
 *
 * <p><b>PRD 维护</b>（#624）：任务提示要求内核完成改动后把本轮需求并入 PRD 对应
 * 章节、保持完整文档；平台不写 PRD，收口对比工作区 PRD 与起跑快照，有变化才发
 * 既有 document-updated。</p>
 */
@Service
@Slf4j
public class IterationAppService {

    /**
     * 迭代任务提示的 PRD 维护句（#624）：完成改动后把本轮需求并入 PRD 对应章节，
     * 让 PRD 始终是一份完整文档——需求写进相应位置、不在文末堆积原始意见；内核
     * 如需保留历史留痕可自行安排，平台不强制固定章节。平台不写 PRD，本文只是
     * 对内核的任务要求。
     */
    static final String PRD_MERGE_INSTRUCTION =
            "\n完成改动后，把本轮需求并入 " + ProjectArtifacts.PRD
                    + " 的对应章节，让 PRD 始终是一份完整文档（需求写进相应位置、"
                    + "不在文末堆积原始意见；如需保留改动留痕可自行安排，平台不要求"
                    + "固定章节）。";

    /** 迭代内核会话寻址（#114 更新 run 每次新会话）：runId 逐场换新 → 会话逐场换新。 */
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
    /** 用户面版本自动成版（#626 单轴时间线：探活通过即记录版本）。 */
    private final ProjectVersionAppService projectVersionAppService;
    private final DemoSeedAppService demoSeed;
    private final BalanceGateAppService balanceGate;
    /** 内核收尾判据（#601 接线消费 #597 落位件）：build 过＋8081 探活双件。 */
    private final KernelClosingCriteria kernelClosingCriteria;

    /** 对话面在途入待办的轻提示落对话史（刷新/回访不丢，#89 闭史口径）。 */
    private final ConversationHistoryAppService conversationHistory;

    /** 通知发射（#624：迭代收口 PRD 变化 → document-updated）。 */
    private final EventsAppService eventsAppService;

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
            ProjectVersionAppService projectVersionAppService,
            DemoSeedAppService demoSeed,
            BalanceGateAppService balanceGate,
            KernelClosingCriteria kernelClosingCriteria,
            ConversationHistoryAppService conversationHistory,
            EventsAppService eventsAppService) {
        this.projectRepository = projectRepository;
        this.sessionExecutor = sessionExecutor;
        this.codingRunDispatcher = codingRunDispatcher;
        this.eventBridge = eventBridge;
        this.codingRunTrack = codingRunTrack;
        this.demoVersionAppService = demoVersionAppService;
        this.prdVersionAppService = prdVersionAppService;
        this.projectVersionAppService = projectVersionAppService;
        this.demoSeed = demoSeed;
        this.balanceGate = balanceGate;
        this.kernelClosingCriteria = kernelClosingCriteria;
        this.conversationHistory = conversationHistory;
        this.eventsAppService = eventsAppService;
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
     * 即恢复。</p>
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
     * 方法（待办保留，用户重说即触发下一轮)；未生成（首建失败/停止）直接返回。
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

    // ---------- 内部 ----------

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

    /** 修改轨道提交（异步，同执行器/停止登记面）。 */
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
     * 修改轨道（#623）：整场内核重跑至收尾判据双件全过（同生成轨装配、同收口
     * 回调）。成功后若待办非空，合并为<b>恰好一场</b>后续 run 续派（多轮待办合并，
     * 不逐条烧 run）；失败/停止即终态——不自动补派、待办保留（用户重说即新一轮，
     * 不造第三层重试）。
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
                // Demo 类 run 起跑自动记行（#462 去门化口径沿用）；返回值即本场起跑时
                // 的 PRD 全文，留作收口变化比对基准（#624）
                String prdBaseline = null;
                try {
                    prdBaseline = prdVersionAppService.recordOnDemoRunStart(projectId, runId);
                }
                catch (RuntimeException e) {
                    log.warn("[modify] 项目 {} run {} 起跑记行失败（不反噬本场）：{}",
                            projectId, runId, e.toString());
                }
                Integer prdVersionNo = demoVersionAppService.latestPrdVersionNo(projectId);
                String prdSnapshot = prdBaseline;
                // 迭代版本标签（#626）：本轮修改指令的用户原话（多轮合并展示）
                String versionLabel = modificationVersionLabel(current);
                CodingRunDispatcher.RunResult result;
                try {
                    result = codingRunDispatcher.run(project, runId,
                            fixSession(projectId, runId),
                            modificationRunPrompt(current),
                            (attemptRunId, attemptChanges) -> closeKernelFix(project,
                                    attemptRunId, prdVersionNo, prdSnapshot, versionLabel,
                                    attemptChanges),
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
     * 迭代版本标签（#626）：本轮修改指令的用户原话——多条（合并待办补派）按到达
     * 序以「；」合并展示；空/全空白回落「更新系统」。
     */
    static String modificationVersionLabel(List<ModificationHandoffFacts.Instruction> instructions) {
        String merged = instructions.stream()
                .map(ModificationHandoffFacts.Instruction::userWords)
                .filter(words -> words != null && !words.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining("；"));
        return ProjectVersionAppService.cleanUserSentence(merged, "更新系统");
    }

    /**
     * 修改 run 任务提示（#623）：逐条携<b>用户原话＋平台整理的自足修改指令</b>（附件
     * 定位说明有则附），要求内核在现有工作区完成修改、其余部分保持可用；需求正本
     * 引用工作区 PRD、收口判据句同内核口径（{@code pnpm build}＋8081 探活双件）。
     * #624 起追加 PRD 维护句——内核把需求整合进 PRD 对应章节，PRD 恒为完整文档。
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
                .append("（本轮完成后要更新的完整文档），以正本与上述修改要求为准。");
        prompt.append(PRD_MERGE_INSTRUCTION);
        prompt.append("\n完成后用 pnpm build 验证可构建、用 curl http://localhost:8081 "
                + "确认有响应——双件全过本轮修改才算完成。");
        return prompt.toString();
    }

    /**
     * 充值回正排空（#537——「充值到账后下一段自动放行〔不手动重试〕」run 场面）：
     * 余额闸暂存的对话面修改 intent 逐项目重派（触发时门已过〔入账事件〕，重派自然
     * 再守全守卫与在途口径）。重派失败（项目已归档等）intent 丢弃并记日志——用户
     * 重提即新段（段边界门判定自然接管）。入账事件（AFTER_COMMIT 监听）触发。
     */
    public void drainBalanceBlocked() {
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
     * 内核收口判据回调（#601 接线，ADR-0054）：run 正常返回后的平台侧机器核验
     * ——{@code pnpm build} 过＋8081 探活双件全过才判完成（{@link KernelClosingCriteria}）；
     * 未过即抛——该场 run 失败转终态（#604 单发：不自动重跑）。系统改没改以派发器
     * 的平台可观测文件改动事实为准（{@code attemptChanges} 非空——不依赖模型自报）；
     * 实动即 Demo 迭代轮成版（#333 同口径）＋演示种子补位（#340）。逐件判据结果
     * （#617）随收口判据回给派发器，进收尾卡「判据结果」。
     *
     * <p>#624：收口对比工作区 PRD 与起跑快照（{@code prdBaseline}），有变化才发既有
     * document-updated（右栏按现有失效逻辑刷新）；平台自己不写 PRD，变化只由内核按
     * 任务提示整合带来。失败判据先抛——PRD 变化通知只在 run 成功后发。</p>
     */
    private CodingRunDispatcher.ClosingJudgment closeKernelFix(Project project, String attemptRunId,
            Integer prdVersionNo, String prdBaseline, String versionLabel,
            List<FileChange> attemptChanges) {
        KernelClosingCriteria.KernelClosingResult closing =
                kernelClosingCriteria.assess(project.getWorkspaceId());
        if (!closing.complete()) {
            throw new IllegalStateException("收尾判据未过：pnpm build "
                    + (closing.buildPassed() ? "通过" : "未通过") + "、8081 探活"
                    + (closing.probePassed() ? "有响应" : "无响应")
                    + "——按完成判据继续修复后再收口");
        }
        // 探活通过＝一次完整交付（#626 自动成版）：run-finish 发出前把工作区记为用户面
        // 版本 vN（主题＝本轮用户原话）；成版失败降级不反噬收口
        projectVersionAppService.commitUserVersion(project, attemptRunId, versionLabel);
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
        // 迭代后 PRD 仍是完整文档（#624）：内核按任务提示把需求整合进 PRD 对应章节，
        // 收口比对起跑快照——变了才发既有 document-updated；无变化不发（不误刷新右栏）。
        emitDocumentUpdatedIfPrdChanged(project, prdBaseline);
        // 演示种子预置（#340）：迭代收口补位——系统实动后可能有新空表（幂等引擎
        // 只补空表）；失败不阻断收工。
        demoSeed.seedSandbox(project);
        return new CodingRunDispatcher.ClosingJudgment(
                closing.buildPassed(), closing.probePassed());
    }

    /**
     * 迭代收口 PRD 变化通知（#624）：工作区 PRD 与起跑快照不同即发既有
     * document-updated（载荷与 savePrd 同源，前端失效文档域重拉 PRD 全文）；
     * 无变化/读取失败保守不发，通知发射失败不反噬收口。
     */
    private void emitDocumentUpdatedIfPrdChanged(Project project, String prdBaseline) {
        try {
            if (!prdVersionAppService.changedSince(project.getId(), prdBaseline)) {
                return;
            }
            eventsAppService.publishNotification(ProjectEventTypes.DOCUMENT_UPDATED,
                    ProjectEventTypes.documentUpdatedPayload(project,
                            ProjectEventTypes.DOCUMENT_TYPE_PRD));
            log.info("[fix] 项目 {} 迭代收口 PRD 已变化，发 document-updated", project.getId());
        }
        catch (RuntimeException e) {
            log.warn("[fix] 项目 {} PRD 变化通知发射失败（不反噬收口）：{}",
                    project.getId(), e.toString());
        }
    }
}
