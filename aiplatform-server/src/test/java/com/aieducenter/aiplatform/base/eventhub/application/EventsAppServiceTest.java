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
 * 智能体事件族（runId 关联 + 断线补发：注册缓冲 + 锚点窗口 + 容量配置，#89 起
 * 重放缓冲降级——新连接/刷新不补发，对话史经 REST 水合）与平台通知族（projectId
 * 关联 + 永不补发：不进缓冲）。族投递规则：智能体事件只投给带过滤（projectId/
 * runId）的订阅，未过滤订阅只收通知族。
 *
 * <p>ADR-0018（#208 通道按订阅者隔离）：订阅握手绑定登录账号，每事件以
 * {@code ownerAccountId} 路由键携带归属，投递谓词统一叠加「事件归属 == 订阅者」
 * 匹配（实时与重放同谓词）；两个发布口对归属键强制校验、漏传发布期即炸。未过滤
 * 订阅语义 = 我的全部通知；他人 projectId 过滤订阅 = 静默空流。用真内核 + 记录
 * sender 验证接线（通道语义归应用层，内核零业务概念）。</p>
 */
class EventsAppServiceTest {

    /** 测试订阅者 A / B 的归属账号（双用户隔离断言的两端）。 */
    private static final String OWNER = "u-owner";
    private static final String OTHER_OWNER = "u-other";

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

    // ---------- 发射契约（两族各自的必带关联字段 + 归属路由键） ----------

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

    @Test
    void given_payload_without_owner_when_publish_notification_then_rejected() {
        // 归属路由键与 projectId 同级强制校验（ADR-0018：漏传发布期即炸）
        assertThatThrownBy(() -> appService.publishNotification("preview-ready",
                Map.of("projectId", "p1", "url", "http://localhost:30080")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerAccountId");
    }

    @Test
    void given_payload_without_owner_when_publish_agent_event_then_rejected() {
        assertThatThrownBy(() -> appService.publishAgentEvent("run-start",
                Map.of("runId", "run-1", "prompt", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerAccountId");
    }

    @Test
    void given_blank_owner_when_publish_notification_then_rejected() {
        assertThatThrownBy(() -> appService.publishNotification("preview-ready",
                Map.of("projectId", "p1", EventsAppService.OWNER_FIELD, " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerAccountId");
    }

    // ---------- 同流两族：id 口径（通知 {projectId}:{seq}，智能体 {runId}:{seq}） ----------

    @Test
    void given_both_families_published_when_subscribed_then_single_stream_carries_both() {
        SseEmitter subscriber = appService.subscribe(OWNER, "p1", null, null);

        appService.publishNotification("project-renamed", Map.of(
                "projectId", "p1", "projectName", "新名字", EventsAppService.OWNER_FIELD, OWNER));
        appService.publishAgentEvent("run-start", Map.of(
                "runId", "run-9", "projectId", "p1", "prompt", "写个落地页",
                EventsAppService.OWNER_FIELD, OWNER));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("p1:1", "run-9:1");
    }

    @Test
    void given_subscribe_without_project_id_when_publish_any_project_then_own_notifications_all_received() {
        // 通知族缺省全量（ADR-0001 寻址：开发平台视角）——ADR-0018 起 = 我的全部通知
        SseEmitter subscriber = appService.subscribe(OWNER, null, null, null);

        appService.publishNotification("project-renamed", Map.of(
                "projectId", "p1", "projectName", "名字一", EventsAppService.OWNER_FIELD, OWNER));
        appService.publishNotification("project-renamed", Map.of(
                "projectId", "p2", "projectName", "名字二", EventsAppService.OWNER_FIELD, OWNER));

        assertThat(sender.eventFramesOf(subscriber)).hasSize(2);
    }

    @Test
    void given_subscribe_with_project_id_when_publish_other_project_then_filtered_out() {
        SseEmitter subscriber = appService.subscribe(OWNER, "p1", null, null);

        appService.publishNotification("project-renamed", Map.of(
                "projectId", "p2", "projectName", "名字二", EventsAppService.OWNER_FIELD, OWNER));
        appService.publishNotification("project-renamed", Map.of(
                "projectId", "p1", "projectName", "名字一", EventsAppService.OWNER_FIELD, OWNER));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("p1:1"); // p2 事件被过滤，p1 序列不受影响
    }

    // ---------- 族投递规则：智能体事件只投给带过滤的订阅 ----------

    @Test
    void given_unfiltered_subscription_when_agent_events_published_then_not_delivered() {
        // 站点级常开连接（无过滤）只收通知族——智能体过程细节是项目内事实，
        // 不进未过滤订阅（也不进其重放，见下）
        SseEmitter siteWide = appService.subscribe(OWNER, null, null, "run-0:0");

        appService.publishAgentEvent("run-start", Map.of(
                "runId", "run-1", "projectId", "p1", "prompt", "x",
                EventsAppService.OWNER_FIELD, OWNER));
        appService.publishNotification("project-renamed", Map.of(
                "projectId", "p1", "projectName", "名字", EventsAppService.OWNER_FIELD, OWNER));

        assertThat(sender.eventFramesOf(siteWide))
                .extracting(SseServerEvent::id)
                .containsExactly("p1:1");
    }

    @Test
    void given_run_filter_when_publish_other_run_then_filtered_out() {
        // 「看某个运行才挂」：?runId= 过滤（与 payload 关联字段同名）
        SseEmitter subscriber = appService.subscribe(OWNER, null, "run-1", null);

        appService.publishAgentEvent("run-start", Map.of(
                "runId", "run-2", "prompt", "x", EventsAppService.OWNER_FIELD, OWNER));
        appService.publishAgentEvent("run-start", Map.of(
                "runId", "run-1", "prompt", "y", EventsAppService.OWNER_FIELD, OWNER));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1");
    }

    @Test
    void given_project_and_run_filters_when_publish_then_both_must_match() {
        // projectId 是业务桥接注入的透传字段——过滤位 AND 语义
        SseEmitter subscriber = appService.subscribe(OWNER, "proj-1", "run-1", null);

        appService.publishAgentEvent("run-start", Map.of(
                "runId", "run-1", "prompt", "x", EventsAppService.OWNER_FIELD, OWNER));
        appService.publishAgentEvent("run-start", Map.of(
                "runId", "run-1", "projectId", "proj-1", "prompt", "y",
                EventsAppService.OWNER_FIELD, OWNER));

        assertThat(sender.eventFramesOf(subscriber)).hasSize(1);
        assertThat(sender.eventFramesOf(subscriber).get(0).id()).isEqualTo("run-1:2");
    }

    // ---------- 订阅者归属隔离（ADR-0018，#208） ----------

    @Test
    void given_unfiltered_subscription_of_owner_when_publish_other_owner_then_not_delivered() {
        // 站点级常开订阅（未过滤）只收「我的」通知——别人项目（owner 不同）不达
        SseEmitter mine = appService.subscribe(OWNER, null, null, null);

        appService.publishNotification("project-renamed", Map.of(
                "projectId", "p-other", "projectName", "别人的项目", EventsAppService.OWNER_FIELD, OTHER_OWNER));
        appService.publishNotification("project-renamed", Map.of(
                "projectId", "p-mine", "projectName", "我的项目", EventsAppService.OWNER_FIELD, OWNER));

        assertThat(sender.eventFramesOf(mine))
                .extracting(SseServerEvent::id)
                .containsExactly("p-mine:1");
    }

    @Test
    void given_other_owner_subscribes_with_my_project_filter_when_own_events_published_then_silent_empty_stream() {
        // 第二泄漏面收口：别人用我的 projectId 建过滤订阅 = 静默空流（连接不断）
        SseEmitter intruder = appService.subscribe(OTHER_OWNER, "p-mine", null, null);

        appService.publishNotification("project-renamed", Map.of(
                "projectId", "p-mine", "projectName", "我的项目", EventsAppService.OWNER_FIELD, OWNER));
        appService.publishAgentEvent("run-start", Map.of(
                "runId", "run-mine", "projectId", "p-mine", "prompt", "我的对话内容流",
                EventsAppService.OWNER_FIELD, OWNER));

        assertThat(sender.eventFramesOf(intruder)).isEmpty(); // 通知族与智能体族（对话内容）皆不达
    }

    @Test
    void given_my_project_filter_subscription_when_publish_own_both_families_then_received() {
        // 本人 projectId 过滤订阅照常收到该项目的两族（隔离不伤既有过滤语义）
        SseEmitter mine = appService.subscribe(OWNER, "p-mine", null, null);

        appService.publishNotification("preview-ready", Map.of(
                "projectId", "p-mine", "url", "http://localhost:30080",
                EventsAppService.OWNER_FIELD, OWNER));
        appService.publishAgentEvent("run-start", Map.of(
                "runId", "run-mine", "projectId", "p-mine", "prompt", "x",
                EventsAppService.OWNER_FIELD, OWNER));

        assertThat(sender.eventFramesOf(mine))
                .extracting(SseServerEvent::id)
                .containsExactly("p-mine:1", "run-mine:1");
    }

    // ---------- 合并通道不合并语义：智能体族断线补发、通知族永不补发 ----------

    /**
     * 事故场景（#53 真机时序的断线面）：零订阅窗口（断线）发出的 error 事件进
     * 缓冲——重连（锚已被逐出/丢失）补发整段缓冲，事件必须到达（补发非重发，
     * id 即原事件 id）。补发同样过订阅谓词——别的项目的事件不泄漏。
     */
    @Test
    void given_agent_event_at_zero_subscribers_when_reconnect_then_event_replayed() {
        appService.publishAgentEvent(AgentEventTypes.ERROR, Map.of(
                EventsAppService.PROJECT_FIELD, "7",
                EventsAppService.RUN_FIELD, "run-9",
                EventsAppService.OWNER_FIELD, OWNER,
                "message", "Failed to create model: DEEPSEEK_API_KEY is required"));
        appService.publishAgentEvent(AgentEventTypes.ERROR, Map.of(
                EventsAppService.PROJECT_FIELD, "8",
                EventsAppService.RUN_FIELD, "run-10",
                EventsAppService.OWNER_FIELD, OWNER,
                "message", "别的项目的事件"));

        SseEmitter subscription = appService.subscribe(OWNER, "7", null, "run-0:0");

        assertThat(sender.eventFramesOf(subscription)).hasSize(1);
        SseServerEvent frame = sender.eventFramesOf(subscription).get(0);
        assertThat(frame.id()).isEqualTo("run-9:1");   // 补发事件与实时事件同一 id 口径
        EventEnvelope envelope = (EventEnvelope) frame.data();
        assertThat(envelope.type()).isEqualTo(AgentEventTypes.ERROR);
        assertThat(envelope.payload())
                .containsEntry("projectId", "7")
                .containsEntry("runId", "run-9");
    }

    /**
     * 重放同谓词（ADR-0018）：同一 projectId 下，别人（owner 不同）的智能体事件
     * 不进重放缓冲命中窗口——断线补发也只补本人。
     */
    @Test
    void given_agent_event_of_other_owner_when_reconnect_with_own_filter_then_not_replayed() {
        appService.publishAgentEvent(AgentEventTypes.PART_TEXT, Map.of(
                EventsAppService.PROJECT_FIELD, "7",
                EventsAppService.RUN_FIELD, "run-9",
                EventsAppService.OWNER_FIELD, OTHER_OWNER,
                "text", "别家同 projectId 的对话内容"));
        appService.publishAgentEvent(AgentEventTypes.PART_TEXT, Map.of(
                EventsAppService.PROJECT_FIELD, "7",
                EventsAppService.RUN_FIELD, "run-9",
                EventsAppService.OWNER_FIELD, OWNER,
                "text", "我的对话内容"));

        SseEmitter subscription = appService.subscribe(OWNER, "7", null, "run-0:0");

        assertThat(sender.eventFramesOf(subscription)).hasSize(1);
        EventEnvelope envelope = (EventEnvelope) sender.eventFramesOf(subscription).get(0).data();
        assertThat(envelope.payload()).containsEntry("text", "我的对话内容");
    }

    /**
     * 通知族「只作实时呈现，状态以查询为准」：不进缓冲——重连也不补发（与智能体
     * 事件族同一连接上语义分家，断线由 REST 重查收敛）。
     */
    @Test
    void given_notification_at_zero_subscribers_when_reconnect_then_not_replayed() {
        appService.publishNotification("project-renamed", Map.of(
                "projectId", "7", "projectName", "名字", EventsAppService.OWNER_FIELD, OWNER));

        SseEmitter latecomer = appService.subscribe(OWNER, "7", null, "run-0:0");

        assertThat(sender.eventFramesOf(latecomer)).isEmpty();
    }

    /** 新连/重连分野的通道层对应：新连接（无 Last-Event-ID——刷新/回访）不补发
     *  （#89：对话史经 REST 水合，重放缓冲只承担断线窗口）。 */
    @Test
    void given_buffered_agent_events_when_fresh_connection_then_no_backlog() {
        appService.publishAgentEvent("run-start", Map.of(
                "runId", "run-1", "prompt", "x", EventsAppService.OWNER_FIELD, OWNER));

        SseEmitter fresh = appService.subscribe(OWNER, null, "run-1", null);

        assertThat(sender.eventFramesOf(fresh)).isEmpty();
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

    /** 容量可配（app.agent-events.replay-depth）：depth=2 → 3 条只补发最近 2 条。 */
    @Test
    void given_replay_depth_2_when_publish_3_agent_events_then_only_last_2_replayed() {
        AgentEventProperties properties = new AgentEventProperties();
        properties.setReplayDepth(2);
        EventsAppService shallow = newService(properties);
        for (int i = 1; i <= 3; i++) {
            shallow.publishAgentEvent("text", Map.of(
                    "runId", "run-1", "data", Map.of("delta", "块" + i),
                    EventsAppService.OWNER_FIELD, OWNER));
        }

        SseEmitter late = shallow.subscribe(OWNER, null, "run-1", "run-0:0");

        assertThat(sender.eventFramesOf(late))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:2", "run-1:3");
    }
}
