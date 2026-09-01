package com.aieducenter.aiplatform.business.order.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付归档一事务集成测试（#30 交易环③验收）：跨 BC 写面（项目归档、平台通知）
 * mock 收口，订单链路 + 知识沉淀（分块 → 真库 knw_chunks）全真——embedding 端口
 * mock 供给向量（本机 fastembed 不属测试依赖）。钉死三面：①支付成功一事务
 * （订单 已支付→已归档 + 项目归档联动 + 提交后知识沉淀入库 + 订单态变化通知）；
 * ②失败回滚面（项目归档失败 → 订单整体回滚留待支付态、沉淀与通知不出）；
 * ③知识沉淀降级面（embedding 故障 → 支付照常成功、沉淀跳过不回滚）。
 */
@SpringBootTest
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
    private JdbcTemplate jdbcTemplate;

    /** 跨 BC 项目读面：mock 掉 docker/工作区依赖（detail 供下单与沉淀取名，prd 供快照与沉淀取文）。 */
    @MockitoBean
    private ProjectQueryAppService projectQueryAppService;

    /** 跨 BC 唯一写交叉：项目归档联动（事务编排的回滚面挂点）。 */
    @MockitoBean
    private ProjectLifecycleAppService projectLifecycleAppService;

    /** SSE 发布器：验证订单态变化通知（副作用落定后发射）。 */
    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    /** embedding 端口：mock 供给 512 维向量（知识沉淀入库的真向量面）。 */
    @MockitoBean
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void stubProject() {
        when(projectQueryAppService.detail(PROJECT_ID)).thenReturn(new ProjectDetailResponse(
                Long.toString(PROJECT_ID), "宠物店官网", ProjectType.WEBSITE, "官网", "9200",
                ProjectStatus.IN_PROGRESS, "进行中", false,
                LocalDateTime.of(2026, 9, 1, 9, 0), null, null, null, null, null));
        when(projectQueryAppService.prd(PROJECT_ID)).thenReturn(new PrdResponse(
                Long.toString(PROJECT_ID), PRD, Instant.parse("2026-09-01T01:00:00Z")));
    }

    @AfterEach
    void tearDown() {
        // ord_price_entries 随 FK 级联；知识块按项目清（沉淀断言面）
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM knw_chunks WHERE project_id = ?", Long.toString(PROJECT_ID));
    }

    @Test
    void given_quoted_order_when_pay_then_archive_project_and_sink_prd_in_one_action() {
        stubEmbeddingOk();
        String orderId = quotedOrder();

        OrderResponse paid = appService.pay(Long.parseLong(orderId));

        // 订单事实（库内为准）：一跳已归档、paidAt 与 archivedAt 同拍、mock 流水号落值
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, paid_at, archived_at, payment_no FROM ord_orders WHERE id = ?",
                Long.parseLong(orderId));
        assertThat(row.get("status")).isEqualTo(OrderStatus.ARCHIVED.getCode());
        assertThat(row.get("paid_at")).isNotNull();
        assertThat(row.get("archived_at")).isEqualTo(row.get("paid_at"));
        assertThat((String) row.get("payment_no")).startsWith("MOCK-");
        assertThat(paid.status()).isEqualTo(OrderStatus.ARCHIVED);
        assertThat(paid.paidAt()).isEqualTo(paid.archivedAt());
        // 项目归档联动（跨 BC 唯一写交叉，同事务）
        verify(projectLifecycleAppService).archive(PROJECT_ID);
        // 知识沉淀入库（真分块真行）：kind=PRD、幂等键=projectId、块正文来自归档时 PRD
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(
                "SELECT kind, source_ref, project_name, title, chunk FROM knw_chunks "
                        + "WHERE project_id = ? ORDER BY seq",
                Long.toString(PROJECT_ID));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0)).containsEntry("kind", "PRD")
                .containsEntry("source_ref", Long.toString(PROJECT_ID))
                .containsEntry("project_name", "宠物店官网")
                .containsEntry("title", "PRD");
        assertThat(String.join("\n", chunks.stream().map(c -> (String) c.get("chunk")).toList()))
                .contains("宠物店官网", "购物车页");
        // 订单态变化通知（副作用落定后）：本用例链上共三发（待报价/已报价/已归档），
        // 末发即支付归档——载荷 projectId/orderId/status=4
        assertThat(publishedStatuses()).containsExactly(
                OrderStatus.PENDING_QUOTE.getCode(),
                OrderStatus.QUOTED.getCode(),
                OrderStatus.ARCHIVED.getCode());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService, atLeastOnce()).publish(eq("order-status-changed"), payload.capture());
        assertThat(payload.getValue()) // captor 末值 = 支付归档那发
                .containsEntry("projectId", Long.toString(PROJECT_ID))
                .containsEntry("status", OrderStatus.ARCHIVED.getCode())
                .containsEntry("statusName", "已归档")
                .containsKey("orderId");
    }

    @Test
    void given_project_archive_fails_when_pay_then_rollback_order_stays_quoted() {
        stubEmbeddingOk();
        String orderId = quotedOrder();
        when(projectLifecycleAppService.archive(anyLong()))
                .thenThrow(new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED));

        assertThatThrownBy(() -> appService.pay(Long.parseLong(orderId)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());

        // 回滚面：订单整体退回待支付态（未支付/未归档/无流水号），沉淀与通知不出
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, paid_at, archived_at, payment_no FROM ord_orders WHERE id = ?",
                Long.parseLong(orderId));
        assertThat(row.get("status")).isEqualTo(OrderStatus.QUOTED.getCode());
        assertThat(row.get("paid_at")).isNull();
        assertThat(row.get("archived_at")).isNull();
        assertThat(row.get("payment_no")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID))).isZero();
        // 回滚面通知口径：落定的两发（待报价/已报价）之外无新发——已归档态绝不出
        assertThat(publishedStatuses()).containsExactly(
                OrderStatus.PENDING_QUOTE.getCode(), OrderStatus.QUOTED.getCode());
    }

    @Test
    void given_embedding_down_when_pay_then_payment_succeeds_and_sink_degrades() {
        // 沉淀降级面：embedding 故障不拖回滚已成功的支付（丢失容忍，#5 决议）
        when(embeddingClient.embed(anyList())).thenThrow(new IllegalStateException("fastembed down"));
        String orderId = quotedOrder();

        OrderResponse paid = appService.pay(Long.parseLong(orderId));

        assertThat(paid.status()).isEqualTo(OrderStatus.ARCHIVED);
        verify(projectLifecycleAppService).archive(PROJECT_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID))).isZero();
    }

    @Test
    void given_already_paid_when_pay_again_then_rejected() {
        // 重复支付（含双击竞态的后到者）：非待支付态聚合守卫拒绝，库内事实不被破坏
        stubEmbeddingOk();
        String orderId = quotedOrder();
        appService.pay(Long.parseLong(orderId));

        assertThatThrownBy(() -> appService.pay(Long.parseLong(orderId)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.ORDER_PAY_NOT_ALLOWED.message());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ord_orders WHERE id = ?", Integer.class,
                Long.parseLong(orderId))).isEqualTo(OrderStatus.ARCHIVED.getCode());
    }

    @Test
    void given_pending_quote_or_terminal_order_when_pay_then_rejected() {
        // 逐态钉死：待报价（无价不可付）、已归档、已取消均非待支付（订单态直改 SQL，
        // 项目保持进行中——订单终态与项目归档是两件事）
        for (OrderStatus status : List.of(OrderStatus.PENDING_QUOTE, OrderStatus.ARCHIVED,
                OrderStatus.CANCELLED)) {
            String orderId = appService.place(PROJECT_ID).id();
            jdbcTemplate.update("UPDATE ord_orders SET status = ? WHERE id = ?",
                    status.getCode(), Long.parseLong(orderId));

            assertThatThrownBy(() -> appService.pay(Long.parseLong(orderId)))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.ORDER_PAY_NOT_ALLOWED.message());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM ord_orders WHERE id = ?", Integer.class,
                    Long.parseLong(orderId))).isEqualTo(status.getCode());
            // 非待支付不触支付端口副作用面：项目归档联动未被调用
            verify(projectLifecycleAppService, never()).archive(anyLong());
            jdbcTemplate.update("DELETE FROM ord_orders"); // 清场再验下一态
        }
    }

    @Test
    void given_missing_order_when_pay_then_not_found() {
        assertThatThrownBy(() -> appService.pay(900999L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_NOT_FOUND.message());
    }

    // ---------- 夹具 ----------

    /** 落一张已报价订单（place → submitQuote，全走真链路）。 */
    private String quotedOrder() {
        String orderId = appService.place(PROJECT_ID).id();
        appService.submitQuote(Long.parseLong(orderId), 128000L, "首版报价");
        return orderId;
    }

    /** 本测试已发射的 order-status-changed 状态 code 序（发序 = 到达序）。 */
    @SuppressWarnings("unchecked")
    private List<Object> publishedStatuses() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService, atLeastOnce())
                .publish(eq("order-status-changed"), payload.capture());
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
