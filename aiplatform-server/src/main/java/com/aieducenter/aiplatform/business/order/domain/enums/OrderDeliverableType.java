package com.aieducenter.aiplatform.business.order.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 订单交付物类型（#297，ADR-0023「一单一程」——订单只加交付物类型维度）：下单时
 * 自项目终点类型冻结（此后项目终点变更不影响本单，取消再下 = 新单新类型）。码位
 * 与 {@code business.project} 的 {@code ProjectEndpointType} 对齐（1=设计 2=系统
 * 3=系统＋设计，两端按 code 映射——跨 BC 不共享枚举类型，码表互指为约）。
 *
 * <p>冻结语义按类型分岔（#297）：{@link #DESIGN}＝PRD 快照＋设计资产包（选定稿
 * ＋设计规范＋衍生，下单冻结时选件式 tar 入导出物目录）；{@link #SYSTEM_DESIGN}
 * ＝源码包之上追加选定设计稿部件（统一部件容器、类型可区分——同一冻结件内
 * design/ 部件与系统源码并存）；{@link #SYSTEM}＝源码包实时取（存量口径零改）。
 * 报价/改价/状态机/取消对三类单同一律——交付物类型不参与交易机制。</p>
 */
public enum OrderDeliverableType implements BaseEnum<OrderDeliverableType> {

    DESIGN(1, "设计"),

    SYSTEM(2, "系统"),

    SYSTEM_DESIGN(3, "系统＋设计");

    private final Integer code;
    private final String name;

    OrderDeliverableType(Integer code, String name) {
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

    /** 交付物含设计面（设计单或系统＋设计）——下单冻结设计资产包的判据。 */
    public boolean designInvolved() {
        return this == DESIGN || this == SYSTEM_DESIGN;
    }

    /** 码 → 枚举（项目终点类型冻结入口的映射面；未知码＝两端码表漂移，如实拒绝）。 */
    public static OrderDeliverableType ofCode(Integer code) {
        for (OrderDeliverableType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的项目终点类型码，无法冻结交付物类型: " + code);
    }

    /**
     * JPA Converter - 必须声明为 public static class。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<OrderDeliverableType> {
        public JpaConverter() {
            super(OrderDeliverableType.class);
        }
    }
}
