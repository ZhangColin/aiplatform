package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.business.project.application.FinishFixFacts;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 修正收口结束工具（编码智能体资产，#46）：编码智能体对「本轮要不要动系统」的
 * 开发侧判定动作——判定结果由平台从本工具调用事实观测（{@link FinishFixFacts}），
 * 不解析自由文本。必调（含不动系统的情形）：动了系统 → changed=true + 改了什么；
 * 不动（纯文档性修订、系统现状已满足等）→ changed=false + 原因——用户侧据此
 * 区分「不需要改」与「链路断了」。仅修正任务收口时调用（首次生成不走本工具，
 * 收口判据仍是 8081 常驻）。
 *
 * <p>无需用户确认（权限自检恒放行）：判定收口是修正协议的预期终点。效果仅
 * 平台侧事实登记（不动工作区），readOnly；重复调用后写胜出。仅随项目 dev 工作
 * 区注册（{@link RoleToolkitSupplier}）。</p>
 */
public class FinishFixTool extends ToolBase {

    public static final String NAME = "finish_fix";
    private static final String CHANGED_KEY = "changed";
    private static final String TEXT_KEY = "text";

    private final String workspaceId;
    private final FinishFixFacts finishFacts;

    public FinishFixTool(String workspaceId, FinishFixFacts finishFacts) {
        super(ToolBase.builder()
                .name(NAME)
                .description("修正任务的结束工具（收口判定，必调）：动了系统传 changed=true 并在 text"
                        + " 说明改了什么；判定系统无需改动（纯文档性修订、系统现状已满足等）也必须"
                        + "调用，传 changed=false 并在 text 说明原因。不调用本工具即本轮修正未收口。"
                        + "仅修正任务收口时调用，首次生成不适用。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                CHANGED_KEY, Map.of(
                                        "type", "boolean",
                                        "description", "本轮修正是否修改了系统"),
                                TEXT_KEY, Map.of(
                                        "type", "string",
                                        "description", "changed=true：改了什么；changed=false：为什么不需要动系统")),
                        "required", List.of(CHANGED_KEY, TEXT_KEY)))
                .readOnly(true)
                .concurrencySafe(true));
        this.workspaceId = workspaceId;
        this.finishFacts = finishFacts;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 判定收口是修正协议的预期终点，工具点不放确认
        return Mono.just(PermissionDecision.allow("修正收口是协议的预期终点，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
        Object changed = input.get(CHANGED_KEY);
        Object text = input.get(TEXT_KEY);
        if (!(changed instanceof Boolean changedFlag)) {
            return Mono.just(ToolResultBlock.error(
                    "changed 必须是布尔值：动了系统传 true，判定无需改动传 false"));
        }
        if (text == null || String.valueOf(text).isBlank()) {
            return Mono.just(ToolResultBlock.error(
                    "text 不能为空：changed=true 说明改了什么，changed=false 说明原因"));
        }
        finishFacts.record(workspaceId, changedFlag, String.valueOf(text));
        return Mono.just(ToolResultBlock.text(changedFlag
                ? "收口已记录：本次修正已落实。请确认 8081 端口服务在跑后结束本轮。"
                : "收口已记录：系统无需改动。"));
    }
}
