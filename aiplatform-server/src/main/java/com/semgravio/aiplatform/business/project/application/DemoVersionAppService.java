package com.semgravio.aiplatform.business.project.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.project.application.dto.response.DemoVersionSummaryResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.DemoVersion;
import com.semgravio.aiplatform.business.project.domain.aggregate.PrdVersion;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.DemoVersionRepository;
import com.semgravio.aiplatform.business.project.domain.repository.PrdVersionRepository;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Demo 构建版本用例（#333「Demo 版本化与确认标记」，spec #320 实施序③）：每轮
 * Demo 构建收口自动成版（项目内整数递增 v1/v2/v3…），每版锚定<b>起跑时</b>已确认
 * PRD 版本（{@code prdVersionNo}——「Demo vN（基于 PRD vM 快照）」式关联：在途
 * Demo 照跑完收口绑起跑时版本，不随后续确认漂移；锚由派发侧生成/迭代轨捕获，
 * 本用例不复查——锚是起跑事实不是收口判定）；「确认此版本，准备签约」＝既有
 * 版本上打确认标记（项目单指针 {@code confirmed_demo_version_no}——选定动作、
 * 不产生新版本号；换确认版本＝指针随移，旧标记自然失效）。
 *
 * <p>版本行不可变＝只插不改（{@link DemoVersion} 零变更方法，PrdVersion V32 同款
 * 版本表模式）；确认标记不动版本行、只移项目指针。生成收口成版与
 * {@code generated_at} 落位同刻原子（短事务：要么同落要么同缺——「片全收口而
 * 落位失败」的补落路径同口径自愈）；迭代轮收口成版只插版本行（项目已生成是
 * 迭代轨守卫既有事实）。版本号分配＝「查最新＋1」（同项目单在途 run——
 * CodingRunTrack 串行——无并发分配面；(project_id, version_no) 唯一约束兜底）。
 * 版本创建由生成/迭代轨 best-effort 调用（失败记日志不反噬 run，下次收口自愈
 * 补位——markGenerated 同口径）。</p>
 */
@Service
@Slf4j
public class DemoVersionAppService {

    private final ProjectRepository projectRepository;
    private final DemoVersionRepository demoVersions;
    private final PrdVersionRepository prdVersions;
    private final TransactionTemplate transactionTemplate;

    public DemoVersionAppService(ProjectRepository projectRepository,
            DemoVersionRepository demoVersions, PrdVersionRepository prdVersions,
            TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.demoVersions = demoVersions;
        this.prdVersions = prdVersions;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 起跑时已确认 PRD 版本锚（#333 锚捕获单点——生成/迭代轨派发处共用）：
     * 最新已确认版本号；从未确认（legacy/设计类起跑）为 null。
     */
    public Integer latestConfirmedPrdVersionNo(Long projectId) {
        return prdVersions.findFirstByProjectIdOrderByVersionNoDesc(projectId)
                .map(PrdVersion::getVersionNo).orElse(null);
    }

    /**
     * 生成收口成版（首版）：与 {@code generated_at} 首次落位同刻原子——仅当
     * generated_at 尚未落（首次收口/补落位）时置位并插版本行；已落位即本版
     * 已随刻成版，幂等跳过（不产生第二版）。项目已不存在＝warn 不炸收口轨道
     * （markGenerated 同口径）。
     *
     * @param prdVersionNo 起跑时已确认 PRD 版本锚（无确认版起跑 = null）
     */
    public void closeOnGeneration(Long projectId, String runId, Integer prdVersionNo) {
        transactionTemplate.executeWithoutResult(status ->
                projectRepository.findById(projectId).ifPresentOrElse(project -> {
                    if (project.getGeneratedAt() != null) {
                        return; // 首版已随 generated_at 同刻成版——幂等跳过
                    }
                    project.markGenerated();
                    projectRepository.save(project);
                    demoVersions.saveAndFlush(nextVersion(project, runId, prdVersionNo));
                }, () -> log.warn("[demo-version] 项目 {} 生成收口但记录已不存在（版本无处落）",
                        projectId)));
    }

    /**
     * 迭代轮收口成版（系统实动的修正/更新 run 收口——递增成版；调用侧只在
     * finish_edit changed=true 时调用，无新状态即无新版本）。
     *
     * @param prdVersionNo 起跑时已确认 PRD 版本锚（无确认版起跑 = null）
     */
    public void closeOnIteration(Long projectId, String runId, Integer prdVersionNo) {
        transactionTemplate.executeWithoutResult(status ->
                projectRepository.findById(projectId).ifPresentOrElse(
                        project -> demoVersions.saveAndFlush(
                                nextVersion(project, runId, prdVersionNo)),
                        () -> log.warn("[demo-version] 项目 {} 迭代收口但记录已不存在（版本无处落）",
                                projectId)));
    }

    /**
     * Demo 版本清单（version_no 倒序——最新在前；确认标记投影到行——仅指针
     * 所在版带 confirmedAt）。归档项目照读（历史可追溯）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public List<DemoVersionSummaryResponse> versions(Long projectId) {
        Project project = requireProject(projectId);
        return demoVersions.findByProjectIdOrderByVersionNoDesc(project.getId()).stream()
                .map(version -> toSummary(project, version))
                .toList();
    }

    /**
     * 确认标记动作（「确认此版本，准备签约」）：既有版本上打标记——不产生新
     * 版本号（版本行不可变）；换确认版本＝项目单指针随移（旧标记自然失效）。
     * 回执＝所确认版本的清单形（含确认时间——两段式呈现与下单前置 #335 的
     * 干净挂点）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档（历史版本
     *                              照读、不再确认）；PRJ_054 Demo 版本不存在
     */
    public DemoVersionSummaryResponse confirm(Long projectId, int versionNo) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        DemoVersion version = demoVersions
                .findByProjectIdAndVersionNo(project.getId(), versionNo)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.DEMO_VERSION_NOT_FOUND));
        project.confirmDemoVersion(versionNo);
        projectRepository.save(project);
        return toSummary(project, version);
    }

    // ---------- 内部 ----------

    /** 下一版成版（查最新＋1——单行取件不拉全表，同项目单在途 run 串行无并发分配面）。 */
    private DemoVersion nextVersion(Project project, String runId, Integer prdVersionNo) {
        int nextVersionNo = demoVersions
                .findFirstByProjectIdOrderByVersionNoDesc(project.getId())
                .map(DemoVersion::getVersionNo).map(no -> no + 1).orElse(1);
        return DemoVersion.built(project.getId(), nextVersionNo, prdVersionNo, runId);
    }

    /** 清单形（确认标记投影：仅指针所在版带确认时间）。 */
    private DemoVersionSummaryResponse toSummary(Project project, DemoVersion version) {
        boolean confirmed = Integer.valueOf(version.getVersionNo())
                .equals(project.getConfirmedDemoVersionNo());
        return new DemoVersionSummaryResponse(project.getId().toString(), version.getVersionNo(),
                version.getPrdVersionNo(), version.getRunId(), version.getCreatedAt(),
                confirmed ? project.getConfirmedDemoAt() : null);
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
