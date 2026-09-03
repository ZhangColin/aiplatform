package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.agentscope.DockerExecFilesystem;
import com.aieducenter.aiplatform.business.project.application.PrdRevisionFacts;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

/**
 * 保存 PRD 工具（BA 访谈资产）：BA 判定需求明确（含催促收敛）后的产物动作——效果 =
 * 写 {@code docs/PRD.md} 到项目 dev 工作区（经 {@link PrdArtifactAdapter#workspacePath}
 * 的业务正本路径，docker exec 覆盖写——修订再执行即更新）+ 回调
 * {@link PrdArtifactAdapter#onWritten}（置「PRD 已产出」状态位 + 发 document-updated）
 * + 修订事实登记（{@link PrdRevisionFacts}——summary 必传，平台从工具调用事实
 * 观测「改没改、改了什么」入交接物，#52）。落盘/回调任一失败回错误结果（模型
 * 可见，可再次调用重试）；summary 漏传校验报错逼补（对齐 finish_fix 必调哲学）。
 *
 * <p>无需用户确认（权限自检恒放行）：PRD 产出是访谈协议的预期终点，无最终版一说
 * （唯一不可逆门 = 确认下单的快照冻结）。仅随项目 dev 工作区注册
 * （{@link RoleToolkitSupplier}）。执行体阻塞（docker exec + 置位事务），框架
 * ToolExecutor 缺省 boundedElastic 调度，阻塞安全。</p>
 */
public class SavePrdTool extends ToolBase {

    public static final String NAME = "savePrd";
    private static final String CONTENT_KEY = "content";
    private static final String SUMMARY_KEY = "summary";

    private final String workspaceId;
    private final PrdArtifactAdapter artifactAdapter;
    private final PrdRevisionFacts prdRevisions;
    private final String containerFile;
    private final DockerExecFilesystem.ExecCommand exec;

    public SavePrdTool(String artifactPath, String workspaceId, String containerName,
            PrdArtifactAdapter artifactAdapter, PrdRevisionFacts prdRevisions) {
        this(artifactPath, workspaceId, artifactAdapter, prdRevisions,
                new DockerExecFilesystem.DockerExecCommand(
                        containerName, AgentWorkspace.ProjectDev.CONTAINER_ROOT));
    }

    /** 执行缝注入构造（单测替身 ExecCommand）。 */
    SavePrdTool(String artifactPath, String workspaceId, PrdArtifactAdapter artifactAdapter,
            PrdRevisionFacts prdRevisions, DockerExecFilesystem.ExecCommand exec) {
        super(ToolBase.builder()
                .name(NAME)
                .description("保存/修订 PRD 到项目工作区（docs/PRD.md）。判定需求已明确"
                        + "（四方面信息齐备或用户催促收敛）时调用：content 传完整 PRD "
                        + "markdown 全文，固定七章节依序为" + ProjectArtifacts.PRD_SECTIONS
                        + "（功能清单编号列出页面与功能点、每点附验收要点）；summary "
                        + "传本轮说明（修订：改了什么、覆盖哪些意见；首次产出：PRD "
                        + "覆盖了什么）——必传，平台经此观测修订事实。每次调用"
                        + "覆盖旧版；成功后向用户简短总结（修订时给修订摘要），不再提问。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                CONTENT_KEY, Map.of(
                                        "type", "string",
                                        "description", "完整 PRD markdown 全文（覆盖旧版）"),
                                SUMMARY_KEY, Map.of(
                                        "type", "string",
                                        "description", "本轮说明：修订传改了什么、覆盖哪些意见；首次产出传 PRD 覆盖了什么")),
                        "required", List.of(CONTENT_KEY, SUMMARY_KEY)))
                .readOnly(false)
                .concurrencySafe(false));
        this.workspaceId = workspaceId;
        this.artifactAdapter = artifactAdapter;
        this.prdRevisions = prdRevisions;
        this.containerFile = AgentWorkspace.ProjectDev.CONTAINER_ROOT + "/" + artifactPath;
        this.exec = exec;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 产出是访谈的预期终点（无确认门概念），工具点不放确认
        return Mono.just(PermissionDecision.allow("PRD 产出是访谈的预期终点，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Object content = param.getInput() != null ? param.getInput().get(CONTENT_KEY) : null;
        if (content == null || String.valueOf(content).isBlank()) {
            return Mono.just(ToolResultBlock.error("content 不能为空：传完整 PRD markdown 全文"));
        }
        Object summary = param.getInput().get(SUMMARY_KEY);
        if (summary == null || String.valueOf(summary).isBlank()) {
            return Mono.just(ToolResultBlock.error("summary 不能为空：修订传改了什么、"
                    + "覆盖哪些意见（首次产出传 PRD 覆盖了什么）——补传 summary 后"
                    + "再次调用本工具"));
        }
        // 覆盖写（文件面写路径单一缝，修订即更新）
        DockerExecFilesystem.ExecOutput out = exec.run(
                DockerExecFilesystem.overwriteWriteCommand(containerFile),
                String.valueOf(content).getBytes(StandardCharsets.UTF_8));
        if (!out.ok()) {
            return Mono.just(ToolResultBlock.error("PRD 写入工作区失败: " + out.stderr()));
        }
        try {
            artifactAdapter.onWritten(workspaceId);
        }
        catch (RuntimeException e) {
            return Mono.just(ToolResultBlock.error("PRD 已写入但业务登记失败，可重试保存: "
                    + e.getMessage()));
        }
        prdRevisions.record(workspaceId, String.valueOf(summary));
        return Mono.just(ToolResultBlock.text("PRD 已保存到项目工作区 " + containerFile));
    }
}
