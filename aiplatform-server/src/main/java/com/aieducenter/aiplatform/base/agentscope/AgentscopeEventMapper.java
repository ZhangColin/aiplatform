package com.aieducenter.aiplatform.base.agentscope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ToolUseBlock;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * 事件映射表单点：AgentScope 类型化事件 → 平台智能体事件（SSE事件清单·智能体
 * 事件族，词汇表正本在 eventhub 的 {@link AgentEventTypes}，payload 关联键同源
 * 常量化）。run 生命周期事件（run-start / run-finish / error，平台封闭集合）经
 * 静态工厂构造（run-start 并入智能体配置键——引擎信息归一，前端工作消息/对话
 * 面的锚定判据）；过程事件（引擎透传开放集合，清单已知名型）由 {@link #map} 逐事件
 * 产出——每事件 payload 盖 runId/sessionId/engine，引擎侧细节藏 {@code data}
 * 键内层。消息部件事件（part-*）不在本表——由 {@link AgentscopePartsMapper}
 * 另行产出。
 *
 * <p>映射表（未列类型跳过不产事件）：</p>
 * <table border="1">
 *   <caption>AgentScope 事件 → 平台事件</caption>
 *   <tr><th>AgentScope 事件</th><th>平台 type</th><th>data 键</th></tr>
 *   <tr><td>TextBlockDelta</td><td>{@code text}</td><td>delta / blockId</td></tr>
 *   <tr><td>ThinkingBlockDelta</td><td>{@code reasoning}</td><td>delta / blockId</td></tr>
 *   <tr><td>ToolCallStart / ToolCallEnd</td><td>{@code tool}</td><td>toolCallId / toolName / phase</td></tr>
 *   <tr><td>ModelCallStart / ModelCallEnd</td><td>{@code step-start} / {@code step-finish}</td><td>replyId</td></tr>
 *   <tr><td>ExceedMaxIters</td><td>（结煞语）</td><td>{@link #finishToken}</td></tr>
 *   <tr><td>RequireUserConfirm</td><td>{@code question-raised} / {@code permission-required}</td><td>{@link #suspension}（挂起事件分诊，作答续跑归业务编排）</td></tr>
 * </table>
 *
 * <p>HITL 挂起（{@code RequireUserConfirmEvent}）不是过程事件也不是终态：
 * {@link #map} 不产透传事件、{@link #finishToken} 无结煞语，由调用方以
 * {@link #suspension} 显式产事件发射；挂起轮的收尾口径 = 不发 run-finish
 * （run 尚未终态，等作答续跑后再收口）。</p>
 */
final class AgentscopeEventMapper {

    // 引擎透传名型（SSE事件清单·引擎透传开放集合已知名型；本类是唯一引用点）
    private static final String TEXT = "text";
    private static final String REASONING = "reasoning";
    private static final String TOOL = "tool";
    private static final String STEP_START = "step-start";
    private static final String STEP_FINISH = "step-finish";

    /** ExceedMaxIters 的结煞语（run-finish.finish，对齐「引擎结煞语」口径）。 */
    private static final String FINISH_EXCEED_MAX_ITERS = "exceed_max_iters";
    private static final String FINISH_END = "end";

    /**
     * 提问类工具名（业务侧 AskUserTool 的注册名，见
     * {@code AgentscopeAgentClient.ASK_USER_TOOL_NAME}）：其确认挂起按 QUESTION
     * 载荷形状呈现（向用户提问）。
     */
    private static final String ASK_USER_TOOL = AgentscopeAgentClient.ASK_USER_TOOL_NAME;

    private final String runId;
    private final String sessionId;
    private final String engine;

    AgentscopeEventMapper(String runId, String sessionId, String engine) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.engine = engine;
    }

    /** 过程事件 → 透传事件；未映射类型返回 {@code null}（跳过）。 */
    AgentEvent map(io.agentscope.core.event.AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            return passthrough(TEXT, Map.of(
                    "delta", nvl(delta.getDelta()),
                    "blockId", nvl(delta.getBlockId())));
        }
        if (event instanceof ThinkingBlockDeltaEvent delta) {
            return passthrough(REASONING, Map.of(
                    "delta", nvl(delta.getDelta()),
                    "blockId", nvl(delta.getBlockId())));
        }
        if (event instanceof ToolCallStartEvent start) {
            return passthrough(TOOL, Map.of(
                    "toolCallId", nvl(start.getToolCallId()),
                    "toolName", nvl(start.getToolCallName()),
                    "phase", "start"));
        }
        if (event instanceof ToolCallEndEvent end) {
            return passthrough(TOOL, Map.of(
                    "toolCallId", nvl(end.getToolCallId()),
                    "toolName", nvl(end.getToolCallName()),
                    "phase", "end"));
        }
        if (event instanceof ModelCallStartEvent start) {
            return passthrough(STEP_START, Map.of("replyId", nvl(start.getReplyId())));
        }
        if (event instanceof ModelCallEndEvent end) {
            return passthrough(STEP_FINISH, Map.of("replyId", nvl(end.getReplyId())));
        }
        return null;
    }

    /** 终态结煞语：流正常收尾时由调用方以默认 {@code end} 兜底。 */
    Optional<String> finishToken(io.agentscope.core.event.AgentEvent event) {
        if (event instanceof ExceedMaxItersEvent) {
            return Optional.of(FINISH_EXCEED_MAX_ITERS);
        }
        return Optional.empty();
    }

    /**
     * 挂起事件分诊（#83 事件拆分）：待确认工具含提问类（ask_user，向用户提问）→
     * {@code question-raised}（问答卡，问答作答通道）；其余（工具操作确认，如危险
     * 命令）→ {@code permission-required}（确认卡，权限作答通道）——两族事件与
     * 作答通道分家，互不串扰。
     */
    AgentEvent suspension(RequireUserConfirmEvent event) {
        return hasQuestion(event) ? questionRaised(event) : permissionRequired(event);
    }

    private static boolean hasQuestion(RequireUserConfirmEvent event) {
        return event.getToolCalls().stream()
                .anyMatch(tc -> ASK_USER_TOOL.equals(tc.getName()));
    }

    /**
     * 提问挂起事件（纯 QUESTION——权限面已拆 {@link #permissionRequired}）：
     * payload 按 {@link AgentEventTypes} WAIT_* 契约。data = toolCalls 待确认清单
     * （答复续跑重建 ConfirmResult 所需的最小面）+ questions 投影。
     *
     * <p>questions 投影（header/question/multiple/custom/options[{label}]，前端问答卡
     * 契约——multiple 恒 false / custom 恒 true：ask_user 一次一题开放可自由输入；
     * custom 必须显式 true，否则无选项题整题被前端收窄丢弃）。摘要口径 = 问题文本
     * （截断保短）。恢复入参（模型档位/会话寻址/计量）由业务编排从项目侧事实重建，
     * 不随事件携带。</p>
     */
    private AgentEvent questionRaised(RequireUserConfirmEvent event) {
        List<ToolUseBlock> toolCalls = event.getToolCalls();
        List<ToolUseBlock> questions = toolCalls.stream()
                .filter(tc -> ASK_USER_TOOL.equals(tc.getName())).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCalls", toolCallPayloads(toolCalls));
        data.put("questions", questionPayloads(questions));
        return new AgentEvent(AgentEventTypes.QUESTION_RAISED, Map.of(
                AgentEventTypes.RUN_FIELD, runId,
                AgentEventTypes.SESSION_FIELD, sessionId,
                AgentEventTypes.ENGINE_FIELD, engine,
                AgentEventTypes.WAIT_SUMMARY_FIELD, summaryOfQuestion(questions.get(0)),
                AgentEventTypes.WAIT_ENGINE_REF_FIELD, nvl(event.getReplyId()),
                AgentEventTypes.WAIT_DATA_FIELD, data));
    }

    /**
     * 权限确认挂起事件（#83 拆分独立事件，词根 = 引擎权限确认原语的待确认工具面）：
     * run 执行中需用户批准的工具操作（如危险命令）→ 对话区确认卡（长在工作消息流）。
     * data = toolCalls 待确认清单（批准/拒绝续跑重建 ConfirmResult 的最小面，输入
     * 原样——确认卡呈现待批准操作的依据）。摘要口径 = 首工具的命令文本（截断保短，
     * 非命令工具回落工具名）。恢复入参由业务编排从项目侧事实重建，不随事件携带。
     */
    private AgentEvent permissionRequired(RequireUserConfirmEvent event) {
        List<ToolUseBlock> toolCalls = event.getToolCalls();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCalls", toolCallPayloads(toolCalls));
        return new AgentEvent(AgentEventTypes.PERMISSION_REQUIRED, Map.of(
                AgentEventTypes.RUN_FIELD, runId,
                AgentEventTypes.SESSION_FIELD, sessionId,
                AgentEventTypes.ENGINE_FIELD, engine,
                AgentEventTypes.WAIT_SUMMARY_FIELD, summaryOfOperation(toolCalls),
                AgentEventTypes.WAIT_ENGINE_REF_FIELD, nvl(event.getReplyId()),
                AgentEventTypes.WAIT_DATA_FIELD, data));
    }

    /**
     * 提问载荷投影（ask_user input → 前端问答卡形状）：header 缺省中性兜底；
     * options 字符串列表 → {label} 对象列表（无选项纯开放题也保留——custom=true
     * 恒可自由输入）；multiple 从工具入参投影（多选勾选提交，缺省 false 单选点即答）。
     */
    private static List<Map<String, Object>> questionPayloads(List<ToolUseBlock> questions) {
        return questions.stream().map(tc -> {
            Map<String, Object> input = tc.getInput() != null ? tc.getInput() : Map.of();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("header", textOrDefault(input.get("header"), "提问"));
            payload.put("question", textOrDefault(input.get("question"), ""));
            payload.put("multiple", Boolean.TRUE.equals(input.get("multiple")));
            payload.put("custom", true);
            payload.put("options", optionPayloads(input.get("options")));
            return payload;
        }).toList();
    }

    private static List<Map<String, Object>> optionPayloads(Object options) {
        if (!(options instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(option -> Map.<String, Object>of("label", String.valueOf(option)))
                .toList();
    }

    /** 问答中性短文本：问题文本（截断保短）。 */
    private static String summaryOfQuestion(ToolUseBlock askUser) {
        Map<String, Object> input = askUser.getInput() != null ? askUser.getInput() : Map.of();
        String text = textOrDefault(input.get("question"), "");
        if (text.isBlank()) {
            return "智能体提问";
        }
        return text.length() > 100 ? text.substring(0, 100) : text;
    }

    private static String textOrDefault(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    /** 待确认工具清单载荷（id/name/input——ConfirmResult 重建所需的最小面；同包
     *  {@link AgentscopeAgentClient} 的挂起面同形共用——单点产出防双轨）。 */
    static List<Map<String, Object>> toolCallPayloads(List<ToolUseBlock> toolCalls) {
        return toolCalls.stream()
                .map(tc -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("id", nvl(tc.getId()));
                    payload.put("name", nvl(tc.getName()));
                    payload.put("input", tc.getInput() != null ? tc.getInput() : Map.of());
                    return payload;
                })
                .toList();
    }

    /** 挂起中性短文本：首工具的命令文本（截断保短，确认卡摘要行）；非命令工具回落工具名。 */
    private static String summaryOfOperation(List<ToolUseBlock> toolCalls) {
        if (toolCalls.isEmpty()) {
            return "";
        }
        ToolUseBlock first = toolCalls.get(0);
        if (first.getInput() instanceof Map<?, ?> input
                && input.get("command") instanceof String command && !command.isBlank()) {
            return command.length() > 100 ? command.substring(0, 100) : command;
        }
        return nvl(first.getName());
    }

    // ---------- run 生命周期事件（平台封闭集合） ----------

    /**
     * run 开始事件：引擎信息归一——engine/model 之外携带智能体配置键
     * {@code agent}（业务侧 AgentCommand 的配置键，可空不携带；前端工作消息/
     * 对话面的锚定判据）。
     */
    static AgentEvent runStart(String runId, String prompt, String model, String engine,
            String agentKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AgentEventTypes.RUN_FIELD, runId);
        payload.put("prompt", prompt);
        payload.put("model", model);
        payload.put(AgentEventTypes.ENGINE_FIELD, engine);
        if (agentKey != null && !agentKey.isBlank()) {
            payload.put(AgentEventTypes.AGENT_FIELD, agentKey);
        }
        return new AgentEvent(AgentEventTypes.RUN_START, payload);
    }

    static AgentEvent runFinish(String runId, String sessionId, String finish, String engine) {
        return new AgentEvent(AgentEventTypes.RUN_FINISH, Map.of(
                AgentEventTypes.RUN_FIELD, runId,
                AgentEventTypes.SESSION_FIELD, sessionId,
                AgentEventTypes.ENGINE_FIELD, engine,
                AgentEventTypes.FINISH_FIELD, finish != null ? finish : FINISH_END));
    }

    static AgentEvent error(String runId, String message) {
        return new AgentEvent(AgentEventTypes.ERROR, Map.of(
                AgentEventTypes.RUN_FIELD, runId,
                AgentEventTypes.ERROR_MESSAGE_FIELD, message != null ? message : "智能体运行失败"));
    }

    // ---------- 内部 ----------

    private AgentEvent passthrough(String type, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AgentEventTypes.RUN_FIELD, runId);
        payload.put(AgentEventTypes.SESSION_FIELD, sessionId);
        payload.put(AgentEventTypes.ENGINE_FIELD, engine);
        payload.put("data", data);
        return new AgentEvent(type, payload);
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
