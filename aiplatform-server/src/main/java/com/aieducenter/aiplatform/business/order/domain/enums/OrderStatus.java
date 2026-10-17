package com.aieducenter.aiplatform.business.order.domain.enums;

import java.util.List;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 订单状态（#4 决议五态；#335 语义重对位——四态骨架不动、语义改挂，spec #320
 * 实施序④）：待报价 → 已报价（=待支付）→ 已支付 → 已归档 单向推进；已取消自
 * 取消门可达（取消即回迭代）。
 *
 * <p><b>重对位语义正本（判定位立数据面，实际判定挂合同/付款在场后接通
 * #338）</b>：待报价＝确认 Demo 下单起（下单前置＝已确认 PRD 版本＋Demo 确认
 * 版本——双版本引用随单冻结，#335；用户面呈现＝「待确认报价」占位态，后台
 * 落定报价〔＋范围文本，#336 起〕前不以 0 元或示例价代替）；已报价＝后台落定
 * 报价后（改价仅限本态）；<b>已支付＝开工条件满足</b>（合同签署完成 && 开工门
 * 节点全部到账——与生产承接触发同一判定，重对位判定位，接通归 #338）；<b>已
 * 归档＝后台人工终局</b>（交付完成、尾款核验后，不再自动——接通归 #338）。
 * 取消边界收窄判定位同批立：合同不存在或合同签署完成前非终态可取消、签署完成
 * 后取消关门（接通归 #338）。UI 呈现照原型三子步骤（核对范围与报价/签署合同/
 * 付款），内部状态机不直出。不设「支付中」——v1 mock 同步成功，真实接入的
 * 中间态经 PaymentPort 吸收不进状态机。</p>
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

    /**
     * 是否未支付（待报价/已报价）——取消门的判定口径单点。重对位判定位（#335 立、
     * #338 接通）：收窄后的门＝「合同不存在或合同签署完成前非终态」——合同域不在
     * 场的现下与未支付等价（已支付重对位后蕴含合同签署完成、门已关），合同在场后
     * 在此单点接「活跃合同签署态」判定。
     */
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
