package com.semgravio.aiplatform.business.billing.domain.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingPool;
import com.semgravio.aiplatform.business.billing.domain.enums.PoolKind;

/**
 * 余额池仓储。双池各至多一池由库侧部分唯一索引兜底（uk_bil_pools_account/
 * project）；并发建池撞索引归应用层吸收（重查既有池续作）。
 */
public interface BillingPoolRepository extends BaseRepository<BillingPool, Long> {

    /** 账户名下的账户池（至多一行；无池 = Optional.empty）。 */
    Optional<BillingPool> findByKindAndAccountId(PoolKind kind, Long accountId);

    /** 项目名下的项目池（至多一行；无池 = Optional.empty）。 */
    Optional<BillingPool> findByKindAndProjectId(PoolKind kind, Long projectId);

    /**
     * 行锁取池（金额变动的并发串行化面）：入账/扣减/调整前先锁后改，行后余额
     * 快照（balanceAfter）与账本追加在同一串行化序上不漂。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BillingPool p where p.id = :id")
    Optional<BillingPool> lockById(@Param("id") Long id);
}
