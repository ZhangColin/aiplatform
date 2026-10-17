package com.aieducenter.aiplatform.business.order.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand;
import com.aieducenter.aiplatform.business.order.application.dto.response.PaymentPlanResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractParty;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;
import com.aieducenter.aiplatform.business.order.domain.model.PaymentNodeSpec;
import com.aieducenter.aiplatform.business.order.domain.repository.ContractRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 开工判定集成测试（#338 验收：双门禁＝合同签署完成 && 全部开工门节点已到账，
 * 两触发点〔合同推进签署完成/节点核验到账〕同判定器、同一事务＝订单转已支付
 * 〔新语义＝开工条件满足〕＋创建生产承接——应用服务 → 聚合 → 落库全真；跨 BC
 * 承接面走真 {@code ProductionHandoffAppService} 同库同事务，SSE 发布器 mock
 * 收口验通知口径）。「只建一次」幂等＝QUOTED 预检＋承接唯一约束＋服务端事务
 * 内判定（三腿在本类与 ProductionHandoffAppServiceTest 分摊钉死）。
 */
@IntegrationTest
class OrderKickoffProductionTest {

    /** 每次落单换新项目号（同项目至多一张未终结订单）。 */
    private static final AtomicLong PROJECT_SEQ = new AtomicLong(900900L);
    private static final Operator OPERATOR = new Operator("700100", "运营·小刘");

    @Autowired
    private ContractAppService contractAppService;

    @Autowired
    private PaymentPlanAppService paymentAppService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** SSE 发布器：验证开工落定的「已支付」通知（副作用落定后发射）。 */
    @MockitoBean
    private EventsAppService eventsAppService;

    private final List<Long> projectIds = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        // 承接两表挂 prj_projects FK，清 ord_orders 级联清不到——删真项目行级联清
        for (Long projectId : projectIds) {
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
        jdbcTemplate.update("DELETE FROM ord_orders");
    }

    // ---------- 双门禁两触发点：任一后满足的腿触发判定 ----------

    @Test
    void given_signed_contract_when_last_gate_node_verified_then_paid_and_handoff_created() {
        // 触发点 B（节点核验到账后）：合同先签、开工门后到账——末腿到账即开工
        Order order = quotedOrderWithSignedContract();
        paymentAppService.enterNodes(order.getId(), List.of(
                new PaymentNodeSpec("首期开工款", 19200L * 100, true),
                new PaymentNodeSpec("尾款结算", 9600L * 100, false)));
        verifyNode(order, 1, 9600L * 100); // 尾款（非开工门）先行到账——不触发
        assertThat(orderStatusOf(order)).isEqualTo(OrderStatus.QUOTED.getCode());
        assertThat(handoffCount(order)).isZero();

        verifyNode(order, 0, 19200L * 100); // 开工门节点到账（触发点 B）

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, paid_at, payment_no FROM ord_orders WHERE id = ?", order.getId());
        assertThat(row.get("status")).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(row.get("paid_at")).isNotNull();
        // paymentNo＝合同号（开工路径的语义值——无渠道流水号，溯源正身即合同）
        assertThat(row.get("payment_no")).isEqualTo(contractNoOf(order));
        // 同一刻建承接（待安排生产起步）；通知＝「已支付」状态语义（开工条件满足）
        assertThat(handoffCount(order)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM prj_production_handoffs WHERE project_id = ?",
                Integer.class, order.getProjectId())).isEqualTo(1);
        assertThat(publishedStatuses()).contains(OrderStatus.PAID.getCode());
    }

    @Test
    void given_all_gates_received_when_contract_signed_then_paid_on_advance() {
        // 触发点 A（合同推进签署完成后）：开工门先到账、合同后签——签署完成即开工
        Order order = quotedOrderWithContractAtSigning();
        paymentAppService.enterNodes(order.getId(), List.of(
                new PaymentNodeSpec("首期开工款", 19200L * 100, true)));
        verifyNode(order, 0, 19200L * 100);
        assertThat(orderStatusOf(order)).isEqualTo(OrderStatus.QUOTED.getCode()); // 门到账不签不触发

        signToComplete(order); // 推进到签署完成（触发点 A）

        assertThat(orderStatusOf(order)).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(handoffCount(order)).isEqualTo(1);
    }

    // ---------- 门未满足的中间态：判定空转（订单与承接不动） ----------

    @Test
    void given_insufficient_gate_arrival_when_verify_then_still_quoted_no_handoff() {
        Order order = quotedOrderWithSignedContract();
        paymentAppService.enterNodes(order.getId(), List.of(
                new PaymentNodeSpec("首期开工款", 19200L * 100, true)));
        paymentAppService.uploadVoucher(order.getId(), firstNodeId(order), pdfVoucher("receipt.pdf"));
        // 不足额核验：回待支付（累计不清零）——开工门未到账，判定空转
        paymentAppService.verifyArrival(order.getId(), firstNodeId(order),
                new VerifyPaymentCommand(10000L * 100, LocalDateTime.of(2026, 10, 17, 9, 0), null, null, null),
                OPERATOR);

        assertThat(orderStatusOf(order)).isEqualTo(OrderStatus.QUOTED.getCode());
        assertThat(handoffCount(order)).isZero();
    }

    @Test
    void given_signed_without_nodes_entered_when_settled_then_not_kicked_off() {
        // 计划未录节点≠过门（等后台录节点/等付款同呈现「待付款」）
        Order order = quotedOrderWithSignedContract();
        assertThat(orderStatusOf(order)).isEqualTo(OrderStatus.QUOTED.getCode());
        assertThat(handoffCount(order)).isZero();
    }

    @Test
    void given_nodes_without_any_gate_flag_when_signed_then_kicked_off() {
        // 录了节点但无任何开工门标志＝空真过门（拍板留档：没设门＝无款门，门＝仅
        // 合同腿；后台录入面提示归 admin 侧）
        Order order = quotedOrderWithSignedContract();
        paymentAppService.enterNodes(order.getId(), List.of(
                new PaymentNodeSpec("全款", 48000L * 100, false)));
        verifyNode(order, 0, 48000L * 100);

        assertThat(orderStatusOf(order)).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(handoffCount(order)).isEqualTo(1);
    }

    // ---------- 幂等：判定重入不重复建单/不重复支付 ----------

    @Test
    void given_kicked_off_when_settle_triggered_again_then_idempotent() {
        Order order = quotedOrderWithSignedContract();
        paymentAppService.enterNodes(order.getId(), List.of(
                new PaymentNodeSpec("首期开工款", 19200L * 100, true),
                new PaymentNodeSpec("尾款结算", 9600L * 100, false)));
        verifyNode(order, 0, 19200L * 100);
        assertThat(handoffCount(order)).isEqualTo(1);

        // 双触发点常态：开工后再核验尾款节点 → verifyArrival 再触发判定 → 空转
        verifyNode(order, 1, 9600L * 100);

        assertThat(orderStatusOf(order)).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(handoffCount(order)).isEqualTo(1); // 只建一次
        assertThat(publishedStatuses().stream().filter(s -> s.equals(OrderStatus.PAID.getCode())).count())
                .isEqualTo(1); // 「已支付」通知只发一次
    }

    // ---------- 夹具（真链路：直接落库驱动到目标态，聚合经公共入口） ----------

    private Order placedOrder() {
        // 承接挂真项目行（prj_production_handoffs FK prj_projects——测试单不虚构
        // 项目号，逐单建真项目随 teardown 级联清）
        Long projectId = projectRepository.save(com.aieducenter.aiplatform.business.project.domain.aggregate.Project
                .create("开工判定项目" + PROJECT_SEQ.get(), null, null,
                        PROJECT_SEQ.incrementAndGet(), 1L, null)).getId();
        projectIds.add(projectId);
        return orderRepository.save(Order.place(projectId, 1L, null,
                "# PRD v3\n\n已确认版本全文", OrderDeliverableType.SYSTEM, 3, 2));
    }

    /** 已报价＋范围齐备的订单（未建合同）。 */
    private Order quotedOrder() {
        Order order = placedOrder();
        order.enterScope("交付清单：管理后台", "验收方式：按 PRD v3 验收", "工期口径：4 周");
        order.quote(48000L * 100, "首版报价", OPERATOR);
        return orderRepository.save(order);
    }

    /** 已报价＋合同推进到签署进行中（双方未签）。 */
    private Order quotedOrderWithContractAtSigning() {
        Order order = quotedOrder();
        contractAppService.confirmScope(order.getId());
        Long contractId = activeContractId(order);
        contractAppService.advance(contractId, "内容核对完毕，送用户签署", OPERATOR);
        contractAppService.advance(contractId, "用户已核对，开始签署", OPERATOR);
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    /** 已报价＋最近合同已签署完成（订单仍 QUOTED——开工门未满足的常态窗口）。 */
    private Order quotedOrderWithSignedContract() {
        Order order = quotedOrderWithContractAtSigning();
        signToComplete(order);
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private void signToComplete(Order order) {
        Long contractId = latestContractId(order);
        contractAppService.sign(contractId, ContractParty.PARTY_A, "甲方盖章", OPERATOR);
        contractAppService.sign(contractId, ContractParty.PARTY_B, "乙方盖章", OPERATOR);
        contractAppService.advance(contractId, "双方落签，合同完成", OPERATOR);
    }

    private void verifyNode(Order order, int seqIndex, long amount) {
        Long nodeId = nodeIdOf(order, seqIndex);
        paymentAppService.uploadVoucher(order.getId(), nodeId, pdfVoucher("receipt.pdf"));
        paymentAppService.verifyArrival(order.getId(), nodeId,
                new VerifyPaymentCommand(amount, LocalDateTime.of(2026, 10, 17, 10, 0), null, null, null),
                OPERATOR);
    }

    private Long activeContractId(Order order) {
        return contractRepository.findActiveByOrderId(order.getId()).orElseThrow().getId();
    }

    private Long latestContractId(Order order) {
        return contractRepository.findFirstByOrderIdOrderByCreatedAtDescIdDesc(order.getId())
                .orElseThrow().getId();
    }

    private String contractNoOf(Order order) {
        return contractRepository.findFirstByOrderIdOrderByCreatedAtDescIdDesc(order.getId())
                .orElseThrow().getContractNo();
    }

    private Long nodeIdOf(Order order, int seqIndex) {
        PaymentPlanResponse plan = paymentAppService.planOfOrder(order.getId());
        return Long.parseLong(plan.nodes().get(seqIndex).id());
    }

    private Long firstNodeId(Order order) {
        return nodeIdOf(order, 0);
    }

    private int orderStatusOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT status FROM ord_orders WHERE id = ?",
                Integer.class, order.getId());
    }

    private int handoffCount(Order order) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_production_handoffs WHERE project_id = ?",
                Integer.class, order.getProjectId());
    }

    private static org.springframework.mock.web.MockMultipartFile pdfVoucher(String name) {
        return new org.springframework.mock.web.MockMultipartFile("file", name, "application/pdf",
                new byte[] {1, 2, 3});
    }

    /** 本测试已发射的 order-status-changed 状态 code 清单（重复触发也如实计）。 */
    @SuppressWarnings("unchecked")
    private List<Object> publishedStatuses() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, atLeastOnce())
                .publishNotification(eq("order-status-changed"), payload.capture());
        return payload.getAllValues().stream().map(p -> p.get("status")).toList();
    }
}
