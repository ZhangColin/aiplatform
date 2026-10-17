package com.aieducenter.aiplatform.business.order.domain.enums;

import java.util.List;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 订单状态（#4 决议五态；#335 语义重对位——四态骨架不动、语义改挂；#338 判定
 * 接通收口，spec #320 实施序④）：待报价 → 已报价（=待支付）→ 已支付 → 已归档
 * 单向推进；已取消自取消门可达（取消即回迭代）。
 *
 * <p><b>重对位语义正本（#338 已接通）</b>：待报价＝确认 Demo 下单起（下单前置＝
 * 已确认 PRD 版本＋Demo 确认版本——双版本引用随单冻结，#335；用户面呈现＝
 * 「待确认报价」占位态，后台落定报价〔＋范围文本，#336 起〕前不以 0 元或示例
 * 价代替）；已报价＝后台落定报价后（改价仅限本态）；<b>已支付＝开工条件满足</b>
 * （合同签署完成 && 开工门节点全部到账——双门禁判定归 {@code KickoffAppService}，
 * 与生产承接创建同一事务；触发点＝合同推进签署完成后/开工门核验到账后）；
 * <b>已归档＝后台人工终局</b>（交付完成、尾款核验后，不再自动——归档触发只剩
 * {@code retryArchive} 后台口）。取消边界（#338 收窄）：合同不存在或合同签署
 * 完成前非终态可取消、签署完成后关门（ORD_047，应用层守卫）＋取消联动活跃
 * 合同作废留痕。UI 呈现照原型三子步骤（核对范围与报价/签署合同/付款），内部
 * 状态机不直出；交付生命周期六态（前台）＝四源纯推导读面（DeliveryState，
 * 不存储）。不设「支付中」——支付是凭证核验路径的累计判定，中间态在付款
 * 节点上（PaymentNode 五态）。</p>
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
     * 是否未支付（待报价/已报价）——取消门的聚合侧判定口径（#338 收窄后门＝
     * 「合同不存在或合同签署完成前非终态」：本谓词拦非未支付腿 ORD_005，已签
     * 合同腿的关门守卫在应用层 {@code OrderAppService#requireCancellable}——
     * 聚合读不到合同，两腿分工）。
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
