package com.semgravio.aiplatform.business.project.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersion;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersions;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 版本用例（#91 版本层地基 + #93 回滚）：版本正本 = git log，无便利表；版本
 * 列表/详情读面 + 回滚到此（追加版本、只回代码不回数据）。
 *
 * <p><b>成版写口已随审查与收口组退役（#605）</b>：run 收口自动成版（Run-Id
 * trailer）与设计定稿成版提交一并出局——版本语义归阶段四重建（单轴时间线、
 * 探活通过即自动成版）；本类保留读面与回滚写口（存量 Rollback-From 版本仍可
 * 回），不新增版本。</p>
 *
 * <p><b>回滚写口</b>：{@link #rollback} 由用户显式触发，失败如实上抛（用户面
 * 动作，不 quietly 吞），走环境故障 WSP_002。</p>
 *
 * <p><b>读面</b>：git log 实时读取（exec 不经业务事务，同 {@code ProjectQueryAppService}
 * 形制）；详情锚定收尾卡（版本 runId 联接对话史 closing 条目——#605 后新版本
 * 无 run，closing 为空）。</p>
 */
@Service
@Slf4j
public class ProjectVersionAppService {

    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final CodingRunTrack codingRunTrack;

    public ProjectVersionAppService(ProjectRepository projectRepository,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            CodingRunTrack codingRunTrack) {
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.codingRunTrack = codingRunTrack;
    }

    /**
     * 版本序列（git log 原生序：新→旧）。零版本 = 空列表非错误。
     *
     * @throws ApplicationException PRJ_001 项目不存在；WSP_002 环境故障
     */
    public List<VersionResponse> list(Long projectId) {
        Project project = loadProject(projectId);
        ExecResultResponse result = exec(project, WorkspaceVersions.listCommand());
        if (result.exitCode() == WorkspaceVersions.EXIT_NO_VERSION) {
            return List.of();
        }
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "版本列表读取失败: " + result.stderr());
        }
        return WorkspaceVersions.parseLog(result.stdout()).stream()
                .map(VersionResponse::of)
                .toList();
    }

    /**
     * 版本详情：版本元数据（closing 恒空——Version 与收尾卡的 Run-Id 联接随
     * 收口成版提交退役，#605；字段留至阶段四版本重建）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_028 版本不存在（含非
     *                              hash 形态 ref——用户可控入参不进 shell）；
     *                              WSP_002 环境故障
     */
    public VersionDetailResponse detail(Long projectId, String ref) {
        Project project = loadProject(projectId);
        return VersionDetailResponse.of(resolveVersion(project, ref), null);
    }

    /**
     * 校验 ref 是可查看的成版 commit（存在 + hex 形态）；不存在抛 PRJ_028。供
     * 「查看当时」（#92）等版本动作的寻址守卫——用户可控入参不进 shell。
     */
    public void requireVersion(Long projectId, String ref) {
        requireVersion(loadProject(projectId), ref);
    }

    /**
     * 回滚到此（#93）：把工作区系统代码复位到目标版本的树、追加为一个新版本
     * （历史只追加不改写），数据不在此跟踪面故原样保留（回滚只回代码不回数据）。
     * 目标版本经寻址守卫（{@link #requireVersion}）——非成版 / 非 hex 一律 404
     * 且不触工作区；再经并发守卫（#100 {@link #requireRollbackSafe}）——在途 run
     * 或脏工作树 409 拒绝。失败如实上抛（用户面动作）。
     *
     * @return 追加出的新版本（回滚版本：runId 空、rollbackFrom 锚定源版本）
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_028 版本不存在（含非
     *                              hash 形态 ref）；PRJ_031 在途 run；PRJ_032
     *                              脏工作树；WSP_002 回滚执行环境故障
     */
    public VersionResponse rollback(Long projectId, String ref) {
        Project project = loadProject(projectId);
        WorkspaceVersion target = resolveVersion(project, ref);
        // 并发守卫（#100）：在途 run / 脏工作树 → 拒（回退安全感——不丢在途未提交改动）
        requireRollbackSafe(project);
        String subject = "回滚到「" + target.subject() + "」";
        ExecResultResponse result = exec(project, WorkspaceVersions.rollbackCommand(ref, subject));
        if (result == null || result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "回滚失败: " + (result == null ? "exec 无结果" : result.stderr()));
        }
        // 回读追加出的新版本（正本 = git log）——响应 committedAt 与列表口径一致，不取本地 now
        return VersionResponse.of(resolveVersion(project, result.stdout().trim()));
    }

    /** 同上，但复用调用方已加载的聚合（避免查看编排的二次查库）。 */
    public void requireVersion(Project project, String ref) {
        resolveVersion(project, ref);
    }

    /** ref → 版本 commit 元数据（showCommand + 解析）；非版本 / 野 commit / 非 hex 一律 PRJ_028。 */
    private WorkspaceVersion resolveVersion(Project project, String ref) {
        ExecResultResponse result;
        try {
            result = exec(project, WorkspaceVersions.showCommand(ref));
        } catch (IllegalArgumentException malformedRef) {
            throw new ApplicationException(ProjectMessage.VERSION_NOT_FOUND);
        }
        if (result.exitCode() == WorkspaceVersions.EXIT_NO_VERSION) {
            throw new ApplicationException(ProjectMessage.VERSION_NOT_FOUND);
        }
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "版本详情读取失败: " + result.stderr());
        }
        List<WorkspaceVersion> parsed = WorkspaceVersions.parseLog(result.stdout());
        if (parsed.isEmpty()) {
            // rev-parse 过了但条目被过滤 = 野 commit（无 Rollback-From trailer）——不是版本
            throw new ApplicationException(ProjectMessage.VERSION_NOT_FOUND);
        }
        return parsed.get(0);
    }

    /**
     * 回滚前并发守卫（#100）：在途编码 run（生成/修正——工作区正被 run 执行体读写）
     * 或脏工作树（未提交 tracked 改动，含失败 run 残留）一律 409 拒绝——回退安全感：
     * 回滚不静默丢弃在途未提交改动。在途检查是进程内标记（无 exec）；脏树检查
     * {@code git status --porcelain} 兜「失败 run 残留脏树但无在途标记」的边界。
     */
    private void requireRollbackSafe(Project project) {
        if (codingRunTrack.isInFlight(project.getId())) {
            throw new ApplicationException(ProjectMessage.VERSION_ROLLBACK_RUN_IN_FLIGHT);
        }
        ExecResultResponse status = exec(project, WorkspaceVersions.statusCommand());
        if (status == null || status.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "回滚前工作树检查失败: " + (status == null ? "exec 无结果" : status.stderr()));
        }
        if (WorkspaceVersions.hasUncommittedTrackedChanges(status.stdout())) {
            throw new ApplicationException(ProjectMessage.VERSION_ROLLBACK_DIRTY_TREE);
        }
    }

    /** exec 通道出口（命令构造归 {@link WorkspaceVersions} 纯函数）。 */
    private ExecResultResponse exec(Project project, String command) {
        return workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(command));
    }

    private Project loadProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
