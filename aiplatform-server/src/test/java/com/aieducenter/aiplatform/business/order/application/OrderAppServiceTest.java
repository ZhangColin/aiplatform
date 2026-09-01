package com.aieducenter.aiplatform.business.order.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 下单缝集成测试（#18 验收：「同项目至多一个未终结订单」的库侧部分唯一索引 +
 * 应用预检双保险）：跨 BC 项目事实（详情/PRD 读）mock 收口，订单链路（应用服务 →
 * 聚合 → 落库 → 唯一索引）全真，以库内真实行为为准。#28 交易环①接出取消
 * 状态机（未支付态可达/已支付与终态拒绝）、快照冻结（PRD 后续修订不回写）、
 * 详情与取消后再下新单（新单新快照）；#29 交易环②接出报价/改价（append-only
 * 价目留痕、现值取最新行、quotedAt 不刷新）；#30 支付归档一事务归
 * {@link OrderPaymentArchiveTest}。
 */
@SpringBootTest
class OrderAppServiceTest {

    private static final long PROJECT_ID = 900100L;
    private static final String PRD = "# PRD\n\n需求背景：缝测试。";

    @Autowired
    private OrderAppService appService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 跨 BC 软引用的项目读面：mock 掉 docker/工作区依赖，聚焦订单缝。 */
    @MockitoBean
    private ProjectQueryAppService projectQueryAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ord_orders");
    }

    @Test
    void given_first_place_when_place_then_pending_quote_row_with_frozen_snapshot() {
        stubProject(ProjectStatus.IN_PROGRESS);

        OrderResponse order = appService.place(PROJECT_ID);

        // 下单事实以库内行为为准：待报价起步、快照冻结、金额未落、created_at 即下单时间
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, prd_snapshot, amount, created_at FROM ord_orders WHERE id = ?",
                Long.parseLong(order.id()));
        assertThat(row.get("status")).isEqualTo(1);
        assertThat(row.get("prd_snapshot")).isEqualTo(PRD);
        assertThat(row.get("amount")).isNull();
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void given_active_order_when_place_again_then_rejected_with_friendly_error() {
        stubProject(ProjectStatus.IN_PROGRESS);
        appService.place(PROJECT_ID);

        assertThatThrownBy(() -> appService.place(PROJECT_ID))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_ALREADY_ACTIVE.message());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_orders WHERE project_id = ?", Integer.class, PROJECT_ID))
                .isEqualTo(1); // 后到者被拒，不落行
    }

    @Test
    void given_active_order_when_second_insert_bypasses_precheck_then_unique_index_rejects() {
        // 库侧兜底面：绕过应用预检直插第二行（并发漏过预检的等价形态），部分唯一
        // 索引拒绝——「同项目至多一个未终结订单」的最终防线在库不在代码
        stubProject(ProjectStatus.IN_PROGRESS);
        appService.place(PROJECT_ID);

        assertThatThrownBy(() -> orderRepository.save(Order.place(PROJECT_ID, null, PRD)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void given_terminal_order_when_place_again_then_new_order_allowed() {
        // 终态（已取消/已归档）不占未终结名额：取消后再下 = 新单新快照
        stubProject(ProjectStatus.IN_PROGRESS);
        String first = appService.place(PROJECT_ID).id();
        jdbcTemplate.update("UPDATE ord_orders SET status = ? WHERE id = ?",
                OrderStatus.CANCELLED.getCode(), Long.parseLong(first));

        String second = appService.place(PROJECT_ID).id();

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_orders WHERE project_id = ?", Integer.class, PROJECT_ID))
                .isEqualTo(2);
    }

    @Test
    void given_archived_project_when_place_then_rejected() {
        stubProject(ProjectStatus.ARCHIVED);

        assertThatThrownBy(() -> appService.place(PROJECT_ID))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_PROJECT_ARCHIVED.message());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_orders", Integer.class)).isZero();
    }

    // ---------- #28 交易环①：快照冻结 / 取消状态机 / 详情 ----------

    @Test
    void given_placed_order_when_prd_changes_then_snapshot_stays_frozen() {
        // 快照自含：下单后的 PRD 修订不回写本单（不依赖工作区存亡）
        stubProject(ProjectStatus.IN_PROGRESS);
        String orderId = appService.place(PROJECT_ID).id();
        stubProject(ProjectStatus.IN_PROGRESS, PRD + "\n\n修订后：多了一个章节。");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT prd_snapshot FROM ord_orders WHERE id = ?", String.class,
                Long.parseLong(orderId))).isEqualTo(PRD);
    }

    @Test
    void given_pending_quote_order_when_cancel_then_cancelled_with_timestamp() {
        stubProject(ProjectStatus.IN_PROGRESS);
        String orderId = appService.place(PROJECT_ID).id();

        OrderResponse cancelled = appService.cancel(Long.parseLong(orderId));

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.statusName()).isEqualTo("已取消");
        assertThat(cancelled.cancelledAt()).isNotNull();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, cancelled_at FROM ord_orders WHERE id = ?", Long.parseLong(orderId));
        assertThat(row.get("status")).isEqualTo(OrderStatus.CANCELLED.getCode());
        assertThat(row.get("cancelled_at")).isNotNull();
    }

    @Test
    void given_quoted_order_when_cancel_then_allowed() {
        // 已报价（=待支付）仍未支付：取消可达
        stubProject(ProjectStatus.IN_PROGRESS);
        String orderId = appService.place(PROJECT_ID).id();
        jdbcTemplate.update("UPDATE ord_orders SET status = ? WHERE id = ?",
                OrderStatus.QUOTED.getCode(), Long.parseLong(orderId));

        assertThat(appService.cancel(Long.parseLong(orderId)).status())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void given_paid_or_terminal_order_when_cancel_then_rejected() {
        // 已支付（支付成功即联动归档）与终态不可取消；逐态钉死
        for (OrderStatus status : List.of(OrderStatus.PAID, OrderStatus.ARCHIVED, OrderStatus.CANCELLED)) {
            stubProject(ProjectStatus.IN_PROGRESS);
            String orderId = appService.place(PROJECT_ID).id();
            jdbcTemplate.update("UPDATE ord_orders SET status = ? WHERE id = ?",
                    status.getCode(), Long.parseLong(orderId));

            assertThatThrownBy(() -> appService.cancel(Long.parseLong(orderId)))
                    .isInstanceOf(DomainException.class) // 聚合守卫原生异常（REST 面由全局处理器翻译）
                    .hasMessageContaining(OrderMessage.ORDER_CANCEL_NOT_ALLOWED.message());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM ord_orders WHERE id = ?", Integer.class,
                    Long.parseLong(orderId))).isEqualTo(status.getCode()); // 状态不被破坏
            // 已支付仍占未终结名额（支付成功才联动归档），清场后再验下一态
            jdbcTemplate.update("DELETE FROM ord_orders");
        }
    }

    @Test
    void given_cancelled_order_when_place_again_then_new_snapshot() {
        // 取消即解冻：再下新单重新冻结下单时 PRD（新单新快照）
        stubProject(ProjectStatus.IN_PROGRESS);
        String first = appService.place(PROJECT_ID).id();
        appService.cancel(Long.parseLong(first));
        String revisedPrd = PRD + "\n\n取消后继续迭代出的修订。";
        stubProject(ProjectStatus.IN_PROGRESS, revisedPrd);

        OrderResponse second = appService.place(PROJECT_ID);

        assertThat(second.id()).isNotEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT prd_snapshot FROM ord_orders WHERE id = ?", String.class,
                Long.parseLong(second.id()))).isEqualTo(revisedPrd);
    }

    @Test
    void given_placed_order_when_detail_then_response_shape() {
        stubProject(ProjectStatus.IN_PROGRESS);
        String orderId = appService.place(PROJECT_ID).id();

        OrderResponse detail = appService.detail(Long.parseLong(orderId));

        assertThat(detail.id()).isEqualTo(orderId);
        assertThat(detail.projectId()).isEqualTo(Long.toString(PROJECT_ID));
        assertThat(detail.status()).isEqualTo(OrderStatus.PENDING_QUOTE);
        assertThat(detail.statusName()).isEqualTo("待报价");
        assertThat(detail.createdAt()).isNotNull();
        assertThat(detail.cancelledAt()).isNull();
    }

    @Test
    void given_missing_order_when_detail_or_cancel_then_not_found() {
        assertThatThrownBy(() -> appService.detail(900999L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_NOT_FOUND.message());
        assertThatThrownBy(() -> appService.cancel(900999L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_NOT_FOUND.message());
    }

    // ---------- #29 交易环②：报价 / append-only 改价留痕 ----------

    @Test
    void given_pending_quote_order_when_submit_quote_then_quoted_with_first_entry() {
        stubProject(ProjectStatus.IN_PROGRESS);
        String orderId = appService.place(PROJECT_ID).id();

        OrderResponse quoted = appService.submitQuote(Long.parseLong(orderId), 128000L, "首版报价：含三个页面");

        assertThat(quoted.status()).isEqualTo(OrderStatus.QUOTED);
        assertThat(quoted.amount()).isEqualTo(128000L);
        assertThat(quoted.currency()).isEqualTo(Order.CURRENCY_CNY);
        assertThat(quoted.note()).isEqualTo("首版报价：含三个页面");
        assertThat(quoted.quotedAt()).isNotNull();
        assertThat(quoted.priceEntries()).hasSize(1);
        // 库内事实：订单现值 + 价目行各就各位
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, amount, currency, quoted_at FROM ord_orders WHERE id = ?",
                Long.parseLong(orderId));
        assertThat(row.get("status")).isEqualTo(OrderStatus.QUOTED.getCode());
        assertThat(row.get("amount")).isEqualTo(128000L);
        assertThat(row.get("currency")).isEqualTo(Order.CURRENCY_CNY);
        assertThat(row.get("quoted_at")).isNotNull();
        Map<String, Object> entry = jdbcTemplate.queryForMap(
                "SELECT amount, currency, note FROM ord_price_entries WHERE order_id = ?",
                Long.parseLong(orderId));
        assertThat(entry.get("amount")).isEqualTo(128000L);
        assertThat(entry.get("note")).isEqualTo("首版报价：含三个页面");
    }

    @Test
    void given_quoted_order_when_submit_quote_again_then_reprice_appends_entry_only() {
        // 改价 = append-only：旧价目行原样保留、新行追加、订单现值取最新行、
        // quotedAt 不刷新（改价时点留痕在价目行）
        stubProject(ProjectStatus.IN_PROGRESS);
        String orderId = appService.place(PROJECT_ID).id();
        appService.submitQuote(Long.parseLong(orderId), 128000L, "首版报价");
        LocalDateTime quotedAt = appService.detail(Long.parseLong(orderId)).quotedAt();

        OrderResponse repriced = appService.submitQuote(Long.parseLong(orderId), 99000L, "调整：去掉导入功能");

        assertThat(repriced.status()).isEqualTo(OrderStatus.QUOTED); // 改价不换状态
        assertThat(repriced.amount()).isEqualTo(99000L);
        assertThat(repriced.note()).isEqualTo("调整：去掉导入功能");
        assertThat(repriced.quotedAt()).isEqualTo(quotedAt);
        // 历史新 → 旧：首条即最新改价，末条即首次报价
        assertThat(repriced.priceEntries()).hasSize(2);
        assertThat(repriced.priceEntries().get(0).amount()).isEqualTo(99000L);
        assertThat(repriced.priceEntries().get(1).amount()).isEqualTo(128000L);
        // 库内事实：两行都在、旧行未被改写、订单现值 = 最新行
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT amount, note FROM ord_price_entries WHERE order_id = ? ORDER BY id",
                Long.parseLong(orderId));
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("amount", 128000L)
                .containsEntry("note", "首版报价"); // 旧行原样
        assertThat(entries.get(1)).containsEntry("amount", 99000L)
                .containsEntry("note", "调整：去掉导入功能");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT amount, quoted_at FROM ord_orders WHERE id = ?", Long.parseLong(orderId)))
                .containsEntry("amount", 99000L);
        assertThat(appService.detail(Long.parseLong(orderId)).quotedAt()).isEqualTo(quotedAt);
    }

    @Test
    void given_paid_or_terminal_order_when_submit_quote_then_rejected_without_entry() {
        // 报价/改价限未支付态：已支付与终态拒绝，且不留半条价目行；逐态钉死
        for (OrderStatus status : List.of(OrderStatus.PAID, OrderStatus.ARCHIVED, OrderStatus.CANCELLED)) {
            stubProject(ProjectStatus.IN_PROGRESS);
            String orderId = appService.place(PROJECT_ID).id();
            jdbcTemplate.update("UPDATE ord_orders SET status = ? WHERE id = ?",
                    status.getCode(), Long.parseLong(orderId));

            assertThatThrownBy(() -> appService.submitQuote(Long.parseLong(orderId), 1000L, "迟到的报价"))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.ORDER_QUOTE_NOT_ALLOWED.message());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ord_price_entries WHERE order_id = ?", Integer.class,
                    Long.parseLong(orderId))).isZero();
            jdbcTemplate.update("DELETE FROM ord_orders"); // 清场再验下一态
        }
    }

    @Test
    void given_invalid_amount_or_overlong_note_when_submit_quote_then_rejected() {
        stubProject(ProjectStatus.IN_PROGRESS);
        String orderId = appService.place(PROJECT_ID).id();
        long id = Long.parseLong(orderId);

        assertThatThrownBy(() -> appService.submitQuote(id, 0L, "零元"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.ORDER_QUOTE_AMOUNT_INVALID.message());
        assertThatThrownBy(() -> appService.submitQuote(id, -5L, "负数"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.ORDER_QUOTE_AMOUNT_INVALID.message());
        assertThatThrownBy(() -> appService.submitQuote(id, 1000L, "长".repeat(Order.QUOTE_NOTE_MAX_LENGTH + 1)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.ORDER_QUOTE_NOTE_TOO_LONG.message());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_price_entries WHERE order_id = ?", Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ord_orders WHERE id = ?", Integer.class, id))
                .isEqualTo(OrderStatus.PENDING_QUOTE.getCode()); // 守卫先于状态变更，订单不动
    }

    @Test
    void given_missing_order_when_submit_quote_then_not_found() {
        assertThatThrownBy(() -> appService.submitQuote(900999L, 1000L, "无此单"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_NOT_FOUND.message());
    }

    private void stubProject(ProjectStatus status) {
        stubProject(status, PRD);
    }

    private void stubProject(ProjectStatus status, String prd) {
        when(projectQueryAppService.detail(PROJECT_ID)).thenReturn(new ProjectDetailResponse(
                Long.toString(PROJECT_ID), "订单缝测试", ProjectType.WEBSITE, "官网", "9100",
                status, status.getName(), status == ProjectStatus.ARCHIVED,
                LocalDateTime.of(2026, 8, 31, 10, 0), null, null, null, null, null));
        when(projectQueryAppService.prd(PROJECT_ID)).thenReturn(new PrdResponse(
                Long.toString(PROJECT_ID), prd, Instant.parse("2026-08-31T02:00:00Z")));
    }
}
