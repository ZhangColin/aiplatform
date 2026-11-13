package com.semgravio.aiplatform.base.metering.domain.aggregate;

import java.time.Instant;

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

import com.semgravio.aiplatform.base.metering.domain.enums.Band;
import com.semgravio.aiplatform.base.metering.domain.error.MeteringMessage;
import com.semgravio.aiplatform.base.metering.domain.model.Operator;

/**
 * 时段规则行（{@code met_time_band_rules}，#497／ADR-0044 峰谷 1/4）：通道 ×
 * 生效区间 × 星期口径 × UTC 窗口行——供应商峰谷定价的时段定义（base.metering
 * 私有表，不经端口暴露）。行粒度＝一个 UTC 窗口：一次开行原子落 N 行窗口行＝
 * 一个规则版本；生效区间重叠校验按通道（同版本多行共享起点，版本内不自撞）。
 *
 * <p><b>生命周期与单价表同构</b>（关行开新行，append 式）：匹配列只插入不写，
 * 唯 {@code effectiveTo} 可落（关行）；改窗/停用在版本级——寻址当前版本任一
 * 行，关该通道全部敞口行。维护入口唯一＝后台管理 API（开/改/停三签名端点，
 * 种子经幂等签名脚本走开行端点）。</p>
 *
 * <p><b>星期口径</b>＝ISO 周 bitmask（Mon=1 Tue=2 … Sun=64，合法域 1..127），
 * 引擎归段（后续票）单点判定：ts 星期 ∈ mask ∧ ts UTC 分钟 ∈ [start, end)。
 * <b>跨午夜窗口 v1 不支持</b>：单行须 start &lt; end（同日窗口），跨午夜拆两行
 * （22:00–02:00 ＝ 22:00–24:00 ＋ 00:00–02:00）——星期口径×跨午夜语义有歧义，
 * 表示法定案见 #497 票内评论。</p>
 *
 * <p><b>操作者两列</b>（#160 同款口径）：开行/改窗的操作者落所开新行；停用
 * 操作者落被关版本各行（停用不接新行，被关行是唯一落点）。存量行与无头请求
 * （含种子脚本种入行）为 null。</p>
 */
@Entity
@Table(name = "met_time_band_rules")
@Aggregate
@Getter
public class TimeBandRule extends Auditable implements AggregateRoot<TimeBandRule, Long> {

    /** 星期口径合法域上界：Mon..Sun 七位全置 = 127。 */
    private static final int DAY_MASK_MAX = 0x7F;

    /** UTC 窗口终点（不含）上界：24:00＝1440。 */
    private static final int MINUTE_OF_DAY_UPPER = 24 * 60;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "provider", nullable = false, updatable = false, length = 50)
    private String provider;

    @Column(name = "band", nullable = false, updatable = false)
    private Band band;

    @Column(name = "day_mask", nullable = false, updatable = false)
    private Integer dayMask;

    @Column(name = "start_minute", nullable = false, updatable = false)
    private Integer startMinute;

    @Column(name = "end_minute", nullable = false, updatable = false)
    private Integer endMinute;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    /** 生效终点（不含）；null = 当前行。整行唯一可写区间列（关行动作）。 */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** 操作者标识（admin 侧 TSID 十进制字符串；存量行/无头落 NULL，#160 口径）。 */
    @Column(name = "operator_id", length = 64)
    private String operatorId;

    /** 操作者昵称（直读展示；存量行/无头落 NULL，#160 口径）。 */
    @Column(name = "operator_name", length = 200)
    private String operatorName;

    protected TimeBandRule() {
    }

    private TimeBandRule(String provider, Band band, int dayMask, int startMinute, int endMinute,
                         Instant effectiveFrom, Operator operator) {
        this.provider = provider;
        this.band = band;
        this.dayMask = dayMask;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.effectiveFrom = effectiveFrom;
        stampOperator(operator);
    }

    /**
     * 开新行（单窗口行；一次开行的 N 行由写口在同一事务内各自调用本方法聚合，
     * 版本一致性由服务层同键区间校验保证）。字段合法性聚合守卫先行裁决
     * （METER_013）：provider 空／band 空／生效起点空／星期口径越 1..127／
     * 窗口空区间或跨午夜（start ≥ end）／end 越 1440。操作者可空（null＝
     * 种子/无头落空口径）。
     */
    public static TimeBandRule open(String provider, Band band, int dayMask, int startMinute,
                                    int endMinute, Instant effectiveFrom, Operator operator) {
        if (isBlank(provider) || band == null || effectiveFrom == null
                || dayMask < 1 || dayMask > DAY_MASK_MAX
                || startMinute < 0 || startMinute >= endMinute
                || endMinute > MINUTE_OF_DAY_UPPER) {
            throw new DomainException(MeteringMessage.TIME_BAND_RULE_FIELDS_INCOMPLETE);
        }
        return new TimeBandRule(provider, band, dayMask, startMinute, endMinute, effectiveFrom,
                operator);
    }

    /**
     * 关行（改窗上半步：落 effective_to；下半步＝{@link #open} 开新版本行）。仅
     * 当前行可达——已关行再关即改写历史（METER_016）。
     */
    public void close(Instant effectiveTo) {
        if (effectiveTo == null || effectiveTo.isBefore(effectiveFrom)) {
            throw new DomainException(MeteringMessage.TIME_BAND_RULE_CLOSE_INVALID);
        }
        if (this.effectiveTo != null) {
            throw new DomainException(MeteringMessage.TIME_BAND_RULE_NOT_CURRENT);
        }
        this.effectiveTo = effectiveTo;
    }

    /**
     * 停用关行（关行不接新行，此后通道 ts 无窗口命中＝不归带）：即时生效——
     * {@code at} 落 {@code effectiveTo}；对未生效版本（预发布未来起点）停用钳到
     * 自身起点成空区间（从未生效，而非倒挂区间）。停用动作的操作者落本行
     * （被关版本各行皆落，唯一落点）。
     */
    public void deactivate(Instant at, Operator operator) {
        if (this.effectiveTo != null) {
            throw new DomainException(MeteringMessage.TIME_BAND_RULE_NOT_CURRENT);
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
