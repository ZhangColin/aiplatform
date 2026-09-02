package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.agentscope.DockerExecFilesystem;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 工作区文件树只读工具（助理职能体资产，#47）：交付文件视图的清单——与文件模式
 * 端点同一口径（{@link ProjectFiles#listCommand}：源头剪枝非交付物，机密与巨树
 * 不进清单）。纯读：docker exec 只跑 find，无任何写面；工具面 readOnly。
 *
 * <p>仅随项目只读工作区注册（{@link RoleToolkitSupplier}——ASSISTANT 角色不含
 * 内核文件/shell 工具，本工具即其文件面）。执行体阻塞（docker exec），框架
 * ToolExecutor 缺省 boundedElastic 调度，阻塞安全。</p>
 */
public class ListWorkspaceFilesTool extends ToolBase {

    public static final String NAME = "list_workspace_files";

    private final DockerExecFilesystem.ExecCommand exec;

    public ListWorkspaceFilesTool(String containerName) {
        this(new DockerExecFilesystem.DockerExecCommand(containerName,
                AgentWorkspace.ProjectDev.CONTAINER_ROOT));
    }

    /** 执行缝注入构造（单测替身 ExecCommand）。 */
    ListWorkspaceFilesTool(DockerExecFilesystem.ExecCommand exec) {
        super(ToolBase.builder()
                .name(NAME)
                .description("列出项目工作区的交付文件清单（路径 + 字节大小，按路径排序）。"
                        + "查证项目里有什么文件（说明文档、初始数据代码等）时调用；"
                        + "机密（.env）、数据目录与依赖目录不在清单内。")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .readOnly(true)
                .concurrencySafe(true));
        this.exec = exec;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 只读查证是答疑协议的预期动作，工具点不放确认
        return Mono.just(PermissionDecision.allow("只读列文件，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        DockerExecFilesystem.ExecOutput out = exec.run(ProjectFiles.listCommand(), null);
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
                ? "工作区暂无交付文件。"
                : listing.toString()));
    }
}
