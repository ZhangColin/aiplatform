package com.semgravio.aiplatform.business.project.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ForkState;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 岔路口卡与派发门（#542 访谈②，ADR-0048 切片②＋ADR-0049 并入——自动派发两
 * 入口整体退役后的用户触发面单点）：首个构建 run 的点火权归用户——试用态＝岔
 * 路口三选卡「立即生成」（{@code POST /generate} 既有口，本服务只出卡读面），
 * 付费态＝PRD 文档面确认按钮（{@link #confirmFirstBuild} 恢复 #462 之前的原
 * 动作形态）；快道（收敛令/零必答短路/付费「直接开始做」）不经本缝——收口观测
 * 直派（{@link MainAgentAppService}），不出卡不确认。
 *
 * <p><b>岔路口状态位</b>（{@link #ensureForkPending}）：试用态访谈自然收口
 * （判定明确含提前明确——非快道）由收口观测点调用置 {@link ForkState#PENDING}
 * ——持久可回（未选择前关页回来卡仍在）；快道直生成不置位（不出卡）、点火后
 * 不回清（卡隐藏由首建点火时点派生承担——失败重试走「继续生成」、卡与确认门
 * 都不复现）。设计类终点不出卡不设门（设计轨触发律不改：PRD 收口起设计过程、
 * 全部定稿起构建）。</p>
 *
 * <p><b>派发门读面</b>（{@link #gateOf}，{@code GET /build-gate} 单读点）：
 * 岔路口卡＝试用态∧PENDING∧PRD 在∧首建未点火；PRD 确认按钮＝付费态∧PRD
 * 在∧首建未点火（ADR-0049 呈现条件的唯一正本）。两轨判定归扣费域单读点
 * （{@link BillingAppService#trackOf}，ADR-0049——不立第二份标志）。</p>
 *
 * <p><b>确认即派发、版本机制零改动</b>：确认经 {@link GenerationAppService
 * #startGeneration} 共用编排（守卫/余额门/设计定稿门/起跑记行全同构）——确认
 * 不写版本行（「确认即成版」#330 写表语义不复活），记行归起跑单点、版本号＝
 * 构建基准序全域一致；点击幂等＝已点火拒绝 PRJ_017（不重复派发），失败重试走
 * 「继续生成」不重复确认。</p>
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

    /** 岔路口出卡前置（置位与读面同判据）：试用态 ∧ 系统终点 ∧ PRD 在 ∧ 首建未点火 ∧ 未生成未归档。 */
    private boolean forkEligible(Project project) {
        return billingAppService.trackOf(project.getId()).trial()
                && !project.getEndpointType().designInvolved()
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
        boolean trial = billingAppService.trackOf(projectId).trial();
        boolean firstBuildOpen = project.getGenerationRequestedAt() == null
                && project.getGeneratedAt() == null
                && project.getArchivedAt() == null
                && project.getPrdProducedAt() != null;
        return new BuildGateView(
                trial && firstBuildOpen && project.getForkState() == ForkState.PENDING
                        && !project.getEndpointType().designInvolved(),
                !trial && firstBuildOpen && !project.getEndpointType().designInvolved());
    }

    /**
     * 付费轨 PRD 确认（POST /prd/confirm——原动作形态恢复，#542/ADR-0049）：确认
     * 即派发首个构建（点火时点随派发落位）。守卫序＝付费态（PRJ_084——试用轨
     * 点火动作是岔路口「立即生成」）→ PRD 在（PRJ_018）→ 未归档（PRJ_013）→
     * 首建未点火（PRJ_017——点击幂等，失败重试走「继续生成」不重复确认）；在途/
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
     * 派发门读面结果（两呈现条件的同源接口形）。
     *
     * @param forkCardPending   岔路口三选卡呈现（试用态自然收口待选择）
     * @param prdConfirmable    PRD 文档面确认按钮呈现（付费态首建待确认）
     */
    public record BuildGateView(boolean forkCardPending, boolean prdConfirmable) {
    }
}
