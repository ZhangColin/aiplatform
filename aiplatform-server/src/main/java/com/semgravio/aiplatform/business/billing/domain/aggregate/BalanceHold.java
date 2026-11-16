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

import com.semgravio.aiplatform.business.billing.domain.enums.BalanceHoldLane;
import com.semgravio.aiplatform.business.billing.domain.enums.BalanceHoldStatus;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;

/**
 * 余额提醒留存聚合根（{@code bil_balance_holds}，#537 计费③，ADR-0047 段间硬门）：
 * 付费项目段边界余额检查≤0 时被拦输入（意见/咨询/作用域改稿——兜底零调用天然
 * 免费永不入拦）原文＋附件随留存落库——充值回正自动放行（恢复受理不重输，「充值
 * 到账后下一段自动放行〔不手动重试〕」）；新拦取代旧待决（一项目至多一条待决，
 * 部分唯一索引 uk_bil_balance_holds_pending）。受理前的拦下不进对话面（原文随卡
 * 呈现），卡可回（GET 读面）。
 *
 * <p><b>状态机</b>：待决 → 已放行（{@link #resume}——充值回正自动恢复受理的
 * 落定点，非待决放行＝BILL_027）／被取代（{@link #supersede}——更新的拦提醒
 * 顶替，或恢复受理失败的退场）。行保留不删（留存即审计面）。</p>
 *
 * <p>与试用拦截留存（{@link InterceptHold}）对偶同构：一个拦「未付费」（确认
 * 动作＝用户点确认扣减），一个拦「已欠费」（确认动作＝充值到账）。</p>
 */
@Entity
@Table(name = "bil_balance_holds")
@Aggregate
@Getter
public class BalanceHold extends Auditable implements AggregateRoot<BalanceHold, Long> {

    /** 被拦输入原文上限（与对话区发言同源 5000）。 */
    public static final int PROMPT_MAX_LENGTH = 5000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 项目（prj_projects 软引用，无 FK）。 */
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 被拦车道（1=意见 2=咨询 3=作用域改稿）。 */
    @Column(name = "lane", nullable = false, updatable = false)
    private BalanceHoldLane lane;

    /** 被拦输入原文。 */
    @Column(name = "prompt", nullable = false, updatable = false, length = PROMPT_MAX_LENGTH)
    private String prompt;

    /** 随拦附件载荷原样（JSONB 数组；可空——纯文字输入）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb", updatable = false)
    private List<Map<String, Object>> attachments;

    /** 作用域改稿目标件序（仅 DESIGN_REVISION 车道；其余车道 null）。 */
    @Column(name = "design_item", updatable = false)
    private Integer designItem;

    /** 作用域改稿发散度档位名（仅 DESIGN_REVISION 车道可空；其余车道 null）。 */
    @Column(name = "divergence", updatable = false, length = 20)
    private String divergence;

    /** 留存状态（1=待决 2=已放行 3=被取代）。 */
    @Column(name = "status", nullable = false)
    private BalanceHoldStatus status;

    /** 余额提醒卡事件锚（派发响应同值返回，挂 /api/events?runId=）。 */
    @Column(name = "run_id", nullable = false, updatable = false, length = 100)
    private String runId;

    protected BalanceHold() {
    }

    private BalanceHold(Long projectId, BalanceHoldLane lane, String prompt,
            List<Map<String, Object>> attachments, Integer designItem, String divergence,
            String runId) {
        if (projectId == null || lane == null || prompt == null || prompt.isBlank()
                || runId == null || runId.isBlank()
                || (lane == BalanceHoldLane.DESIGN_REVISION && designItem == null)) {
            throw new DomainException(BillingMessage.BALANCE_HOLD_FIELDS_INCOMPLETE);
        }
        this.projectId = projectId;
        this.lane = lane;
        this.prompt = prompt;
        this.attachments = attachments == null || attachments.isEmpty() ? null : List.copyOf(attachments);
        this.designItem = lane == BalanceHoldLane.DESIGN_REVISION ? designItem : null;
        this.divergence = lane == BalanceHoldLane.DESIGN_REVISION && divergence != null
                && !divergence.isBlank() ? divergence : null;
        this.runId = runId;
        this.status = BalanceHoldStatus.PENDING;
    }

    /** 落一条待决留存（被拦输入不丢失——充值回正自动恢复受理面）。 */
    public static BalanceHold pendingOf(Long projectId, BalanceHoldLane lane, String prompt,
            List<Map<String, Object>> attachments, Integer designItem, String divergence,
            String runId) {
        return new BalanceHold(projectId, lane, prompt, attachments, designItem, divergence, runId);
    }

    /** 是否待决（卡可回读面单点）。 */
    public boolean pending() {
        return status == BalanceHoldStatus.PENDING;
    }

    /**
     * 放行落定（充值回正自动恢复受理的认领点）：非待决（已放行/被取代/缺行）一律
     * BILL_027——认领消费的是「当前待决」语义，被更新的提醒顶替后旧卡不可再放行。
     */
    public void resume() {
        if (!pending()) {
            throw new DomainException(BillingMessage.BALANCE_HOLD_NOT_PENDING);
        }
        this.status = BalanceHoldStatus.RESUMED;
    }

    /** 被更新的拦提醒顶替（旧待决退场，输入已由新留存承接）。 */
    public void supersede() {
        if (pending()) {
            this.status = BalanceHoldStatus.SUPERSEDED;
        }
    }

    /**
     * 恢复受理失败退场：认领已落定（RESUMED）但恢复受理被天然守卫拦下的行——
     * 认领与受理之间的缝以「被取代」落定（输入不自动恢复，用户重发即新段）；
     * 待决行等同 {@link #supersede}。行保留不删（留存即审计面，定格须如实）。
     */
    public void retire() {
        if (status == BalanceHoldStatus.PENDING || status == BalanceHoldStatus.RESUMED) {
            this.status = BalanceHoldStatus.SUPERSEDED;
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
