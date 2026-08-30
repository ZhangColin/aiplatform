package com.aieducenter.aiplatform.base.eventhub.application;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;

/**
 * 智能体流通道语义（SSE事件清单·通道二）：{@code GET /api/agent-events} 的订阅与
 * 发射入口。eventhub 是唯一 SSE 管道、双通道合一（平台通知 + 智能体流）——本服务
 * 管智能体流通道，通知通道归 {@link PlatformNotificationAppService}，两通道共用
 * {@link SseChannelHub} 传输内核。
 *
 * <p>通道是<b>带近期帧缓冲的热流</b>（{@code Flux.replay(N)} 语义）：构造期向内核
 * 注册重放缓冲（容量 {@link AgentStreamProperties}，默认 1000 帧）——帧一经发射即进
 * per-channel 有界缓冲（零订阅时也进，seq 照常分配），新连接（无 Last-Event-ID）先收
 * 命中订阅过滤的最近缓冲帧（原事件 id）再无缝进实时流；带 Last-Event-ID 的断线重连
 * 不补发、维持 REST 重查兜底（分野由订阅端点按请求头裁定，本层只见 replay 开关）。
 * 缓冲为单实例内存态（重启即失），多实例化时需重估。</p>
 *
 * <ul>
 *   <li>关联字段 {@code runId}：智能体流事件 payload 必带（mapper 产出时已盖上）、
 *       streamId 同值（事件 id 取 {@code {runId}:{seq}}）；{@code projectId} 由
 *       业务编排桥接注入（透传字段，过滤位同名）</li>
 *   <li>订阅过滤：{@code ?projectId=} / {@code ?runId=}（与 payload 字段同名，
 *       可叠用 AND；缺省全量，「看某个运行才挂」即 ?runId=）——对重放帧同样
 *       生效，不泄漏别的项目/运行的帧</li>
 *   <li>发射制：业务编排（BA 访谈等）的流桥在执行线程透传</li>
 * </ul>
 *
 * <p>事件 type 名册见 docs/spec/SSE事件清单.md（代码侧引用 {@code AgentEventTypes}
 * 常量，禁止字符串字面量散落）。</p>
 */
@Service
public class AgentStreamAppService {

    /** 智能体流通道名（内核按名泛化，与通知通道是两回事）。 */
    public static final String CHANNEL = "agent-stream";

    /** 必带关联字段（事件 id 的 streamId 同值；正本 = {@link AgentEventTypes#RUN_FIELD}）。 */
    public static final String RUN_FIELD = AgentEventTypes.RUN_FIELD;

    /** 业务编排桥接注入的透传关联字段（过滤位同名）。 */
    public static final String PROJECT_FIELD = "projectId";

    private final SseChannelHub hub;

    public AgentStreamAppService(SseChannelHub hub, AgentStreamProperties properties) {
        this.hub = hub;
        // 注册面即「哪些通道补发」的唯一定义处（内核 opt-in）：智能体流 = 带近期帧
        // 缓冲的热流。构造期注册，先于任何订阅/广播（内核 fail-fast 约定）
        hub.registerReplay(CHANNEL, properties.getReplayDepth());
    }

    /**
     * 订阅智能体流。过滤参数可单用可叠用（AND），均为空 = 全量。replay：新连接
     * （无 Last-Event-ID）开——先收命中过滤谓词的最近缓冲帧再进实时流；重连关——
     * 不重放，REST 重查兜底（分野由订阅端点按 Last-Event-ID 请求头裁定）。
     */
    public SseEmitter subscribe(String projectId, String runId, boolean replay) {
        return hub.subscribe(CHANNEL, payload ->
                matches(payload, PROJECT_FIELD, projectId)
                        && matches(payload, RUN_FIELD, runId), replay);
    }

    /**
     * 发射一帧智能体流事件（fire-and-forget）。payload 必带关联字段 runId——本层
     * fail-fast；payload 内禁 type 键名由信封 {@code EventEnvelope} 构造时统一拒收
     * （内核落地的信封契约）。
     */
    public void publish(String type, Map<String, Object> payload) {
        Object runId = payload.get(RUN_FIELD);
        if (runId == null || runId.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "智能体流 payload 必带关联字段 " + RUN_FIELD + "（SSE事件清单·信封）");
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

    private static boolean matches(Map<String, Object> payload, String field, String expected) {
        return expected == null || expected.isBlank()
                || Objects.equals(String.valueOf(payload.get(field)), expected);
    }
}
