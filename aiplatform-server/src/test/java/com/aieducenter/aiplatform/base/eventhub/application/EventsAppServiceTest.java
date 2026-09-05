package com.aieducenter.aiplatform.base.eventhub.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.RecordingSseSender;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseServerEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事件通道语义（SSE事件清单·单端点单流，#82 双通道合并）：一条流两族事件——
 * 智能体事件族（runId 关联 + 近期事件重放：注册缓冲 + 新连/重连分野 + 容量配置）
 * 与平台通知族（projectId 关联 + 永不补发：不进缓冲，新连接零补发）。族投递规则：
 * 智能体事件只投给带过滤（projectId/runId）的订阅，未过滤订阅只收通知族。
 * 用真内核 + 记录 sender 验证接线（通道语义归应用层，内核零业务概念）。
 */
class EventsAppServiceTest {

    private final RecordingSseSender sender = new RecordingSseSender();
    private final List<SseChannelHub> hubs = new ArrayList<>();
    private EventsAppService appService;

    @BeforeEach
    void setUp() {
        appService = newService(new AgentEventProperties());
    }

    @AfterEach
    void tearDown() {
        hubs.forEach(SseChannelHub::shutdown);
    }

    private EventsAppService newService(AgentEventProperties properties) {
        SseChannelHub hub = new SseChannelHub(sender, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                Duration.ofSeconds(600));
        hubs.add(hub);
        return new EventsAppService(hub, properties);
    }

    // ---------- 发射契约（两族各自的必带关联字段） ----------

    @Test
    void given_payload_without_run_id_when_publish_agent_event_then_rejected() {
        assertThatThrownBy(() -> appService.publishAgentEvent("run-start", Map.of("prompt", "写个落地页")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void given_payload_without_project_id_when_publish_notification_then_rejected() {
        assertThatThrownBy(() -> appService.publishNotification("preview-ready",
                Map.of("url", "http://localhost:30080")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    void given_blank_project_id_when_publish_notification_then_rejected() {
        assertThatThrownBy(() -> appService.publishNotification("preview-ready",
                Map.of("projectId", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    // ---------- 同流两族：id 口径（通知 {projectId}:{seq}，智能体 {runId}:{seq}） ----------

    @Test
    void given_both_families_published_when_subscribed_then_single_stream_carries_both() {
        SseEmitter subscriber = appService.subscribe("p1", null, false);

        appService.publishNotification("project-renamed", Map.of("projectId", "p1", "projectName", "新名字"));
        appService.publishAgentEvent("run-start", Map.of("runId", "run-9", "projectId", "p1", "prompt", "写个落地页"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("p1:1", "run-9:1");
    }

    @Test
    void given_subscribe_without_project_id_when_publish_any_project_then_notifications_all_received() {
        // 通知族缺省全量（ADR-0001 寻址：开发平台视角）
        SseEmitter subscriber = appService.subscribe(null, null, false);

        appService.publishNotification("project-renamed", Map.of("projectId", "p1", "projectName", "名字一"));
        appService.publishNotification("project-renamed", Map.of("projectId", "p2", "projectName", "名字二"));

        assertThat(sender.eventFramesOf(subscriber)).hasSize(2);
    }

    @Test
    void given_subscribe_with_project_id_when_publish_other_project_then_filtered_out() {
        SseEmitter subscriber = appService.subscribe("p1", null, false);

        appService.publishNotification("project-renamed", Map.of("projectId", "p2", "projectName", "名字二"));
        appService.publishNotification("project-renamed", Map.of("projectId", "p1", "projectName", "名字一"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("p1:1"); // p2 事件被过滤，p1 序列不受影响
    }

    // ---------- 族投递规则：智能体事件只投给带过滤的订阅 ----------

    @Test
    void given_unfiltered_subscription_when_agent_events_published_then_not_delivered() {
        // 站点级常开连接（无过滤）只收通知族——智能体过程细节是项目内事实，
        // 不进未过滤订阅（也不进其重放，见下）
        SseEmitter siteWide = appService.subscribe(null, null, true);

        appService.publishAgentEvent("run-start", Map.of("runId", "run-1", "projectId", "p1", "prompt", "x"));
        appService.publishNotification("project-renamed", Map.of("projectId", "p1", "projectName", "名字"));

        assertThat(sender.eventFramesOf(siteWide))
                .extracting(SseServerEvent::id)
                .containsExactly("p1:1");
    }

    @Test
    void given_run_filter_when_publish_other_run_then_filtered_out() {
        // 「看某个运行才挂」：?runId= 过滤（与 payload 关联字段同名）
        SseEmitter subscriber = appService.subscribe(null, "run-1", false);

        appService.publishAgentEvent("run-start", Map.of("runId", "run-2", "prompt", "x"));
        appService.publishAgentEvent("run-start", Map.of("runId", "run-1", "prompt", "y"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1");
    }

    @Test
    void given_project_and_run_filters_when_publish_then_both_must_match() {
        // projectId 是业务桥接注入的透传字段——过滤位 AND 语义
        SseEmitter subscriber = appService.subscribe("proj-1", "run-1", false);

        appService.publishAgentEvent("run-start", Map.of("runId", "run-1", "prompt", "x"));
        appService.publishAgentEvent("run-start",
                Map.of("runId", "run-1", "projectId", "proj-1", "prompt", "y"));

        assertThat(sender.eventFramesOf(subscriber)).hasSize(1);
        assertThat(sender.eventFramesOf(subscriber).get(0).id()).isEqualTo("run-1:2");
    }

    // ---------- 合并通道不合并语义：智能体族重放、通知族永不补发 ----------

    /**
     * 事故回归（#53 真机时序，#52 同路径）：建项目后 BA 起跑即死——error 事件（带
     * projectId 关联）在零订阅时发出，彼时浏览器还在导航/首编译；项目页就绪后按
     * projectId 建立订阅，事件必须到达（重放非重发，id 即原事件 id）。重放同样过
     * 订阅谓词——别的项目的事件不泄漏。
     */
    @Test
    void given_agent_event_at_zero_subscribers_when_delayed_project_subscribe_then_event_replayed() {
        appService.publishAgentEvent(AgentEventTypes.ERROR, Map.of(
                EventsAppService.PROJECT_FIELD, "7",
                EventsAppService.RUN_FIELD, "run-9",
                "message", "Failed to create model: DEEPSEEK_API_KEY is required"));
        appService.publishAgentEvent(AgentEventTypes.ERROR, Map.of(
                EventsAppService.PROJECT_FIELD, "8",
                EventsAppService.RUN_FIELD, "run-10",
                "message", "别的项目的事件"));

        SseEmitter subscription = appService.subscribe("7", null, true);

        assertThat(sender.eventFramesOf(subscription)).hasSize(1);
        SseServerEvent frame = sender.eventFramesOf(subscription).get(0);
        assertThat(frame.id()).isEqualTo("run-9:1");   // 重放事件与实时事件同一 id 口径
        EventEnvelope envelope = (EventEnvelope) frame.data();
        assertThat(envelope.type()).isEqualTo(AgentEventTypes.ERROR);
        assertThat(envelope.payload())
                .containsEntry("projectId", "7")
                .containsEntry("runId", "run-9");
    }

    /**
     * 通知族「只作实时呈现，状态以查询为准」：零订阅窗口发出的通知不进缓冲——
     * 新连接（重拉开）不补发（与智能体事件族同一连接上语义分家）。
     */
    @Test
    void given_notification_at_zero_subscribers_when_new_connection_then_not_replayed() {
        appService.publishNotification("project-renamed", Map.of("projectId", "7", "projectName", "名字"));

        SseEmitter latecomer = appService.subscribe("7", null, true);

        assertThat(sender.eventFramesOf(latecomer)).isEmpty();
    }

    /** 重连分野的通道层对应：replay 关（带 Last-Event-ID 的重连）不收缓冲事件。 */
    @Test
    void given_buffered_agent_events_when_subscribe_without_replay_then_no_backlog() {
        appService.publishAgentEvent("run-start", Map.of("runId", "run-1", "prompt", "x"));

        SseEmitter reconnecting = appService.subscribe(null, "run-1", false);

        assertThat(sender.eventFramesOf(reconnecting)).isEmpty();
    }

    // ---------- 重放缓冲容量配置 ----------

    /** 规格值断言：默认重放深度 1000（#53 spec 定值；容量上界即内存上界，防无意改动）。 */
    @Test
    void given_default_properties_when_get_replay_depth_then_1000() {
        assertThat(new AgentEventProperties().getReplayDepth()).isEqualTo(1000);
    }

    /**
     * 配置键真绑定（app.agent-events.replay-depth）：走 Spring Boot Binder 实绑——
     * 前缀/字段名拼写错时字段静默吃默认值，POJO setter 测不出来（#56 AC）。
     */
    @Test
    void given_config_key_when_bind_then_replay_depth_wired() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("app.agent-events.replay-depth", "3"));

        AgentEventProperties bound = new Binder(source)
                .bind("app.agent-events", Bindable.ofInstance(new AgentEventProperties()))
                .get();

        assertThat(bound.getReplayDepth()).isEqualTo(3);
    }

    /** 容量可配（app.agent-events.replay-depth）：depth=2 → 3 条只重放最近 2 条。 */
    @Test
    void given_replay_depth_2_when_publish_3_agent_events_then_only_last_2_replayed() {
        AgentEventProperties properties = new AgentEventProperties();
        properties.setReplayDepth(2);
        EventsAppService shallow = newService(properties);
        for (int i = 1; i <= 3; i++) {
            shallow.publishAgentEvent("text", Map.of("runId", "run-1", "data", Map.of("delta", "块" + i)));
        }

        SseEmitter late = shallow.subscribe(null, "run-1", true);

        assertThat(sender.eventFramesOf(late))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:2", "run-1:3");
    }
}
