package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.project.application.dto.response.PrdReadinessResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.PrdVersionResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.PrdVersionSummaryResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.PrdVersion;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.PrdVersionRepository;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * PRD 版本用例（<b>去门化 #462，ADR-0039</b>——旧「确认即成版/两级门/停驻待确认」
 * 整段随确认门退役：confirm 端点、PRJ_053 拦截、dispatchFirstDemoOnConfirm 触发、
 * parkDraftChange/consumeParkedDraftChange 停驻读法全部移除；停驻两列留库不删、
 * 聚合写方法随读法退役一并注销，Flyway 只增不改）。现行机制：
 *
 * <p><b>版本表去门化</b>：{@code prj_prd_versions} 只增不改——Demo 类 run（首构建/
 * 修正重建）起跑自动记行（{@link #recordOnDemoRunStart}，快照当时工作区 PRD 全文
 * ＋来源 run_id），版本号＝<b>构建基准序</b>（第 N 个构建基准快照）。记行幂等语义
 * 保留：成版判定正本＝快照全文比对——内容与最新版一致（同文）即成行跳过、不产生
 * 新行（锚随每次 savePrd 刷新，同文重存锚漂不成新行，故不拿锚做判定）。全文来源
 * ＝PRD 读端点同缝（{@link ProjectQueryAppService#prd}——PRJ_015 未产出/WSP_002
 * 环境故障口径复用，读源单一）。
 *
 * <p><b>事务形制</b>（沿确认制时代 confirmSnapshot 同款）：docker exec（PRD 读取）
 * 在事务外；判定＋插入收进短事务；并发撞 (project_id, version_no) 唯一约束时败者
 * 回读比对——内容一致＝同一基准的重复记行，幂等跳过。记行 best-effort：调用侧是
 * run 起跑的次生事实，失败只记日志不反噬 run（版本缺位由下轮起跑补位，Demo 版本
 * 锚捕获读最新版随之顺延）。</p>
 *
 * <p><b>读面</b>：版本清单/版本详情归档项目照读（历史可追溯）；版本详情附收敛度
 * 投影（对快照全文即时解析，草稿后续改动不污染历史版本读面）。四处消费「最新已
 * 确认版」的读法统一改读「最新版」（本表最新行，语义顺延、比对逻辑不动）：
 * 迭代基准（{@code PrdDraftBaseline} 组件随停派退役一并注销，基准恒工作区正本）、
 * 下单冻结（{@link ProjectQueryAppService#latestVersionRefs}）、归档沉淀取版
 * （{@code OrderAppService#archivePrdContent}，经 latestVersionRefs 同口）。</p>
 */
@Service
@Slf4j
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
     * Demo 类 run 起跑自动记行（去门化 #462）：快照当时工作区 PRD 全文为构建基准
     * （版本号＝构建基准序）＋来源 run_id；同文（内容＝最新版）幂等跳过。PRD 读不出
     * （未产出/环境故障）如实跳过记日志——记行是 run 起跑的次生事实，不反噬 run；
     * 唯一约束竞态败者回读比对，同文即幂等返回。
     *
     * @param runId 起跑 run（来源锚——落版本行 run_id 列）
     */
    public void recordOnDemoRunStart(Long projectId, String runId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            log.warn("[prd-version] 项目 {} 记版时记录已不存在——跳过记行", projectId);
            return;
        }
        final String draft;
        try {
            draft = projectQueryAppService.prd(projectId).content();
        }
        catch (RuntimeException e) {
            log.warn("[prd-version] 项目 {} run {} 起跑记行但 PRD 读不出（{}）——跳过"
                    + "（下轮起跑补位）", projectId, runId, e.toString());
            return;
        }
        snapshot(project, draft, runId);
    }

    /** 成行（短事务：最新版比对→同文跳过或插新行；唯一约束竞态败者幂等回读）。 */
    private void snapshot(Project project, String draft, String runId) {
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                List<PrdVersion> existing =
                        prdVersions.findByProjectIdOrderByVersionNoDesc(project.getId());
                if (!existing.isEmpty() && existing.get(0).getContent().equals(draft)) {
                    return; // 同文＝同一构建基准（全文比对正本，锚不作数）——幂等跳过
                }
                int nextVersionNo = existing.isEmpty() ? 1 : existing.get(0).getVersionNo() + 1;
                // saveAndFlush：把唯一约束违反压进事务内（翻译可靠，catch 面确定性）
                prdVersions.saveAndFlush(PrdVersion.recorded(
                        project.getId(), nextVersionNo, draft,
                        LocalDateTime.now(), project.getPrdProducedAt(), runId));
            });
        }
        catch (DataIntegrityViolationException e) {
            boolean duplicated = prdVersions.findByProjectIdOrderByVersionNoDesc(project.getId())
                    .stream().findFirst()
                    .filter(latest -> latest.getContent().equals(draft))
                    .isPresent();
            if (!duplicated) {
                throw e; // 非本约束的完整性违例不冒名，原样上抛
            }
            log.info("[prd-version] 项目 {} 并发记行撞唯一约束，败者回读同文——幂等跳过",
                    project.getId());
        }
    }

    /**
     * PRD 版本清单（version_no 倒序——最新在前；空 = 尚无构建基准记行，草稿态呈现
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
     * 历史版本的读面）。归档项目照读。
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
