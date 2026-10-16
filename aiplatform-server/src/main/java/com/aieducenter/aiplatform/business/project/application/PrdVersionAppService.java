package com.aieducenter.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.application.dto.response.PrdVersionResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdVersionSummaryResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.PrdVersion;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.PrdVersionRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

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
 * （「库唯一约束＋服务端事务内判定」防重同款，生产承接先例）。生成触发门
 * （确认后自动派 Demo run）挂 #332——本用例确认成功返回版本号即干净挂点。</p>
 *
 * <p>版本行不可变＝只插不改：本用例无任何历史行更新路径；「快照不可变」的
 * 行为面等价物＝确认后改草稿再读历史版本，原文不动（集测钉）。</p>
 */
@Service
public class PrdVersionAppService {

    private final ProjectRepository projectRepository;
    private final ProjectQueryAppService projectQueryAppService;
    private final PrdVersionRepository prdVersions;
    private final TransactionTemplate transactionTemplate;

    public PrdVersionAppService(ProjectRepository projectRepository,
            ProjectQueryAppService projectQueryAppService,
            PrdVersionRepository prdVersions,
            TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.projectQueryAppService = projectQueryAppService;
        this.prdVersions = prdVersions;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 确认此版 PRD：工作区 PRD 全文冻结成版（重复确认无新草稿＝幂等返回既有版）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档（对话区
     *                              关闭即全停，历史版本照读）；PRJ_015 PRD 尚未
     *                              产出（产出锚未落或工作区无 docs/PRD.md）；
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
        String draft = projectQueryAppService.prd(projectId).content();
        return confirmSnapshot(project, draft);
    }

    /** 成版（短事务：最新版比对→幂等返回或插新行；唯一约束竞态败者幂等回读）。 */
    private PrdVersionResponse confirmSnapshot(Project project, String draft) {
        try {
            return transactionTemplate.execute(tx -> {
                List<PrdVersion> existing =
                        prdVersions.findByProjectIdOrderByVersionNoDesc(project.getId());
                if (!existing.isEmpty() && existing.get(0).getContent().equals(draft)) {
                    return toResponse(project.getId(), existing.get(0));
                }
                int nextVersionNo = existing.isEmpty() ? 1 : existing.get(0).getVersionNo() + 1;
                // saveAndFlush：把唯一约束违反压进事务内（翻译可靠，catch 面确定性）
                PrdVersion saved = prdVersions.saveAndFlush(PrdVersion.confirmed(
                        project.getId(), nextVersionNo, draft,
                        LocalDateTime.now(), project.getPrdProducedAt()));
                return toResponse(project.getId(), saved);
            });
        }
        catch (DataIntegrityViolationException e) {
            return prdVersions.findByProjectIdOrderByVersionNoDesc(project.getId()).stream()
                    .findFirst()
                    .filter(latest -> latest.getContent().equals(draft))
                    .map(latest -> toResponse(project.getId(), latest))
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
     * 版本详情（含全文快照）。归档项目照读。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_052 版本不存在
     */
    public PrdVersionResponse version(Long projectId, int versionNo) {
        Project project = requireProject(projectId);
        return toResponse(project.getId(), prdVersions
                .findByProjectIdAndVersionNo(project.getId(), versionNo)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PRD_VERSION_NOT_FOUND)));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

    private PrdVersionResponse toResponse(Long projectId, PrdVersion version) {
        return new PrdVersionResponse(projectId.toString(), version.getVersionNo(),
                version.getContent(), version.getConfirmedAt());
    }
}
