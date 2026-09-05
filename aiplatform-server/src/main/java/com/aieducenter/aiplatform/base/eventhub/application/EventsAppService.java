package com.aieducenter.aiplatform.base.eventhub.application;

import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;

/**
 * SSE 事件通道语义（SSE事件清单·单端点单流，#82 合并）：{@code GET /api/events}
 * 的订阅与发射入口——一条流承载两族事件，<b>合并通道不合并语义</b>：
 *
 * <ul>
 *   <li><b>智能体事件族</b>（{@link #publishAgentEvent}）：关联字段 {@code runId}
 *       必带（{@code projectId} 由业务编排桥接注入）；进近期事件缓冲（断线补发族）
 *       ——断线重连（Last-Event-ID 在场）先收缓冲中锚事件之后命中订阅过滤的事件
 *       （断线窗口），新连接（刷新）不补发（对话史经 REST 水合，#89——重放缓冲
 *       只承担断线窗口，不再承担刷新重建）。streamId = runId（id 行
 *       {@code {runId}:{seq}}）。</li>
 *   <li><b>平台通知族</b>（{@link #publishNotification}）：关联字段 {@code projectId}
 *       必带；<b>不进缓冲</b>（{@code broadcastUnbuffered}）——只达实时订阅、新连接
 *       不补发，「通知只作实时呈现、状态以查询为准」。streamId = projectId。</li>
 * </ul>
 *
 * <p><b>订阅过滤与族投递</b>：{@code ?projectId=} / {@code ?runId=}（与 payload
 * 关联字段同名，可叠用 AND；缺省 = 通知族全量）。智能体事件族只投递给带过滤
 * （projectId 或 runId）的订阅——过程细节是项目内事实，无跨项目消费面；未过滤
 * 订阅（站点级常开连接）只收平台通知族。Last-Event-ID 请求头作新连/重连分野：
 * 有值（浏览器断线重连自动携带）= 断线补发（缓冲中锚事件之后的窗口），无值
 * （新连接/刷新）= 不补发（#89 起——对话史水合归 REST，重放缓冲降级为断线补发）。</p>
 *
 * <p>发射制：业务编排层在副作用真实落定后调用（base 区不发 SSE）；事件 type
 * 名册见 docs/spec/SSE事件清单.md（代码侧引用 {@code XxxEventTypes} 常量类，
 * 禁止字符串字面量散落）。</p>
 */
@Service
public class EventsAppService {

    /** 事件通道名（单端点单流，内核按名泛化）。 */
    public static final String CHANNEL = "events";

    /** 通知族关联字段（payload 字段与订阅过滤参数同名，ADR-0001 寻址）。 */
    public static final String PROJECT_FIELD = "projectId";

    /** 智能体事件族关联字段（正本 = {@link AgentEventTypes#RUN_FIELD}）。 */
    public static final String RUN_FIELD = AgentEventTypes.RUN_FIELD;

    private final SseChannelHub hub;

    public EventsAppService(SseChannelHub hub, AgentEventProperties properties) {
        this.hub = hub;
        // 智能体事件族 = 带近期事件缓冲的热流（断线补发族，#89）：构造期注册，先于
        // 任何订阅/广播（内核 fail-fast 约定）；通知族经 broadcastUnbuffered 逐发射豁免
        hub.registerReplay(CHANNEL, properties.getReplayDepth());
    }

    /**
     * 订阅事件流（单端点单流，两族混载）。过滤参数可单用可叠用（AND），均为空 =
     * 只收平台通知族（智能体事件族不投递给未过滤订阅）。断线补发（#89 重放缓冲
     * 降级）：lastEventId 非空（浏览器断线重连自动携带）——先收缓冲中锚事件之后
     * 命中过滤谓词的智能体事件（断线窗口，锚不在缓冲则整段缓冲）再进实时流；空
     * （新连接/刷新）不补发——对话史经 REST 水合，重放缓冲只承担断线窗口、不再
     * 承担刷新重建。补发谓词与实时谓词同一（含族投递规则），通知族不在缓冲、
     * 天然不补发。
     */
    public SseEmitter subscribe(String projectId, String runId, String lastEventId) {
        return hub.subscribe(CHANNEL, deliveryPredicate(projectId, runId), lastEventId);
    }

    /**
     * 发射一条平台通知（fire-and-forget）。payload 必带关联字段 projectId——本层
     * fail-fast；不进重放缓冲（新连接不补发，REST 查询兜底）。
     */
    public void publishNotification(String type, Map<String, Object> payload) {
        Object projectId = payload.get(PROJECT_FIELD);
        if (projectId == null || projectId.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "平台通知 payload 必带关联字段 " + PROJECT_FIELD + "（SSE事件清单·信封）");
        }
        hub.broadcastUnbuffered(CHANNEL, projectId.toString(), type, payload);
    }

    /**
     * 发射一条智能体事件（fire-and-forget）。payload 必带关联字段 runId——本层
     * fail-fast；进重放缓冲（新连接按订阅过滤补发，断线重连不补发）。
     */
    public void publishAgentEvent(String type, Map<String, Object> payload) {
        Object runId = payload.get(RUN_FIELD);
        if (runId == null || runId.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "智能体事件 payload 必带关联字段 " + RUN_FIELD + "（SSE事件清单·信封）");
        }
        hub.broadcast(CHANNEL, runId.toString(), type, payload);
    }

    /**
     * runId 生成（业务编排层用）：TSID 十进制字符串——编排层与底座同构的唯一生成口
     * （SSE id / 库列 / 日志共用形）。
     */
    public static String newRunId() {
        return Long.toString(TsidGenerator.newInstance().generate());
    }

    /**
     * 投递谓词（实时与重放同源）：字段过滤 AND + 族投递规则——智能体事件族
     * （payload 携带 runId）只投递给带过滤的订阅，未过滤订阅只收通知族。
     */
    private static Predicate<Map<String, Object>> deliveryPredicate(
            String projectId, String runId) {
        boolean filtered = notBlank(projectId) || notBlank(runId);
        return payload -> matches(payload, PROJECT_FIELD, projectId)
                && matches(payload, RUN_FIELD, runId)
                && (filtered || payload.get(RUN_FIELD) == null);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean matches(Map<String, Object> payload, String field, String expected) {
        return expected == null || expected.isBlank()
                || Objects.equals(String.valueOf(payload.get(field)), expected);
    }
}
