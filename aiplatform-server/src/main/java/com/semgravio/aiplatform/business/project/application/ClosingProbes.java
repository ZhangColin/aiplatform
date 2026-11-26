package com.semgravio.aiplatform.business.project.application;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;

/**
 * 收口探针单点（#35 起、#597 内核收口判据双件之一）：8081 服务探活（容器内 curl，
 * exitCode 0＝有 HTTP 应答）——<b>预览转可用的门</b>（ADR-0055 保留项）。不可达抛
 * {@link IllegalStateException}，由收尾判据记为判据红。探针命令常量正本在
 * {@link GenerationAppService}（#35 落位点）。入口文件完整检查已随审查与收口组
 * 退役（#605，ADR-0055：无入口则起服失败，被探活吸收）。
 */
@Component
class ClosingProbes {

    private final WorkspaceLifecycleAppService workspaceLifecycle;

    ClosingProbes(WorkspaceLifecycleAppService workspaceLifecycle) {
        this.workspaceLifecycle = workspaceLifecycle;
    }

    /** 8081 可达核验（收口判据探活项＝预览转可用的门）：不可达即抛。 */
    void requireReachable(Long workspaceId) {
        ExecResultResponse result = workspaceLifecycle.exec(Long.toString(workspaceId),
                new WorkspaceExecCommand(GenerationAppService.CLOSING_PROBE));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("8081 不可达（curl 退出码 " + result.exitCode() + "）");
        }
    }
}
