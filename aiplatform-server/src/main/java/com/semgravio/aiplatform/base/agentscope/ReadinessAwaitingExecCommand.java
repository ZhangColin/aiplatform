package com.semgravio.aiplatform.base.agentscope;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceReadinessWaiter;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 就绪感知的 docker exec 执行缝：主智能体工作区工具（savePrd / list_workspace_files /
 * read_workspace_file）的执行装饰——每次执行前先经 {@link WorkspaceReadinessWaiter}
 * 等工作区就绪（置备中的工作区隐式等待，#62 同口径），READY 才下放真实
 * {@link DockerExecFilesystem.DockerExecCommand}。
 *
 * <p>动机：工作区创建是「点创建即对话」的异步置备（#58/#61），REST exec 面早有
 * 就绪等待，而主智能体工具面此前拿容器名裸 docker exec——访谈答得快时 savePrd
 * 在容器还没建出来前执行，报「No such container」且重试照样失败（2026-11-13 本地
 * 实录：置备因 dev 镜像现场构建拉长到 2m23s，savePrd 三连败判访谈悬空，用户被迫
 * 说「继续」才收口）。</p>
 *
 * <p>等待失败（置备 FAILED / 超时 / 记录不存在）<b>不抛出</b>：以退出码
 * {@link #AWAIT_FAILED_EXIT_CODE}（125，docker daemon 不可达族码位——工具面据此报
 * 「容器不可达 / 写入失败」）+ 归一化文案（错误码 + message）回 fail 结果，与
 * {@link DockerExecFilesystem}「容器不在即以 fail 结果如实暴露，不抛出」同口径。
 * 执行体阻塞（轮询等待），框架 ToolExecutor 缺省 boundedElastic 调度，阻塞安全。</p>
 */
public final class ReadinessAwaitingExecCommand implements DockerExecFilesystem.ExecCommand {

    /** 等待失败（置备败/超时/无记录）的合成退出码：docker daemon 不可达族码位。 */
    static final int AWAIT_FAILED_EXIT_CODE = 125;

    private final DockerExecFilesystem.ExecCommand delegate;
    private final WorkspaceReadinessWaiter readinessWaiter;
    private final WorkspaceId workspaceId;

    public ReadinessAwaitingExecCommand(DockerExecFilesystem.ExecCommand delegate,
            WorkspaceReadinessWaiter readinessWaiter, WorkspaceId workspaceId) {
        this.delegate = delegate;
        this.readinessWaiter = readinessWaiter;
        this.workspaceId = workspaceId;
    }

    /** 真实 docker exec 的就绪感知形态（主智能体工作区工具装配口）。 */
    public static DockerExecFilesystem.ExecCommand dockerExec(String containerName,
            WorkspaceId workspaceId, WorkspaceReadinessWaiter readinessWaiter) {
        return new ReadinessAwaitingExecCommand(
                new DockerExecFilesystem.DockerExecCommand(containerName,
                        AgentWorkspace.ProjectDev.CONTAINER_ROOT),
                readinessWaiter, workspaceId);
    }

    @Override
    public DockerExecFilesystem.ExecOutput run(String command, byte[] stdin) {
        String failure = awaitFailureOrNull();
        return failure != null
                ? new DockerExecFilesystem.ExecOutput(AWAIT_FAILED_EXIT_CODE, new byte[0], failure)
                : delegate.run(command, stdin);
    }

    @Override
    public DockerExecFilesystem.ExecOutput run(String command, byte[] stdin, int timeoutSeconds) {
        String failure = awaitFailureOrNull();
        return failure != null
                ? new DockerExecFilesystem.ExecOutput(AWAIT_FAILED_EXIT_CODE, new byte[0], failure)
                : delegate.run(command, stdin, timeoutSeconds);
    }

    /** 就绪即回 null 下放执行；等待失败归一化为「错误码：文案」（fail 结果载体）。 */
    private String awaitFailureOrNull() {
        try {
            readinessWaiter.awaitReadyById(workspaceId);
            return null;
        }
        catch (ApplicationException e) {
            return e.getCodeMessage().code() + "：" + e.getMessage();
        }
    }
}
