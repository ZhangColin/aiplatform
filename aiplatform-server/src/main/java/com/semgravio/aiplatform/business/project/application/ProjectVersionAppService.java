package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
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
 * 版本用例（#91 版本层地基 + #93 回滚 + #626 自动成版／单轴时间线）：版本正本 =
 * git log，无便利表；版本列表/详情读面 + 回滚到此（追加版本、只回代码不回数据）
 * + 用户面版本自动成版写口。
 *
 * <p><b>自动成版（#626，ADR-0056）</b>：每轮成功生成（首建/迭代）探活通过后，界面
 * 侧经 {@link #commitUserVersion} 把当时工作区提交为用户面版本（{@code Platform-Version}
 * ＋{@code Run-Id} trailer，主题＝用户那句话）；版本序列＝按标记过滤的 git log，
 * 无新表。成版失败降级（日志留痕、不反噬生成）——下一轮成功交付覆盖累计现状。</p>
 *
 * <p><b>回滚写口</b>：{@link #rollback} 由用户显式触发，失败如实上抛（用户面
 * 动作，不 quietly 吞），走环境故障 WSP_002。</p>
 *
 * <p><b>读面</b>：git log 实时读取（exec 不经业务事务，同 {@code ProjectQueryAppService}
 * 形制）；列表首条＝当前版，confirmed 按旧确认指针映射（商业面改接归 #629）。</p>
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
     * 版本序列（git log 原生序：新→旧）。零版本 = 空列表非错误。时间线标位：
     * 首条＝当前版（最新交付）；confirmed 按项目旧确认指针映射版本号（transition
     * ——商业面改接归 #629）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；WSP_002 环境故障
     */
    public List<VersionResponse> list(Long projectId) {
        Project project = loadProject(projectId);
        List<WorkspaceVersion> versions = readVersions(project);
        List<VersionResponse> responses = new ArrayList<>(versions.size());
        for (int index = 0; index < versions.size(); index++) {
            WorkspaceVersion version = versions.get(index);
            responses.add(VersionResponse.of(version, index == 0, confirmedOf(project, version)));
        }
        return responses;
    }

    /**
     * 用户面版本自动成版（#626，ADR-0056）：跑前快照（#619）不是用户版本——每轮
     * 生成探活通过后，平台把当时工作区提交为用户面版本（版本号＝已有版本最大值+1，
     * 主题＝用户那句话，正文携版本标记与 run 标识）。<b>成版失败降级</b>：任何
     * exec 失败/回执非 hash 只记日志返回 null，绝不反噬生成——下一轮成功交付的
     * 提交覆盖累计后的工作区现状，时间线可能少一格但不丢代码。
     *
     * @param project 成版项目（工作区锚）
     * @param runId   成版 run 标识（用户面 runId）
     * @param label   版本主题＝用户那句话（调用方已清洗为单行）
     * @return 新版本号（成功）；失败/降级为 null
     */
    public Integer commitUserVersion(Project project, String runId, String label) {
        try {
            List<WorkspaceVersion> existing = readVersions(project);
            int nextVersionNo = existing.stream()
                    .map(WorkspaceVersion::versionNo)
                    .filter(versionNo -> versionNo != null)
                    .max(Integer::compareTo)
                    .orElse(0) + 1;
            ExecResultResponse result = exec(project,
                    WorkspaceVersions.commitVersionCommand(nextVersionNo, runId, label));
            if (result == null || result.exitCode() != 0) {
                log.warn("[version] 项目 {} run {} 用户面版本提交失败（生成照常，下一次成功交付"
                                + "覆盖累计现状）：{}", project.getId(), runId,
                        result == null ? "exec 无结果" : result.stderr());
                return null;
            }
            log.info("[version] 项目 {} run {} 记录用户面版本 v{}（{}）", project.getId(), runId,
                    nextVersionNo, label);
            return nextVersionNo;
        }
        catch (RuntimeException e) {
            log.warn("[version] 项目 {} 用户面版本提交执行异常（生成照常，下一次成功交付覆盖累计"
                    + "现状）：{}", project.getId(), e.toString());
            return null;
        }
    }

    /** git log 读取（零版本 = 空列表；其它非零 = WSP_002 环境故障）。 */
    private List<WorkspaceVersion> readVersions(Project project) {
        ExecResultResponse result = exec(project, WorkspaceVersions.listCommand());
        if (result == null) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "版本列表读取失败: exec 无结果");
        }
        if (result.exitCode() == WorkspaceVersions.EXIT_NO_VERSION) {
            return List.of();
        }
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "版本列表读取失败: " + result.stderr());
        }
        return WorkspaceVersions.parseLog(result.stdout());
    }

    /** 确认标记映射（#626 过渡）：旧 Demo 确认指针的版本号＝本用户面版本号即已确认。 */
    private static boolean confirmedOf(Project project, WorkspaceVersion version) {
        return version.versionNo() != null
                && version.versionNo().equals(project.getConfirmedDemoVersionNo());
    }

    /**
     * 版本主题清洗（#626）：用户那句话可能含换行/多空白——折成单行再作提交主题；
     * 空即回落（首建「首次生成」/ 迭代「更新系统」）。
     */
    public static String cleanUserSentence(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String cleaned = raw.replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? fallback : cleaned;
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
