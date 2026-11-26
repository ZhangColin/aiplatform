package com.semgravio.aiplatform.business.order.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 订单交付物类型（#297，ADR-0023「一单一程」——订单带交付物类型维度；
 * <b>#611 收窄为系统单值</b>：设计线整条退役，商业面设计单与设计交付物包
 * 一并退场，交付物只剩系统一种——源码包实时取）。下单即冻结（只插不改），
 * 此前项目终点类型变更不影响本单。报价/改价/状态机/取消不参与交付物类型
 * 判定。
 *
 * <p>码位保留 2=系统（与 {@code ord_orders.deliverable_type} 存量数据及
 * {@code ProjectEndpointType} 的历史码表对齐；V86 迁移把存量 1／3 行收敛为 2）。</p>
 */
public enum OrderDeliverableType implements BaseEnum<OrderDeliverableType> {

    SYSTEM(2, "系统");

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
