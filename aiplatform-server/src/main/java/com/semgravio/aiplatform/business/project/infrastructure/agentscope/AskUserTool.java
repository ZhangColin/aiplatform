package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.business.project.infrastructure.InterviewToolGate;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 向用户提问工具（主智能体资产）：访谈式收集信息的挂起源——工具自检按<b>提问侧
 * 额度闸</b>分形（{@link InterviewToolGate#questionBlockReason}，ADR-0048「程序计数
 * 权威」的工具点对偶）：五问未答满 → ASK，触发 {@code RequireUserConfirmEvent} 挂起
 * → 智能体事件族 {@code question-raised}（QUESTION 载荷形状，见
 * {@code AgentscopeEventMapper}）；试用访谈五问已答满 → PASSTHROUGH 放行到执行体，
 * 由执行体回错误结果（拒因模型可见、<b>不挂起、不出问答卡、不发 question-raised</b>
 * ——漂移提问在工具点拦下，而非先出卡给用户再由轮落定的收口纠偏链平台代答；旧
 * 形态「第 6 问先出卡、随后自问自答按推荐补全」实测项目 381073661124370156）。
 * 模型同轮拿到拒因后按收口指令转 savePrd。
 *
 * <p>用户答复经 {@code MainAgentAppService#answerQuestion} 续跑：挂起批复重写本调用
 * block 的 metadata（{@link AgentscopeAgentClient#ANSWER_METADATA_KEY}，模型不可见
 * 通道）携带答复——内核对挂起批复置 ALLOWED 的调用整体跳过权限引擎，执行体读
 * metadata 作为工具结果回给模型，访谈继续。</p>
 *
 * <p>答复不进工具 input（#34）：input 持久化进会话且模型可见，模型会从回放
 * 上下文学到「ask_user 可自带答案」进而自答后续提问；metadata 不序列化给模型。
 * 闸拒因同样只走工具错误结果通道，不写入 input。</p>
 *
 * <p>闸查询 fail-open：判定异常（库抖动等）按 ASK 放行——提问照常挂起，轮落定的
 * 收窄链（第 5 答后挂起代答收口）仍是兜底，不因读面故障卡死访谈。无需配置
 * permissionContext（trivial 上下文也走工具自检），未调用本工具的轮次不产生挂起。</p>
 */
@Slf4j
public class AskUserTool extends ToolBase {

    /** 注册名正本在内核（mapper 的 QUESTION 判定同源）。 */
    public static final String NAME = AgentscopeAgentClient.ASK_USER_TOOL_NAME;

    private final String workspaceId;
    private final InterviewToolGate quotaGate;

    public AskUserTool(String workspaceId, InterviewToolGate quotaGate) {
        super(ToolBase.builder()
                .name(NAME)
                .description("向用户提问并等待答复（访谈式收集信息；一次一个问题，选项可选）。"
                        + "调用后会挂起等待用户回答，答复将作为本工具的结果返回。"
                        + "试用访谈固定五问：第五问答复后额度即用尽，此后调用本工具会被"
                        + "平台拒绝（错误结果可见、提问不生效也不会呈现给用户）——收到拒绝"
                        + "结果即停止提问，按收口指令以推荐补全剩余缺口并调用 savePrd。")
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
                                "recommended", Map.of(
                                        "type", "string",
                                        "description", "推荐项（推荐答案，#459）：必须是 options 中的一项——"
                                                + "问答卡将其标「推荐」并预选缺省，用户可改选或自由输入；"
                                                + "无把握不填，行为与现状一致"),
                                "multiple", Map.of(
                                        "type", "boolean",
                                        "description", "是否允许多选（组合式回答）；缺省 false 单选")),
                        "required", List.of("question")))
                .readOnly(true)
                .concurrencySafe(true));
        this.workspaceId = workspaceId;
        this.quotaGate = quotaGate;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            PermissionContextState context) {
        // 提问侧额度闸：闸判定是阻塞短事务（JDBC），显式放 boundedElastic——
        // 不堵权限评估的 reactor 线程。命中 → PASSTHROUGH 进执行体回错误结果
        //（不挂起、零用户面事件）；放行/null → ASK 恒挂起（含模型编造 answer 键
        // 的形状——重演 ALLOWED 不经此引擎，无需 input 键判据）。异常 fail-open。
        // 注意：fromCallable 的 callable 不得返回 null（空序列会让 map 不执行、
        // block() 得 null）——ASK/PASSTHROUGH 判定必须在 callable 内完成
        return Mono.fromCallable(() -> {
                    String reason = quotaGate.questionBlockReason(workspaceId);
                    return reason == null
                            ? PermissionDecision.ask("向用户提问，等待答复")
                            : PermissionDecision.passthrough("五问额度已用尽，执行体回拒因");
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("ask_user 提问侧额度闸查询失败（fail-open 照常挂起）：workspaceId={}, {}",
                            workspaceId, e.toString());
                    return Mono.just(PermissionDecision.ask("向用户提问，等待答复"));
                });
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 挂起批复续跑：答复在重写 block 的 metadata（模型不可见），优先作为工具结果
        Object answer = param.getToolUseBlock() != null && param.getToolUseBlock().getMetadata() != null
                ? param.getToolUseBlock().getMetadata().get(AgentscopeAgentClient.ANSWER_METADATA_KEY)
                : null;
        if (answer != null) {
            return Mono.just(ToolResultBlock.text(String.valueOf(answer)));
        }
        // 闸拒的新调用（自检 PASSTHROUGH）：执行体权威再判一次，回模型可见拒因——
        // 不挂起、不出卡；闸已放开（状态翻转竞态等）回落空串异常面
        String blocked = quotaGate.questionBlockReason(workspaceId);
        if (blocked != null) {
            return Mono.just(ToolResultBlock.error(blocked));
        }
        return Mono.just(ToolResultBlock.text(""));
    }
}
