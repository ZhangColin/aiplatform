package com.semgravio.aiplatform.business.billing.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.billing.domain.aggregate.BalanceHold;
import com.semgravio.aiplatform.business.billing.domain.enums.BalanceHoldStatus;

/**
 * 余额提醒留存仓储（#537 计费③，ADR-0047 段间硬门）：一项目至多一条待决（部分
 * 唯一索引兜底），读面＝项目最近待决（卡可回）＋全部待决（充值回正放行排空面）。
 */
public interface BalanceHoldRepository extends BaseRepository<BalanceHold, Long> {

    /** 项目最近一条待决留存（卡可回读面单点）。 */
    Optional<BalanceHold> findFirstByProjectIdAndStatusOrderByIdDesc(
            Long projectId, BalanceHoldStatus status);

    /** 全部待决留存（充值回正后的放行排空面——量小，全表待决扫描可接受）。 */
    List<BalanceHold> findAllByStatus(BalanceHoldStatus status);
}
