package com.semgravio.aiplatform.business.project.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.billing.application.BillingAppService.BillingTrack;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ForkState;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 岔路口卡与派发门（#542 访谈②，ADR-0048 切片②＋ADR-0049 并入——自动派发两
 * 入口整体退役后的用户触发面单点）：首个构建 run 的点火权归用户——试用态＝岔
 * 路口三选卡「立即生成」（{@code POST /generate} 既有口，本服务只出卡读面），
 * 付费态＝PRD 文档面确认按钮（{@link #confirmFirstBuild} 恢复 #462 之前的原
 * 动作形态）；快道（试用态＝用户明确收敛令；付费态＝零必答短路/「直接开始做」
 * 视同确认）不经本缝——收口观测直派（{@link MainAgentAppService}），不出卡不确认。
 *
 * <p><b>岔路口状态位</b>（{@link #ensureForkPending}）：试用态访谈问满五问后的
 * 自然收口（非快道——2026-11-17 起未答满五问且无收敛令的 savePrd 在工具闸即被
 * 拒，走不到本缝）由收口观测点调用置 {@link ForkState#PENDING}
 * ——持久可回（未选择前关页回来卡仍在）；快道直生成不置位（不出卡）、点火后
 * 不回清（卡隐藏由首建点火时点派生承担——#604 起终态失败/停止收口清点火位，
 * 卡与确认门复现：用户在点火入口重跑即恢复）。旧「设计类终点不出卡不设门」
 * 分支已随 #610 设计线退役消失。</p>
 *
 * <p><b>派发门读面</b>（{@link #gateOf}，{@code GET /build-gate} 单读点）：
 * 岔路口卡＝试用态∧PENDING∧PRD 在∧首建未点火；PRD 确认按钮＝付费态∧PRD
 * 在∧首建未点火（ADR-0049 呈现条件的唯一正本）。两轨判定归扣费域单读点
 * （{@link BillingAppService#trackOf}，ADR-0049——不立第二份标志）。</p>
 *
 * <p><b>确认即派发、版本机制零改动</b>：确认经 {@link GenerationAppService
 * #startGeneration} 共用编排（守卫/余额门/起跑记行全同构）——确认
 * 不写版本行（「确认即成版」#330 写表语义不复活），记行归起跑单点、版本号＝
 * 构建基准序全域一致；点击幂等＝已点火拒绝 PRJ_017（不重复派发）。#604 起首建
 * 终态失败/停止收口清点火位（{@link Project#reopenFirstBuildRequest}）——确认
 * 门复现，用户重走点火入口重跑即恢复。</p>
 */
@Service
public class BuildGateAppService {

    private final ProjectRepository projectRepository;
    private final BillingAppService billingAppService;
    private final GenerationAppService generationAppService;

    public BuildGateAppService(ProjectRepository projectRepository,
            BillingAppService billingAppService,
            GenerationAppService generationAppService) {
        this.projectRepository = projectRepository;
        this.billingAppService = billingAppService;
        this.generationAppService = generationAppService;
    }

    /**
     * 岔路口置位（自然收口观测点调用，幂等）：试用态 ∧ 系统终点 ∧ PRD 在 ∧ 首建
     * 未点火 ∧ 未归档才置（前置自持——调用方只管「自然收口、非快道」的分岔）。
     *
     * @return 本次是否新置位（false＝前置不满足或已在场——调用方记日志用，非判据）
     */
    @Transactional
    public boolean ensureForkPending(Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !forkEligible(project)) {
            return false;
        }
        if (project.getForkState() == ForkState.PENDING) {
            return false; // 已在场（意见修订轮再收口——卡照旧，幂等）
        }
        project.markForkPending();
        projectRepository.save(project);
        return true;
    }

    /** 岔路口出卡前置（置位与读面同判据）：试用态 ∧ PRD 在 ∧ 首建未点火 ∧ 未生成未归档。 */
    private boolean forkEligible(Project project) {
        return billingAppService.trackOf(project.getId()).trial()
                && project.getPrdProducedAt() != null
                && project.getGenerationRequestedAt() == null
                && project.getGeneratedAt() == null
                && project.getArchivedAt() == null;
    }

    /**
     * 派发门读面（GET /build-gate）：岔路口卡与 PRD 确认按钮的呈现条件单读点
     * （前端不自算）。项目不存在返回 null（端点 data=null 形，同拦截卡读面口径）。
     * 状态位可能滞后于派生条件翻转（如点火后未回清 fork_state）——呈现以派生
     * 条件为准逐项判，状态位只是「自然收口发生过」的事实锚。
     */
    @Transactional(readOnly = true)
    public BuildGateView gateOf(Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return null;
        }
        BillingTrack track = billingAppService.trackOf(projectId);
        boolean trial = track.trial();
        boolean firstBuildOpen = firstBuildOpen(project);
        boolean forkCardPending = trial && firstBuildOpen
                && project.getForkState() == ForkState.PENDING;
        // 到账自动翻转的读面信号（#544——「自动」不止收银台 onDone 单缝：他处
        // 到账〔账户页/项目账单节/他端〕后卡回读即显 settleDue，前端空 source
        // 重判即翻转、无需重走选择）：卡在场 ∧ 源已选定 ∧ 源池余额为正
        boolean forkSettleDue = forkCardPending
                && track.deductionSource() != null && track.sourcePoolPositive();
        return new BuildGateView(forkCardPending,
                !trial && firstBuildOpen,
                forkSettleDue);
    }

    /** 首建未点火判定（读面与引导流命令的共用谓词——判据单源防漂移）：PRD 在 ∧ 未请求 ∧ 未生成 ∧ 未归档。 */
    private boolean firstBuildOpen(Project project) {
        return project.getPrdProducedAt() != null
                && project.getGenerationRequestedAt() == null
                && project.getGeneratedAt() == null
                && project.getArchivedAt() == null;
    }

    /**
     * 付费轨 PRD 确认（POST /prd/confirm——原动作形态恢复，#542/ADR-0049）：确认
     * 即派发首个构建（点火时点随派发落位）。守卫序＝付费态（PRJ_084——试用轨
     * 点火动作是岔路口「立即生成」）→ PRD 在（PRJ_018）→ 未归档（PRJ_013）→
     * 首建未点火（PRJ_017——点击幂等；#604 起失败/停止收口已清点火位，重确认
     * 即重跑）；在途/
     * 设计终点等其余守卫由 {@link GenerationAppService#startGeneration} 单点兜
     * （确认与「立即生成」共用同一派发编排——两轨唯一差异＝触发源）。
     *
     * @return 派发的 run 标识（挂 /api/events?runId= 的锚）
     */
    public GenerationAppService.GenerationRun confirmFirstBuild(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        if (billingAppService.trackOf(projectId).trial()) {
            throw new ApplicationException(ProjectMessage.PRD_CONFIRMATION_NOT_PAID);
        }
        if (project.getPrdProducedAt() == null) {
            throw new ApplicationException(ProjectMessage.GENERATION_PRD_NOT_PRODUCED);
        }
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        if (project.getGenerationRequestedAt() != null) {
            throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
        }
        return generationAppService.startGeneration(projectId);
    }

    /**
     * 「继续讨论」引导流（#544 访谈③，ADR-0048 切片③——翻转门①交互时序，三选
     * 上卡压平版）：访客在岔路口卡选付费项 → 本命令一次选定扣减源并按所选池
     * 判定——源池余额为正即翻转回访谈（五问额度解除、协议分轨回原归读面派生），
     * 非正返回「引导充值」载荷（源已一次选定——重选拒绝 BILL_008 防自助改口），
     * 到账后经本端点重判（空 source）即翻转、无需重走选择。
     *
     * <p><b>编排分岔</b>（ADR-0048：访客选付费项才走 M01 登录＋项目平移——归
     * 登录编排 {@code GuestBindingAppService} 同事务完成，本层只收选定事实）：
     * 「由我的账户付费」＝账户源须带登录账户（BILL_009 指路登录）；「由本项目
     * 付费」＝项目源免账户（后台已充项目池的项目源已选定——本命令空 source 直达
     * 翻转判定＝免登录直接翻转的销售演示动线）。中途放弃＝不落翻转、卡留岔路口
     * （试用态 PENDING 不回清），随时改选「立即生成」。</p>
     *
     * <p><b>幂等与守卫</b>：已付费（他路翻转竞态/重复提交）→ 幂等成功返回
     * flipped＝true；卡不在场（未自然收口/已点火/已归档/设计终点）→ PRJ_085；
     * 源已选定且随命令改传异源 → BILL_008（防自助改口——同源重传容忍＝重判语义）；
     * 源未定且未随命令选定 → BILL_010；翻转不可逆等前置全走
     * {@link BillingAppService} 单点不变。</p>
     *
     * @param source   随卡选源（1=账户余额 2=项目余额；空＝不改动既有选定——
     *                 后台已充项目池的免选直翻转/到账后重判 settle）
     * @param accountId 当前登录账户（账户源定格用；访客未登录＝null 吃 BILL_009）
     * @return 引导判定结果（翻转 / 引导充值）
     */
    @Transactional
    public ForkGuideView continueDiscussion(Long projectId, DeductionSource source, Long accountId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        BillingTrack track = billingAppService.trackOf(projectId);
        if (track.paid()) {
            return ForkGuideView.flippedOf(track); // 已翻转（他路竞态/重复提交）——幂等成功
        }
        if (!forkCardOnScreen(project)) {
            throw new ApplicationException(ProjectMessage.FORK_NOT_PENDING);
        }
        if (track.deductionSource() != null && source != null && source != track.deductionSource()) {
            // 源已随选择一次选定——改口拒绝（BILL_008，同拦截卡确认扣减语义）；同源
            // 重复传容忍＝重判语义（引导充值支重点同选项/到账后 settle）
            throw new ApplicationException(BillingMessage.SOURCE_ALREADY_SELECTED);
        }
        if (track.deductionSource() == null) {
            if (source == null) {
                throw new ApplicationException(BillingMessage.FLIP_SOURCE_REQUIRED);
            }
            billingAppService.selectSource(projectId, source, accountId);
        }
        BillingTrack selected = billingAppService.trackOf(projectId);
        if (!selected.sourcePoolPositive()) {
            // 按池判定的「引导充值」支：源已一次选定，到账后重判即翻转
            return new ForkGuideView(false, true,
                    selected.deductionSource().getCode(), selected.sourcePoolBalance());
        }
        billingAppService.flipToPaid(projectId, FlipGate.FORK);
        return ForkGuideView.flippedOf(billingAppService.trackOf(projectId));
    }

    /** 岔路口卡在场判定（读面同判据的命令侧镜像）：PENDING ∧ 首建未点火。 */
    private boolean forkCardOnScreen(Project project) {
        return project.getForkState() == ForkState.PENDING
                && firstBuildOpen(project);
    }

    /**
     * 派发门读面结果（两呈现条件的同源接口形）。
     *
     * @param forkCardPending   岔路口三选卡呈现（试用态自然收口待选择）
     * @param prdConfirmable    PRD 文档面确认按钮呈现（付费态首建待确认）
     * @param forkSettleDue     引导流到账自动翻转信号（#544——卡在场∧源已选定∧源池
     *                          余额为正：他处到账后卡回读即显，前端空 source 重判
     *                          即翻转、无需重走选择）
     */
    public record BuildGateView(boolean forkCardPending, boolean prdConfirmable,
            boolean forkSettleDue) {
    }

    /**
     * 「继续讨论」引导流判定结果（#544——前端分岔编排的单源：flipped＝回执
     * 翻转（卡随读面熄灭、访谈继续）；rechargeRequired＝按池判定的引导充值支
     * （源已选定，开收银台、到账后重判即翻转）。
     *
     * @param flipped           是否已翻转（本次或他路竞态既往——幂等成功语义）
     * @param rechargeRequired  源池余额非正、引导充值（false 且 flipped=false 不成立）
     * @param deductionSource   已选定扣减源码位（1=账户余额 2=项目余额——收银台目标池推导素材）
     * @param sourcePoolBalance 源池余额快照（分；池未建 null——项目池从未入账必空）
     */
    public record ForkGuideView(boolean flipped, boolean rechargeRequired,
            Integer deductionSource, Long sourcePoolBalance) {

        /** 已翻转视图的统一构造（幂等成功回执同形）。 */
        static ForkGuideView flippedOf(BillingTrack track) {
            return new ForkGuideView(true, false,
                    track.deductionSource() == null ? null : track.deductionSource().getCode(),
                    track.sourcePoolBalance());
        }
    }
}
