package com.semgravio.aiplatform.business.project.application;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.model.ProjectFiles;

/**
 * 收口探针单点（#35/#339 起的收口判据前两件，#427 抽出共享——生成轨片收口、fork
 * 片收口、合并裁决与整轨终验共用）：8081 服务探活（容器内 curl，exitCode 0＝有
 * HTTP 应答）＋预览入口文件完整（缺失或含「其余代码略」式截断占位即残缺）。不可
 * 达/残缺抛 {@link IllegalStateException}，被共用尝试环当作该次尝试失败（走重试/
 * 终态路径）。探针命令常量正本在 {@link GenerationAppService}（#35/#339 落位点）。
 */
@Component
class ClosingProbes {

    private final WorkspaceLifecycleAppService workspaceLifecycle;

    ClosingProbes(WorkspaceLifecycleAppService workspaceLifecycle) {
        this.workspaceLifecycle = workspaceLifecycle;
    }

    /** 8081 可达核验（收口判据首件）：不可达即抛（驱动尝试环重试/终态）。 */
    void requireReachable(Long workspaceId) {
        ExecResultResponse result = workspaceLifecycle.exec(Long.toString(workspaceId),
                new WorkspaceExecCommand(GenerationAppService.CLOSING_PROBE));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("8081 不可达（curl 退出码 " + result.exitCode() + "）");
        }
    }

    /**
     * 入口文件完整性核验（收口判据第二件）：预览入口文件（基座首页）取件失败（缺失）
     * 或内容含截断占位即判残缺——8081 探活是「服务在跑」，本判据是「首屏写全」。
     */
    void requireEntryFileComplete(Long workspaceId) {
        ExecResultResponse result = workspaceLifecycle.exec(Long.toString(workspaceId),
                new WorkspaceExecCommand(GenerationAppService.ENTRY_FILE_PROBE));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("入口文件缺失（" + ProjectFiles.BASELINE_ENTRY
                    + " 取件失败，退出码 " + result.exitCode() + "）");
        }
        if (ProjectFiles.hasTruncationPlaceholder(result.stdout())) {
            throw new IllegalStateException("入口文件残缺（" + ProjectFiles.BASELINE_ENTRY
                    + " 含「其余代码略」式截断占位，未一次写全）");
        }
    }
}
