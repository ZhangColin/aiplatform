package com.aieducenter.aiplatform.business.order.domain.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;

import com.cartisan.core.domain.DomainEntity;
import com.cartisan.data.jpa.id.TsidGenerator;

/**
 * 订单价目行（{@code ord_price_entries}，Order 聚合内实体）：首次报价与每次改价
 * 各一行，<b>append-only 只插不改写</b>——全列 {@code updatable = false}，聚合外
 * 无任何修改入口；订单当前金额 = 最新一条价目行。时间戳由库列默认值补齐
 * （updated_at 不映射），业务时间 createdAt 随行落。
 */
@Entity
@Table(name = "ord_price_entries")
@Getter
public class OrderPriceEntry implements DomainEntity<OrderPriceEntry, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属订单（由聚合 OneToMany JoinColumn 管理，不重复映射本列）。 */

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    /** 币种（v1 恒 CNY，与订单同口径）。 */
    @Column(name = "currency", nullable = false, updatable = false, length = 10)
    private String currency;

    /** 报价备注（后台文本，用户面展示；可空）。 */
    @Column(name = "note", updatable = false, length = 1000)
    private String note;

    /** 报价/改价时间（业务时间）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected OrderPriceEntry() {
    }

    /**
     * 记一价目行（报价/改价各一行）：业务时间随行落，其余生命周期列由库默认补齐。
     * 只经 {@code Order.quote} 调用（聚合内追加，外部无直接写入口）。
     */
    public static OrderPriceEntry record(Long amount, String currency, String note) {
        OrderPriceEntry entry = new OrderPriceEntry();
        entry.amount = amount;
        entry.currency = currency;
        entry.note = note;
        entry.createdAt = LocalDateTime.now();
        return entry;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return sameIdentityAs((OrderPriceEntry) o);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
