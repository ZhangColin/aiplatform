package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.application.IterationAppService;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 派修正任务工具（BA 迭代资产，#26）：意见判定内化 BA——判定分流（实现问题直接
 * 派 / 需求变更先 savePrd 再派）是 BA 协议，本工具是判定的动作出口：把意见转化成
 * 编码智能体可执行的修正任务（prompt）即派一场修正 run（复用 coder 会话与同工作区）。
 *
 * <p>无需用户确认（权限自检恒放行）：迭代无门无次数上限（唯一不可逆门 = 确认下单），
 * 修正 run 由 BA 判定即起。run 在途时新任务排队、当前 run 收口后合并续派——工具
 * 结果如实告知（起跑 / 排入下一轮），BA 据此回复用户。守卫拒绝（项目未生成等）回
 * 错误结果，模型可见可如实转告。仅随项目 dev 工作区注册
 * （{@link BaToolkitSupplier}）。执行体阻塞短（守卫 + 提交异步轨道），框架
 * ToolExecutor 缺省 boundedElastic 调度，阻塞安全。</p>
 */
public class StartFixRunTool extends ToolBase {

    public static final String NAME = "startFixRun";
    private static final String PROMPT_KEY = "prompt";

    private final String workspaceId;
    private final IterationAppService iterationAppService;

    public StartFixRunTool(String workspaceId, IterationAppService iterationAppService) {
        super(ToolBase.builder()
                .name(NAME)
                .description("派修正任务给编码智能体（系统已生成后，用户在指令区对系统提意见时调用）。"
                        + "prompt 传给编码智能体的修正说明：具体、可执行（要改哪里、改成什么样），"
                        + "一次意见归纳为一个任务（同类意见可合并）。修正 run 复用系统的工作区，"
                        + "无需重复系统背景；若修正涉及需求变化，先调用 savePrd 修订 PRD 再调用本工具。"
                        + "修正 run 进行中调用不会丢失：任务自动排入下一轮合并处理。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                PROMPT_KEY, Map.of(
                                        "type", "string",
                                        "description", "修正任务说明（给编码智能体执行：要改什么、"
                                                + "改成什么样、完成标准）")),
                        "required", List.of(PROMPT_KEY)))
                .readOnly(false)
                .concurrencySafe(false));
        this.workspaceId = workspaceId;
        this.iterationAppService = iterationAppService;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 修正是迭代协议的预期动作（无确认门概念），工具点不放确认
        return Mono.just(PermissionDecision.allow("修正任务是迭代协议的预期动作，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Object prompt = param.getInput() != null ? param.getInput().get(PROMPT_KEY) : null;
        if (prompt == null || String.valueOf(prompt).isBlank()) {
            return Mono.just(ToolResultBlock.error("prompt 不能为空：传具体可执行的修正任务说明"));
        }
        try {
            IterationAppService.FixDispatch dispatch = iterationAppService
                    .startFixRunByWorkspace(workspaceId, String.valueOf(prompt));
            if (dispatch.queued()) {
                return Mono.just(ToolResultBlock.text(
                        "当前有修正正在进行，本条修正任务已排入下一轮（将与后续意见合并处理）。"
                                + "请告知用户：这条意见会在下一轮修正一并处理。"));
            }
            return Mono.just(ToolResultBlock.text(
                    "修正任务已下发（runId=" + dispatch.runId() + "），编码智能体开始修正，"
                            + "完成后系统会自动更新。请简短告知用户将怎么改。"));
        }
        catch (ApplicationException e) {
            // 守卫拒绝（项目未生成 / 已归档等）如实回模型，可转告用户
            return Mono.just(ToolResultBlock.error(e.getMessage()));
        }
    }
}
