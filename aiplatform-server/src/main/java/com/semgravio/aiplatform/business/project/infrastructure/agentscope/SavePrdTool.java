package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.agentscope.DockerExecFilesystem;
import com.semgravio.aiplatform.base.agentscope.ReadinessAwaitingExecCommand;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceReadinessWaiter;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.business.project.application.FastLaneFacts;
import com.semgravio.aiplatform.business.project.application.PrdRevisionFacts;
import com.semgravio.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

/**
 * 保存 PRD 工具（主智能体资产）：主智能体判定需求明确（没有必答题，含收敛令收口，#461）后的产物动作——效果 =
 * 写 {@code docs/PRD.md} 到项目 dev 工作区（经 {@link PrdArtifactAdapter#workspacePath}
 * 的业务正本路径，docker exec 覆盖写——修订再执行即更新）+ 回调
 * {@link PrdArtifactAdapter#onWritten}（置「PRD 已产出」状态位 + 发 document-updated
 * ——#542 首建派发门：#462 的首产自动派发回调整段退役，落盘只登记事实、点火归
 * 用户）+ 修订事实登记（{@link PrdRevisionFacts}——summary 必传，平台从工具调用
 * 事实观测「改没改、改了什么」入交接物，#52）+ <b>快道事实登记</b>（
 * {@link FastLaneFacts}——{@code fastLane=true} 即快道收口〔用户明确收敛令/付费
 * 「直接开始做」视同确认〕，收口观测直派首个构建、不出卡不确认；缺席＝自然收口，
 * 试用态出岔路口三选卡、付费态确认门在场——#542，ADR-0048/0049）+ <b>五问承诺闸</b>
 *（{@link PrdArtifactAdapter#earlyClosureBlockReason}——试用访谈期未答满五问且无
 * 用户收敛令即拒，拒因模型可见、不落盘不置位；2026-11-17 用户裁决：「已答 N/5」
 * 是明示承诺，除非用户明确不要否则问满）。
 * 落盘/回调任一失败回错误结果（模型
 * 可见，可再次调用重试）；summary 漏传校验报错逼补（对齐 finish_edit 必调哲学）。
 *
 * <p>无需用户确认（权限自检恒放行）：PRD 产出是访谈协议的预期终点，保存动作本身
 * 不设工具点确认——去门化（#462）后产出不再自动进入生成（#542 派发门：快道直派、
 * 自然收口出岔路口/确认门），工具点确认动作不存在。仅随项目只读工作区注册
 * （{@link ProfileToolkitSupplier}，#86 主智能体对话姿态）。执行体阻塞（docker
 * exec + 置位事务），框架 ToolExecutor 缺省 boundedElastic 调度，阻塞安全。</p>
 *
 * <p>落盘经 {@link ReadinessAwaitingExecCommand} 就绪感知执行（#62 同口径）：
 * 工作区创建是异步置备（#58/#61「点创建即对话」），置备窗口期内调用本工具隐式
 * 等待就绪再写——不裸撞「No such container」（2026-11-13 本地实录：镜像现场构建
 * 拉长置备至 2m23s，savePrd 三连败判悬空）；置备失败/超时如实回错误结果。</p>
 */
public class SavePrdTool extends ToolBase {

    public static final String NAME = "savePrd";
    private static final String CONTENT_KEY = "content";
    private static final String SUMMARY_KEY = "summary";
    private static final String FAST_LANE_KEY = "fastLane";

    private final String workspaceId;
    private final PrdArtifactAdapter artifactAdapter;
    private final PrdRevisionFacts prdRevisions;
    private final FastLaneFacts fastLaneFacts;
    private final String containerFile;
    private final DockerExecFilesystem.ExecCommand exec;

    public SavePrdTool(String artifactPath, String workspaceId, String containerName,
            WorkspaceReadinessWaiter readinessWaiter,
            PrdArtifactAdapter artifactAdapter, PrdRevisionFacts prdRevisions,
            FastLaneFacts fastLaneFacts) {
        this(artifactPath, workspaceId, artifactAdapter, prdRevisions, fastLaneFacts,
                ReadinessAwaitingExecCommand.dockerExec(containerName,
                        WorkspaceId.of(workspaceId), readinessWaiter));
    }

    /** 执行缝注入构造（单测替身 ExecCommand）。 */
    SavePrdTool(String artifactPath, String workspaceId, PrdArtifactAdapter artifactAdapter,
            PrdRevisionFacts prdRevisions, FastLaneFacts fastLaneFacts,
            DockerExecFilesystem.ExecCommand exec) {
        super(ToolBase.builder()
                .name(NAME)
                .description("保存/修订 PRD 到项目工作区（docs/PRD.md）。调用时机：付费态"
                        + "判定需求已明确（没有必答题）、试用态问满五问，或用户已下收敛令"
                        + "——试用访谈期未答满五问且用户未明确要求提前收口时本调用会被平台"
                        + "拒绝（错误结果可见，照其指引继续用 ask_user 提问）。content 传"
                        + "完整 PRD markdown 全文（结构与写法遵循 prd-writing 技能）；"
                        + "summary 传本轮说明（markdown 变更说明——修订：改了什么、覆盖"
                        + "哪些意见；首次产出：PRD 覆盖了什么——界面层按 markdown 渲染"
                        + "呈现，勿出源码记号）——必传，平台经此观测修订事实；fastLane "
                        + "仅在用户明确下了收敛令（「不用再问了/直接出 PRD/直接开始做」"
                        + "——试用态未问满五问时唯一合法的提前收口形）或付费态「直接开始"
                        + "做」视同确认时传 true——平台直接开始生成、不出岔路口卡；问满"
                        + "五问后的自然收口、判定明确都不传（false）。每次调用"
                        + "覆盖旧版；成功后向用户简短总结（修订时给修订摘要），不再提问。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                CONTENT_KEY, Map.of(
                                        "type", "string",
                                        "description", "完整 PRD markdown 全文（覆盖旧版）"),
                                SUMMARY_KEY, Map.of(
                                        "type", "string",
                                        "description", "本轮说明：修订传改了什么、覆盖了哪些意见；首次产出传 PRD 覆盖了什么"),
                                FAST_LANE_KEY, Map.of(
                                        "type", "boolean",
                                        "description", "快道收口标记：仅当用户明确下达收敛令"
                                                + "（「不用再问了/直接出 PRD/直接开始做」——"
                                                + "试用态未答满五问时唯一可提前保存 PRD 的形）"
                                                + "或付费态「直接开始做」视同确认时传 true"
                                                + "（平台直接生成、不出卡不确认）；问满五问后的"
                                                + "自然收口与判定明确都不传；零必答不再是试用态"
                                                + "提前收口的依据")),
                        "required", List.of(CONTENT_KEY, SUMMARY_KEY)))
                .readOnly(false)
                .concurrencySafe(false));
        this.workspaceId = workspaceId;
        this.artifactAdapter = artifactAdapter;
        this.prdRevisions = prdRevisions;
        this.fastLaneFacts = fastLaneFacts;
        this.containerFile = AgentWorkspace.ProjectDev.CONTAINER_ROOT + "/" + artifactPath;
        this.exec = exec;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 产出是访谈的预期终点：工具点不放确认（#462 去确认门；#542 派发门——产出
        // 不自动进入生成，点火归快道事实/岔路口选择/确认动作，保存不是点火面）
        return Mono.just(PermissionDecision.allow("PRD 产出是访谈的预期终点，工具点无需确认"));
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
                    + "覆盖了哪些意见（markdown 变更说明；首次产出传 PRD 覆盖了什么）"
                    + "——补传 summary 后再次调用本工具"));
        }
        // 快道标记（#542）：模型按协议上报的收口形态——true 即登记（收口观测直派），
        // 缺席/false 不登记（自然收口——岔路口/确认门）。仅成功路径登记（失败重试
        // 成功后的终值为准，同修订事实口径）。inputSchema 已声明 boolean 型，Jackson
        // 按型反序列化——Boolean.TRUE.equals 单判即足（同 ReportVerificationTool 先例）。
        boolean fastLane = Boolean.TRUE.equals(param.getInput().get(FAST_LANE_KEY));
        // 提前收口准入（2026-11-17 用户裁决：五问＝对用户明示的承诺）：试用访谈期
        // 未答满五问且无用户收敛令——拒因模型可见、不落盘不置位，指引继续 ask_user
        //（对偶 ①族守卫即改即愈口径）。问满/付费态/非访谈期返回 null 零影响。
        String earlyBlock = artifactAdapter.earlyClosureBlockReason(workspaceId, fastLane);
        if (earlyBlock != null) {
            return Mono.just(ToolResultBlock.error(earlyBlock));
        }
        // 覆盖写（文件面写路径单一缝，修订即更新）
        DockerExecFilesystem.ExecOutput out = exec.run(
                DockerExecFilesystem.overwriteWriteCommand(containerFile),
                String.valueOf(content).getBytes(StandardCharsets.UTF_8));
        if (!out.ok()) {
            // 失败事实登记（ADR-0032）：PRD 未成——轮收口若悬空（无问无 PRD），平台
            // 据此点名「刚才写 PRD 一步失败了」而非泛形
            prdRevisions.recordFailure(workspaceId);
            return Mono.just(ToolResultBlock.error("PRD 写入工作区失败: " + out.stderr()));
        }
        try {
            artifactAdapter.onWritten(workspaceId);
        }
        catch (RuntimeException e) {
            // 同上：状态位未置即 PRD 未成，失败事实同面登记
            prdRevisions.recordFailure(workspaceId);
            return Mono.just(ToolResultBlock.error("PRD 已写入但业务登记失败，可重试保存: "
                    + e.getMessage()));
        }
        prdRevisions.record(workspaceId, String.valueOf(summary));
        if (fastLane) {
            fastLaneFacts.record(workspaceId);
        }
        return Mono.just(ToolResultBlock.text("PRD 已保存到项目工作区 " + containerFile
                + (fastLane ? "（快道收口——平台将直接开始生成）" : "")));
    }
}
