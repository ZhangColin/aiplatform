package com.semgravio.aiplatform.business.billing.domain.aggregate;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.billing.domain.enums.EntryKind;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;

/**
 * 流水行聚合根（{@code bil_entries}）：append-only 账本——每笔池变动恰一行、只
 * 插不改（全业务列 {@code updatable = false}，与计量事件同律），带行后余额快照
 * （{@code balanceAfter}）与后台操作者留痕。扣减行（{@link EntryKind#DEDUCTION}）
 * 带结算段归属（{@code segmentType}/{@code segmentRef}）＋<b>用量/单价/折算率
 * 快照</b>（{@code snapshot} JSONB——历史不随调参漂移，与单价表快照语义同律）。
 * pool_id 同 BC 软引用（池永不删除，不设 FK）。
 *
 * <p>金额符号约定：入账恒正、扣减恒负（入参恒正、行上落负）、调整带符号。
 * {@code balanceAfter} 可负（负余额软模型的事实快照）。</p>
 */
@Entity
@Table(name = "bil_entries")
@Aggregate
@Getter
public class BillingEntry extends Auditable implements AggregateRoot<BillingEntry, Long> {

    /** 备注上限（与 {@code bil_entries.note} 列宽对齐）。 */
    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属池（bil_pools 同 BC 软引用）。 */
    @Column(name = "pool_id", nullable = false, updatable = false)
    private Long poolId;

    /** 段归属项目（扣减行必落；账户池入账/调整行可空）。 */
    @Column(name = "project_id", updatable = false)
    private Long projectId;

    /** 行种类（入账/扣减/调整）。 */
    @Column(name = "kind", nullable = false, updatable = false)
    private EntryKind kind;

    /** 金额（分；入账恒正、扣减恒负、调整带符号）。 */
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    /** 行落定后池余额（可负）。 */
    @Column(name = "balance_after", nullable = false, updatable = false)
    private long balanceAfter;

    /** ISO 4217（v1 恒 CNY）。 */
    @Column(name = "currency", nullable = false, updatable = false, length = 10)
    private String currency;

    /** 扣减行：结算段类型（固定单价项直记落 FIXED_ITEM）。 */
    @Column(name = "segment_type", updatable = false)
    private SegmentType segmentType;

    /** 扣减行：段寻址（runId 等调用方标识）。 */
    @Column(name = "segment_ref", updatable = false, length = 100)
    private String segmentRef;

    /** 扣减行：用量/单价/折算率快照（null/空归一为 NULL）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> snapshot;

    /** 后台动作操作者（平台动作/无头落 NULL）。 */
    @Column(name = "operator_id", updatable = false, length = 64)
    private String operatorId;

    /** 后台动作操作者名（直读展示）。 */
    @Column(name = "operator_name", updatable = false, length = 200)
    private String operatorName;

    /** 备注（充值/调整留痕面）。 */
    @Column(name = "note", updatable = false, length = NOTE_MAX_LENGTH)
    private String note;

    protected BillingEntry() {
    }

    /**
     * 入账行（充值/赠送）：金额恒正；操作者可空（模拟收银台自助充值无操作者，
     * 后台赠送/手动录入带操作者留痕）。
     */
    public static BillingEntry topup(Long poolId, Long projectId, long amount, long balanceAfter,
            Operator operator, String note) {
        requirePool(poolId);
        if (amount <= 0) {
            throw new DomainException(BillingMessage.TOPUP_AMOUNT_INVALID);
        }
        return build(poolId, projectId, EntryKind.TOPUP, amount, balanceAfter, null, null,
                null, operator, note);
    }

    /**
     * 扣减行（段收口结算/固定单价项直记）：{@code amount} 恒正入参、行上落负；
     * 段归属项目/段型/段址/快照必填（BILL_005——金额＋用量＋单价快照齐全是扣减行
     * 的行格；projectId 必填是段幂等唯一索引的前提，扣减行必落段归属）。
     */
    public static BillingEntry deduction(Long poolId, Long projectId, SegmentType segmentType,
            String segmentRef, long amount, long balanceAfter, Map<String, Object> snapshot) {
        requirePool(poolId);
        if (amount <= 0) {
            throw new DomainException(BillingMessage.DEDUCTION_AMOUNT_INVALID);
        }
        if (projectId == null || segmentType == null || isBlank(segmentRef)
                || snapshot == null || snapshot.isEmpty()) {
            throw new DomainException(BillingMessage.ENTRY_FIELDS_INCOMPLETE);
        }
        return build(poolId, projectId, EntryKind.DEDUCTION, -amount, balanceAfter,
                segmentType, segmentRef.trim(), snapshot, null, null);
    }

    /**
     * 调整行（后台余额调整——运营纠错与平台故障返还同载体）：非零带符号、原因
     * 必填留痕（BILL_005）。
     */
    public static BillingEntry adjustment(Long poolId, Long projectId, long delta, long balanceAfter,
            Operator operator, String reason) {
        requirePool(poolId);
        if (delta == 0) {
            throw new DomainException(BillingMessage.ADJUSTMENT_AMOUNT_INVALID);
        }
        if (isBlank(reason)) {
            throw new DomainException(BillingMessage.ENTRY_FIELDS_INCOMPLETE);
        }
        return build(poolId, projectId, EntryKind.ADJUSTMENT, delta, balanceAfter, null, null,
                null, operator, reason);
    }

    private static void requirePool(Long poolId) {
        if (poolId == null) {
            throw new DomainException(BillingMessage.ENTRY_FIELDS_INCOMPLETE);
        }
    }

    private static BillingEntry build(Long poolId, Long projectId, EntryKind kind, long amount,
            long balanceAfter, SegmentType segmentType, String segmentRef,
            Map<String, Object> snapshot, Operator operator, String note) {
        if (note != null && note.length() > NOTE_MAX_LENGTH) {
            throw new DomainException(BillingMessage.ENTRY_NOTE_TOO_LONG);
        }
        BillingEntry entry = new BillingEntry();
        entry.poolId = poolId;
        entry.projectId = projectId;
        entry.kind = kind;
        entry.amount = amount;
        entry.balanceAfter = balanceAfter;
        entry.currency = BillingPool.CURRENCY_CNY;
        entry.segmentType = segmentType;
        entry.segmentRef = segmentRef;
        entry.snapshot = snapshot == null || snapshot.isEmpty() ? null : Map.copyOf(snapshot);
        entry.operatorId = operator == null ? null : operator.id();
        entry.operatorName = operator == null ? null : operator.name();
        entry.note = isBlank(note) ? null : note.trim();
        return entry;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 聚合 ID（TSID 时间有序——追加序即时间序）。
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
