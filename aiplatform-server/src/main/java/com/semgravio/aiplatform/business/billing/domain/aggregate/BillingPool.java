package com.semgravio.aiplatform.business.billing.domain.aggregate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.billing.domain.enums.PoolKind;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;

/**
 * 余额池聚合根（{@code bil_pools}，ADR-0047 双池）：账户余额池（账户名下、跨
 * 项目）与项目余额池（项目专属）各至多一池（库侧部分唯一索引兜底，聚合不做跨
 * 行查重）。余额单位分（Long）、可负——<b>负余额软模型</b>（扣成负照跑、负余额
 * 落源池，上界≈一段实耗由段间硬门保证；充值回正）。池惰性建（首笔入账才出现）
 * ；projectId/accountId 跨上下文软引用（无 FK，账本可分离）。
 *
 * <p>金额变动只经 {@link #deposit}（入账，恒正）/ {@link #deduct}（扣减，恒正
 * 入参、余额做减——可减成负）/ {@link #adjust}（后台调整，非零带符号）；变动与
 * 流水行（bil_entries）同事务成对落（行带 {@code balanceAfter} 快照），并发写
 * 池由应用层行锁串行化（{@code BillingPoolRepository#lockById}）。</p>
 */
@Entity
@Table(name = "bil_pools")
@Aggregate
@Getter
public class BillingPool extends Auditable implements AggregateRoot<BillingPool, Long> {

    /** 币种（ISO 4217；v1 恒 CNY，与订单域同型）。 */
    public static final String CURRENCY_CNY = "CNY";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 池种类（账户池/项目池——归属列互斥由库层 CHECK 兜底）。 */
    @Column(name = "kind", nullable = false, updatable = false)
    private PoolKind kind;

    /** 账户（idn_accounts 软引用；账户池非空、项目池恒 NULL）。 */
    @Column(name = "account_id", updatable = false)
    private Long accountId;

    /** 项目（prj_projects 软引用；项目池非空、账户池恒 NULL）。 */
    @Column(name = "project_id", updatable = false)
    private Long projectId;

    /** 余额（分；可负）。 */
    @Column(name = "balance", nullable = false)
    private long balance;

    /** ISO 4217 币种（v1 恒 CNY）。 */
    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    protected BillingPool() {
    }

    private BillingPool(PoolKind kind, Long accountId, Long projectId) {
        if (kind == null
                || (kind == PoolKind.ACCOUNT && accountId == null)
                || (kind == PoolKind.PROJECT && projectId == null)) {
            throw new DomainException(BillingMessage.POOL_TARGET_INVALID);
        }
        this.kind = kind;
        this.accountId = kind == PoolKind.ACCOUNT ? accountId : null;
        this.projectId = kind == PoolKind.PROJECT ? projectId : null;
        this.balance = 0;
        this.currency = CURRENCY_CNY;
    }

    /** 开账户余额池（balance = 0 起步，首笔入账落值）。 */
    public static BillingPool forAccount(Long accountId) {
        return new BillingPool(PoolKind.ACCOUNT, accountId, null);
    }

    /** 开项目余额池（balance = 0 起步，首笔入账落值）。 */
    public static BillingPool forProject(Long projectId) {
        return new BillingPool(PoolKind.PROJECT, null, projectId);
    }

    /** 入账（充值/赠送——恒正，BILL_002）。 */
    public void deposit(long amount) {
        if (amount <= 0) {
            throw new DomainException(BillingMessage.TOPUP_AMOUNT_INVALID);
        }
        this.balance += amount;
    }

    /** 扣减（恒正入参、余额做减；扣成负合法——负余额软模型，不设下限守卫）。 */
    public void deduct(long amount) {
        if (amount <= 0) {
            throw new DomainException(BillingMessage.DEDUCTION_AMOUNT_INVALID);
        }
        this.balance -= amount;
    }

    /** 后台余额调整（非零带符号——纠错/返还同载体，BILL_003）。 */
    public void adjust(long delta) {
        if (delta == 0) {
            throw new DomainException(BillingMessage.ADJUSTMENT_AMOUNT_INVALID);
        }
        this.balance += delta;
    }

    /** 余额是否为正（段边界放行/翻转前置的判定口径——大于 0 才放行）。 */
    public boolean positive() {
        return balance > 0;
    }

    /**
     * 聚合 ID。
     */
    @Override
    public Long getId() {
        return id;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
