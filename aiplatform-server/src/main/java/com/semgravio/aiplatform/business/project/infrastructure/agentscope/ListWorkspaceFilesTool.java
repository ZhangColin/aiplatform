package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.semgravio.aiplatform.base.agentscope.DockerExecFilesystem;
import com.semgravio.aiplatform.base.agentscope.ReadinessAwaitingExecCommand;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceReadinessWaiter;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.business.project.domain.model.ProjectFiles;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 工作区文件树只读工具（主智能体答询资产，#47 → #86 并轨）：交付文件视图的
 * 清单——与文件模式端点同一口径（{@link ProjectFiles#listCommand}：源头剪枝
 * 非交付物，机密与巨树不进清单）。纯读：docker exec 只跑 find，无任何写面；
 * 工具面 readOnly。
 *
 * <p>仅随项目只读工作区注册（{@link ProfileToolkitSupplier}——只读形态已关
 * 内核文件/shell 工具，本工具即其文件面）。执行体阻塞（docker exec），框架
 * ToolExecutor 缺省 boundedElastic 调度，阻塞安全。</p>
 */
public class ListWorkspaceFilesTool extends ToolBase {

    public static final String NAME = "list_workspace_files";

    private final DockerExecFilesystem.ExecCommand exec;
    /** 对话面只读视图（#622）：清单含 .env 与 seed/脚本、只排 data/pg。 */
    private final boolean dialogPlane;

    public ListWorkspaceFilesTool(String workspaceId, String containerName,
            WorkspaceReadinessWaiter readinessWaiter) {
        this(workspaceId, containerName, readinessWaiter, false);
    }

    /** 对话面形态（#622）：生成后问答查证面——清单含 .env 与 seed/脚本。 */
    public ListWorkspaceFilesTool(String workspaceId, String containerName,
            WorkspaceReadinessWaiter readinessWaiter, boolean dialogPlane) {
        this(ReadinessAwaitingExecCommand.dockerExec(containerName,
                WorkspaceId.of(workspaceId), readinessWaiter), dialogPlane);
    }

    /** 执行缝注入构造（单测替身 ExecCommand）。 */
    ListWorkspaceFilesTool(DockerExecFilesystem.ExecCommand exec) {
        this(exec, false);
    }

    ListWorkspaceFilesTool(DockerExecFilesystem.ExecCommand exec, boolean dialogPlane) {
        super(ToolBase.builder()
                .name(NAME)
                .description(dialogPlane
                        ? "列出项目工作区的文件清单（路径 + 字节大小，按路径排序）。"
                                + "查证项目里有什么文件（说明文档、初始数据种子、配置等）时调用；"
                                + ".env 与 seed/脚本可见可读；pg 数据目录（data/pg）、"
                                + "依赖目录（node_modules）、版本库（.git）不在清单内。"
                        : "列出项目工作区的交付文件清单（路径 + 字节大小，按路径排序）。"
                                + "查证项目里有什么文件（说明文档、初始数据代码等）时调用；"
                                + "机密（.env）、数据目录与依赖目录不在清单内。")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .readOnly(true)
                .concurrencySafe(true));
        this.exec = exec;
        this.dialogPlane = dialogPlane;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 只读查证是答疑协议的预期动作，工具点不放确认
        return Mono.just(PermissionDecision.allow("只读列文件，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        DockerExecFilesystem.ExecOutput out = exec.run(
                dialogPlane ? ProjectFiles.dialogListCommand() : ProjectFiles.listCommand(), null);
        if (!out.ok()) {
            return Mono.just(ToolResultBlock.error("文件清单读取失败（容器不可达？）: "
                    + out.stderr()));
        }
        StringBuilder listing = new StringBuilder();
        for (ProjectFiles.Entry entry : ProjectFiles.parseEntries(
                new String(out.stdout(), StandardCharsets.UTF_8))) {
            listing.append(entry.path()).append("（").append(entry.size()).append(" 字节）\n");
        }
        return Mono.just(ToolResultBlock.text(listing.isEmpty()
                ? (dialogPlane ? "工作区暂无文件。" : "工作区暂无交付文件。")
                : listing.toString()));
    }
}
