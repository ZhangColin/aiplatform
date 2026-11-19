package com.semgravio.aiplatform.base.metering.domain.aggregate;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;

import com.semgravio.aiplatform.base.metering.domain.error.MeteringMessage;
import com.semgravio.aiplatform.base.metering.domain.model.TokenUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;

/**
 * 用量事件聚合根（{@code met_usage_events}）：append-only 事件日志行，无状态迁移。
 *
 * <p>{@code eventId} 是调用方生成的幂等键，直接作主键（重复上报 first-write-wins，
 * 主键即幂等约束）；五档 token 列互斥分解（见 {@link TokenUsage}）；{@code images}
 * 按张用量列（#288 图片生成计量事件——与 token 五档并列不混算，按张事件五档为零、
 * token 事件张数为零）；dims 透传存储（null/空归一为 NULL，分维度聚合自然跳过）。
 * ID 由调用方显式赋值（先于落库存在），只插入不更新——全业务列
 * {@code updatable = false}。</p>
 */
@Entity
@Table(name = "met_usage_events")
@Aggregate
@Getter
public class UsageEventEntry extends Auditable implements AggregateRoot<UsageEventEntry, String> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 64)
    private String eventId;

    @Column(name = "ts", nullable = false, updatable = false)
    private Instant ts;

    @Column(name = "subject", nullable = false, updatable = false, length = 100)
    private String subject;

    @Column(name = "run_id", updatable = false, length = 100)
    private String runId;

    @Column(name = "session_id", updatable = false, length = 100)
    private String sessionId;

    @Column(name = "provider", nullable = false, updatable = false, length = 50)
    private String provider;

    /**
     * 供应商维度（ADR-0053 ②，V79 列＋历史同值回填）：与 provider 同值＝供应商即
     * 账号本体；不同值＝同供应商多账号（deepseek/deepseek-2 同填 deepseek）。
     * 单价表/峰谷规则匹配键与模型视角聚合（vendor+model）的腿——通道行将来可删、
     * 历史事件维度自持（volcengine 行已删的教训）。
     */
    @Column(name = "vendor", nullable = false, updatable = false, length = 50)
    private String vendor;

    @Column(name = "model", nullable = false, updatable = false, length = 100)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dims", columnDefinition = "jsonb", updatable = false)
    private Map<String, String> dims;

    @Column(name = "input", nullable = false, updatable = false)
    private long input;

    @Column(name = "output", nullable = false, updatable = false)
    private long output;

    @Column(name = "cache_read", nullable = false, updatable = false)
    private long cacheRead;

    @Column(name = "cache_write", nullable = false, updatable = false)
    private long cacheWrite;

    @Column(name = "reasoning", nullable = false, updatable = false)
    private long reasoning;

    /** 按张用量（#288 图片生成）：按张事件张数 > 0、五档 token 为零。 */
    @Column(name = "images", nullable = false, updatable = false)
    private long images;

    /** 按件用量（#531 固定单价项族——联网搜索按次等）：按件事件件数 > 0、五档 token 与张数均为零。 */
    @Column(name = "units", nullable = false, updatable = false)
    private long units;

    protected UsageEventEntry() {
    }

    private UsageEventEntry(UsageEvent event) {
        this.eventId = event.eventId();
        this.ts = event.ts();
        this.subject = event.subject();
        this.runId = event.runId();
        this.sessionId = event.sessionId();
        this.provider = event.provider();
        this.vendor = event.vendor();
        this.model = event.model();
        this.dims = normalizeDims(event.dims());
        TokenUsage tokens = event.tokens();
        this.input = tokens.input();
        this.output = tokens.output();
        this.cacheRead = tokens.cacheRead();
        this.cacheWrite = tokens.cacheWrite();
        this.reasoning = tokens.reasoning();
        this.images = event.images();
        this.units = event.units();
    }

    /**
     * 从协议事件落成存储行（必填校验：eventId/ts/subject/provider/vendor/model/
     * tokens——vendor 缺腿即拒，事件缺供应商维度＝读面匹配静默缺价的源头）。
     */
    public static UsageEventEntry of(UsageEvent event) {
        if (event == null || isBlank(event.eventId()) || event.ts() == null
                || isBlank(event.subject()) || isBlank(event.provider())
                || isBlank(event.vendor()) || isBlank(event.model())
                || event.tokens() == null) {
            throw new DomainException(MeteringMessage.USAGE_EVENT_FIELDS_INCOMPLETE);
        }
        return new UsageEventEntry(event);
    }

    /**
     * 五档 token 的协议形（聚合行的领域读法）。
     */
    public TokenUsage tokens() {
        return new TokenUsage(input, output, cacheRead, cacheWrite, reasoning);
    }

    /**
     * 聚合 ID = eventId（幂等键）。
     */
    @Override
    public String getId() {
        return eventId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, String> normalizeDims(Map<String, String> dims) {
        return dims == null || dims.isEmpty() ? null : dims;
    }
}
