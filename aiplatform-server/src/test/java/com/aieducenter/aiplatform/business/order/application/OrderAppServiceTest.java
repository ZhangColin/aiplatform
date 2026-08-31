package com.aieducenter.aiplatform.business.order.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

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
 * 聚合 → 落库 → 唯一索引）全真，以库内真实行为为准。报价/改价/取消/支付的用例
 * 测试随片4 落位。
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

        Long orderId = appService.place(PROJECT_ID);

        // 下单事实以库内行为为准：待报价起步、快照冻结、金额未落、created_at 即下单时间
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, prd_snapshot, amount, created_at FROM ord_orders WHERE id = ?",
                orderId);
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
        Long first = appService.place(PROJECT_ID);
        jdbcTemplate.update("UPDATE ord_orders SET status = ? WHERE id = ?",
                OrderStatus.CANCELLED.getCode(), first);

        Long second = appService.place(PROJECT_ID);

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

    private void stubProject(ProjectStatus status) {
        when(projectQueryAppService.detail(PROJECT_ID)).thenReturn(new ProjectDetailResponse(
                Long.toString(PROJECT_ID), "订单缝测试", ProjectType.WEBSITE, "官网", "9100",
                status, status.getName(), status == ProjectStatus.ARCHIVED,
                LocalDateTime.of(2026, 8, 31, 10, 0)));
        when(projectQueryAppService.prd(PROJECT_ID)).thenReturn(new PrdResponse(
                Long.toString(PROJECT_ID), PRD, Instant.parse("2026-08-31T02:00:00Z")));
    }
}
