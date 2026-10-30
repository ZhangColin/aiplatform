package com.semgravio.aiplatform.business.order.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.order.domain.aggregate.PaymentPlan;

/**
 * 付款计划仓储（#337）。
 */
public interface PaymentPlanRepository extends BaseRepository<PaymentPlan, Long> {

    /**
     * 订单名下全部计划行（带节点，新 → 旧）：<b>首行＝活跃计划</b>——撤回重建＋
     * 范围重冻各起新行，旧行随旧冻结集留痕（append-only 不改写、无状态列无双写
     * 漂移）。createdAt 同拍按 id 破平（TSID 时间有序）。动作（凭证上传/核验/
     * 打回）与读面（面板/后台详情）在仓储事务之外，JOIN FETCH 一次取齐（单集合
     * fetch 无笛卡尔），避免懒加载在会话外炸开（同
     * {@code ContractRepository#findWithTrail} 形制）。
     */
    @Query("SELECT DISTINCT p FROM PaymentPlan p LEFT JOIN FETCH p.nodes "
            + "WHERE p.orderId = :orderId ORDER BY p.createdAt DESC, p.id DESC")
    List<PaymentPlan> findWithNodesByOrderId(@Param("orderId") Long orderId);

    /**
     * 订单名下活跃计划（带节点一次取齐）＝全部计划行的首行，无计划即空（#338
     * 收敛：开工判定/六态推读写面共用——语义同 {@link #findWithNodesByOrderId}
     * 的「首行＝活跃计划」，取首档免散落）。
     */
    default Optional<PaymentPlan> findActiveWithNodesByOrderId(Long orderId) {
        List<PaymentPlan> plans = findWithNodesByOrderId(orderId);
        return plans.isEmpty() ? Optional.empty() : Optional.of(plans.get(0));
    }
}
