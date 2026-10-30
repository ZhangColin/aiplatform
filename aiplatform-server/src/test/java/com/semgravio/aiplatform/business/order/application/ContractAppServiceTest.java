package com.semgravio.aiplatform.business.order.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.order.application.dto.response.ContractResponse;
import com.semgravio.aiplatform.business.order.domain.aggregate.Contract;
import com.semgravio.aiplatform.business.order.domain.aggregate.Order;
import com.semgravio.aiplatform.business.order.domain.enums.ContractParty;
import com.semgravio.aiplatform.business.order.domain.enums.ContractStatus;
import com.semgravio.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.order.domain.model.Operator;
import com.semgravio.aiplatform.business.order.domain.repository.ContractRepository;
import com.semgravio.aiplatform.business.order.domain.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 合同域集成测试（#336 验收：六态状态机/留痕/唯一约束/重录重建/冻结集引用——
 * 应用服务 → 聚合 → 落库 → 唯一索引全真，以库内真实行为为准；合同域纯
 * BC 内闭环，无跨 BC mock）。「确认范围并准备合同」＝合同创建口（用户确认与
 * 后台重建共用同一冻结正本）；活跃唯一双保险（预检 ORD_024＋库侧部分唯一
 * 索引）；撤回重建＝新号新合同、旧合同终态保留；草稿重录＝同号重新冻结＋
 * 版本递增＋「需更新」清零。
 */
@IntegrationTest
class ContractAppServiceTest {

    /** 每次落单换新项目号（同项目至多一张未终结订单——并发造单不撞活跃唯一索引）。 */
    private static final AtomicLong PROJECT_SEQ = new AtomicLong(900200L);
    private static final Operator OPERATOR = new Operator("700100", "运营·小刘");

    @Autowired
    private ContractAppService appService;

    @Autowired
    private OrderAppService orderAppService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 合同与留痕行经 FK 级联随订单清（同价目行口径）
        jdbcTemplate.update("DELETE FROM ord_orders");
    }

    // ---------- 确认范围并准备合同：冻结集捕获（报价版本引用＋范围快照＋双版本冗余） ----------

    @Test
    void given_quoted_scoped_order_when_confirm_scope_then_contract_row_with_frozen_set() {
        Order order = placedOrder();
        order.enterScope("交付清单：管理后台＋小程序", "验收方式：按 PRD v3 验收", "工期口径：4 周");
        order.quote(128000L, "首版报价", OPERATOR);
        orderRepository.save(order);

        ContractResponse contract = appService.confirmScope(order.getId());

        // 库内事实：准备中起步、SG- 编号、冻结集五面（报价版本引用/金额/范围快照
        // 三段/PRD 版本＋全文冗余/Demo 版本引用）随单定格
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT contract_no, status, version, quote_entry_id, amount, currency,"
                        + " scope_deliverables, scope_acceptance, scope_timeline,"
                        + " prd_version_no, prd_snapshot, demo_version_no"
                        + " FROM ord_contracts WHERE id = ?",
                Long.parseLong(contract.id()));
        assertThat(row.get("status")).isEqualTo(1);
        assertThat(row.get("version")).isEqualTo(1);
        assertThat((String) row.get("contract_no")).startsWith("SG-");
        // 报价版本引用＝冻结时点最新价目行（独立取证：直查价目行表）
        assertThat(row.get("quote_entry_id")).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT id FROM ord_price_entries WHERE order_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, order.getId()));
        assertThat(row.get("amount")).isEqualTo(128000L);
        assertThat(row.get("scope_deliverables")).isEqualTo("交付清单：管理后台＋小程序");
        assertThat(row.get("scope_acceptance")).isEqualTo("验收方式：按 PRD v3 验收");
        assertThat(row.get("scope_timeline")).isEqualTo("工期口径：4 周");
        assertThat(row.get("prd_version_no")).isEqualTo(3);
        assertThat(row.get("prd_snapshot")).isEqualTo("# PRD v3\n\n已确认版本全文");
        assertThat(row.get("demo_version_no")).isEqualTo(2);
        assertThat(contract.needsUpdate()).isFalse();
    }

    @Test
    void given_unquoted_or_unscoped_order_when_confirm_scope_then_rejected_with_gate_error() {
        // 前置门两腿：无报价 ORD_025（范围快照冻结报价版本引用，报价必须先在）；
        // 无范围文本 ORD_026（后台未录入范围，无从冻结）
        Order unquoted = placedOrder();
        orderRepository.save(unquoted);
        assertThatThrownBy(() -> appService.confirmScope(unquoted.getId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.CONTRACT_QUOTE_REQUIRED.message());

        Order unscoped = placedOrder();
        unscoped.quote(128000L, "首版报价", OPERATOR);
        orderRepository.save(unscoped);
        assertThatThrownBy(() -> appService.confirmScope(unscoped.getId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.CONTRACT_SCOPE_REQUIRED.message());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_contracts", Integer.class)).isZero(); // 拦截零落行
    }

    @Test
    void given_active_contract_when_confirm_scope_again_then_rejected_ord_024() {
        // 活跃唯一·预检腿：同订单第二份活跃合同拒（ORD_024）；后台重建同守卫
        Order order = confirmedOrder();

        assertThatThrownBy(() -> appService.confirmScope(order.getId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.CONTRACT_ALREADY_ACTIVE.message());
    }

    @Test
    void given_signed_latest_contract_when_confirm_scope_then_rejected_ord_021() {
        // #336 遗留缝收口（#338）：最近合同已签署完成（终态——活跃预检查不到）
        // 后拒再建合同：已签走变更沟通不碰原合同，新草稿不污染开工判定的
        // 「最近合同」读面。订单仍 QUOTED（开工门未满足的常态等待窗口）
        Order order = confirmedOrder();
        Contract contract = activeContract(order);
        appService.advance(contract.getId(), "内容核对完毕，送用户签署", OPERATOR);
        appService.advance(contract.getId(), "用户已核对，开始签署", OPERATOR);
        appService.sign(contract.getId(), ContractParty.PARTY_A, "甲方盖章", OPERATOR);
        appService.sign(contract.getId(), ContractParty.PARTY_B, "乙方盖章", OPERATOR);
        appService.advance(contract.getId(), "双方落签，合同完成", OPERATOR);

        assertThatThrownBy(() -> appService.confirmScope(order.getId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED.message());
        // 单合同不增行（已签正身不被新草稿覆盖）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_contracts WHERE order_id = ?", Integer.class,
                order.getId())).isEqualTo(1);
    }

    @Test
    void given_active_contract_when_second_insert_bypasses_precheck_then_unique_index_rejects() {
        // 活跃唯一·库侧兜底腿：绕过应用预检直插第二行（并发漏过预检的等价形态），
        // 部分唯一索引拒绝——「同订单至多一份活跃合同」的最终防线在库不在代码
        Order order = confirmedOrder();

        assertThatThrownBy(() -> contractRepository.save(Contract.create(order,
                order.latestPriceEntry())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- 后台人工推进：留痕 append-only 落库（操作人/时间/依据） ----------

    @Test
    void given_backoffice_actions_when_advance_and_sign_then_trail_rows_persisted() {
        Order order = confirmedOrder();
        Contract contract = activeContract(order);

        appService.advance(contract.getId(), "内容核对完毕，送用户签署", OPERATOR);
        appService.advance(contract.getId(), "用户已核对，开始签署", OPERATOR);
        appService.sign(contract.getId(), ContractParty.PARTY_A, "甲方盖章", OPERATOR);
        appService.sign(contract.getId(), ContractParty.PARTY_B, "乙方盖章", OPERATOR);
        appService.advance(contract.getId(), "双方落签，合同完成", OPERATOR);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, party_a_signed, party_b_signed FROM ord_contracts WHERE id = ?",
                contract.getId());
        assertThat(row.get("status")).isEqualTo(4); // 签署完成＝双方均签后推进
        assertThat(row.get("party_a_signed")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("party_b_signed")).isEqualTo(Boolean.TRUE);

        // 留痕五行 append-only：动作/两侧状态/依据/操作人随行落（时间＝created_at 非空）
        List<Map<String, Object>> trail = jdbcTemplate.queryForList(
                "SELECT action, from_status, to_status, basis, operator_id, operator_name, created_at"
                        + " FROM ord_contract_transitions WHERE contract_id = ? ORDER BY id",
                contract.getId());
        assertThat(trail).hasSize(5);
        assertThat(trail.get(0).get("action")).isEqualTo(1); // 推进
        assertThat(trail.get(3).get("action")).isEqualTo(4); // 签署标记（from=to）
        assertThat(trail.get(3).get("from_status")).isEqualTo(trail.get(3).get("to_status"));
        assertThat(trail.get(4).get("basis")).isEqualTo("双方落签，合同完成");
        assertThat(trail.get(4).get("operator_name")).isEqualTo("运营·小刘");
        assertThat(trail).allSatisfy(t -> assertThat(t.get("created_at")).isNotNull());
    }

    @Test
    void given_illegal_advance_when_act_then_rejected_and_no_trail_row() {
        // 非法迁移拒绝（ORD_021）：准备中直接想完成？推进是线性单步——到签署完成
        // 须走全链；被拒动作零留痕行。聚合守卫抛 DomainException（应用层透传）
        Order order = confirmedOrder();
        Contract contract = activeContract(order);

        assertThatThrownBy(() -> appService.markFailed(contract.getId(), "早失败", OPERATOR))
                .hasMessageContaining(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED.message());
        assertThatThrownBy(() -> appService.sign(contract.getId(), ContractParty.PARTY_A, "早签", OPERATOR))
                .hasMessageContaining(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED.message());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_contract_transitions WHERE contract_id = ?",
                Integer.class, contract.getId())).isZero();
    }

    // ---------- 撤回重建：新号新合同、旧合同终态保留（终态永不物理删除） ----------

    @Test
    void given_signing_contract_when_withdraw_then_rebuild_gets_new_no_and_old_kept() {
        Order order = confirmedOrder();
        Contract first = activeContract(order);
        appService.advance(first.getId(), "内容核对完毕，送用户签署", OPERATOR);
        appService.advance(first.getId(), "用户已核对，开始签署", OPERATOR);
        String firstNo = appService.detailForBackoffice(first.getId()).contract().contractNo();

        appService.withdraw(first.getId(), "范围录错，整案回炉", OPERATOR);

        // 旧合同终态保留（已撤回或作废，行还在）；重建＝新号新合同、版本从 1 起
        ContractResponse rebuilt = appService.confirmScope(order.getId());
        assertThat(rebuilt.status()).isEqualTo(ContractStatus.PREPARING);
        assertThat(rebuilt.contractNo()).isNotEqualTo(firstNo);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM ord_contracts WHERE order_id = ? ORDER BY id", Integer.class, order.getId()))
                .containsExactly(6, 1); // 旧终态行在前、新活跃行在后，都在
    }

    // ---------- 草稿重录：同号重新冻结＋版本递增＋「需更新」派生清零 ----------

    @Test
    void given_draft_contract_when_reprice_then_needs_update_and_record_clears_it() {
        Order order = confirmedOrder();
        Contract contract = activeContract(order);

        // 改价后：草稿对照订单现值「需更新」（后台须重录重新冻结）
        order.quote(99000L, "改价", OPERATOR);
        orderRepository.save(order);
        assertThat(appService.latestByOrder(order.getId()).needsUpdate()).isTrue();

        appService.record(contract.getId(), "张三", "语义引力科技有限公司",
                "管理后台＋小程序", "开工款 50%，验收 50%", OPERATOR, "改价后重录");

        ContractResponse after = appService.latestByOrder(order.getId());
        assertThat(after.needsUpdate()).isFalse(); // 重冻即清零
        assertThat(after.version()).isEqualTo(2);
        assertThat(after.amount()).isEqualTo(99000L); // 冻结集取现值
        assertThat(after.contractNo()).isEqualTo(appService.latestByOrder(order.getId()).contractNo()); // 同号
        Map<String, Object> trailRow = jdbcTemplate.queryForMap(
                "SELECT action, basis FROM ord_contract_transitions WHERE contract_id = ?",
                contract.getId());
        assertThat(trailRow.get("action")).isEqualTo(5); // 草稿重录
        assertThat(trailRow.get("basis")).isEqualTo("改价后重录");
    }

    // ---------- 用户面读：订单名下最近合同（无合同如实 404） ----------

    @Test
    void given_order_without_contract_when_latest_by_order_then_ord_020() {
        Order order = placedOrder();
        orderRepository.save(order);

        assertThatThrownBy(() -> appService.latestByOrder(order.getId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_CONTRACT_NOT_FOUND.message());
    }

    // ---------- 夹具（真链路：直接落库驱动到目标态，聚合经公共入口） ----------

    private Order placedOrder() {
        return orderRepository.save(Order.place(PROJECT_SEQ.incrementAndGet(), 1L, null,
                "# PRD v3\n\n已确认版本全文", OrderDeliverableType.SYSTEM, 3, 2));
    }

    /** 已报价＋范围齐备＋合同在场（确认范围已过）的订单。 */
    private Order confirmedOrder() {
        Order order = placedOrder();
        order.enterScope("交付清单：管理后台", "验收方式：按 PRD v3 验收", "工期口径：4 周");
        order.quote(128000L, "首版报价", OPERATOR);
        order = orderRepository.save(order);
        appService.confirmScope(order.getId());
        return orderRepository.findWithHistory(order.getId()).orElseThrow();
    }

    private Contract activeContract(Order order) {
        return contractRepository.findActiveByOrderId(order.getId()).orElseThrow();
    }
}
