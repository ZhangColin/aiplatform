package com.semgravio.aiplatform.business.billing.domain.aggregate;

import java.util.List;
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

import com.semgravio.aiplatform.business.billing.domain.enums.InterceptLane;
import com.semgravio.aiplatform.business.billing.domain.enums.InterceptStatus;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;

/**
 * 试用拦截留存聚合根（{@code bil_intercepts}，#533 计费②门②）：被拦输入
 * （意见/兜底——咨询放行、下单意图旧轨）原文＋附件随卡留存——确认扣减即翻转
 * （门②）并立即受理原输入（不重输）；走充值引导或中途关闭后输入不丢失、卡可回
 * （一项目至多一条待决，新拦取代旧待决——部分唯一索引 uk_bil_intercepts_pending）。
 *
 * <p><b>状态机</b>：待决 → 已确认（{@link #confirm}——翻转落定同事务，非待决
 * 确认＝BILL_017）／被取代（{@link #supersede}——更新的拦截顶替）。行保留
 * 不删（留存即审计面：被拦过什么、何时确认/取代）。</p>
 */
@Entity
@Table(name = "bil_intercepts")
@Aggregate
@Getter
public class InterceptHold extends Auditable implements AggregateRoot<InterceptHold, Long> {

    /** 被拦输入原文上限（与对话区发言同源 5000）。 */
    public static final int PROMPT_MAX_LENGTH = 5000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 项目（prj_projects 软引用，无 FK）。 */
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 被拦车道（1=意见 2=兜底）。 */
    @Column(name = "lane", nullable = false, updatable = false)
    private InterceptLane lane;

    /** 被拦输入原文。 */
    @Column(name = "prompt", nullable = false, updatable = false, length = PROMPT_MAX_LENGTH)
    private String prompt;

    /** 随拦附件载荷原样（JSONB 数组；可空——纯文字输入）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb", updatable = false)
    private List<Map<String, Object>> attachments;

    /** 留存状态（1=待决 2=已确认 3=被取代）。 */
    @Column(name = "status", nullable = false)
    private InterceptStatus status;

    /** 拦截卡事件锚（派发响应同值返回，挂 /api/events?runId=）。 */
    @Column(name = "run_id", nullable = false, updatable = false, length = 100)
    private String runId;

    protected InterceptHold() {
    }

    private InterceptHold(Long projectId, InterceptLane lane, String prompt,
            List<Map<String, Object>> attachments, String runId) {
        if (projectId == null || lane == null || prompt == null || prompt.isBlank()
                || runId == null || runId.isBlank()) {
            throw new DomainException(BillingMessage.INTERCEPT_FIELDS_INCOMPLETE);
        }
        this.projectId = projectId;
        this.lane = lane;
        this.prompt = prompt;
        this.attachments = attachments == null || attachments.isEmpty() ? null : List.copyOf(attachments);
        this.runId = runId;
        this.status = InterceptStatus.PENDING;
    }

    /** 落一条待决留存（被拦输入不丢失——确认/关卡的恢复面）。 */
    public static InterceptHold pendingOf(Long projectId, InterceptLane lane, String prompt,
            List<Map<String, Object>> attachments, String runId) {
        return new InterceptHold(projectId, lane, prompt, attachments, runId);
    }

    /** 是否待决（卡可回读面单点）。 */
    public boolean pending() {
        return status == InterceptStatus.PENDING;
    }

    /**
     * 确认落定（确认扣减同事务）：非待决（已确认/被取代/缺行）一律 BILL_017
     * ——确认消费的是「当前待决」语义，被更新的拦截顶替后旧卡不可再确认。
     */
    public void confirm() {
        if (!pending()) {
            throw new DomainException(BillingMessage.INTERCEPT_NOT_PENDING);
        }
        this.status = InterceptStatus.CONFIRMED;
    }

    /** 被更新的拦截顶替（旧待决退场，输入已由新留存承接）。 */
    public void supersede() {
        if (pending()) {
            this.status = InterceptStatus.SUPERSEDED;
        }
    }

    /**
     * 聚合 ID。
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
