package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.semgravio.aiplatform.base.agentscope.RunHeading;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.GenerationPlanJourney;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.ProjectFiles;
import com.semgravio.aiplatform.business.project.domain.repository.GenerationPlanJourneyRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 整轨终验＋fix 循环（#427，#406 下半——CONTEXT.md「整轨终验」）：轨道收口边界的
 * 轨道级验证回路——真浏览器以核心旅程清单为轴走跨片旅程（复用片轻验的验证子智能体
 * 形态与工具面：终验 run 委派 verify 逐旅程走主径、report_verification 登记，平台
 * 从工具调用事实观测结果），fail 拦「生成完成」叙事→失败项自动起终验修复 run→
 * 修复收口后复走终验（防修 A 坏 B）→修复预算（缺省两轮、经智能体运营配置面
 * executor 行 {@code final_verify_fix_budget} 可调）耗尽：版本照成＋已知缺陷清单
 * 如实进完成叙事与收尾卡（closing.knownDefects，前端如实呈现）。
 *
 * <p><b>复走面（两调用点共此一回路）</b>：①生成轨道末（全部切片收口后、
 * {@code generated_at}/Demo 首版落位前——{@code GenerationAppService} 的轨道完成
 * 点）；②更新轨每场更新 run 成功收口后（{@code IterationAppService}——防用户意见
 * 修复修坏别的旅程）。回路内的终验修复 run 收口后同复走（本回路自带，非两调用点
 * 之列）。</p>
 *
 * <p><b>开关化（#433；#435 翻转缺省）</b>：整轨终验拦「生成完成」接入
 * 运营配置面开关（executor 行 {@code final_verification_gate_enabled}，#435 起
 * 缺省 false＝闸关＝#432 Step 1 砍验证税落地态）——闸关＝{@link #verifyTrack}
 * 入口即返回跳过形（终验不起 run、不拦完成叙事，成版锚回落最深收口片 run
 * 的 #427 前形态）；运营可一键切回 true＝闸开＝现行为。判定处动态查库，
 * 不发版、进行中 run 不定格。</p>
 *
 * <p><b>终验 run 形态</b>：run 执行体作验证编排者——任务＝把核心旅程逐条委派
 * verify 子智能体（agent_spawn）走真浏览器跨片旅程并登记，<b>不改系统代码</b>
 * （发现问题如实登记，修复由平台经终验修复 run 另行安排——验证与修复分离，修
 * 不叠在验上）；收口判据＝8081 探活＋入口文件完整＋验证在场（本 run 无
 * report_verification 登记不准收口，同片轻验硬门口径）；产出证明闸不适用
 * （验证编排 run 的产出即验证登记，零 diff 是预期形态而非空转）。结果判定＝
 * 平台读验证事实（末轮快照全过＝过；有未过项＝失败项清单——终验修复 run 的载荷）。</p>
 *
 * <p><b>终验修复 run 形态</b>（复用 fix run 机制）：载荷＝失败项清单（项名＋证据），
 * 会话 {@code -vfix-} 逐场换新；收口判据＝8081 探活＋入口文件完整＋产出证明
 * （#420 更新轨同盖）＋验证在场（#424 修复自证）＋finish_edit 事实（#46 修正轨
 * 收口口径）。不建 Demo 版本行——轨道里程碑的成版归调用点（生成末＝Demo 首版
 * 随 {@code generated_at} 同刻原子；更新轨＝该场更新 run 自身），修复 run 的收口
 * 成版仍进 git 版本流（closing.version 锚）。</p>
 *
 * <p><b>预算与如实呈现</b>：修复预算动态读运营配置（改完下一轮判定即生效）；
 * 耗尽时末轮终验 run 的收尾卡带 {@code knownDefects}（缺陷清单＋已用修复轮数）、
 * summary 如实叙事「按现状交付」——不粉饰、不静默。终验/修复 run 重试耗尽＝轨道
 * 失败（run-failed 已由本服务发射，调用点据此不落完成位——续跑/下轮更新复走）。
 * 能抓面如实：跨片集成断点、缺功能、旅程级故障、确定性错误抓得住；运行时偶发态
 * 与性能带宽类抓不住（归基座健壮件治本与发布冒烟——CONTEXT.md「整轨终验」词条）。</p>
 */
@Service
@Slf4j
class TrackFinalVerificationAppService {

    /** 终验 run 工作消息头部标题（用户语言；对话区工作消息的呈现锚）。 */
    static final String FINAL_VERIFY_TITLE = "系统整体验证";

    /** 终验 run 会话寻址中缀（{@code coder-{projectId}-final-verify-{runId}}——逐场换新）。 */
    static final String FINAL_VERIFY_SESSION_INFIX = "-final-verify-";

    /** 终验修复 run 会话寻址中缀（{@code coder-{projectId}-vfix-{runId}}——对偶修正轨 fix 惯例）。 */
    static final String VFIX_SESSION_INFIX = "-vfix-";

    /** 终验 run 日志标签（正本住 {@link CoderRunAttempts#VERIFY_LABEL}——summary 口径分岔共用）。 */
    static final String VERIFY_LABEL = CoderRunAttempts.VERIFY_LABEL;

    /**
     * 一项已知缺陷（预算耗尽时如实呈现的单元）：name 项名（用户语言）＋detail
     * 证据或失败现场（verify 登记原样，可空）。
     */
    record Defect(String name, String detail) {
    }

    /**
     * 一场整轨终验回路的结局（调用点的分岔依据）：
     * <ul>
     * <li>{@link #skipped()}——无核心旅程轴（V49 前存量计划不回补）：终验不起 run，
     *     调用点照旧直接完成（无验证声明）；</li>
     * <li>{@link #passed}——末轮全过：干净完成；</li>
     * <li>{@link #defects}——修复预算耗尽仍有未过：版本照成＋已知缺陷清单随末轮
     *     终验 run 收尾卡如实呈现（verifyRunId 即完成叙事锚）；</li>
     * <li>{@link #terminal}——回路内 run 重试耗尽（终验/修复 run 环境级失败）：
     *     run-failed 已发射，调用点不落完成位（生成轨 generated_at 不落、续跑复走；
     *     更新轨照常消化排队意见），lastError 随回传作续跑现场旁证。</li>
     * </ul>
     */
    record Result(String verifyRunId, boolean passed, List<Defect> knownDefects,
            String lastError, Map<String, Object> failure) {

        /** 无旅程轴的跳过形（不跑任何 run——存量口径，行为与终验前一致）。 */
        static Result skipped() {
            return new Result(null, true, List.of(), null, null);
        }

        static Result passed(String verifyRunId) {
            return new Result(verifyRunId, true, List.of(), null, null);
        }

        static Result defects(String verifyRunId, List<Defect> defects) {
            return new Result(verifyRunId, false, List.copyOf(defects), null, null);
        }

        static Result terminal(String verifyRunId, String lastError, Map<String, Object> failure) {
            return new Result(verifyRunId, false, List.of(), lastError, failure);
        }

        /** 回路内 run 终态失败（failure 面在场即真——与 passed/defects 互斥）。 */
        boolean runTerminallyFailed() {
            return failure != null;
        }
    }

    private final CoderRunAttempts coderRunAttempts;
    private final AgentEventBridge eventBridge;
    private final WorkspaceLifecycleAppService workspaceLifecycle;
    private final VerificationFacts verificationFacts;
    private final VerificationPresenceGate verificationPresence;
    private final ProofOfOutputGate proofGate;
    private final FinishEditFacts finishFacts;
    private final RobustKitLintAppService robustKitLint;
    private final DesignSpecAppService designSpecs;
    private final AgentConfigAppService agentConfigs;
    private final GenerationPlanJourneyRepository generationPlanJourneys;
    private final DemoSeedAppService demoSeed;
    private final ClosingProbes closingProbes;

    TrackFinalVerificationAppService(CoderRunAttempts coderRunAttempts,
            AgentEventBridge eventBridge, WorkspaceLifecycleAppService workspaceLifecycle,
            VerificationFacts verificationFacts, VerificationPresenceGate verificationPresence,
            ProofOfOutputGate proofGate, FinishEditFacts finishFacts,
            RobustKitLintAppService robustKitLint, DesignSpecAppService designSpecs,
            AgentConfigAppService agentConfigs,
            GenerationPlanJourneyRepository generationPlanJourneys,
            DemoSeedAppService demoSeed,
            ClosingProbes closingProbes) {
        this.coderRunAttempts = coderRunAttempts;
        this.eventBridge = eventBridge;
        this.workspaceLifecycle = workspaceLifecycle;
        this.verificationFacts = verificationFacts;
        this.verificationPresence = verificationPresence;
        this.proofGate = proofGate;
        this.finishFacts = finishFacts;
        this.robustKitLint = robustKitLint;
        this.designSpecs = designSpecs;
        this.agentConfigs = agentConfigs;
        this.generationPlanJourneys = generationPlanJourneys;
        this.demoSeed = demoSeed;
        this.closingProbes = closingProbes;
    }

    /**
     * 现行核心旅程清单（更新轨复走的载荷轴单点）：计划旅程表读回＋PRD 版本锚门
     * （{@link GenerationPlanJourney#planMatchesPrd}——锚一致才是现行计划的旅程；
     * 空集或锚漂＝无轴）。生成轨道走 {@code verifyTrack(project, plan.coreJourneys())}
     * 携执行中计划（in-hand 正本），不走本读口。
     */
    List<BuildPlan.CoreJourney> currentJourneys(Project project) {
        List<GenerationPlanJourney> rows =
                generationPlanJourneys.findByProjectIdOrderByOrdAsc(project.getId());
        if (!GenerationPlanJourney.planMatchesPrd(rows, project.getPrdProducedAt())) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new BuildPlan.CoreJourney(row.getRole(), row.getEntry(),
                        row.getKeyActions()))
                .toList();
    }

    /**
     * 更新轨收口后的复走（#427「更新 run 与 fix run 收口后均复走整轨终验」的更新
     * 轨腿）：以现行计划（PRD 锚门）的核心旅程清单为轴复验——fail 自动修复（回路
     * 内终验修复 run）→复走，预算耗尽已知缺陷如实呈现（末轮终验 run 收尾卡——
     * 更新 run 自身的版本与收尾卡不受影响）；无旅程轴（存量计划/锚漂待重产）跳过
     * 不终验。结局只观测不拦截：回路内 run 终态失败已发 run-failed（如实），更新
     * 轨照常消化排队意见。
     */
    void verifyAfterUpdateRun(Project project) {
        Result result = verifyTrack(project, currentJourneys(project));
        if (result.runTerminallyFailed()) {
            log.warn("[verify] 项目 {} 更新后复走整轨终验 run 终态失败（run-failed 已发，"
                    + "更新轨照常续）", project.getId());
        }
        else if (!result.passed()) {
            log.warn("[verify] 项目 {} 更新后复走整轨终验：修复预算耗尽，已知缺陷 {} 项"
                            + "随末轮终验收尾卡如实呈现", project.getId(),
                    result.knownDefects().size());
        }
    }

    /**
     * 整轨终验回路（两调用点共用）：闸关（运营面开关 #433）即跳过（不拦「生成完成」，
     * 成版锚回落最深收口片 run）；终验 run → 全过即收；有未过且预算有余 → 终验
     * 修复 run（载荷＝失败项清单）→ 复走终验；预算耗尽 → 版本照成＋已知缺陷清单
     * （末轮终验 run 收尾卡如实呈现）。修复预算动态读运营配置（缺省两轮；0＝不起
     * 修复 run、fail 直达已知缺陷形态——合法运营选择）。
     *
     * <p>停止旁路透传：{@code RunStoppedException} 不在本层吞——调用点按各自轨道
     * 的停止收口处置（生成轨停止收口/更新轨停止账）。</p>
     */
    Result verifyTrack(Project project, List<BuildPlan.CoreJourney> journeys) {
        if (!agentConfigs.effectiveOf(AgentProfile.EXECUTOR).finalVerificationGateEnabled()) {
            // 闸关（#433 运营面开关；#435 起为缺省态——#432 砍验证税落地）：终验
            // 不起 run、不拦「生成完成」，调用点照旧直接完成（无验证声明——
            // 同无旅程轴的 skipped 形，成版锚回落最深收口片 run 的 #427 前形态）；
            // 判定处动态查库，改完下一次轨道完成即生效
            log.info("[verify] 项目 {} 整轨终验闸关（运营面开关），跳过终验（完成口径"
                    + "回落 #427 前形态）", project.getId());
            return Result.skipped();
        }
        if (journeys.isEmpty()) {
            log.info("[verify] 项目 {} 计划无核心旅程（存量不回补），整轨终验跳过", project.getId());
            return Result.skipped();
        }
        Long projectId = project.getId();
        int roundsUsed = 0;
        while (true) {
            // 预算动态读（运营面改完下一轮判定即生效；roundsUsed 达预算即本轮终验为末轮）
            int fixBudget = agentConfigs.effectiveOf(AgentProfile.EXECUTOR)
                    .finalVerifyFixBudget();
            boolean finalRound = roundsUsed >= fixBudget;
            AtomicReference<VerificationFacts.Facts> captured = new AtomicReference<>();
            String verifyRunId = EventsAppService.newRunId();
            CoderRunAttempts.RunResult verifyResult = runVerificationRound(project, journeys,
                    verifyRunId, finalRound, captured);
            if (!verifyResult.succeeded()) {
                log.error("[verify] 项目 {} 整轨终验 run 重试耗尽（终验不可判——轨道按失败收口，"
                        + "续跑/下轮更新复走）", projectId);
                eventBridge.emitRunFailed(project, verifyRunId, verifyResult.failure());
                return Result.terminal(verifyRunId, verifyResult.lastError(),
                        verifyResult.failure());
            }
            List<Defect> defects = defectsOf(captured.get());
            if (defects.isEmpty()) {
                log.info("[verify] 项目 {} 整轨终验通过（核心旅程 {} 条，修复 {} 轮）", projectId,
                        journeys.size(), roundsUsed);
                return Result.passed(verifyRunId);
            }
            if (finalRound) {
                log.warn("[verify] 项目 {} 整轨终验修复预算耗尽（{} 轮），版本照成＋已知缺陷 {} 项"
                        + "如实呈现", projectId, roundsUsed, defects.size());
                return Result.defects(verifyRunId, defects);
            }
            roundsUsed++;
            log.info("[verify] 项目 {} 整轨终验 {} 项未过，起终验修复 run（第 {}/{} 轮）", projectId,
                    defects.size(), roundsUsed, fixBudget);
            String fixRunId = EventsAppService.newRunId();
            CoderRunAttempts.RunResult fixResult = runVerificationFixRound(project, fixRunId,
                    defects);
            if (!fixResult.succeeded()) {
                log.error("[verify] 项目 {} 终验修复 run 重试耗尽（轨道按失败收口，续跑复走终验）",
                        projectId);
                eventBridge.emitRunFailed(project, fixRunId, fixResult.failure());
                return Result.terminal(fixRunId, fixResult.lastError(), fixResult.failure());
            }
        }
    }

    /**
     * 一轮终验 run：验证编排 run——载荷＝核心旅程清单＋委派 verify 口径；收口判据
     * ＝探活＋入口完整＋验证在场（无登记不准收口），验证结果经 {@code captured}
     * 回传（onSuccess 时 peek——收口统计的 consume 在收尾卡拼装处，两读不互斥）。
     * 末轮（预算耗尽前的最后一场）收尾卡随判定带 knownDefects。
     */
    private CoderRunAttempts.RunResult runVerificationRound(Project project,
            List<BuildPlan.CoreJourney> journeys, String runId, boolean finalRound,
            AtomicReference<VerificationFacts.Facts> captured) {
        String sessionId = CoderRunAttempts.SESSION_PREFIX + project.getId()
                + FINAL_VERIFY_SESSION_INFIX + runId;
        return coderRunAttempts.run(project, project.getWorkspaceId(), runId, sessionId,
                new CoderRunAttempts.Prompts(verificationPrompt(journeys),
                        TrackFinalVerificationAppService::verificationRetryPrompt),
                (attemptRunId, attemptChanges) -> closeFinalVerification(project, captured,
                        journeys.size(), finalRound),
                VERIFY_LABEL, /* injectKnowledge= */ false, CoderRunAttempts.RunSeat.executor(),
                RunHeading.titled(FINAL_VERIFY_TITLE));
    }

    /**
     * 终验 run 收口判据：8081 探活＋入口文件完整＋验证在场（常开硬门同口径——本 run
     * 无 report_verification 登记不准收口）；产出证明闸不适用（验证编排 run 零 diff
     * 是预期形态）。判定即叙事：全过＝通过叙事、有未过＝如实（末轮另带 knownDefects
     * ——预算耗尽的已知缺陷清单随收尾卡呈现）。验证事实 peek 回传（consume 归收尾
     * 卡拼装，两读不互斥）。
     */
    private CoderRunAttempts.ClosingJudgment closeFinalVerification(Project project,
            AtomicReference<VerificationFacts.Facts> captured, int journeyCount,
            boolean finalRound) {
        Long workspaceId = project.getWorkspaceId();
        closingProbes.requireReachable(workspaceId);
        closingProbes.requireEntryFileComplete(workspaceId);
        verificationPresence.requirePresence(Long.toString(workspaceId));
        VerificationFacts.Facts facts = verificationFacts.peek(Long.toString(workspaceId));
        captured.set(facts);
        List<Defect> defects = defectsOf(facts);
        String summary = defects.isEmpty()
                ? FINAL_VERIFY_TITLE + "通过：核心旅程 " + journeyCount + " 条全部走通"
                : finalRound
                        ? FINAL_VERIFY_TITLE + "：" + defects.size() + " 项未过、修复预算已尽"
                                + "——系统按现状交付，已知缺陷如下如实呈现"
                        : FINAL_VERIFY_TITLE + "：" + defects.size() + " 项未过，已自动安排修复";
        // 判定形＝验证编排 run（PRD 未动、系统未动——验证不产码；summary 经 verify
        // 标签走 systemNote 同源）：判定行如实「系统：无需改动——验证叙事」
        CoderRunAttempts.ClosingJudgment judgment =
                CoderRunAttempts.ClosingJudgment.update(false, null, false, summary);
        if (finalRound && !defects.isEmpty()) {
            judgment = judgment.withKnownDefects(knownDefectsPayload(defects));
        }
        return judgment;
    }

    /**
     * 一轮终验修复 run（复用 fix run 机制）：载荷＝失败项清单（项名＋证据）；收口
     * 判据＝探活＋入口完整＋产出证明＋验证在场＋finish_edit 事实（修正轨收口口径，
     * 修复自证）；不建 Demo 版本行（轨道里程碑成版归调用点）。修复轮收口即复走
     * 终验（回路 caller 侧循环）。
     */
    private CoderRunAttempts.RunResult runVerificationFixRound(Project project, String runId,
            List<Defect> defects) {
        Long projectId = project.getId();
        String sessionId = CoderRunAttempts.SESSION_PREFIX + projectId
                + VFIX_SESSION_INFIX + runId;
        // finish_edit 事实清残留（对偶修正轨起手清）：终验 run 若曾误调 finish_edit
        // 不串进修复轮判定
        finishFacts.clear(Long.toString(project.getWorkspaceId()));
        ProofOfOutputGate.Checking proof = proofGate.forRun(project.getWorkspaceId());
        return coderRunAttempts.run(project, runId, sessionId,
                new CoderRunAttempts.Prompts(verificationFixPrompt(defects),
                        IterationAppService::fixRetryPrompt,
                        IterationAppService::lintFixRetryPrompt),
                (attemptRunId, attemptChanges) -> closeVerificationFix(project, proof),
                "fix", /* injectKnowledge= */ true, CoderRunAttempts.RunSeat.executor(),
                RunHeading.titled(IterationAppService.FIX_TITLE));
    }

    /**
     * 终验修复 run 收口判据（#46 修正轨口径＋#420/#424 更新轨同盖）：finish_edit
     * 事实为准（无事实＝未收口，抛出走重试/终态）；产出证明与验证在场先于事实消费。
     */
    private CoderRunAttempts.ClosingJudgment closeVerificationFix(Project project,
            ProofOfOutputGate.Checking proof) {
        Long workspaceId = project.getWorkspaceId();
        Map<String, Object> proofAnnotation = proof.proof();
        verificationPresence.requirePresence(Long.toString(workspaceId));
        FinishEditFacts.Fact fact = finishFacts.consume(Long.toString(workspaceId));
        if (fact == null) {
            throw new IllegalStateException("终验修复 run 未以 finish_edit 结束工具收口");
        }
        if (!fact.changed()) {
            log.info("[verify] 项目 {} 终验修复收口：系统未动（{}）——复走终验如实判定",
                    project.getId(), fact.text());
        }
        // 演示种子补位（幂等只补空表——修复可能长出新表）；失败不阻断
        demoSeed.seedSandbox(project);
        return CoderRunAttempts.ClosingJudgment.update(false, null, fact.changed(), fact.text())
                .withProofOfOutput(proofAnnotation)
                .withDesignLint(designSpecs.lintClosingPayload(project))
                .withRobustKitLint(robustKitLint.lintClosingPayload(workspaceId));
    }

    /** 终验 run 首试 prompt：核心旅程清单＋委派 verify 口径＋验证编排边界（不改码）。 */
    static String verificationPrompt(List<BuildPlan.CoreJourney> journeys) {
        StringBuilder prompt = new StringBuilder(
                "系统终验（整体验证）：全部功能切片已完成，系统即将交付。请把下列核心旅程"
                        + "逐条委派 verify 子智能体验证（agent_spawn，agent_id 传 verify）——"
                        + "委派任务说明里逐条自报该旅程的验收要点（哪个角色、从哪个页面进入、"
                        + "按顺序做什么），由它以真浏览器完整走通每条跨片旅程（含刷新一次验真"
                        + "落库）并登记结果。\n核心旅程清单：");
        for (int index = 0; index < journeys.size(); index++) {
            BuildPlan.CoreJourney journey = journeys.get(index);
            prompt.append('\n').append(index + 1).append(". 角色「").append(journey.role())
                    .append("」从「").append(journey.entry()).append("」进入，依次「")
                    .append(journey.keyActions()).append('」');
        }
        prompt.append("\n\n登记要求：每条旅程至少一个验证项（项名含旅程可辨，如「旅程 1·下单"
                        + "支付」）；全部旅程验完后，把全部旅程的逐项结果用 report_verification "
                        + "一次性汇总登记（checks 传全量——不要逐旅程分多次登记，多次登记平台按"
                        + "并集判定但收尾统计只看末轮）。")
                .append("\n\n本 run 是验证编排 run：不修改任何系统代码——发现问题如实登记即可，"
                        + "修复由平台自动另行安排。本 run 无 report_verification 登记不能收口；"
                        + "全部旅程登记完成后即可收口（无需 finish_edit）。")
                .append(GenerationAppService.NARRATION_CONVENTION);
        return prompt.toString();
    }

    /** 终验 run 重试续作 prompt（对偶修正轨重试口径：携现场原地续）。 */
    private static String verificationRetryPrompt(String retryScene) {
        return "上一次整体验证尝试失败。\n" + retryScene + "\n请检查现状（8081 端口服务是否在跑），"
                + "继续完成整体验证——把核心旅程逐条委派 verify 子智能体走真浏览器主径"
                + "并 report_verification 登记后收口；不要修改系统代码。";
    }

    /** 终验修复 run prompt：载荷＝失败项清单（项名＋证据），修复自证后 finish_edit 收口。 */
    static String verificationFixPrompt(List<Defect> defects) {
        StringBuilder prompt = new StringBuilder(
                "系统修正（终验修复）：系统整体验证发现以下验证项未通过，请在现有工作区内"
                        + "修复这些问题（系统的其余部分保持可用）：");
        for (int index = 0; index < defects.size(); index++) {
            Defect defect = defects.get(index);
            prompt.append('\n').append(index + 1).append(". 「").append(defect.name()).append('」');
            if (defect.detail() != null && !defect.detail().isBlank()) {
                prompt.append('：').append(defect.detail());
            }
        }
        prompt.append("\n修复后确认 8081 端口服务在跑、curl 可访问，并把修复的验证要点委派"
                + " verify 子智能体（agent_spawn，agent_id 传 verify）复验通过后收口。")
                .append("\n收尾必须调用 finish_edit 工具：动了系统传 changed=true 并说明"
                        + "改了什么；判定无需改动（如验证环境问题非系统缺陷）也必须调用，"
                        + "传 changed=false 并说明原因——不调用即本轮修复未收口。");
        return prompt.toString();
    }

    /** 已知缺陷清单载荷（closing.knownDefects——收尾卡如实呈现的单元）。 */
    private static Map<String, Object> knownDefectsPayload(List<Defect> defects) {
        List<Map<String, Object>> items = new ArrayList<>(defects.size());
        for (Defect defect : defects) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", defect.name());
            if (defect.detail() != null && !defect.detail().isBlank()) {
                item.put("detail", defect.detail());
            }
            items.add(item);
        }
        return Map.of("defects", items);
    }

    /**
     * 终验判定的未过项（验证事实 → 缺陷清单）：读<b>跨轮累计清单</b>（逐项名后写
     * 胜出并集）而非末轮快照——一场终验 run 内多次登记（逐旅程分别登记）时早登记
     * 的失败项不被末轮遮蔽（code-review #427：末轮快照后写胜出会把「旅程 A 未过＋
     * 旅程 B 已过」误判为通过）；项重验以最新一次为准。无登记（在场门已拦）/空
     * 清单＝无缺陷。
     */
    private static List<Defect> defectsOf(VerificationFacts.Facts facts) {
        if (facts == null) {
            return List.of();
        }
        return facts.cumulative().stream()
                .filter(check -> !check.ok())
                .map(check -> new Defect(check.name(), check.detail()))
                .toList();
    }

}
