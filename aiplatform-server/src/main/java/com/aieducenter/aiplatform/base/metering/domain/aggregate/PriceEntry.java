package com.aieducenter.aiplatform.base.metering.domain.aggregate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;
import com.cartisan.core.stereotype.Aggregate;

import com.aieducenter.aiplatform.base.metering.domain.error.MeteringMessage;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.Operator;

/**
 * 单价行（{@code met_price_entries}，A6 §1）：provider × model × 档位 × 币种 ×
 * 生效区间的每 token 单价——平台成本换算的匹配数据，base.metering 私有表（不经
 * 端口暴露，业务层零感知）。
 *
 * <p><b>改价 = 关旧行开新行</b>（append 式不 UPDATE 单价）：{@code unitPrice} 等
 * 匹配列只插入不写，唯 {@code effectiveTo} 可落（关行）；事件按 ts 落
 * {@code [effectiveFrom, effectiveTo)} 区间匹配单价，历史成本不漂移。生效区间
 * 不得重叠——唯一约束只防同起点，重叠校验在管理写口服务端补（#160，已知维护
 * 事故口）。维护入口唯一＝后台管理 API（#160 改价/停用＋#165 开行；启动种子
 * 已随 #165 退役，初始化经幂等签名脚本走开行端点）。</p>
 *
 * <p><b>操作者两列（#160）</b>＝该行最近一次管理动作的操作者：开行者随行落
 * （改价动作的操作者落在所开新行，被关旧行保留原开行者）；停用动作的操作者
 * 落在被关行（停用不接新行，被关行是唯一落点）。存量行与无头请求（含种子
 * 脚本种入行）为 null。</p>
 */
@Entity
@Table(name = "met_price_entries")
@Aggregate
@Getter
public class PriceEntry extends Auditable implements AggregateRoot<PriceEntry, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "provider", nullable = false, updatable = false, length = 50)
    private String provider;

    @Column(name = "model", nullable = false, updatable = false, length = 100)
    private String model;

    @Column(name = "token_kind", nullable = false, updatable = false)
    private TokenKind tokenKind;

    @Column(name = "unit_price", nullable = false, updatable = false, precision = 20, scale = 10)
    private BigDecimal unitPrice;

    @Column(name = "currency", nullable = false, updatable = false, length = 10)
    private String currency;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    /** 生效终点（不含）；null = 当前行。整行唯一可写区间列（关行动作）。 */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** 操作者标识（admin 侧 TSID 十进制字符串；存量行/无头落 NULL，#160）。 */
    @Column(name = "operator_id", length = 64)
    private String operatorId;

    /** 操作者昵称（直读展示；存量行/无头落 NULL，#160）。 */
    @Column(name = "operator_name", length = 200)
    private String operatorName;

    protected PriceEntry() {
    }

    private PriceEntry(String provider, String model, TokenKind tokenKind, BigDecimal unitPrice,
                       String currency, Instant effectiveFrom, Operator operator) {
        this.provider = provider;
        this.model = model;
        this.tokenKind = tokenKind;
        this.unitPrice = unitPrice;
        this.currency = currency;
        this.effectiveFrom = effectiveFrom;
        stampOperator(operator);
    }

    /**
     * 开新行（生效区间从此刻/指定时点起，敞口）。币种须为 ISO 4217 代码——聚合
     * 读面按币种分桶直读 {@code Currency.getInstance}，垃圾币种会炸所有含该键
     * 用量的查询，写口拦在最前。操作者可空（{@code null} = 种子/无头落空口径）。
     */
    public static PriceEntry open(String provider, String model, TokenKind tokenKind,
                                  BigDecimal unitPrice, String currency, Instant effectiveFrom,
                                  Operator operator) {
        if (isBlank(provider) || isBlank(model) || tokenKind == null || unitPrice == null
                || unitPrice.signum() < 0 || isBlank(currency) || effectiveFrom == null) {
            throw new DomainException(MeteringMessage.PRICE_ENTRY_FIELDS_INCOMPLETE);
        }
        try {
            Currency.getInstance(currency.trim());
        } catch (IllegalArgumentException e) {
            throw new DomainException(MeteringMessage.PRICE_ENTRY_CURRENCY_UNKNOWN);
        }
        return new PriceEntry(provider, model, tokenKind, unitPrice, currency, effectiveFrom,
                operator);
    }

    /**
     * 关旧行（改价上半步：落 effective_to；下半步 = {@link #open} 开新行）。仅
     * 当前行可达——已关行再关即改写历史（METER_007）。
     */
    public void close(Instant effectiveTo) {
        if (effectiveTo == null || effectiveTo.isBefore(effectiveFrom)) {
            throw new DomainException(MeteringMessage.PRICE_ENTRY_CLOSE_INVALID);
        }
        if (this.effectiveTo != null) {
            throw new DomainException(MeteringMessage.PRICE_ENTRY_NOT_CURRENT);
        }
        this.effectiveTo = effectiveTo;
    }

    /**
     * 停用关行（关行不接新行，此后用量进 unpriced）：即时生效——{@code at} 落
     * {@code effectiveTo}；对未生效行（预发布未来起点）停用时钳到自身起点成空
     * 区间（从未生效，而非倒挂区间）。停用动作的操作者落本行（唯一落点）。
     */
    public void deactivate(Instant at, Operator operator) {
        if (this.effectiveTo != null) {
            throw new DomainException(MeteringMessage.PRICE_ENTRY_NOT_CURRENT);
        }
        this.effectiveTo = effectiveFrom.isAfter(at) ? effectiveFrom : at;
        stampOperator(operator);
    }

    private void stampOperator(Operator operator) {
        if (operator != null) {
            this.operatorId = operator.id();
            this.operatorName = operator.name();
        }
    }

    /**
     * 聚合 ID = 行 id。
     */
    @Override
    public Long getId() {
        return id;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
