package com.aieducenter.aiplatform.business.order.domain.enums;

import java.util.List;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 订单状态（#4 决议五态）：待报价 → 已报价（=待支付）→ 已支付 → 已归档 单向
 * 推进；已取消自任何未支付态可达（取消即回迭代）。不设「支付中」——v1 mock
 * 同步成功，真实接入的中间态经 PaymentPort 吸收不进状态机。
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）。{@link #TERMINAL} 是
 * 「未终结订单唯一性」的判定口径单点（{@link #isTerminal} 与仓储预检共用；
 * 库侧部分唯一索引同谓词——SQL 侧无法引用 Java 常量，靠注释对齐）。</p>
 */
public enum OrderStatus implements BaseEnum<OrderStatus> {

    PENDING_QUOTE(1, "待报价"),

    QUOTED(2, "已报价"),

    PAID(3, "已支付"),

    ARCHIVED(4, "已归档"),

    CANCELLED(5, "已取消");

    /** 终态清单（已归档/已取消）：终态后同项目可再下新单（重新购买 = 新快照新单）。 */
    public static final List<OrderStatus> TERMINAL = List.of(ARCHIVED, CANCELLED);

    private final Integer code;
    private final String name;

    OrderStatus(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /** 是否终态（见 {@link #TERMINAL}）。 */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 是否未支付（待报价/已报价）——「取消自任何未支付态可达」的判定口径单点。 */
    public boolean isUnpaid() {
        return this == PENDING_QUOTE || this == QUOTED;
    }

    /**
     * JPA Converter（框架自动应用）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<OrderStatus> {
        public JpaConverter() {
            super(OrderStatus.class);
        }
    }
}
