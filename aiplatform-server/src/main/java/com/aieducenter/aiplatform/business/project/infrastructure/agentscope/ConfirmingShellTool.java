package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.aieducenter.aiplatform.base.agentscope.DockerExecFilesystem;

import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import reactor.core.publisher.Mono;

/**
 * 需确认的命令工具（#83 权限确认的生产触发面）：平台侧 {@code command} 工具——
 * 执行体全量委托内核 {@link ShellExecuteTool}（同一 docker exec 文件面，语义零
 * 分叉），仅自检层对破坏性命令返回 ASK（引擎权限确认原语 RequireUserConfirmEvent
 * → 前端确认卡，批准/拒绝续跑）。内核 shell 工具非 ToolBase（引擎规则拦不住），
 * 且其注册名 {@code execute} 不在部件播报封闭表（command 才在）——平台侧以本工具
 * 替位内核 shell（工厂对项目 dev 工作区 disableShellTool），注册名取
 * {@code command} 对齐动作卡播报表。
 *
 * <p><b>破坏性清单是封闭小表</b>（宁漏勿滥：沙箱可随时销毁重建、工作区卷外皆可弃，
 * 确认卡是过程透明面不是安全边界；清单按常见灾难形态维护，扩表走代码）。
 * 批准后的重演由内核按 ALLOWED 态整体跳过权限引擎（同 ask_user 口径），无死循环。</p>
 */
public class ConfirmingShellTool extends ToolBase {

    /** 注册名（= 部件播报封闭表的 command，动作卡「运行命令」行）。 */
    public static final String NAME = "command";

    /** 破坏性命令形态（确认卡触发面）：递归强删 / 提权 / 格式化与裸写设备 / 关机族 / fork 炸弹。 */
    private static final List<Pattern> DESTRUCTIVE_COMMANDS = List.of(
            // rm 带 r 与 f 旗标（-rf / -fr / -r -f 任意排布；分号管道与 && 之外的同段内）
            Pattern.compile("\\brm\\b(?=[^|;&]*-\\w*r)(?=[^|;&]*-\\w*f)"),
            // 提权（容器内通常无 sudo，防的是形态命中）
            Pattern.compile("(^|[\\s;&|])sudo\\b"),
            // 格式化 / 裸写块设备
            Pattern.compile("\\bmkfs(\\.\\w+)?\\b"),
            Pattern.compile("\\bdd\\b[^|;&]*\\bof=/dev/"),
            // 关机重启族
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b"),
            // fork 炸弹
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{"));

    private final ShellExecuteTool delegate;

    public ConfirmingShellTool(String containerName) {
        super(ToolBase.builder()
                .name(NAME)
                .description("Execute a shell command. Use for git, npm, build, test, and other"
                        + " terminal operations. Returns combined output and exit code. If a"
                        + " dedicated tool exists (e.g. read_file, write_file), you MUST use it"
                        + " instead of shell commands.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of(
                                        "type", "string",
                                        "description", "Shell command to execute"),
                                "working_directory", Map.of(
                                        "type", "string",
                                        "description", "Working directory (relative to workspace"
                                                + " root, optional)"),
                                "timeout", Map.of(
                                        "type", "integer",
                                        "description", "Timeout in seconds (default: 30)")),
                        "required", List.of("command")))
                .readOnly(false)
                .concurrencySafe(true));
        this.delegate = new ShellExecuteTool(new DockerExecFilesystem(containerName));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            PermissionContextState context) {
        // 破坏性形态 → ASK（挂起等确认卡作答）；其余放行（passthrough——轻量路径下
        // 非 ASK 即执行，与内核 shell 无确认时的行为同构）
        String command = toolInput.get("command") instanceof String value ? value : "";
        return DESTRUCTIVE_COMMANDS.stream().anyMatch(pattern -> pattern.matcher(command).find())
                ? Mono.just(PermissionDecision.ask("破坏性命令，需用户确认后执行"))
                : Mono.just(PermissionDecision.passthrough(NAME));
    }

    @Override
    public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
        String output = delegate.execute(
                param.getRuntimeContext(),
                text(input.get("command")),
                text(input.get("working_directory")),
                input.get("timeout") instanceof Number number ? number.intValue() : null);
        return Mono.just(io.agentscope.core.message.ToolResultBlock.text(output));
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
