package com.aieducenter.aiplatform.business.order.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Contract;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractStatus;

/**
 * 合同仓储（#336）。
 */
public interface ContractRepository extends BaseRepository<Contract, Long> {

    /**
     * 订单名下的活跃（非终态）合同（「同订单至多一份活跃合同」的应用预检面）。
     * 谓词 = {@link ContractStatus#TERMINAL} 取反，与库侧部分唯一索引
     * （uk_ord_contracts_active）同口径：非终态至多一行，本查询即 0/1 行。
     */
    default Optional<Contract> findActiveByOrderId(Long orderId) {
        return findByOrderIdAndStatusNotIn(orderId, ContractStatus.TERMINAL)
                .stream().findFirst();
    }

    /** 订单名下不在给定状态清单的合同。 */
    List<Contract> findByOrderIdAndStatusNotIn(Long orderId, List<ContractStatus> statuses);

    /**
     * 订单名下最近一份合同（任意状态；#336 用户面合同预览卡的取数面——活跃合同
     * 在场即最新，撤回/作废重建后最新＝新活跃行，签署完成终态后仍可见〔终态
     * 永不物理删除〕）。createdAt 同拍按 id 破平（TSID 时间有序）。
     */
    Optional<Contract> findFirstByOrderIdOrderByCreatedAtDescIdDesc(Long orderId);

    /**
     * 订单名下最近一份合同（<b>含终态</b>）已签署完成（SIGNED）——「最近合同已签」
     * 谓词/取数单点（#338 收敛五处改写）：SIGNED 是终态、活跃查询查不到已签合同，
     * 须走 {@link #findFirstByOrderIdOrderByCreatedAtDescIdDesc} 再判状态。在场即
     * 该订单合同腿的既成事实（取消关门 ORD_047/再建合同拒绝/开工判定合同腿/归档
     * 取版/六态推导共用口径）；不在场＝空（无合同或最近合同非 SIGNED）。
     */
    default Optional<Contract> findLatestSignedByOrderId(Long orderId) {
        return findFirstByOrderIdOrderByCreatedAtDescIdDesc(orderId)
                .filter(contract -> contract.getStatus() == ContractStatus.SIGNED);
    }

    /**
     * 带留痕行的单笔查询：详情拼装（后台详情/留痕时间线）在仓储事务之外，
     * JOIN FETCH 一次取齐，避免懒加载在会话外炸开（同
     * {@code OrderRepository#findWithHistory} 形制）。
     */
    @Query("SELECT c FROM Contract c LEFT JOIN FETCH c.transitions WHERE c.id = :id")
    Optional<Contract> findWithTrail(@Param("id") Long id);
}
