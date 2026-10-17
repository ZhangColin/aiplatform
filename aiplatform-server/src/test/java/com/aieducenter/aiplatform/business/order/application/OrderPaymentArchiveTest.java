package com.aieducenter.aiplatform.business.order.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponseFixture;
import com.aieducenter.aiplatform.business.project.application.PrdReadiness;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdReadinessResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归档链集成测试（#158 重试归档为正身；#37/#39 归档独立步骤语义随 #337 mock
 * 支付闭环退役收窄——支付语义并入付款节点凭证核验路径，「已支付＝开工条件
 * 满足」的判定接通归 #338）：跨 BC 写面（项目归档、平台通知）mock 收口，订单
 * 链路 + 知识沉淀（分块 → 真库 knw_chunks）全真——embedding 端口 mock 供给向量
 * （本机 fastembed 不属测试依赖）。钉死：①成功面（重试归档落「已归档」＋操作者
 * 留痕＋项目归档联动＋知识块落库＋补发「已归档」通知）；②项目重复归档拦截面
 * （PRJ_013 原样上抛——订单整行不动、无沉淀）；③知识沉淀降级面（embedding 故障
 * → 归档照常、沉淀跳过不回滚）；④守卫幂等（非已支付 ORD_012、重复触发不产生
 * 重复素材）。已支付态夹具走聚合正身（{@code Order#pay} 直落——#338 接通前的
 * 领域事实构造口）。
 */
@IntegrationTest
class OrderPaymentArchiveTest {

    private static final long PROJECT_ID = 900200L;
    private static final String PRD = """
            # PRD：宠物店官网

            ## 需求背景

            店主希望把线下宠物店搬到线上，顾客可以浏览商品、下单购买。

            ## 功能清单

            1. 商品列表页——按分类浏览商品，展示图片、价格与库存
            2. 购物车页——增删商品、改数量，实时合计金额
            3. 订单确认页——填收货地址，提交后生成订单号""";

    @Autowired
    private OrderAppService appService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 跨 BC 项目读面：mock 掉 docker/工作区依赖（detail 供下单与沉淀取名，prd 供快照与沉淀取文）。 */
    @MockitoBean
    private ProjectQueryAppService projectQueryAppService;

    /** 跨 BC 唯一写交叉：项目归档联动（归档事务的失败面挂点）。 */
    @MockitoBean
    private ProjectLifecycleAppService projectLifecycleAppService;

    /** SSE 发布器：验证订单态变化通知（副作用落定后发射）。 */
    @MockitoBean
    private EventsAppService eventsAppService;

    /** embedding 端口：mock 供给 512 维向量（知识沉淀入库的真向量面）。 */
    @MockitoBean
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void stubProject() {
        when(projectQueryAppService.detail(PROJECT_ID)).thenReturn(ProjectDetailResponseFixture
                .detailOf(Long.toString(PROJECT_ID), "宠物店官网")
                .workspaceId("9200")
                .build());
        when(projectQueryAppService.namesOf(List.of(PROJECT_ID)))
                .thenReturn(Map.of(PROJECT_ID, "宠物店官网")); // 沉淀取名面（namesOf 单查询）
        when(projectQueryAppService.prd(PROJECT_ID)).thenReturn(new PrdResponse(
                Long.toString(PROJECT_ID), PRD, Instant.parse("2026-09-01T01:00:00Z"),
                PrdReadinessResponse.from(PrdReadiness.parse(PRD)), /* draftChangeSummary= */ null));
        // 下单前置双确认缺省桩（#335）：已确认 PRD v1＋Demo v1
        when(projectQueryAppService.confirmedVersionRefs(PROJECT_ID)).thenReturn(
                new ProjectQueryAppService.ConfirmedVersionRefs(1, PRD, 1));
    }

    @AfterEach
    void tearDown() {
        // ord_price_entries 随 FK 级联；知识块/素材登记按项目清（沉淀断言面，#158 起两表）
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM knw_chunks WHERE project_id = ?", Long.toString(PROJECT_ID));
        jdbcTemplate.update("DELETE FROM knw_materials WHERE project_id = ?", Long.toString(PROJECT_ID));
    }

    // ---------- #158：重试归档——卡单补偿（沉淀跟随归档成功） ----------

    @Test
    void given_stuck_paid_order_when_retry_archive_then_archived_project_sunk_and_traced() {
        // 一链：已支付卡单（归档未发生）→ 重试归档（带操作者）→ 订单落已归档＋
        // 操作者留痕 → 项目归档联动 → 知识块落库（sinkPrd 生效）→ 补发「已归档」通知
        stubEmbeddingOk();
        String orderId = paidOrder();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, archived_at FROM ord_orders WHERE id = ?", Long.parseLong(orderId)))
                .containsEntry("status", OrderStatus.PAID.getCode())
                .containsEntry("archived_at", null); // 卡单事实：已支付、未归档

        OrderResponse archived = appService.retryArchive(Long.parseLong(orderId),
                new Operator("700100", "运营·小刘"));

        assertThat(archived.status()).isEqualTo(OrderStatus.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, archived_at, archive_operator_id, archive_operator_name "
                        + "FROM ord_orders WHERE id = ?",
                Long.parseLong(orderId));
        assertThat(row.get("status")).isEqualTo(OrderStatus.ARCHIVED.getCode());
        assertThat(row.get("archived_at")).isNotNull();
        assertThat(row.get("archive_operator_id")).isEqualTo("700100");
        assertThat(row.get("archive_operator_name")).isEqualTo("运营·小刘");
        // 项目归档联动一触（重试归档事务内）
        verify(projectLifecycleAppService, times(1)).archive(PROJECT_ID);
        // 知识块落库（sinkPrd 补调生效）：kind=PRD、幂等键=projectId，素材登记行同落
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(
                "SELECT kind, source_ref FROM knw_chunks WHERE project_id = ?",
                Long.toString(PROJECT_ID));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0)).containsEntry("kind", "PRD")
                .containsEntry("source_ref", Long.toString(PROJECT_ID));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_materials WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID))).isEqualTo(1);
        // 通知口径：下单/报价两发＋重试归档补发「已归档」——补偿闭环
        assertThat(publishedStatuses()).containsExactly(
                OrderStatus.PENDING_QUOTE.getCode(),
                OrderStatus.QUOTED.getCode(),
                OrderStatus.ARCHIVED.getCode());
    }

    @Test
    void given_non_paid_order_when_retry_archive_then_rejected_without_side_effects() {
        // 守卫幂等（幂等由守卫保证）：待报价/已报价/已归档/已取消均非已支付，逐态钉死；
        // 归档联动不被触达、留痕不落半截
        for (OrderStatus status : List.of(OrderStatus.PENDING_QUOTE, OrderStatus.QUOTED,
                OrderStatus.ARCHIVED, OrderStatus.CANCELLED)) {
            String orderId = appService.place(PROJECT_ID).id();
            jdbcTemplate.update("UPDATE ord_orders SET status = ? WHERE id = ?",
                    status.getCode(), Long.parseLong(orderId));

            assertThatThrownBy(() -> appService.retryArchive(Long.parseLong(orderId),
                    new Operator("700100", "运营·小刘")))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.ORDER_ARCHIVE_NOT_ALLOWED.message());
            assertThat(jdbcTemplate.queryForMap(
                    "SELECT status, archive_operator_id FROM ord_orders WHERE id = ?",
                    Long.parseLong(orderId)))
                    .containsEntry("status", status.getCode()) // 状态不被破坏
                    .containsEntry("archive_operator_id", null); // 留痕不落半截
            verify(projectLifecycleAppService, never()).archive(anyLong());
            jdbcTemplate.update("DELETE FROM ord_orders"); // 清场再验下一态
        }
    }

    @Test
    void given_project_already_archived_when_retry_archive_then_propagated_and_no_material() {
        // 项目重复归档被既有错误码拦截（PRJ_013）：无支付事实可保，异常原样上抛——
        // 运营须看见拦截原因，不留静默半成态；订单留已支付、无沉淀
        stubEmbeddingOk();
        String orderId = paidOrder();
        when(projectLifecycleAppService.archive(anyLong()))
                .thenThrow(new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED));

        assertThatThrownBy(() -> appService.retryArchive(Long.parseLong(orderId),
                new Operator("700100", "运营·小刘")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, archived_at, archive_operator_id FROM ord_orders WHERE id = ?",
                Long.parseLong(orderId)))
                .containsEntry("status", OrderStatus.PAID.getCode())
                .containsEntry("archived_at", null)
                .containsEntry("archive_operator_id", null); // 归档事务回滚，订单整行不动
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID))).isZero();
    }

    @Test
    void given_embedding_down_when_retry_archive_then_archive_succeeds_and_sink_degrades() {
        // 沉淀降级面（既有口径不变）：embedding 故障 → 重试归档照常完结、沉淀跳过不炸
        String orderId = paidOrder();
        when(embeddingClient.embed(anyList())).thenThrow(new IllegalStateException("fastembed down"));

        OrderResponse archived = appService.retryArchive(Long.parseLong(orderId),
                new Operator("700100", "运营·小刘"));

        assertThat(archived.status()).isEqualTo(OrderStatus.ARCHIVED); // 主流程不受拖累
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ord_orders WHERE id = ?", Integer.class,
                Long.parseLong(orderId))).isEqualTo(OrderStatus.ARCHIVED.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID))).isZero(); // 沉淀跳过（丢失容忍）
    }

    @Test
    void given_retried_order_when_retry_archive_again_then_rejected_and_materials_unchanged() {
        // 重复触发（误触/双击竞态的后到者）：守卫拦 ORD_012，不产生重复素材
        stubEmbeddingOk();
        String orderId = paidOrder();
        appService.retryArchive(Long.parseLong(orderId), new Operator("700100", "运营·小刘"));
        int chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID));

        assertThatThrownBy(() -> appService.retryArchive(Long.parseLong(orderId),
                new Operator("700100", "运营·小刘")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.ORDER_ARCHIVE_NOT_ALLOWED.message());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID))).isEqualTo(chunkCount); // 素材不重复
    }

    @Test
    void given_missing_order_when_retry_archive_then_not_found() {
        assertThatThrownBy(() -> appService.retryArchive(900999L, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_NOT_FOUND.message());
    }

    // ---------- 夹具 ----------

    /** 落一张已报价订单（place → submitQuote，全走真链路）。 */
    private String quotedOrder() {
        String orderId = appService.place(PROJECT_ID).id();
        appService.submitQuote(Long.parseLong(orderId), 128000L, "首版报价", null);
        return orderId;
    }

    /**
     * 落一张已支付订单（聚合正身直落已支付——#337 mock 支付链退役，「已支付＝
     * 开工条件满足」判定接通归 #338，夹具走 {@code Order#pay} 领域事实构造口）。
     */
    private String paidOrder() {
        String orderId = quotedOrder();
        Order order = orderRepository.findWithHistory(Long.parseLong(orderId)).orElseThrow();
        order.pay("TEST-" + orderId);
        orderRepository.save(order);
        return orderId;
    }

    /** 本测试已发射的 order-status-changed 状态 code 序（发序 = 到达序）。 */
    @SuppressWarnings("unchecked")
    private List<Object> publishedStatuses() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, atLeastOnce())
                .publishNotification(eq("order-status-changed"), payload.capture());
        return payload.getAllValues().stream().map(p -> p.get("status")).toList();
    }

    /** embedding 正常供给：每块一个 512 维向量（列宽口径，与 bge-small-zh 一致）。 */
    private void stubEmbeddingOk() {
        when(embeddingClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> chunks = invocation.getArgument(0);
            return chunks.stream().map(chunk -> {
                float[] vector = new float[512];
                vector[0] = chunk.hashCode() % 97 / 97f; // 确定性伪向量（仅入库，不验相似）
                return vector;
            }).toList();
        });
    }
}
