package com.semgravio.aiplatform.business.order.application;

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

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.semgravio.aiplatform.business.order.application.dto.response.OrderResponse;
import com.semgravio.aiplatform.business.order.domain.aggregate.Order;
import com.semgravio.aiplatform.business.order.domain.enums.OrderStatus;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.order.domain.model.Operator;
import com.semgravio.aiplatform.business.order.domain.repository.OrderRepository;
import com.semgravio.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.semgravio.aiplatform.business.project.application.ProjectQueryAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.ProjectDetailResponseFixture;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;

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
 * 链路全真。钉死：①成功面（重试归档落「已归档」＋操作者留痕＋项目归档联动＋
 * 补发「已归档」通知）；②项目重复归档拦截面（PRJ_013 原样上抛——订单整行不动）；
 * ③归档不再触发知识入库（#640 改挂用户面版本成版——订单归档后 knw 两表零写入、
 * embedding 端口零调用）；④守卫幂等（非已支付 ORD_012、重复触发不产生任何知识
 * 素材）。已支付态夹具走聚合正身（{@code Order#pay} 直落——#338 接通前的领域
 * 事实构造口）。
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

    /** 跨 BC 项目读面：mock 掉 docker/工作区依赖（detail 供下单前置）。 */
    @MockitoBean
    private ProjectQueryAppService projectQueryAppService;

    /** 跨 BC 唯一写交叉：项目归档联动（归档事务的失败面挂点）。 */
    @MockitoBean
    private ProjectLifecycleAppService projectLifecycleAppService;

    /** SSE 发布器：验证订单态变化通知（副作用落定后发射）。 */
    @MockitoBean
    private EventsAppService eventsAppService;

    /** embedding 端口：只用于断言归档不再触发入库（#640 后归档路径零调用）。 */
    @MockitoBean
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void stubProject() {
        when(projectQueryAppService.detail(PROJECT_ID)).thenReturn(ProjectDetailResponseFixture
                .detailOf(Long.toString(PROJECT_ID), "宠物店官网")
                .workspaceId("9200")
                .build());
        // 下单前置缺省桩（#629）：PRD 全文＋已确认用户面版本 v1
        when(projectQueryAppService.orderFreezeRefs(PROJECT_ID)).thenReturn(
                new ProjectQueryAppService.OrderFreezeRefs(PRD, 1));
    }

    @AfterEach
    void tearDown() {
        // ord_price_entries 随 FK 级联；知识块/素材登记按项目清（沉淀断言面，#158 起两表）
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM knw_chunks WHERE project_id = ?", Long.toString(PROJECT_ID));
        jdbcTemplate.update("DELETE FROM knw_materials WHERE project_id = ?", Long.toString(PROJECT_ID));
    }

    // ---------- #158：重试归档——卡单补偿（#640 起归档不再触发知识入库） ----------

    @Test
    void given_stuck_paid_order_when_retry_archive_then_archived_traced_without_knowledge_ingest() {
        // 一链：已支付卡单（归档未发生）→ 重试归档（带操作者）→ 订单落已归档＋
        // 操作者留痕 → 项目归档联动 → 补发「已归档」通知；知识入库 #640 改挂用户面
        // 版本成版，归档路径不再调用入库（knw 两表零写入、embedding 端口零调用）
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
        // 订单归档不再触发知识入库：块表与登记表零写入、embedding 零调用
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID))).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_materials WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID))).isZero();
        verify(embeddingClient, never()).embed(anyList());
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
        // 运营须看见拦截原因，不留静默半成态；订单留已支付、无知识素材
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
    void given_retried_order_when_retry_archive_again_then_rejected_without_knowledge_material() {
        // 重复触发（误触/双击竞态的后到者）：守卫拦 ORD_012，不产生任何知识素材
        String orderId = paidOrder();
        appService.retryArchive(Long.parseLong(orderId), new Operator("700100", "运营·小刘"));

        assertThatThrownBy(() -> appService.retryArchive(Long.parseLong(orderId),
                new Operator("700100", "运营·小刘")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.ORDER_ARCHIVE_NOT_ALLOWED.message());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID))).isZero(); // 素材不产生
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
}
