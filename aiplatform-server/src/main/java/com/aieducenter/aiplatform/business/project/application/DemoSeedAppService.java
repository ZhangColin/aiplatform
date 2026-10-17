package com.aieducenter.aiplatform.business.project.application;

import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;

import lombok.extern.slf4j.Slf4j;

/**
 * 演示种子数据预置（#340）：Demo 将可见前一刻把「通用示例集」确定性补进沙箱真库
 * ——镜像内 {@code /opt/seed/seed.sql} 按列形合成（空表才补、已有数据不碰），
 * 非模型义务化。施用入口 {@code /opt/seed/apply.sh} 三处共用：容器自愈
 * （init-workspace.sh）、应用拉起（appStartCommand）、run 收口（本服务——首跑的
 * 表在收口前才成形，纯起跑点注不进首跑）。
 *
 * <p>尽力而为面：失败只记日志不抛——种子是体验增强面，绝不阻断收口/起跑（票面
 * AC）；幂等性由引擎保证（只补空表），重复施用安全。速度触发器留档（票面）：实测
 * 落库对 Demo 速度影响非常大时再议降形态方案。</p>
 */
@Service
@Slf4j
public class DemoSeedAppService {

    /** 种子施用命令（镜像内幂等入口 {@link WorkspaceLayout#SEED_APPLY_ENTRY}——路径契约单点，与 appStartCommand 共用防漂移）。 */
    static final String SEED_COMMAND = "sh " + WorkspaceLayout.SEED_APPLY_ENTRY;

    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public DemoSeedAppService(WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * 对项目沙箱施用演示种子（尽力而为）：exec 失败或退出码非 0 只记日志——
     * 幂等引擎下缺位可由下次施用（迭代收口/容器自愈）自愈补位。
     */
    public void seedSandbox(Project project) {
        try {
            ExecResultResponse result = workspaceLifecycleAppService.exec(
                    Long.toString(project.getWorkspaceId()),
                    new WorkspaceExecCommand(SEED_COMMAND));
            if (result != null && result.exitCode() != 0) {
                log.warn("[seed] 项目 {} 演示种子预置退出码 {}（不阻断）：{}",
                        project.getId(), result.exitCode(), result.stderr());
            }
        }
        catch (RuntimeException e) {
            log.warn("[seed] 项目 {} 演示种子预置失败（不阻断）：{}",
                    project.getId(), e.toString());
        }
    }
}
