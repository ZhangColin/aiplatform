package com.semgravio.aiplatform.business.project.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;

import lombok.extern.slf4j.Slf4j;

/**
 * 业务编排的智能体事件桥（主智能体对话 / 生成共用）：关联字段（projectId）逐事件注入
 * 后经事件通道发射。关联字段底座不解释、透传；发射失败护栏：单事件发射异常只记
 * 日志不断流（SSE 是「让 UI 活」的面，不承担正确性）。
 */
@Component
@Slf4j
public class AgentEventBridge {

    private final EventsAppService eventsAppService;

    public AgentEventBridge(EventsAppService eventsAppService) {
        this.eventsAppService = eventsAppService;
    }

    /**
     * 事件桥 sink：关联字段（projectId + 归属路由键双键）逐事件注入后经
     * 事件通道发射（发射失败只记日志不断流）。归属在 sink 创建时捕获一次（每会话/run
     * 一次）——token 流热路径零查库（ADR-0018；#326 归属扩 owner 双列）。
     */
    public Consumer<AgentEvent> sink(Project project) {
        Map<String, Object> correlation = Map.of(
                EventsAppService.PROJECT_FIELD, project.getId().toString(),
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId()),
                EventsAppService.OWNER_GUEST_FIELD,
                EventsAppService.ownerPayload(project.getOwnerGuestId()));
        return event -> {
            try {
                eventsAppService.publishAgentEvent(event.type(), withCorrelation(event.payload(), correlation));
            }
            catch (RuntimeException e) {
                log.warn("[agent-event] 事件发射失败（{}）：{}", event.type(), e.getMessage());
            }
        };
    }

    /**
     * run-failed 发射（编码 run 重试超限·终态收口，#56）：轨道层在真终态落定点
     * 调用——修正轨道与终态账（恢复出口 {@code restartFixRun} 的重派依据）同
     * 事实点，排队合并续派的中途超限不发（轨道仍在途）；生成轨道超限即终态。
     * runId = 该场 run 的用户面标识（首试 runId——#84 静默重试：重试不换新锚，
     * 中间尝试的内部标识不出用户面）。前端恢复出口只认本事件，run 失败为唯一
     * 失败终态（重试全程静默，中间失败不出事件）。
     *
     * <p><b>失败原因随事件（#355 机制检查）</b>：{@code lastError}（末次尝试的
     * 错误现场，原样留档于终态日志/失败留账）经
     * {@link #userFacingRunFailure(String)} 映射为一句用户面原因随
     * {@code message} 键携带（映射不中则截断原文首行；空则缺省不带）——终态
     * 失败不再零原因，用户可分辨「模型没写好」与「服务侧故障」。</p>
     */
    public void emitRunFailed(Project project, String runId, String lastError) {
        Map<String, Object> payload = runScopedPayload(project, runId);
        String reason = userFacingRunFailure(lastError);
        if (reason != null) {
            payload.put(AgentEventTypes.ERROR_MESSAGE_FIELD, reason);
        }
        eventsAppService.publishAgentEvent(AgentEventTypes.RUN_FAILED, payload);
    }

    /**
     * 用户停止的终态收口发射（#359 生成取消）：在途 run 被用户叫停后的轨道层收口
     * ——事件复用 {@code run-failed}（失败账口径：停止的 run 如实留账计量与终态
     * 状态），载荷加 {@code stopped=true} 标记（前端失败档文案分岔「已停止」）＋
     * 定型一句人话（非错误现场映射——停止是用户动作，无失败原因可报）。runId =
     * 被停场次的用户面标识（首试 runId），恢复出口（继续生成/继续更新）照常只认
     * 本事件。
     */
    public void emitRunStopped(Project project, String runId) {
        Map<String, Object> payload = runScopedPayload(project, runId);
        payload.put(AgentEventTypes.RUN_STOPPED_FIELD, true);
        payload.put(AgentEventTypes.ERROR_MESSAGE_FIELD, "已按你的要求停止，已完成的进度都保留");
        eventsAppService.publishAgentEvent(AgentEventTypes.RUN_FAILED, payload);
    }

    /** run 级事件的关联载荷基底（project 双路由键＋runId——emitRunFailed/Stopped 共用）。 */
    private static Map<String, Object> runScopedPayload(Project project, String runId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(EventsAppService.PROJECT_FIELD, project.getId().toString());
        payload.put(EventsAppService.OWNER_FIELD,
                EventsAppService.ownerPayload(project.getOwnerAccountId()));
        payload.put(EventsAppService.OWNER_GUEST_FIELD,
                EventsAppService.ownerPayload(project.getOwnerGuestId()));
        payload.put(EventsAppService.RUN_FIELD, runId);
        return payload;
    }

    /**
     * 终态失败的用户面原因映射（#355）：常见内部错误形态映射为一句人话定型
     * （机制检查口径——机制为体验服务：失败终态必须「原因可理解」，用户借此
     * 分辨该点重试还是找服务）；未命中形态截首行定宽 120（#229 失败留痕同款
     * 截断口径）。平台分析口径的原文原样走终态日志与失败留账，不经本映射。
     */
    static String userFacingRunFailure(String lastError) {
        if (lastError == null || lastError.isBlank()) {
            return null;
        }
        if (lastError.contains("活超时判死") || lastError.contains("纯静默超限")) {
            return "生成长时间没有进展，已自动停止";
        }
        if (lastError.contains("单条模型流总时长超上限")) {
            return "模型响应超时，已自动停止";
        }
        if (lastError.contains("8081 不可达")) {
            return "应用服务未能启动";
        }
        if (lastError.contains("入口文件缺失") || lastError.contains("入口文件残缺")) {
            return "预览入口页面未写完整";
        }
        if (lastError.toLowerCase().contains("api key")) {
            return "模型服务凭证异常，请稍后再试";
        }
        String firstLine = lastError.split("\\R", 2)[0].strip();
        return firstLine.length() <= 120 ? firstLine : firstLine.substring(0, 119) + "…";
    }

    /**
     * error 发射（失败家族——非重试族的失败表达）：意见链收口后派发修正 run 失败
     * （#51 → #82 失败家族归位：dispatch-failed 阶段族退役，失败信号归本事件）——
     * 意见锚已消费、不自动重试，用户重提即兜底；锚定收口对话轮的 runId（对话面
     * 已登记，失败提示随对话呈现）。
     */
    public void emitError(Project project, String runId, String message) {
        eventsAppService.publishAgentEvent(AgentEventTypes.ERROR, Map.of(
                EventsAppService.PROJECT_FIELD, project.getId().toString(),
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId()),
                EventsAppService.OWNER_GUEST_FIELD,
                EventsAppService.ownerPayload(project.getOwnerGuestId()),
                EventsAppService.RUN_FIELD, runId,
                AgentEventTypes.ERROR_MESSAGE_FIELD, message));
    }

    /**
     * guide-reply 发射（兜底轻引导，#47 入口三分类）：平台侧定型文案直达对话面
     * （带标签气泡，非智能体话语）；runId 为派发锚（随派发响应同值返回），
     * label 为呈现标签（「平台」，非智能体角色）。prompt 随事件携带供重放重建
     * 对话面——可空（#360 在途意见回执复用本型：用户气泡已由意见轮自落，
     * 平台只补一条回执，不重复重建用户输入；空即不携带，前端不落用户气泡）。
     */
    public void emitGuideReply(Project project, String runId, String prompt,
            String label, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(EventsAppService.PROJECT_FIELD, project.getId().toString());
        payload.put(EventsAppService.OWNER_FIELD,
                EventsAppService.ownerPayload(project.getOwnerAccountId()));
        payload.put(EventsAppService.OWNER_GUEST_FIELD,
                EventsAppService.ownerPayload(project.getOwnerGuestId()));
        payload.put(EventsAppService.RUN_FIELD, runId);
        if (prompt != null) {
            payload.put(AgentEventTypes.GUIDE_PROMPT_FIELD, prompt);
        }
        payload.put(AgentEventTypes.GUIDE_LABEL_FIELD, label);
        payload.put(AgentEventTypes.GUIDE_TEXT_FIELD, text);
        eventsAppService.publishAgentEvent(AgentEventTypes.GUIDE_REPLY, payload);
    }

    /**
     * acceptance-start 发射（#87 受理动作卡）：受理轮（迭代期意见轮）开场的受理
     * 事实——意见已接住、主智能体正在受理（追问或改 PRD 的过程呈现位，衔接收口
     * 自动派的更新 run 工作消息）。纯呈现事件：受理落定不出新事件（该轮
     * run-finish / error 即收口）；发射走 {@link #publishQuietly}——失败只记日志
     * 不断受理（其余 emit* 是语义关键事件，失败如实上抛不静默）。
     */
    public void emitAcceptanceStarted(Project project, String runId) {
        publishQuietly(AgentEventTypes.ACCEPTANCE_START, Map.of(
                EventsAppService.PROJECT_FIELD, project.getId().toString(),
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId()),
                EventsAppService.OWNER_GUEST_FIELD,
                EventsAppService.ownerPayload(project.getOwnerGuestId()),
                EventsAppService.RUN_FIELD, runId));
    }

    /**
     * 定稿收尾卡发射（#291 定稿机制——平台侧显式动作的收口呈现）：定稿不是智能体
     * run（无 run-start、无过程事件），收尾卡经 {@code run-finish} 载 closing 直达
     * 对话流——事件封闭集零新增（收口即 run-finish 的既有语义，#88 收尾卡复用）。
     * runId ＝ 定稿锚（成版 commit 的 Run-Id trailer 对偶锚定本卡——版本详情联接
     * 对话史 closing 条目同锚）；sessionId 携该设计物的设计会话标识（归属可读，
     * 非续跑锚）。发射走 {@link #publishQuietly}——呈现面失败不牵连定稿事实
     * （件状态与成版已落定，回访经对话史水合）。
     */
    public void emitClosingCard(Project project, String runId,
            String sessionId, Map<String, Object> closing) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(EventsAppService.PROJECT_FIELD, project.getId().toString());
        payload.put(EventsAppService.OWNER_FIELD,
                EventsAppService.ownerPayload(project.getOwnerAccountId()));
        payload.put(EventsAppService.OWNER_GUEST_FIELD,
                EventsAppService.ownerPayload(project.getOwnerGuestId()));
        payload.put(EventsAppService.RUN_FIELD, runId);
        payload.put(AgentEventTypes.SESSION_FIELD, sessionId);
        payload.put(AgentEventTypes.CLOSING_FIELD, closing);
        publishQuietly(AgentEventTypes.RUN_FINISH, payload);
    }

    /**
     * 纯呈现事件的静默发射（#87）：失败只记日志不上抛——呈现面失败不牵连受理/
     * 对话本身（SSE 是「让 UI 活」的面，不承担正确性；sink 护栏同款，收拢单点）。
     */
    private void publishQuietly(String type, Map<String, Object> payload) {
        try {
            eventsAppService.publishAgentEvent(type, payload);
        }
        catch (RuntimeException e) {
            log.warn("[agent-event] 事件发射失败（{}）：{}", type, e.getMessage());
        }
    }

    /** 关联字段注入（透传不解释；事件序在前——寻址字段不覆盖事件本体字段）。 */
    private static Map<String, Object> withCorrelation(Map<String, Object> payload,
                                                       Map<String, Object> correlation) {
        if (correlation == null || correlation.isEmpty()) {
            return new LinkedHashMap<>(payload);
        }
        Map<String, Object> addressed = new LinkedHashMap<>(correlation);
        addressed.putAll(payload);
        return addressed;
    }
}
