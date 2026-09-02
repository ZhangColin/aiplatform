package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.Map;
import java.util.Optional;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 项目事实查询工具（助理职能体资产，#47）：答「我后台的地址与账号密码是什么」
 * 的事实半边——系统访问地址（预览端口映射，置备时已落定）与项目关键事实
 * （名称/状态/创建、PRD 产出、首次生成时点）。纯读（库查询 + 工作区句柄），
 * 工具面 readOnly；账号密码的事实半边在文件面（说明文档/初始数据代码），归
 * {@link ReadWorkspaceFileTool} 查证。
 *
 * <p>仅随项目只读工作区注册（{@link RoleToolkitSupplier}）。时间戳以
 * {@link DateTimeFormat#ISO} 缺省（Instant#toString，秒精度足够答疑）。</p>
 */
public class ProjectFactsTool extends ToolBase {

    public static final String NAME = "query_project_facts";

    private final String workspaceId;
    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public ProjectFactsTool(String workspaceId, ProjectRepository projectRepository,
            WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        super(ToolBase.builder()
                .name(NAME)
                .description("查询本项目的事实清单：系统访问地址（预览地址，端口映射已落定——"
                        + "「我后台的地址」以此为准）、项目名、状态（进行中/已归档）、创建时间、"
                        + "PRD 产出时间、系统首次生成时间。答项目现状类问题时调用；"
                        + "账号密码不在此（在工作区文件里，用文件工具查证）。")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .readOnly(true)
                .concurrencySafe(true));
        this.workspaceId = workspaceId;
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 只读查证是答疑协议的预期动作，工具点不放确认
        return Mono.just(PermissionDecision.allow("只读项目事实，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Optional<Project> found = projectRepository.findByWorkspaceId(Long.parseLong(workspaceId));
        if (found.isEmpty()) {
            return Mono.just(ToolResultBlock.error("项目事实查询失败：工作区 " + workspaceId + " 名下无项目"));
        }
        Project project = found.get();
        return Mono.just(ToolResultBlock.text(factsOf(project)));
    }

    /** 事实清单拼装（平实中文，面向转述给非技术用户）。 */
    private String factsOf(Project project) {
        StringBuilder facts = new StringBuilder("项目事实：\n");
        facts.append("- 项目名：").append(project.getName()).append('\n');
        facts.append("- 状态：").append(project.getArchivedAt() != null ? "已归档" : "进行中").append('\n');
        facts.append("- 创建时间：").append(project.getCreatedAt()).append('\n');
        facts.append("- PRD 产出时间：")
                .append(project.getPrdProducedAt() != null ? project.getPrdProducedAt().toString() : "尚未产出")
                .append('\n');
        facts.append("- 系统首次生成时间：")
                .append(project.getGeneratedAt() != null ? project.getGeneratedAt().toString() : "尚未生成")
                .append('\n');
        facts.append("- 系统访问地址：").append(previewUrl());
        return facts.toString();
    }

    /** 预览地址（端口映射置备时已落定，URL 确定；不探活——答地址不等于答在线）。 */
    private String previewUrl() {
        try {
            return "http://localhost:" + workspaceLifecycleAppService
                    .handleOf(workspaceId).previewPort() + "/";
        }
        catch (RuntimeException e) {
            // 工作区句柄解析失败（环境异常面）：如实报「暂不可知」，不编造地址
            return "暂不可知（工作区状态异常）";
        }
    }
}
