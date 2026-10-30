package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.PrdReadinessResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.PrdResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.PrdVersionResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.PrdVersionSummaryResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.PrdVersion;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.PrdVersionRepository;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * PRD 确认版本用例（#330「确认即成版」，spec #320 实施序③）：确认动作把当时
 * 工作区 PRD 全文拷贝为不可变快照（＋确认时间＋项目内递增整数版本号 v1/v2/v3…）
 * 落平台库；工作区 PRD 仍是活草稿照旧覆盖写（草稿不编号、确认即分配版本号）。
 * 全文来源＝PRD 读端点同缝（{@link ProjectQueryAppService#prd}——PRJ_015 未产出/
 * WSP_002 环境故障口径复用，读源单一）；成版判定正本＝快照全文比对——内容未变
 * （无新草稿）确认幂等返回既有版、不产生新版本（锚随每次 savePrd 刷新，同文
 * 重存锚漂不成新版，故不拿锚做判定）。
 *
 * <p>事务形制：docker exec（PRD 读取）在事务外（Docker 副作用不进业务事务，
 * 本仓既有形制），判定＋插入收进短事务；并发双确认撞 (project_id, version_no)
 * 唯一约束时败者回读最新版——内容一致＝同一确认的重复点击，幂等返回既有版
 * （「库唯一约束＋服务端事务内判定」防重同款，生产承接先例）。<b>生成加门
 * （#332）</b>：确认成版后自动派首个 Demo run（{@link
 * GenerationAppService#dispatchFirstDemoOnConfirm}）——「确认此版 PRD 并生成
 * Demo」成为首个构建 run 的唯一触发；派发是次生事实、失败不反噬确认回执。</p>
 *
 * <p><b>两级门（#331 待定项两级门）</b>：确认前解析「验收与待确认」章——有
 * 阻碍核心流程缺项即拦（PRJ_053，用户不可绕过、须先在对话区补齐）；解析缺失
 * （章缺失/形态漂移——含存量项目旧「待定项」章）fail-open 放行；仅次要未知
 * 放行成版，回执带未解决事项（两段式摘要层数据面）。门判定与 PRD 读面收敛度
 * 投影同源（{@link PrdReadiness} 单一解析器，判定语义归主智能体提示词、门的
 * 执行归平台代码）。</p>
 *
 * <p>版本行不可变＝只插不改：本用例无任何历史行更新路径；「快照不可变」的
 * 行为面等价物＝确认后改草稿再读历史版本，原文不动（集测钉）。</p>
 *
 * <p><b>改需求回路（#334）</b>：范围级意见（新增角色/主流程/交付范围——判定
 * 归主智能体提示词、从 savePrd 修订事实观测）出新草稿后不停驻不派——{@link
 * #parkDraftChange} 停驻意见与差异摘要（重启不丢），用户过确认门（新版本号）
 * 才派下一版 Demo：已生成项目＝修正 run 携停驻交接物、未生成项目＝首个 Demo
 * 全新构建；小反馈照旧受理即迭代（不停驻、基准判定归 {@link PrdDraftBaseline}）。</p>
 */
@Service
@Slf4j
public class PrdVersionAppService {

    private final ProjectRepository projectRepository;
    private final ProjectQueryAppService projectQueryAppService;
    private final PrdVersionRepository prdVersions;
    private final TransactionTemplate transactionTemplate;
    private final GenerationAppService generationAppService;
    private final IterationAppService iterationAppService;
    private final EventsAppService eventsAppService;

    public PrdVersionAppService(ProjectRepository projectRepository,
            ProjectQueryAppService projectQueryAppService,
            PrdVersionRepository prdVersions,
            TransactionTemplate transactionTemplate,
            GenerationAppService generationAppService,
            IterationAppService iterationAppService,
            EventsAppService eventsAppService) {
        this.projectRepository = projectRepository;
        this.projectQueryAppService = projectQueryAppService;
        this.prdVersions = prdVersions;
        this.transactionTemplate = transactionTemplate;
        this.generationAppService = generationAppService;
        this.iterationAppService = iterationAppService;
        this.eventsAppService = eventsAppService;
    }

    /**
     * 确认此版 PRD：过两级门（#331）后工作区 PRD 全文冻结成版（重复确认无新
     * 草稿＝幂等返回既有版）。门＝「验收与待确认」章解析有阻碍核心流程缺项即拦
     * （用户不可绕过——须先在对话区补齐）；解析缺失 fail-open 不拦（误拦即用户
     * 死锁、漏拦可经迭代补救）；仅次要未知放行，回执带未解决事项（两段式摘要层
     * 的确认动作即本端点——「已看过摘要」标记由呈现层承载，后端不另设标记）。
     * 成版后自动派首个 Demo run（生成加门 #332，已生成/在途静默跳过——派发是次生
     * 事实，失败不反噬确认回执）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档（对话区
     *                              关闭即全停，历史版本照读）；PRJ_015 PRD 尚未
     *                              产出（产出锚未落或工作区无 docs/PRD.md）；
     *                              PRJ_053 有阻碍核心流程的待确认项（两级门拦截）；
     *                              WSP_002 环境故障
     */
    public PrdVersionResponse confirm(Long projectId) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        if (project.getPrdProducedAt() == null) {
            throw new ApplicationException(ProjectMessage.PRD_NOT_PRODUCED);
        }
        PrdResponse prd = projectQueryAppService.prd(projectId);
        String draft = prd.content();
        // 两级门（#331）：判定与 PRD 读面同源（读面已解析一次，确认复用同一份
        // readiness——门拦截与投影同一事实），阻碍级在场即拦、不成版
        if (!prd.readiness().blocking().isEmpty()) {
            throw new ApplicationException(ProjectMessage.PRD_CONFIRM_BLOCKING_PENDING);
        }
        Confirmation confirmation = confirmSnapshot(project, draft, prd.readiness());
        // 生成加门（#332）：确认即成版后自动派首个 Demo run——「确认此版 PRD 并
        // 生成 Demo」是首个构建 run 的唯一触发（推翻 #101「PRD 出即起」）。已生成/
        // 在途静默跳过；派发是次生事实、失败不反噬确认回执（版本已落库，用户可重
        // 确认再触发）——best-effort，异常只记日志。
        try {
            generationAppService.dispatchFirstDemoOnConfirm(projectId);
        }
        catch (RuntimeException e) {
            log.warn("[prd-confirm] 项目 {} 确认成版但首个 Demo run 派发失败"
                    + "（确认不反噬，用户重确认即再触发）：{}", projectId, e.toString());
        }
        // 改需求回路（#334）：确认成功即消费停驻的范围级交接物——新版本号落库且
        // 项目已生成时携停驻意见与差异摘要派修正 run（下一版 Demo）；未生成项目
        // 由首个 Demo 全新构建消化（生成读工作区新确认正本）。停驻清位与派发同为
        // 次生事实，失败不反噬确认回执（重提意见即兜底——草稿==已确认版，照旧
        // 受理即迭代）。
        consumeParkedDraftChange(projectId, confirmation.newVersion());
        return confirmation.response();
    }

    /**
     * 范围级草稿停驻（#334 改需求回路）：意见轮收口判定为范围级（savePrd 修订
     * 事实＋草稿≠已确认版，判定缝在 {@link MainAgentAppService} 轮收口）时，
     * 平台停派更新 run，把意见原文与差异摘要（savePrd 的 summary 终值——改稿时
     * 产出的 markdown 变更说明）停驻到项目行——「不落库缺口最小方案」：重启后
     * 确认时的派发与对话区提示（「PRD 已更新，确认新版后生成下一版 Demo」）不丢
     * 事实。停驻取终值（新一轮覆盖写）；停驻即 PRD 读面有新事实（差异摘要入
     * {@code GET /{id}/prd}），复用 document-updated 广播前端失效重拉（事件
     * 封闭集零新增）。
     */
    public void parkDraftChange(Long projectId, String opinion, String changeSummary) {
        Project project = transactionTemplate.execute(tx -> {
            Project loaded = requireProject(projectId);
            loaded.parkPrdDraftChange(opinion, changeSummary);
            return projectRepository.save(loaded);
        });
        eventsAppService.publishNotification(ProjectEventTypes.DOCUMENT_UPDATED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                ProjectEventTypes.DOCUMENT_TYPE_FIELD, ProjectEventTypes.DOCUMENT_TYPE_PRD,
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId()),
                EventsAppService.OWNER_GUEST_FIELD,
                EventsAppService.ownerPayload(project.getOwnerGuestId())));
    }

    /** 确认成版结果（回执＋是否产生了新版本号——#334 下一版 Demo 只在新版本号上派）。 */
    private record Confirmation(PrdVersionResponse response, boolean newVersion) {
    }

    /** 停驻的范围级交接物（确认时取走即清）。 */
    private record ParkedDraftChange(String opinion, String changeSummary) {
    }

    /** 确认成功的停驻消费：清位恒做（停驻描述待确认草稿，确认后即过期）；派发只在新版本号＋已生成项目。 */
    private void consumeParkedDraftChange(Long projectId, boolean newVersion) {
        ParkedDraftChange parked = transactionTemplate.execute(tx -> {
            Project project = requireProject(projectId);
            if (project.getPrdDraftOpinion() == null) {
                return null;
            }
            ParkedDraftChange change = new ParkedDraftChange(
                    project.getPrdDraftOpinion(), project.getPrdDraftSummary());
            project.clearPrdDraftChange();
            projectRepository.save(project);
            return change;
        });
        if (parked == null || !newVersion
                || requireProject(projectId).getGeneratedAt() == null) {
            return;
        }
        try {
            iterationAppService.startFixRun(projectId, parked.opinion(), parked.changeSummary());
            log.info("[prd-confirm] 项目 {} 确认新版，携停驻交接物派下一版 Demo（修正 run）",
                    projectId);
        }
        catch (RuntimeException e) {
            log.warn("[prd-confirm] 项目 {} 确认新版但下一版 Demo 派发失败"
                    + "（确认不反噬，重提意见即兜底）：{}", projectId, e.toString());
        }
    }

    /** 成版（短事务：最新版比对→幂等返回或插新行；唯一约束竞态败者幂等回读）。 */
    private Confirmation confirmSnapshot(Project project, String draft,
            PrdReadinessResponse readiness) {
        try {
            return transactionTemplate.execute(tx -> {
                List<PrdVersion> existing =
                        prdVersions.findByProjectIdOrderByVersionNoDesc(project.getId());
                if (!existing.isEmpty() && existing.get(0).getContent().equals(draft)) {
                    return new Confirmation(toResponse(project.getId(), existing.get(0), readiness),
                            /* newVersion= */ false);
                }
                int nextVersionNo = existing.isEmpty() ? 1 : existing.get(0).getVersionNo() + 1;
                // saveAndFlush：把唯一约束违反压进事务内（翻译可靠，catch 面确定性）
                PrdVersion saved = prdVersions.saveAndFlush(PrdVersion.confirmed(
                        project.getId(), nextVersionNo, draft,
                        LocalDateTime.now(), project.getPrdProducedAt()));
                return new Confirmation(toResponse(project.getId(), saved, readiness),
                        /* newVersion= */ true);
            });
        }
        catch (DataIntegrityViolationException e) {
            return prdVersions.findByProjectIdOrderByVersionNoDesc(project.getId()).stream()
                    .findFirst()
                    .filter(latest -> latest.getContent().equals(draft))
                    .map(latest -> new Confirmation(toResponse(project.getId(), latest, readiness),
                            /* newVersion= */ false))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 已确认版本清单（version_no 倒序——最新在前；空 = 从未确认，草稿态呈现
     * 「草稿」徽章归呈现面）。归档项目照读（历史可追溯）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public List<PrdVersionSummaryResponse> versions(Long projectId) {
        Project project = requireProject(projectId);
        return prdVersions.findByProjectIdOrderByVersionNoDesc(project.getId()).stream()
                .map(version -> new PrdVersionSummaryResponse(
                        project.getId().toString(), version.getVersionNo(), version.getConfirmedAt()))
                .toList();
    }

    /**
     * 版本详情（含全文快照＋收敛度投影——对快照全文即时解析，草稿后续改动不污染
     * 已确认版本的读面）。归档项目照读。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_052 版本不存在
     */
    public PrdVersionResponse version(Long projectId, int versionNo) {
        Project project = requireProject(projectId);
        PrdVersion version = prdVersions.findByProjectIdAndVersionNo(project.getId(), versionNo)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PRD_VERSION_NOT_FOUND));
        return toResponse(project.getId(), version,
                PrdReadinessResponse.from(PrdReadiness.parse(version.getContent())));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

    private PrdVersionResponse toResponse(Long projectId, PrdVersion version,
            PrdReadinessResponse readiness) {
        return new PrdVersionResponse(projectId.toString(), version.getVersionNo(),
                version.getContent(), version.getConfirmedAt(), readiness);
    }
}
