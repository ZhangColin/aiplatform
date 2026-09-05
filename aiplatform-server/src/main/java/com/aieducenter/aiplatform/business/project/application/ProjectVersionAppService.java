package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.ConversationEntry;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ConversationEntryKind;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.WorkspaceVersion;
import com.aieducenter.aiplatform.business.project.domain.model.WorkspaceVersions;
import com.aieducenter.aiplatform.business.project.domain.repository.ConversationEntryRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 版本用例（#91 版本层地基）：每轮 run 收口自动成版（容器内 git，版本正本 =
 * git log，无便利表）+ 版本列表/详情读面。
 *
 * <p><b>成版写口</b>：{@link #commitAtClosing} 由编码 run 尝试环的真收口块调用
 * （生成与更新同环；失败轮不到真收口、天然不成版）。失败 quietly 只记日志——
 * 照 {@code ConversationHistoryAppService.recordClosing} 先例，成版成败不绑架
 * run 收口（缺 version 键即本轮未成版，用户面无感，排查走日志）。</p>
 *
 * <p><b>读面</b>：git log 实时读取（exec 不经业务事务，同 {@code ProjectQueryAppService}
 * 形制）；详情锚定收尾卡——版本的 Run-Id trailer 联接对话史 closing 条目（#89
 * 同载荷复用）。</p>
 */
@Service
@Slf4j
public class ProjectVersionAppService {

    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final ConversationEntryRepository entries;

    public ProjectVersionAppService(ProjectRepository projectRepository,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            ConversationEntryRepository entries) {
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.entries = entries;
    }

    /**
     * 收口自动成版：幂等 init（存量工作区与容器重建自愈同覆盖）→ 全量提交携
     * Run-Id trailer（锚定收尾卡）。
     *
     * @return 成版 commit hash；成版失败返回 null（quietly——不绑架 run 收口）
     */
    public String commitAtClosing(Project project, String runId, String summary) {
        String workspaceId = Long.toString(project.getWorkspaceId());
        try {
            execOrThrow(workspaceId, WorkspaceVersions.ensureRepoCommand(), "仓库初始化");
            ExecResultResponse commit =
                    execOrThrow(workspaceId, WorkspaceVersions.commitCommand(summary, runId), "成版提交");
            return commit.stdout().trim();
        } catch (RuntimeException e) {
            log.error("[version] 项目 {} 收口成版失败（runId={}，run 收口不受影响）：{}",
                    project.getId(), runId, e.toString());
            return null;
        }
    }

    /**
     * 版本序列（git log 原生序：新→旧）。零版本（尚无收口成版）= 空列表非错误。
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
     * 版本详情：版本元数据 + 锚定的收尾卡载荷（Run-Id 联接对话史 closing 条目；
     * 收尾卡缺位时 closing 为 null——落库缺口不遮版本事实）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_028 版本不存在（含非
     *                              hash 形态 ref——用户可控入参不进 shell）；
     *                              WSP_002 环境故障
     */
    public VersionDetailResponse detail(Long projectId, String ref) {
        Project project = loadProject(projectId);
        WorkspaceVersion version = resolveVersion(project, ref);
        Map<String, Object> closing = entries
                .findFirstByProjectIdAndRunIdAndKindOrderByIdDesc(
                        projectId, version.runId(), ConversationEntryKind.CLOSING)
                .map(ConversationEntry::getClosing)
                .orElse(null);
        return VersionDetailResponse.of(version, closing);
    }

    /**
     * 校验 ref 是可查看的成版 commit（存在 + hex 形态）；不存在抛 PRJ_028。供
     * 「查看当时」（#92）等版本动作的寻址守卫——用户可控入参不进 shell。
     */
    public void requireVersion(Long projectId, String ref) {
        requireVersion(loadProject(projectId), ref);
    }

    /** 同上，但复用调用方已加载的聚合（避免查看编排的二次查库）。 */
    public void requireVersion(Project project, String ref) {
        resolveVersion(project, ref);
    }

    /** ref → 成版 commit 元数据（showCommand + 解析）；非成版 / 野 commit / 非 hex 一律 PRJ_028。 */
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
            // rev-parse 过了但条目被过滤 = 野 commit（无 Run-Id trailer）——不是版本
            throw new ApplicationException(ProjectMessage.VERSION_NOT_FOUND);
        }
        return parsed.get(0);
    }

    /** exec 通道出口（命令构造归 {@link WorkspaceVersions} 纯函数）。 */
    private ExecResultResponse exec(Project project, String command) {
        return workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(command));
    }

    /** 成版写口的 exec：结果缺失/非零即失败（由 {@link #commitAtClosing} quietly 兜住）。 */
    private ExecResultResponse execOrThrow(String workspaceId, String command, String what) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                workspaceId, new WorkspaceExecCommand(command));
        if (result == null || result.exitCode() != 0) {
            throw new IllegalStateException(what + "失败: "
                    + (result == null ? "exec 无结果" : result.stderr()));
        }
        return result;
    }

    private Project loadProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
