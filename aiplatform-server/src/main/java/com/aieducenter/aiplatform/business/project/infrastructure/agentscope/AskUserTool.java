package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 向用户提问工具（BA 访谈资产）：访谈式收集信息的挂起源——工具自检恒 ASK
 * （Built-in Checks 不可绕过、不受 permission mode/rules 影响），调用即触发
 * {@code RequireUserConfirmEvent} 挂起 → 智能体流 {@code question-raised} 帧
 * （QUESTION 载荷形状，见 {@code AgentscopeEventMapper}）。用户答复经
 * {@code BaInterviewAppService#answerQuestion} 续跑：挂起批复重写本调用 block 的
 * metadata（{@link AgentscopeAgentClient#ANSWER_METADATA_KEY}，模型不可见通道）
 * 携带答复——本工具执行即以之作为工具结果回给模型，访谈继续。
 *
 * <p>答复不进工具 input（#34）：input 持久化进会话且模型可见，模型会从回放
 * 上下文学到「ask_user 可自带答案」进而自答后续提问；metadata 不序列化给模型。
 * 重演放行不靠 input 键判据——内核对挂起批复置 ALLOWED 的调用整体跳过权限
 * 引擎（含本工具自检），恒 ASK 不会造成重演死循环。
 *
 * <p>无需配置 permissionContext（trivial 上下文也走工具自检），未调用本工具的
 * 轮次不产生挂起。
 */
public class AskUserTool extends ToolBase {

    /** 注册名正本在内核（mapper 的 QUESTION 判定同源）。 */
    public static final String NAME = AgentscopeAgentClient.ASK_USER_TOOL_NAME;

    public AskUserTool() {
        super(ToolBase.builder()
                .name(NAME)
                .description("向用户提问并等待答复（访谈式收集信息；一次一个问题，选项可选）。"
                        + "调用后会挂起等待用户回答，答复将作为本工具的结果返回。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "question", Map.of(
                                        "type", "string",
                                        "description", "要问用户的问题"),
                                "header", Map.of(
                                        "type", "string",
                                        "description", "问题主题短标签（如「目标用户」，呈现于问答卡）"),
                                "options", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "候选选项（无可不填，用户可自由输入）"),
                                "multiple", Map.of(
                                        "type", "boolean",
                                        "description", "是否允许多选（组合式回答）；缺省 false 单选")),
                        "required", List.of("question")))
                .readOnly(true)
                .concurrencySafe(true));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 恒 ASK：新调用（含模型带编造 answer 键自答的形状）一律挂起等真实用户答复；
        // 批复后的重演由内核按 ALLOWED 态整体跳过权限引擎，不经此处、无死循环。
        return Mono.just(PermissionDecision.ask("向用户提问，等待答复"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 批准后的执行体：答复在重写 block 的 metadata（模型不可见），以之作为工具
        // 结果回给模型——访谈上下文直接可读；无 metadata 答复（异常面）回空串
        Object answer = param.getToolUseBlock() != null && param.getToolUseBlock().getMetadata() != null
                ? param.getToolUseBlock().getMetadata().get(AgentscopeAgentClient.ANSWER_METADATA_KEY)
                : null;
        return Mono.just(ToolResultBlock.text(answer != null ? String.valueOf(answer) : ""));
    }
}
