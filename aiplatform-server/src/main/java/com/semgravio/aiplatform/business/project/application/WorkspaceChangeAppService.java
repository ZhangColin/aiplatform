package com.semgravio.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceChanges;

import lombok.extern.slf4j.Slf4j;

/**
 * 工作区真实变更用例（#619 跑前快照链 + 收口 diff）：把 {@link WorkspaceChanges}
 * 的命令经 exec 通道送到容器内执行，输出交回纯函数解析。降级是本类的核心语义
 * ——快照或 diff 的任何执行失败（exec 异常 / 非零退出 / 快照回执非 commit hash）
 * 都<b>不升级成生成失败</b>：快照失败返回 null（收口时变更清单标注不可用），diff
 * 失败返回 {@link WorkspaceChanges.ChangeSet#degraded()}。平台日志留痕，用户面只
 * 见「变更清单不可用」，其余字段照常。
 *
 * <p>快照提交不出现在用户面版本列表：提交信息不带 Rollback-From trailer，版本
 * 序列成员判据（{@link com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersions#parseLog}）
 * 天然过滤；本类不落任何库表/列。</p>
 */
@Service
@Slf4j
public class WorkspaceChangeAppService {

    private final WorkspaceLifecycleAppService workspaceLifecycle;

    public WorkspaceChangeAppService(WorkspaceLifecycleAppService workspaceLifecycle) {
        this.workspaceLifecycle = workspaceLifecycle;
    }

    /**
     * 跑前快照：幂等仓库初始化＋全量提交，返回 commit hash 作为本场 diff 基线；
     * 任何失败返回 null（生成照常起跑，收口变更清单降级）。
     */
    public String snapshot(Long workspaceId, String runId) {
        try {
            ExecResultResponse result = exec(workspaceId,
                    WorkspaceChanges.snapshotCommand(runId));
            if (result == null || result.exitCode() != 0) {
                log.warn("[changes] 工作区 {} 跑前快照失败（生成照常，变更清单将降级）：{}",
                        workspaceId, result == null ? "exec 无结果" : result.stderr());
                return null;
            }
            String hash = result.stdout() == null ? "" : result.stdout().trim();
            if (!WorkspaceChanges.isCommitRef(hash)) {
                log.warn("[changes] 工作区 {} 跑前快照回执非 commit hash（变更清单将降级）：{}",
                        workspaceId, hash);
                return null;
            }
            return hash;
        }
        catch (RuntimeException e) {
            log.warn("[changes] 工作区 {} 跑前快照执行异常（生成照常，变更清单将降级）：{}",
                    workspaceId, e.toString());
            return null;
        }
    }

    /**
     * 收口真实 diff：numstat 取文件级 ± 行数；{@code includePatches}（迭代）再取
     * 完整 patch 并按文件切分（数量/大小上限在 {@link WorkspaceChanges#assemble} 内
     * 兑现）。numstat 或 patch 任一执行失败即整体降级为「变更清单不可用」——不把
     * 半份清单当真。
     */
    public WorkspaceChanges.ChangeSet diff(Long workspaceId, String baseline,
            boolean includePatches) {
        if (baseline == null) {
            return WorkspaceChanges.ChangeSet.degraded();
        }
        try {
            ExecResultResponse numstat = exec(workspaceId,
                    WorkspaceChanges.numstatCommand(baseline));
            if (numstat == null || numstat.exitCode() != 0) {
                log.warn("[changes] 工作区 {} 收口 diff 行数读取失败（变更清单降级）：{}",
                        workspaceId, numstat == null ? "exec 无结果" : numstat.stderr());
                return WorkspaceChanges.ChangeSet.degraded();
            }
            List<WorkspaceChanges.Numstat> entries = WorkspaceChanges.parseNumstat(numstat.stdout());
            Map<String, String> patches = Map.of();
            if (includePatches && !entries.isEmpty()) {
                ExecResultResponse patch = exec(workspaceId,
                        WorkspaceChanges.patchCommand(baseline));
                if (patch == null || patch.exitCode() != 0) {
                    log.warn("[changes] 工作区 {} 收口 diff 文本读取失败（变更清单降级）：{}",
                            workspaceId, patch == null ? "exec 无结果" : patch.stderr());
                    return WorkspaceChanges.ChangeSet.degraded();
                }
                patches = WorkspaceChanges.splitPatch(patch.stdout());
            }
            return WorkspaceChanges.assemble(entries, patches, includePatches);
        }
        catch (RuntimeException e) {
            log.warn("[changes] 工作区 {} 收口 diff 执行异常（变更清单降级）：{}",
                    workspaceId, e.toString());
            return WorkspaceChanges.ChangeSet.degraded();
        }
    }

    private ExecResultResponse exec(Long workspaceId, String command) {
        return workspaceLifecycle.exec(Long.toString(workspaceId),
                new WorkspaceExecCommand(command));
    }
}
