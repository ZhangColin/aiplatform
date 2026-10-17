package com.aieducenter.aiplatform.business.order.domain.aggregate;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.order.domain.entity.OrderPriceEntry;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractParty;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractStatus;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractTransitionAction;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 合同聚合状态机（#336 六态照交接包原样立）：创建（范围快照冻结集捕获＋编号
 * 定格）/ 推进（线性单向、签署完成＝双方均签）/ 签署位两枚 / 撤回·作废·失败
 * 或过期（旁落终态）/ 草稿重录（同号重新冻结＋版本递增）/ 需更新派生（草稿态
 * 对照订单现值）。留痕（操作人/依据必填）经 transitions 只追加集合断言——
 * append-only 语义与库行为归应用服务缝。
 */
class ContractTest {

    private static final Operator OPERATOR = new Operator("700100", "运营·小刘");

    // ---------- 创建：范围快照冻结集捕获＋编号定格（SG- 风格全局唯一） ----------

    @Test
    void given_quoted_scoped_order_when_create_then_preparing_with_freeze_set_captured() {
        Order order = scopedQuotedOrder();

        Contract contract = Contract.create(order, latestQuote(order));

        // 六态起步＝准备中（后台人工推进的起草态）；合同版本自 1 起
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.PREPARING);
        assertThat(contract.getVersion()).isEqualTo(1);
        assertThat(contract.isTerminal()).isFalse();
        // 范围快照（冻结集）：报价版本引用（冻结时点价目行）＋金额＋范围文本不可变副本
        assertThat(contract.getQuoteEntryId()).isEqualTo(latestQuote(order).getId());
        assertThat(contract.getAmount()).isEqualTo(128000L);
        assertThat(contract.getCurrency()).isEqualTo("CNY");
        // 冻结集自订单工作副本单源取（create 不收第二数据源）
        assertThat(contract.getScopeDeliverables()).isEqualTo(order.getScopeDeliverables());
        assertThat(contract.getScopeAcceptance()).isEqualTo(order.getScopeAcceptance());
        assertThat(contract.getScopeTimeline()).isEqualTo(order.getScopeTimeline());
        // 双版本引用（#335 下单冻结集的合同侧冗余——PRD 全文冗余拷贝＋版本号、Demo 版本号）
        assertThat(contract.getPrdVersionNo()).isEqualTo(3);
        assertThat(contract.getPrdSnapshot()).isEqualTo("# PRD v3\n\n已确认版本全文");
        assertThat(contract.getDemoVersionNo()).isEqualTo(2);
        // 签署位两枚起步未签；预览字段集待后台录入（空）
        assertThat(contract.isPartyASigned()).isFalse();
        assertThat(contract.isPartyBSigned()).isFalse();
        assertThat(contract.getPartyA()).isNull();
    }

    @Test
    void given_two_creations_when_read_contract_nos_then_sg_prefixed_and_distinct() {
        // 编号全局唯一（格式实施定：SG-＋小写字母数字，创建即定格）；期望值来自
        // 独立事实（前缀/字符集/互异），不重算实现式
        Order order = scopedQuotedOrder();
        Contract first = Contract.create(order, latestQuote(order));
        Contract second = Contract.create(order, latestQuote(order));

        assertThat(first.getContractNo()).matches("SG-[0-9a-z]{10,}");
        assertThat(second.getContractNo()).matches("SG-[0-9a-z]{10,}");
        assertThat(first.getContractNo()).isNotEqualTo(second.getContractNo());
    }

    @Test
    void given_scope_not_entered_when_create_then_rejected_ord_026() {
        // 范围快照冻结集的守卫腿：范围文本未录入（确认门未过形态）不建合同
        Order order = Order.place(900100L, null, null,
                "# PRD v3\n\n已确认版本全文", OrderDeliverableType.SYSTEM, 3, 2);
        order.quote(128000L, "首版报价", null);
        assertThatThrownBy(() -> Contract.create(order, order.latestPriceEntry()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.CONTRACT_SCOPE_REQUIRED.message());
    }

    @Test
    void given_no_operator_when_action_then_rejected_ord_035() {
        // 留痕以操作人为追责正身：无名动作拒绝（缺透传头如实拦，不落无名留痕）
        Contract contract = preparingContract();
        for (Operator missing : new Operator[] {null, new Operator(null, "运营·小刘")}) {
            assertThatThrownBy(() -> contract.advance("无操作者", missing))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.CONTRACT_OPERATOR_REQUIRED.message());
        }
        assertThat(contract.getTransitions()).isEmpty();
        assertThatThrownBy(() -> contract.sign(ContractParty.PARTY_A, "无操作者", null))
                .hasMessageContaining(OrderMessage.CONTRACT_OPERATOR_REQUIRED.message());
    }

    // ---------- 推进：线性单向＋签署完成守卫＋留痕（操作人/依据必填） ----------

    @Test
    void given_preparing_contract_when_advance_twice_then_signing_with_append_only_trail() {
        Contract contract = preparingContract();

        contract.advance("内容核对完毕，送用户签署", OPERATOR);
        contract.advance("用户已核对，开始签署", OPERATOR);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.SIGNING);
        // 留痕两行 append-only：推进动作、两侧状态、依据与操作人随行落
        assertThat(contract.getTransitions()).hasSize(2);
        assertThat(contract.getTransitions().get(0).getAction())
                .isEqualTo(ContractTransitionAction.ADVANCE);
        assertThat(contract.getTransitions().get(0).getFromStatus()).isEqualTo(ContractStatus.PREPARING);
        assertThat(contract.getTransitions().get(0).getToStatus()).isEqualTo(ContractStatus.AWAITING_SIGNATURE);
        assertThat(contract.getTransitions().get(0).getBasis()).isEqualTo("内容核对完毕，送用户签署");
        assertThat(contract.getTransitions().get(0).getOperatorId()).isEqualTo("700100");
        assertThat(contract.getTransitions().get(1).getFromStatus()).isEqualTo(ContractStatus.AWAITING_SIGNATURE);
        assertThat(contract.getTransitions().get(1).getToStatus()).isEqualTo(ContractStatus.SIGNING);
    }

    @Test
    void given_signing_contract_without_both_signed_when_advance_then_rejected_ord_027() {
        // 「签署完成」＝双方均签：缺任一方推进被拒（ORD_027），状态不前移
        Contract contract = signingContract();

        assertThatThrownBy(() -> contract.advance("试图完成", OPERATOR))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.CONTRACT_BOTH_PARTIES_NOT_SIGNED.message());
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.SIGNING);
        // 留痕不落半截：仍是夹具的两次推进行，被拒的推进不追加
        assertThat(contract.getTransitions()).hasSize(2);
    }

    @Test
    void given_terminal_contract_when_advance_then_rejected_ord_021() {
        // 终态无推进边（到头/旁落）：非法迁移统一 ORD_021
        for (Contract terminal : List.of(signedContract(), failedContract(), withdrawnContract())) {
            assertThatThrownBy(() -> terminal.advance("迟到", OPERATOR))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED.message());
        }
    }

    @Test
    void given_blank_or_overlong_basis_when_advance_then_rejected() {
        Contract contract = preparingContract();
        for (String blank : new String[] {null, "", " "}) {
            assertThatThrownBy(() -> contract.advance(blank, OPERATOR))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.CONTRACT_BASIS_REQUIRED.message());
        }
        assertThatThrownBy(() -> contract.advance("长".repeat(Contract.BASIS_MAX_LENGTH + 1), OPERATOR))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.CONTRACT_BASIS_TOO_LONG.message());
        assertThat(contract.getTransitions()).isEmpty();
    }

    // ---------- 签署位两枚：落签时点＋幂等拒＋「签署完成」＝双方均签 ----------

    @Test
    void given_signing_contract_when_sign_both_then_slots_with_times_and_completion_allowed() {
        Contract contract = signingContract();

        contract.sign(ContractParty.PARTY_A, "甲方盖章", OPERATOR);
        assertThat(contract.isPartyASigned()).isTrue();
        assertThat(contract.getPartyASignedAt()).isNotNull();
        assertThat(contract.isPartyBSigned()).isFalse(); // 单签不成完
        assertThatThrownBy(() -> contract.advance("单签不完成", OPERATOR))
                .hasMessageContaining(OrderMessage.CONTRACT_BOTH_PARTIES_NOT_SIGNED.message());

        contract.sign(ContractParty.PARTY_B, "乙方盖章", OPERATOR);
        contract.advance("双方落签，合同完成", OPERATOR);

        assertThat(contract.bothSigned()).isTrue();
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.SIGNED);
        assertThat(contract.isTerminal()).isTrue(); // 签署完成＝终态（此后不删不改）
    }

    @Test
    void given_already_signed_party_when_sign_again_then_rejected_ord_028() {
        Contract contract = signingContract();
        contract.sign(ContractParty.PARTY_A, "甲方盖章", OPERATOR);

        assertThatThrownBy(() -> contract.sign(ContractParty.PARTY_A, "重复标记", OPERATOR))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.CONTRACT_PARTY_ALREADY_SIGNED.message());
    }

    @Test
    void given_non_signing_contract_when_sign_then_rejected_ord_021() {
        // 签署标记仅在签署进行中：准备中/待核对与签署未开签，终态已关门
        assertThatThrownBy(() -> preparingContract().sign(ContractParty.PARTY_A, "早签", OPERATOR))
                .hasMessageContaining(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED.message());
        assertThatThrownBy(() -> signedContract().sign(ContractParty.PARTY_A, "补签", OPERATOR))
                .hasMessageContaining(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED.message());
    }

    // ---------- 旁落终态：失败或过期／撤回／作废（动作语义分立、去向分合） ----------

    @Test
    void given_signing_contract_when_mark_failed_then_terminal_with_fail_trail() {
        Contract contract = signingContract();

        contract.markFailed("用户超期三十天未签", OPERATOR);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.FAILED_OR_EXPIRED);
        assertThat(contract.isTerminal()).isTrue();
        assertThat(contract.getTransitions()).hasSize(3); // 两次推进＋一次失败
        assertThat(contract.getTransitions().get(2).getAction()).isEqualTo(ContractTransitionAction.FAIL);
        assertThat(contract.getTransitions().get(2).getToStatus()).isEqualTo(ContractStatus.FAILED_OR_EXPIRED);
    }

    @Test
    void given_preparing_contract_when_mark_failed_then_rejected_ord_021() {
        // 准备中未到用户面：失败或过期不成立（回炉走撤回/作废）
        assertThatThrownBy(() -> preparingContract().markFailed("早失败", OPERATOR))
                .hasMessageContaining(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED.message());
    }

    @Test
    void given_non_terminal_contract_when_withdraw_or_void_then_same_terminal_distinct_actions() {
        // 撤回/作废：同一终态（已撤回或作废）、动作语义在留痕行分立
        Contract withdrawn = preparingContract();
        withdrawn.withdraw("范围录错，整案回炉", OPERATOR);
        assertThat(withdrawn.getStatus()).isEqualTo(ContractStatus.WITHDRAWN_OR_VOIDED);
        assertThat(withdrawn.getTransitions().get(0).getAction()).isEqualTo(ContractTransitionAction.WITHDRAW);

        Contract voided = signingContract();
        voided.voidContract("乙方主体信息核实无效", OPERATOR);
        assertThat(voided.getStatus()).isEqualTo(ContractStatus.WITHDRAWN_OR_VOIDED);
        assertThat(voided.getTransitions().get(2).getAction()).isEqualTo(ContractTransitionAction.VOID);
    }

    // ---------- 草稿重录：同号＋重新冻结＋版本递增（「需更新」派生清零） ----------

    @Test
    void given_stale_draft_when_re_record_then_same_no_refrozen_version_bumped() {
        // 改价后草稿对照现值「需更新」→ 后台同号重录：内容录入＋冻结集取现值重冻
        // ＋版本 +1；编号不变（同号重录不是重建）
        Order order = scopedQuotedOrder();
        Contract contract = Contract.create(order, latestQuote(order));
        order.quote(99000L, "改价", OPERATOR); // 报价变动 → 冻结集过期
        order.enterScope("交付清单：管理后台＋小程序", "验收方式：按 PRD v3 验收", "工期口径：6 周");
        assertThat(contract.needsUpdate(order)).isTrue();

        contract.record("张三", "语义引力（北京）科技有限公司", "管理后台＋小程序全量交付",
                "开工款 50%，交付验收 50%", order, OPERATOR, "改价后重录，重新冻结");

        assertThat(contract.getContractNo()).isEqualTo("SG-" + Long.toString(contract.getId(), 36));
        assertThat(contract.getVersion()).isEqualTo(2);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.PREPARING); // 状态不前移
        // 重新冻结＝冻结集取订单现值（新价＋新范围文本）
        assertThat(contract.getAmount()).isEqualTo(99000L);
        assertThat(contract.getScopeDeliverables()).isEqualTo("交付清单：管理后台＋小程序");
        assertThat(contract.getScopeTimeline()).isEqualTo("工期口径：6 周");
        // 内容字段随重录录入；「需更新」清零（对照现值不再过期）
        assertThat(contract.getPartyA()).isEqualTo("张三");
        assertThat(contract.getPartyB()).isEqualTo("语义引力（北京）科技有限公司");
        assertThat(contract.needsUpdate(order)).isFalse();
        // 留痕：非迁移动作（from = to），版本递增即内容换版的事实留痕
        assertThat(contract.getTransitions()).hasSize(1);
        assertThat(contract.getTransitions().get(0).getAction()).isEqualTo(ContractTransitionAction.RE_RECORD);
        assertThat(contract.getTransitions().get(0).getFromStatus()).isEqualTo(ContractStatus.PREPARING);
        assertThat(contract.getTransitions().get(0).getToStatus()).isEqualTo(ContractStatus.PREPARING);
    }

    @Test
    void given_signing_contract_when_re_record_then_rejected_ord_031() {
        // 签署进行中起重录窗口关闭——变动走撤回/作废重建（新号新合同）
        Order order = scopedQuotedOrder();
        Contract contract = signingContract();

        assertThatThrownBy(() -> contract.record("张三", "乙公司", "范围", "计划",
                order, OPERATOR, "越窗重录"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.CONTRACT_RE_RECORD_NOT_ALLOWED.message());
    }

    @Test
    void given_signing_or_terminal_contract_when_needs_update_then_always_false() {
        // 「需更新」只对草稿态有意义：签署中/终态的变动处置不走标志（走重建/不碰）
        Order order = scopedQuotedOrder();
        order.quote(99000L, "改价", OPERATOR);
        assertThat(signingContract().needsUpdate(order)).isFalse();
        assertThat(signedContract().needsUpdate(order)).isFalse();
        assertThat(failedContract().needsUpdate(order)).isFalse();
    }

    @Test
    void given_fresh_draft_when_needs_update_then_false() {
        // 冻结集与订单现值一致（刚确认、无变动）——不虚报「需更新」
        Order order = scopedQuotedOrder();
        Contract contract = Contract.create(order, latestQuote(order));

        assertThat(contract.needsUpdate(order)).isFalse();
    }

    @Test
    void given_overlong_content_when_record_then_rejected_ord_032() {
        Order order = scopedQuotedOrder();
        Contract contract = preparingContract();


        assertThatThrownBy(() -> contract.record("主".repeat(Contract.PARTY_MAX_LENGTH + 1),
                "乙", "范围", "计划", order, OPERATOR, "超长"))
                .hasMessageContaining(OrderMessage.CONTRACT_CONTENT_TOO_LONG.message());
        assertThatThrownBy(() -> contract.record("甲", "乙",
                "范".repeat(Contract.CONTENT_MAX_LENGTH + 1), "计划", order, OPERATOR, "超长"))
                .hasMessageContaining(OrderMessage.CONTRACT_CONTENT_TOO_LONG.message());
    }

    // ---------- 夹具（经公共入口驱动到目标态，不绕私有状态） ----------

    private static Contract preparingContract() {
        Order order = scopedQuotedOrder();
        return Contract.create(order, latestQuote(order));
    }

    private static Contract signingContract() {
        Contract contract = preparingContract();
        contract.advance("内容核对完毕，送用户签署", OPERATOR);
        contract.advance("用户已核对，开始签署", OPERATOR);
        return contract;
    }

    private static Contract signedContract() {
        Contract contract = signingContract();
        contract.sign(ContractParty.PARTY_A, "甲方已签", OPERATOR);
        contract.sign(ContractParty.PARTY_B, "乙方已签", OPERATOR);
        contract.advance("双方落签，合同完成", OPERATOR);
        return contract;
    }

    private static Contract failedContract() {
        Contract contract = signingContract();
        contract.markFailed("用户超期未签", OPERATOR);
        return contract;
    }

    private static Contract withdrawnContract() {
        Contract contract = preparingContract();
        contract.withdraw("内容有误撤回", OPERATOR);
        return contract;
    }

    /** 已报价＋范围文本三段在位的订单（确认范围可过门形态）。 */
    private static Order scopedQuotedOrder() {
        Order order = Order.place(900100L, null, null,
                "# PRD v3\n\n已确认版本全文", OrderDeliverableType.SYSTEM, 3, 2);
        order.enterScope("交付清单：管理后台", "验收方式：按 PRD 验收", "工期口径：4 周");
        order.quote(128000L, "首版报价", null);
        return order;
    }

    private static OrderPriceEntry latestQuote(Order order) {
        List<OrderPriceEntry> entries = order.getPriceEntries();
        return entries.get(entries.size() - 1);
    }
}
