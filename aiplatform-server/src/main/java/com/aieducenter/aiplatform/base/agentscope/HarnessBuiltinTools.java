package com.aieducenter.aiplatform.base.agentscope;

import java.util.Comparator;
import java.util.List;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;

/**
 * harness 内建编码工具的自省口（#252 工具面可观测的呈现口径）：框架在 agent
 * build() 时按构建形态注入的内建工具无公开清单 API（逐条 register、集合随
 * builder 开关与运行条件而定）——本类用「注册即自省」拿<b>编码工具核心集</b>
 * （{@link FilesystemTool} 六件 + {@link ShellExecuteTool} 一件）的名字与描述，
 * 名字跟框架版本走、不手工抄录（漂移由装配面测试兜底）。其余内建件（记忆/搜索/
 * 委派/计划模式等）不属「编码工具」呈现口径；框架自带 WebTools 两件
 * （web_fetch/web_search）被平台结构性出局（构建后校正，见
 * {@code AgentscopeHarnessAgentFactory#realignBuiltinWebTools}），不在任何呈现面。
 *
 * <p>自省实例用 {@link DockerExecFilesystem} 占位（注册只反射扫描 @Tool 注解，
 * 不触容器——probe 容器名不会被执行）；每调用新建探针 Toolkit，名字序稳定
 * （排序后呈现）。</p>
 */
public final class HarnessBuiltinTools {

    /** 一件内建工具的呈现单元（名字即模型可见注册名）。 */
    public record BuiltinTool(String name, String description) {
    }

    private HarnessBuiltinTools() {
    }

    /**
     * harness 内建编码工具全集（read_file / write_file / edit_file / grep_files /
     * glob_files / list_files / execute——具体以框架注册自省为准），按名排序。
     * 描述可空（框架未写则空串）。
     */
    public static List<BuiltinTool> codingTools() {
        Toolkit probe = new Toolkit();
        probe.registerTool(new FilesystemTool(new DockerExecFilesystem("builtin-probe")));
        probe.registerTool(new ShellExecuteTool(new DockerExecFilesystem("builtin-probe")));
        return probe.getToolSchemas().stream()
                .map(schema -> new BuiltinTool(schema.getName(),
                        schema.getDescription() == null ? "" : schema.getDescription()))
                .sorted(Comparator.comparing(BuiltinTool::name))
                .toList();
    }

    /** 内建编码工具注册名全集（含判定用：工具面按名寻址时的「不可开关」判据之一）。 */
    public static List<String> codingToolNames() {
        return codingTools().stream().map(BuiltinTool::name).toList();
    }
}
