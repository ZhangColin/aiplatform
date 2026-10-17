package com.aieducenter.aiplatform.business.order.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.business.order.application.dto.response.PaymentPlanResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Contract;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;
import com.aieducenter.aiplatform.business.order.domain.model.PaymentNodeSpec;
import com.aieducenter.aiplatform.business.order.domain.repository.ContractRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 付款域集成测试（#337 验收：计划随合同冻结同刻创建/撤回重建换新计划旧计划
 * 留痕/节点五态与凭证路径全通/凭证追加保留/全额判定与还需支付推导/渠道接口位
 * /节点录入与开工门——应用服务 → 聚合 → 落库全真，以库内真实行为为准；付款域
 * 纯 BC 内闭环，无跨 BC mock）。「处理中/失败」枚举立着、路径 v1 不通＝无任何
 * 动作可达（枚举值钉在集测里）。
 */
@IntegrationTest
class PaymentPlanAppServiceTest {

    /** 每次落单换新项目号（同项目至多一张未终结订单——并发造单不撞活跃唯一索引）。 */
    private static final AtomicLong PROJECT_SEQ = new AtomicLong(900700L);
    private static final Operator OPERATOR = new Operator("700100", "运营·小刘");

    @Autowired
    private PaymentPlanAppService appService;

    @Autowired
    private ContractAppService contractAppService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 付款四表＋合同与留痕行经 FK 级联随订单清（同价目行口径）
        jdbcTemplate.update("DELETE FROM ord_orders");
    }

    // ---------- 计划随合同冻结同刻创建；撤回重建/范围重冻→新计划、旧计划留痕 ----------

    @Test
    void given_confirmed_scope_when_contract_created_then_plan_row_same_moment() {
        Order order = confirmedOrder();

        Map<String, Object> plan = jdbcTemplate.queryForMap(
                "SELECT p.order_id, p.contract_id, p.contract_no FROM ord_payment_plans p"
                        + " JOIN ord_contracts c ON c.id = p.contract_id WHERE p.order_id = ?",
                order.getId());
        assertThat(plan.get("contract_no")).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT contract_no FROM ord_contracts WHERE order_id = ?", String.class, order.getId()));
        // 同刻创建＝同拍留痕（合同行在场即计划行在场，零节点待后台录入）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_payment_nodes", Integer.class)).isZero();
        assertThat(appService.planOfOrder(order.getId()).nodes()).isEmpty();
    }

    @Test
    void given_withdrawn_contract_when_rebuilt_then_new_plan_and_old_kept() {
        Order order = confirmedOrder();
        Contract first = contractRepository.findActiveByOrderId(order.getId()).orElseThrow();
        appService.enterNodes(order.getId(), threeNodes());
        contractAppService.withdraw(first.getId(), "整案回炉", OPERATOR);
        contractAppService.confirmScope(order.getId()); // 重建＝新合同新计划

        List<Map<String, Object>> plans = jdbcTemplate.queryForList(
                "SELECT id, contract_id FROM ord_payment_plans WHERE order_id = ? ORDER BY id",
                order.getId());
        assertThat(plans).hasSize(2); // 新旧两行都在（旧计划随旧合同留痕）
        assertThat(plans.get(1).get("contract_id")).isNotEqualTo(plans.get(0).get("contract_id"));
        // 旧计划节点定格保留（3 节点）；新活跃计划零节点待录入
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_payment_nodes WHERE plan_id = ?",
                Integer.class, plans.get(0).get("id"))).isEqualTo(3);
        assertThat(appService.planOfOrder(order.getId()).nodes()).isEmpty();
    }

    @Test
    void given_draft_contract_when_re_recorded_then_new_plan_for_refrozen_set() {
        Order order = confirmedOrder();
        Contract contract = contractRepository.findActiveByOrderId(order.getId()).orElseThrow();
        appService.enterNodes(order.getId(), threeNodes());
        order.quote(99000L, "改价", OPERATOR);
        orderRepository.save(order);

        contractAppService.record(contract.getId(), "张三", "语义引力科技有限公司",
                "管理后台＋小程序", "开工款 50%，验收 50%", OPERATOR, "改价后重录");

        // 范围重冻→新付款计划：旧行留痕（3 节点定格）、新活跃行零节点
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_payment_plans WHERE order_id = ?",
                Integer.class, order.getId())).isEqualTo(2);
        assertThat(appService.planOfOrder(order.getId()).nodes()).isEmpty();
    }

    // ---------- 节点录入（含开工门设定）：派生序号/附言码/渠道位与重录窗口 ----------

    @Test
    void given_empty_plan_when_enter_nodes_then_rows_with_remark_codes_and_gates() {
        Order order = confirmedOrder();
        String contractNo = jdbcTemplate.queryForObject(
                "SELECT contract_no FROM ord_contracts WHERE order_id = ?", String.class, order.getId());

        PaymentPlanResponse plan = appService.enterNodes(order.getId(), List.of(
                new PaymentNodeSpec("首期开工款", 19200L * 100, true),
                new PaymentNodeSpec("中期验收款", 19200L * 100, false),
                new PaymentNodeSpec("尾款结算", 9600L * 100, false)));

        assertThat(plan.nodes()).hasSize(3);
        assertThat(plan.totalAmount()).isEqualTo(48000L * 100);
        // 附言码＝<合同号>-K<序>（#337 格式实施定）；序号按录入序派生；开工门随行定
        assertThat(plan.nodes().stream().map(n -> n.remarkCode()))
                .containsExactly(contractNo + "-K1", contractNo + "-K2", contractNo + "-K3");
        assertThat(plan.nodes().get(0).kickoffGate()).isTrue();
        assertThat(plan.nodes().get(1).kickoffGate()).isFalse();
        // 渠道接口位：v1 恒线下转账、流水号空
        assertThat(plan.nodes()).allSatisfy(node -> {
            assertThat(node.channelType()).isEqualTo(1);
            assertThat(node.channelRefNo()).isNull();
        });
        // 占比展示换算：40/40/20（绝对额录入、占比现算）
        assertThat(plan.nodes().stream().map(n -> n.percentage())).containsExactly(40, 40, 20);
        // 开工款≠默认全款：三节点各自绝对额、无「首节点=总额」假设
        assertThat(plan.nodes().get(0).amount()).isEqualTo(19200L * 100);
    }

    @Test
    void given_invalid_specs_when_enter_nodes_then_rejected_ord_039() {
        Order order = confirmedOrder();
        // 聚合守卫抛 DomainException（应用层透传，REST 层同译错误信封）——同
        // ContractAppServiceTest 非法迁移用例口径，认消息不认异常类
        assertThatThrownBy(() -> appService.enterNodes(order.getId(), List.of()))
                .hasMessageContaining(OrderMessage.PAYMENT_NODES_INVALID.message());
        assertThatThrownBy(() -> appService.enterNodes(order.getId(),
                        List.of(new PaymentNodeSpec("  ", 100L, false))))
                .hasMessageContaining(OrderMessage.PAYMENT_NODES_INVALID.message());
        assertThatThrownBy(() -> appService.enterNodes(order.getId(),
                        List.of(new PaymentNodeSpec("尾款", 0L, false))))
                .hasMessageContaining(OrderMessage.PAYMENT_NODES_INVALID.message());
    }

    @Test
    void given_payment_started_when_enter_nodes_again_then_rejected_ord_044() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order);
        appService.uploadVoucher(order.getId(), nodeId, pdfVoucher("receipt-1.pdf"));

        // 凭证腿：有凭证即锁（聚合腿之外的服务预检）
        assertThatThrownBy(() -> appService.enterNodes(order.getId(), threeNodes()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.PAYMENT_NODES_LOCKED.message());
    }

    // ---------- 凭证路径全通：上传→待核验→核验→到账/打回 ----------

    @Test
    void given_pending_node_when_upload_voucher_then_awaiting_and_appended_kept() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order);

        appService.uploadVoucher(order.getId(), nodeId, pdfVoucher("receipt-1.pdf"));
        PaymentPlanResponse afterFirst = appService.uploadVoucher(
                order.getId(), nodeId, pdfVoucher("receipt-2.pdf"));

        // 多次上传追加保留不覆盖（两行都在）；状态＝线下转账待核验
        assertThat(afterFirst.nodes().get(0).vouchers()).hasSize(2);
        assertThat(afterFirst.nodes().get(0).vouchers().stream().map(v -> v.fileName()))
                .containsExactly("receipt-1.pdf", "receipt-2.pdf");
        assertThat(afterFirst.nodes().get(0).status()).isEqualTo(4);
        // 字节伴行落库（点取内容可回）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_payment_voucher_files", Integer.class)).isEqualTo(2);
    }

    @Test
    void given_invalid_file_when_upload_then_rejected_ord_040() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order);

        assertThatThrownBy(() -> appService.uploadVoucher(order.getId(), nodeId,
                        new MockMultipartFile("file", "malware.txt", "text/plain", new byte[] {1})))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.PAYMENT_VOUCHER_INVALID.message());
        byte[] oversize = new byte[(int) (10L * 1024 * 1024 + 1)];
        assertThatThrownBy(() -> appService.uploadVoucher(order.getId(), nodeId,
                        new MockMultipartFile("file", "big.png", "image/png", oversize)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.PAYMENT_VOUCHER_INVALID.message());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_payment_vouchers", Integer.class)).isZero();
    }

    @Test
    void given_awaiting_node_when_verify_full_amount_then_received_with_channel_ref() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order);
        PaymentPlanResponse plan = appService.uploadVoucher(
                order.getId(), nodeId, pdfVoucher("receipt.pdf"));
        String voucherId = plan.nodes().get(0).vouchers().get(0).id();
        LocalDateTime arrivedAt = LocalDateTime.of(2026, 10, 17, 10, 0);

        PaymentPlanResponse verified = appService.verifyArrival(order.getId(), nodeId,
                new com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand(
                        19200L * 100, arrivedAt, voucherId, "BANK-20261017-0001", "银企对账一致"),
                OPERATOR);

        assertThat(verified.nodes().get(0).status()).isEqualTo(5); // 已到账
        assertThat(verified.nodes().get(0).receivedAt()).isEqualTo(arrivedAt);
        assertThat(verified.nodes().get(0).channelRefNo()).isEqualTo("BANK-20261017-0001");
        // 留痕行：到账核验（action=1）、from=4 to=5、实到金额/时间/关联凭证随行落
        Map<String, Object> trail = jdbcTemplate.queryForMap(
                "SELECT action, from_status, to_status, actual_amount, arrived_at, voucher_id,"
                        + " basis, operator_name FROM ord_payment_verifications WHERE node_id = ?",
                nodeId);
        assertThat(trail.get("action")).isEqualTo(1);
        assertThat(trail.get("from_status")).isEqualTo(4);
        assertThat(trail.get("to_status")).isEqualTo(5);
        assertThat(trail.get("actual_amount")).isEqualTo(19200L * 100);
        assertThat(trail.get("basis")).isEqualTo("银企对账一致");
        assertThat(trail.get("operator_name")).isEqualTo("运营·小刘");
    }

    @Test
    void given_insufficient_amount_when_verify_then_back_to_pending_and_remaining_derivable() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order); // 应付 19,200 元
        appService.uploadVoucher(order.getId(), nodeId, pdfVoucher("receipt-1.pdf"));

        // 首核不足：回待支付＋留痕（实到 10,000 元）
        PaymentPlanResponse first = appService.verifyArrival(order.getId(), nodeId,
                new com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand(
                        10000L * 100, LocalDateTime.of(2026, 10, 17, 9, 0), null, null, null),
                OPERATOR);
        assertThat(first.nodes().get(0).status()).isEqualTo(1); // 回待支付
        // 前台推导面：还需支付＝amount − settled（9,200 元）、对应节点即本节点
        assertThat(first.nodes().get(0).remainingAmount()).isEqualTo(9200L * 100);
        assertThat(first.nodes().get(0).settledAmount()).isEqualTo(10000L * 100);

        // 补传补核：累计到全额即转已到账（累计不清零）
        appService.uploadVoucher(order.getId(), nodeId, pdfVoucher("receipt-2.pdf"));
        PaymentPlanResponse settled = appService.verifyArrival(order.getId(), nodeId,
                new com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand(
                        9200L * 100, LocalDateTime.of(2026, 10, 17, 15, 0), null, null, null),
                OPERATOR);
        assertThat(settled.nodes().get(0).status()).isEqualTo(5);
        assertThat(settled.nodes().get(0).remainingAmount()).isEqualTo(0L);
        // 两行留痕 append-only（不足＋足额各一行）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_payment_verifications WHERE node_id = ?",
                Integer.class, nodeId)).isEqualTo(2);
    }

    @Test
    void given_awaiting_node_when_reject_then_back_to_pending_with_basis_trail() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order);
        appService.uploadVoucher(order.getId(), nodeId, pdfVoucher("blurry.pdf"));

        PaymentPlanResponse rejected = appService.reject(order.getId(), nodeId,
                new com.aieducenter.aiplatform.business.order.application.dto.command.RejectPaymentCommand(
                        null, "回单模糊无法对账，请重新上传"),
                OPERATOR);

        assertThat(rejected.nodes().get(0).status()).isEqualTo(1); // 回待支付
        assertThat(rejected.nodes().get(0).settledAmount()).isEqualTo(0L); // 打回不动累计
        Map<String, Object> trail = jdbcTemplate.queryForMap(
                "SELECT action, to_status, basis FROM ord_payment_verifications WHERE node_id = ?",
                nodeId);
        assertThat(trail.get("action")).isEqualTo(2); // 打回
        assertThat(trail.get("to_status")).isEqualTo(1);
        assertThat(trail.get("basis")).isEqualTo("回单模糊无法对账，请重新上传");
        // 打回后可再传（回待支付＝用户可再动作态）
        assertThat(appService.uploadVoucher(order.getId(), nodeId, pdfVoucher("clear.pdf"))
                .nodes().get(0).status()).isEqualTo(4);
    }

    @Test
    void given_wrong_states_when_act_then_rejected_ord_038_without_trail() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order);

        // 待支付直接核验（无凭证主张）拒
        assertThatThrownBy(() -> appService.verifyArrival(order.getId(), nodeId,
                        new com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand(
                                100L, LocalDateTime.now(), null, null, null), OPERATOR))
                .hasMessageContaining(OrderMessage.PAYMENT_ACTION_NOT_ALLOWED.message());

        // 已到账再传凭证拒
        appService.uploadVoucher(order.getId(), nodeId, pdfVoucher("receipt.pdf"));
        appService.verifyArrival(order.getId(), nodeId,
                new com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand(
                        19200L * 100, LocalDateTime.now(), null, null, null), OPERATOR);
        assertThatThrownBy(() -> appService.uploadVoucher(order.getId(), nodeId,
                        pdfVoucher("again.pdf")))
                .hasMessageContaining(OrderMessage.PAYMENT_ACTION_NOT_ALLOWED.message());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_payment_verifications WHERE node_id = ?",
                Integer.class, nodeId)).isEqualTo(1); // 被拒动作零留痕行
    }

    @Test
    void given_missing_entries_when_verify_or_reject_then_rejected_with_field_errors() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order);
        appService.uploadVoucher(order.getId(), nodeId, pdfVoucher("receipt.pdf"));

        // 实到金额非正/实到时间缺失 → ORD_041
        assertThatThrownBy(() -> appService.verifyArrival(order.getId(), nodeId,
                        new com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand(
                                0L, LocalDateTime.now(), null, null, null), OPERATOR))
                .hasMessageContaining(OrderMessage.PAYMENT_VERIFY_ENTRY_INVALID.message());
        assertThatThrownBy(() -> appService.verifyArrival(order.getId(), nodeId,
                        new com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand(
                                100L, null, null, null, null), OPERATOR))
                .hasMessageContaining(OrderMessage.PAYMENT_VERIFY_ENTRY_INVALID.message());
        // 打回依据缺失 → ORD_042；操作者缺失 → ORD_043
        assertThatThrownBy(() -> appService.reject(order.getId(), nodeId,
                        new com.aieducenter.aiplatform.business.order.application.dto.command.RejectPaymentCommand(
                                null, " "), OPERATOR))
                .hasMessageContaining(OrderMessage.PAYMENT_REJECT_BASIS_REQUIRED.message());
        assertThatThrownBy(() -> appService.reject(order.getId(), nodeId,
                        new com.aieducenter.aiplatform.business.order.application.dto.command.RejectPaymentCommand(
                                null, "回单不清"), null))
                .hasMessageContaining(OrderMessage.PAYMENT_OPERATOR_REQUIRED.message());
        // 关联凭证不存在或不属该节点 → ORD_045
        assertThatThrownBy(() -> appService.verifyArrival(order.getId(), nodeId,
                        new com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand(
                                100L, LocalDateTime.now(), "999999999", null, null), OPERATOR))
                .hasMessageContaining(OrderMessage.PAYMENT_VOUCHER_NOT_FOUND.message());
    }

    @Test
    void given_node_not_in_active_plan_when_upload_then_rejected_ord_037() {
        Order order = confirmedOrder();
        Contract first = contractRepository.findActiveByOrderId(order.getId()).orElseThrow();
        appService.enterNodes(order.getId(), threeNodes());
        Long staleNodeId = firstNodeId(order);
        contractAppService.withdraw(first.getId(), "整案回炉", OPERATOR);
        contractAppService.confirmScope(order.getId()); // 新计划（旧节点不在活跃计划）

        assertThatThrownBy(() -> appService.uploadVoucher(order.getId(), staleNodeId,
                        pdfVoucher("stale.pdf")))
                .hasMessageContaining(OrderMessage.PAYMENT_NODE_NOT_FOUND.message());
    }

    @Test
    void given_cancelled_order_when_upload_then_rejected_ord_038() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order);
        order.cancel();
        orderRepository.save(order);

        assertThatThrownBy(() -> appService.uploadVoucher(order.getId(), nodeId,
                        pdfVoucher("late.pdf")))
                .hasMessageContaining(OrderMessage.PAYMENT_ACTION_NOT_ALLOWED.message());
    }

    // ---------- 渠道接口位与五态枚举（处理中/失败路径 v1 不通＝无动作可达） ----------

    @Test
    void given_node_five_states_when_v1_then_only_voucher_path_reachable() {
        // 枚举立着：五态代码齐（1-5）；「处理中/失败」无任何迁移可达——领域动作面
        // （submitVoucher/verifyArrival/reject）只能落 1/4/5 三态
        assertThat(com.aieducenter.aiplatform.business.order.domain.enums.PaymentNodeStatus
                .values()).extracting("code").containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void given_uploaded_voucher_when_fetch_content_then_bytes_round_trip() {
        Order order = confirmedOrder();
        appService.enterNodes(order.getId(), threeNodes());
        Long nodeId = firstNodeId(order);
        byte[] content = "%PDF-1.4 凭证字节".getBytes();
        PaymentPlanResponse plan = appService.uploadVoucher(order.getId(), nodeId,
                new MockMultipartFile("file", "receipt.pdf", "application/pdf", content));
        String voucherId = plan.nodes().get(0).vouchers().get(0).id();

        com.aieducenter.aiplatform.business.order.application.dto.response.PaymentVoucherContent fetched =
                appService.voucherWithContent(Long.parseLong(voucherId));

        assertThat(fetched.content()).isEqualTo(content);
        assertThat(fetched.fileName()).isEqualTo("receipt.pdf");
        assertThat(fetched.contentType()).isEqualTo("application/pdf");
    }

    // ---------- 夹具（真链路：直接落库驱动到目标态，聚合经公共入口） ----------

    private Order placedOrder() {
        return orderRepository.save(Order.place(PROJECT_SEQ.incrementAndGet(), 1L, null,
                "# PRD v3\n\n已确认版本全文", OrderDeliverableType.SYSTEM, 3, 2));
    }

    /** 已报价＋范围齐备＋合同与计划在场（确认范围已过）的订单。 */
    private Order confirmedOrder() {
        Order order = placedOrder();
        order.enterScope("交付清单：管理后台", "验收方式：按 PRD v3 验收", "工期口径：4 周");
        order.quote(48000L * 100, "首版报价", OPERATOR);
        order = orderRepository.save(order);
        contractAppService.confirmScope(order.getId());
        return orderRepository.findWithHistory(order.getId()).orElseThrow();
    }

    /** 三节点示例（原型 40/40/20 仅示例——绝对额录入）。 */
    private static List<PaymentNodeSpec> threeNodes() {
        return List.of(
                new PaymentNodeSpec("首期开工款", 19200L * 100, true),
                new PaymentNodeSpec("中期验收款", 19200L * 100, false),
                new PaymentNodeSpec("尾款结算", 9600L * 100, false));
    }

    /** 活跃计划首节点 id（凭证路径夹具）。 */
    private Long firstNodeId(Order order) {
        return Long.parseLong(appService.planOfOrder(order.getId()).nodes().get(0).id());
    }

    private static MockMultipartFile pdfVoucher(String name) {
        return new MockMultipartFile("file", name, "application/pdf",
                ("%PDF-1.4 " + name).getBytes());
    }
}
