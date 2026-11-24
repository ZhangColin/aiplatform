package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 工作区文件内容只读工具（主智能体答询资产，#47 → #86 并轨）：读一个交付文件的文本内容——
 * 与文件模式「点看」端点同一口径（{@link ProjectFiles#isViewable} 判定 +
 * {@link ProjectFiles#contentCommand} 容器侧限读：非交付物/机密/逃逸路径一律
 * 拒绝，超 1 MiB 不读取）。输出另设模型上下文护栏（超长截断明示）。
 *
 * <p>纯读：无任何写面，工具面 readOnly；仅随项目只读工作区注册
 * （{@link ProfileToolkitSupplier}）。执行体阻塞（docker exec），框架 ToolExecutor
 * 缺省 boundedElastic 调度，阻塞安全。</p>
 */
public class ReadWorkspaceFileTool extends ToolBase {

    public static final String NAME = "read_workspace_file";

    private static final String PATH_KEY = "path";

    /** 模型上下文护栏：单文件输出字符上限（超长截断明示，防打爆上下文）。 */
    private static final int MAX_OUTPUT_CHARS = 32_000;

    private final DockerExecFilesystem.ExecCommand exec;
    /** 对话面只读视图（#622）：.env 与 seed/脚本可读，data/pg 等仍拒。 */
    private final boolean dialogPlane;

    public ReadWorkspaceFileTool(String workspaceId, String containerName,
            WorkspaceReadinessWaiter readinessWaiter) {
        this(workspaceId, containerName, readinessWaiter, false);
    }

    /** 对话面形态（#622）：生成后问答查证面——.env 与 seed/脚本可读。 */
    public ReadWorkspaceFileTool(String workspaceId, String containerName,
            WorkspaceReadinessWaiter readinessWaiter, boolean dialogPlane) {
        this(ReadinessAwaitingExecCommand.dockerExec(containerName,
                WorkspaceId.of(workspaceId), readinessWaiter), dialogPlane);
    }

    /** 执行缝注入构造（单测替身 ExecCommand）。 */
    ReadWorkspaceFileTool(DockerExecFilesystem.ExecCommand exec) {
        this(exec, false);
    }

    ReadWorkspaceFileTool(DockerExecFilesystem.ExecCommand exec, boolean dialogPlane) {
        super(ToolBase.builder()
                .name(NAME)
                .description(dialogPlane
                        ? "读取项目工作区一个文件的文本内容（path 用 list_workspace_files "
                                + "清单里的相对路径）。查证说明文档、初始数据种子、配置"
                                + "（含 .env 里的连接串/凭据）等文件内容时调用；"
                                + "pg 数据目录（data/pg）、依赖目录、版本库不可读。"
                        : "读取项目工作区一个交付文件的文本内容（path 用 list_workspace_files "
                                + "清单里的相对路径）。查证说明文档、初始数据代码等文件内容时调用；"
                                + "非交付物（.env、data/ 等）不可读。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                PATH_KEY, Map.of(
                                        "type", "string",
                                        "description", "文件树清单里的工作区相对路径")),
                        "required", List.of(PATH_KEY)))
                .readOnly(true)
                .concurrencySafe(true));
        this.exec = exec;
        this.dialogPlane = dialogPlane;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 只读查证是答疑协议的预期动作，工具点不放确认
        return Mono.just(PermissionDecision.allow("只读文件，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Object path = param.getInput() != null ? param.getInput().get(PATH_KEY) : null;
        if (path == null || String.valueOf(path).isBlank()) {
            return Mono.just(ToolResultBlock.error("path 不能为空：传 list_workspace_files 清单里的相对路径"));
        }
        String relative = String.valueOf(path).strip();
        boolean viewable = dialogPlane
                ? ProjectFiles.isDialogViewable(relative)
                : ProjectFiles.isViewable(relative);
        if (!viewable) {
            return Mono.just(ToolResultBlock.error(dialogPlane
                    ? "该路径不可读（pg 数据/依赖/版本库/非法路径）: " + relative
                    : "该路径不可读（非交付物/机密/非法路径）: " + relative));
        }
        DockerExecFilesystem.ExecOutput out = exec.run(dialogPlane
                ? ProjectFiles.dialogContentCommand(relative)
                : ProjectFiles.contentCommand(relative), null);
        if (out.exitCode() == 1) {
            return Mono.just(ToolResultBlock.error("文件不存在: " + relative));
        }
        if (out.exitCode() == 2) {
            return Mono.just(ToolResultBlock.error("文件超过在线查看大小上限（1 MiB），不可读: " + relative));
        }
        if (!out.ok()) {
            return Mono.just(ToolResultBlock.error("文件读取失败（容器不可达？）: " + out.stderr()));
        }
        String stdout = new String(out.stdout(), StandardCharsets.UTF_8);
        int newline = stdout.indexOf('\n');
        if (newline < 0) {
            // 大小首行缺失：printf 恒带换行，容器侧成功时不可达，防御性如实暴露
            return Mono.just(ToolResultBlock.error("文件读取结果畸形: " + relative));
        }
        String content = stdout.substring(newline + 1);
        if (content.length() > MAX_OUTPUT_CHARS) {
            return Mono.just(ToolResultBlock.text(content.substring(0, MAX_OUTPUT_CHARS)
                    + "\n…（内容过长已截断，如需后半段请说明）"));
        }
        return Mono.just(ToolResultBlock.text(content));
    }
}
