package com.aieducenter.aiplatform.business.order.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand;
import com.aieducenter.aiplatform.business.order.application.dto.response.DeliveryStatusResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractParty;
import com.aieducenter.aiplatform.business.order.domain.enums.DeliveryState;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;
import com.aieducenter.aiplatform.business.order.domain.model.PaymentNodeSpec;
import com.aieducenter.aiplatform.business.order.domain.repository.ContractRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.application.ProductionHandoffAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 前台六态推导集成测试（#338 验收：订单＋合同＋付款＋承接四源组合各态正确、
 * 「需用户补充」项目级标志盖帽、遗留已支付单兜底、待报价不覆盖、无未终结
 * 订单缺席态——推导单点在 {@code DeliveryStatusAppService}，全链真库驱动、
 * 以 {@code statusOf} 公共读口为准）。
 */
@IntegrationTest
class DeliveryStatusAppServiceTest {

    /** 每次换新工作区号（一项目一工作区——测试直建项目不撞真实工作区）。 */
    private static final AtomicLong WORKSPACE_SEQ = new AtomicLong(973500L);
    private static final Operator OPERATOR = new Operator("700100", "运营·小刘");

    @Autowired
    private DeliveryStatusAppService appService;

    @Autowired
    private ContractAppService contractAppService;

    @Autowired
    private PaymentPlanAppService paymentAppService;

    @Autowired
    private ProductionHandoffAppService handoffAppService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> projectIds = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long projectId : projectIds) {
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
        jdbcTemplate.update("DELETE FROM ord_orders");
    }

    // ---------- 待签约：合同未签署完成的全部形态 ----------

    @Test
    void given_quoted_without_contract_when_status_then_awaiting_contract() {
        Long projectId = newProject();
        quotedOrder(projectId);

        assertThat(appService.statusOf(projectId).state()).isEqualTo(DeliveryState.AWAITING_CONTRACT);
    }

    @Test
    void given_draft_signing_or_dead_contract_when_status_then_awaiting_contract() {
        // 草稿（准备中）／签署中／旁落终态（失败过期、撤回作废）——等签约或重建同态
        Long draftProject = newProject();
        Order draftOrder = quotedOrder(draftProject);
        contractAppService.confirmScope(draftOrder.getId());

        Long signingProject = newProject();
        Order signingOrder = quotedOrder(signingProject);
        advanceToSigning(signingOrder);

        Long failedProject = newProject();
        Order failedOrder = quotedOrder(failedProject);
        contractAppService.confirmScope(failedOrder.getId());
        contractAppService.advance(activeContractId(failedOrder), "内容核对完毕，送用户签署", OPERATOR);
        contractAppService.markFailed(activeContractId(failedOrder), "用户放弃核对", OPERATOR);

        Long withdrawnProject = newProject();
        Order withdrawnOrder = quotedOrder(withdrawnProject);
        contractAppService.confirmScope(withdrawnOrder.getId());
        contractAppService.withdraw(activeContractId(withdrawnOrder), "整案回炉", OPERATOR);

        for (Long projectId : List.of(draftProject, signingProject, failedProject, withdrawnProject)) {
            assertThat(appService.statusOf(projectId).state())
                    .as("项目 %s 应为待签约", projectId)
                    .isEqualTo(DeliveryState.AWAITING_CONTRACT);
        }
    }

    // ---------- 待付款／款项核验中：已签未开工的付款窗口 ----------

    @Test
    void given_signed_when_plan_empty_or_gate_pending_then_awaiting_payment() {
        // 无节点（等后台录）与开工门未到账（等付款）同呈现「待付款」
        Long emptyPlanProject = newProject();
        signedContractOrder(emptyPlanProject);
        assertThat(appService.statusOf(emptyPlanProject).state())
                .isEqualTo(DeliveryState.AWAITING_PAYMENT);

        Long pendingProject = newProject();
        Order pendingOrder = signedContractOrder(pendingProject);
        paymentAppService.enterNodes(pendingOrder.getId(), List.of(
                new PaymentNodeSpec("首期开工款", 19200L * 100, true),
                new PaymentNodeSpec("尾款结算", 9600L * 100, false)));
        assertThat(appService.statusOf(pendingProject).state())
                .isEqualTo(DeliveryState.AWAITING_PAYMENT);
    }

    @Test
    void given_signed_when_voucher_uploaded_then_verifying_payment() {
        Long projectId = newProject();
        Order order = signedContractOrder(projectId);
        paymentAppService.enterNodes(order.getId(), List.of(
                new PaymentNodeSpec("首期开工款", 19200L * 100, true)));
        paymentAppService.uploadVoucher(order.getId(), nodeIdOf(order, 0),
                new org.springframework.mock.web.MockMultipartFile("file", "receipt.pdf",
                        "application/pdf", new byte[] {1, 2, 3}));

        DeliveryStatusResponse status = appService.statusOf(projectId);
        assertThat(status.state()).isEqualTo(DeliveryState.VERIFYING_PAYMENT);
        assertThat(status.stateName()).isEqualTo("款项核验中");
    }

    // ---------- 生产两态：开工后的承接呈现 ----------

    @Test
    void given_kickoff_when_status_then_awaiting_production_with_handoff_fields() {
        Long projectId = newProject();
        kickedOffOrder(projectId);
        handoffAppService.update(projectId, "李经理（微信 ligoods）", "准备服务器环境",
                "已完成技术方案拆解", LocalDateTime.of(2026, 10, 20, 9, 0),
                new com.aieducenter.aiplatform.business.project.domain.model.Operator("700100", "运营·小刘"), "首次排期录入");

        DeliveryStatusResponse status = appService.statusOf(projectId);
        assertThat(status.state()).isEqualTo(DeliveryState.AWAITING_PRODUCTION);
        assertThat(status.orderStatus()).isEqualTo(3); // 已支付＝开工条件满足
        assertThat(status.handoff()).isNotNull();
        assertThat(status.handoff().status()).isEqualTo(1);
        assertThat(status.handoff().contact()).isEqualTo("李经理（微信 ligoods）");
        assertThat(status.handoff().estimatedStartAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0));
    }

    @Test
    void given_handoff_advanced_when_status_then_in_production() {
        Long projectId = newProject();
        kickedOffOrder(projectId);
        handoffAppService.advance(projectId, "排期落定，今日开工",
                new com.aieducenter.aiplatform.business.project.domain.model.Operator("700100", "运营·小刘"));

        DeliveryStatusResponse status = appService.statusOf(projectId);
        assertThat(status.state()).isEqualTo(DeliveryState.IN_PRODUCTION);
        assertThat(status.handoff().status()).isEqualTo(2);
        assertThat(status.handoff().statusName()).isEqualTo("生产中");
    }

    @Test
    void given_legacy_paid_without_contract_when_status_then_awaiting_production_fallback() {
        // mock 时代遗留单（无合同无付款无承接）的兜底：已过门的既成事实不虚构
        // 流程回退——归「待安排生产」、handoff 缺席如实 null
        Long projectId = newProject();
        Order order = quotedOrder(projectId);
        order.pay("LEGACY-" + order.getId());
        orderRepository.save(order);

        DeliveryStatusResponse status = appService.statusOf(projectId);
        assertThat(status.state()).isEqualTo(DeliveryState.AWAITING_PRODUCTION);
        assertThat(status.handoff()).isNull();
    }

    // ---------- 需用户补充：项目级标志盖帽 ----------

    @Test
    void given_needs_supplement_when_status_then_state_6_with_flag_and_handoff_kept() {
        Long projectId = newProject();
        kickedOffOrder(projectId);
        handoffAppService.setNeedsUserSupplement(projectId, true);

        DeliveryStatusResponse status = appService.statusOf(projectId);
        assertThat(status.state()).isEqualTo(DeliveryState.NEEDS_USER_SUPPLEMENT);
        assertThat(status.needsUserSupplement()).isTrue();
        assertThat(status.handoff()).isNotNull(); // 承接字段照报（卡片双行呈现）

        handoffAppService.setNeedsUserSupplement(projectId, false); // 清零即回底层态
        assertThat(appService.statusOf(projectId).state()).isEqualTo(DeliveryState.AWAITING_PRODUCTION);
    }

    // ---------- 覆盖外形态：待报价 null／无未终结订单 404／项目缺档 404 ----------

    @Test
    void given_pending_quote_when_status_then_state_null() {
        Long projectId = newProject();
        placedOrder(projectId);

        DeliveryStatusResponse status = appService.statusOf(projectId);
        assertThat(status.state()).isNull();
        assertThat(status.stateName()).isNull();
    }

    @Test
    void given_no_active_order_when_status_then_ord_001() {
        Long projectId = newProject();
        assertThatThrownBy(() -> appService.statusOf(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(com.aieducenter.aiplatform.business.order.domain.error.OrderMessage.ORDER_NOT_FOUND.message());
    }

    @Test
    void given_missing_project_when_status_then_prj_001() {
        assertThatThrownBy(() -> appService.statusOf(973999L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 夹具（真链路：直接落库＋公共入口驱动到目标态） ----------

    private Long newProject() {
        Long projectId = projectRepository.save(Project.create("六态推导项目" + WORKSPACE_SEQ.get(),
                null, null, WORKSPACE_SEQ.incrementAndGet(), 1L, null)).getId();
        projectIds.add(projectId);
        return projectId;
    }

    private Order placedOrder(Long projectId) {
        return orderRepository.save(Order.place(projectId, 1L, null,
                "# PRD v3\n\n已确认版本全文", OrderDeliverableType.SYSTEM, 3, 2));
    }

    /** 已报价＋范围齐备（未建合同）。 */
    private Order quotedOrder(Long projectId) {
        Order order = placedOrder(projectId);
        order.enterScope("交付清单：管理后台", "验收方式：按 PRD v3 验收", "工期口径：4 周");
        order.quote(48000L * 100, "首版报价", OPERATOR);
        return orderRepository.save(order);
    }

    private void advanceToSigning(Order order) {
        contractAppService.confirmScope(order.getId());
        Long contractId = activeContractId(order);
        contractAppService.advance(contractId, "内容核对完毕，送用户签署", OPERATOR);
        contractAppService.advance(contractId, "用户已核对，开始签署", OPERATOR);
    }

    /** 已报价＋最近合同已签署完成（订单仍 QUOTED 的付款窗口）。 */
    private Order signedContractOrder(Long projectId) {
        Order order = quotedOrder(projectId);
        advanceToSigning(order);
        Long contractId = activeContractId(order);
        contractAppService.sign(contractId, ContractParty.PARTY_A, "甲方盖章", OPERATOR);
        contractAppService.sign(contractId, ContractParty.PARTY_B, "乙方盖章", OPERATOR);
        contractAppService.advance(contractId, "双方落签，合同完成", OPERATOR);
        return order;
    }

    /** 全链开工（真判定链）：订单转已支付＋承接已建（待安排生产起步）。 */
    private Order kickedOffOrder(Long projectId) {
        Order order = signedContractOrder(projectId);
        paymentAppService.enterNodes(order.getId(), List.of(
                new PaymentNodeSpec("首期开工款", 19200L * 100, true)));
        Long nodeId = nodeIdOf(order, 0);
        paymentAppService.uploadVoucher(order.getId(), nodeId,
                new org.springframework.mock.web.MockMultipartFile("file", "receipt.pdf",
                        "application/pdf", new byte[] {1, 2, 3}));
        paymentAppService.verifyArrival(order.getId(), nodeId,
                new VerifyPaymentCommand(19200L * 100, LocalDateTime.of(2026, 10, 17, 10, 0),
                        null, null, null), OPERATOR);
        return order;
    }

    private Long activeContractId(Order order) {
        return contractRepository.findActiveByOrderId(order.getId()).orElseThrow().getId();
    }

    private Long nodeIdOf(Order order, int seqIndex) {
        return Long.parseLong(paymentAppService.planOfOrder(order.getId()).nodes().get(seqIndex).id());
    }
}
