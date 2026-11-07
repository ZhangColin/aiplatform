package com.semgravio.aiplatform.business.project.domain.aggregate;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

/**
 * 计划核心旅程行（{@code prj_generation_plan_journeys}，#423 切片计划结构化输出
 * 扩展）：主智能体随切片计划同批结构化自报的核心旅程清单（#406 整轨终验的载荷
 * 契约）——一条「角色＋入口＋关键动作串」，逐条一行。
 *
 * <p>生命周期同切片计划（ADR-0020 轨道表口径）：落库时记 {@code prdProducedAt}
 * 版本锚（与片行同锚同事务写）——PRD 演进即锚不一致、旧旅程过期不沿用，重产 =
 * 整组替换（旧行不残留）。行不可变（updatable=false 全列——清单是计划的组成，
 * 不是进度状态）。删除真删级联随项目（软引用显式清，同轨道片行惯例）。</p>
 */
@Entity
@Table(name = "prj_generation_plan_journeys")
@Aggregate
@Getter
public class GenerationPlanJourney extends Auditable implements AggregateRoot<GenerationPlanJourney, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 旅程序（1..M，主智能体产出序）。 */
    @Column(name = "ord", nullable = false, updatable = false)
    private int ord;

    /** 角色（谁在用）。 */
    @Column(name = "role", nullable = false, updatable = false)
    private String role;

    /** 入口（从哪个页面进来）。 */
    @Column(name = "entry", nullable = false, updatable = false)
    private String entry;

    /** 关键动作串（一路按顺序做什么，如「注册登录→浏览商品→下单支付」）。 */
    @Column(name = "key_actions", nullable = false, updatable = false)
    private String keyActions;

    /** 计划落库时的 PRD 版本锚（与片行同锚；锚不一致 = 计划过期）。 */
    @Column(name = "prd_produced_at", nullable = false, updatable = false)
    private LocalDateTime prdProducedAt;

    protected GenerationPlanJourney() {
    }

    /** 旅程行（计划落库/重产的整组替换式插入；不可变，无状态推进）。 */
    public static GenerationPlanJourney of(Long projectId, int ord, String role, String entry,
            String keyActions, LocalDateTime prdProducedAt) {
        GenerationPlanJourney journey = new GenerationPlanJourney();
        journey.projectId = projectId;
        journey.ord = ord;
        journey.role = role;
        journey.entry = entry;
        journey.keyActions = keyActions;
        journey.prdProducedAt = prdProducedAt;
        return journey;
    }

    /**
     * 现行旅程集判定（PRD 版本锚门，同 {@link GenerationSegment#planMatchesPrd}
     * 口径）：旅程集非空且锚一致（首行锚 = 落库时的项目 prd_produced_at）＝现行
     * 计划的旅程；空集或锚漂（PRD 已演进）= false——读面消费。
     */
    public static boolean planMatchesPrd(List<GenerationPlanJourney> journeys,
            LocalDateTime prdProducedAt) {
        return !journeys.isEmpty() && journeys.get(0).getPrdProducedAt().equals(prdProducedAt);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
