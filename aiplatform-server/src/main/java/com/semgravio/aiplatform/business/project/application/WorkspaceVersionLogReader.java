package com.semgravio.aiplatform.business.project.application;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersion;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersions;

/**
 * 用户面版本日志读口（#641 抽取）：git log 即版本正本，读命令构造与解析单点在
 * {@link WorkspaceVersions}，本类只做 exec 通道调用与退出码映射——版本列表读面
 * （{@link ProjectVersionAppService}）与事后提炼窗口读取共用，避免两处重复。
 *
 * <p>退出码口径照原版：零版本（无 HEAD）＝空列表非错误；其余非零＝WSP_002 环境
 * 故障。</p>
 */
@Component
public class WorkspaceVersionLogReader {

    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public WorkspaceVersionLogReader(WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * 读该项目全部用户面版本（git 原生序：新→旧）。
     *
     * @throws ApplicationException WSP_002 环境故障（exec 无结果/非零非 3）
     */
    public List<WorkspaceVersion> read(Project project) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()),
                new WorkspaceExecCommand(WorkspaceVersions.listCommand()));
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
}
