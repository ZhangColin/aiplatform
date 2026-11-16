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
 * 计划推迟行（{@code prj_generation_plan_deferrals}，ADR-0050 切片计划结构化
 * 推迟段）：主智能体随切片计划同批结构化自报的推迟清单——本期不做的功能面
 * （业务向默认集＝支付不真扣款/通知站内呈现/多角色降单角色；信息量不足的进
 * 清单），与 PRD「推迟清单」章同内容，逐条一行。
 *
 * <p>协议约定非机器闸：本表是事实观测面（落库有据、随 PRD 版本演进重产可查），
 * 平台不据此刻意拦截任何生成/收口行为；执行体消费句「清单内不做、不自行加回」
 * 是提示层约定。</p>
 *
 * <p>生命周期同切片计划（ADR-0020 轨道表口径）：落库时记 {@code prdProducedAt}
 * 版本锚（与片行/旅程行同锚同事务写）——PRD 演进即锚不一致、旧推迟项过期不
 * 沿用，重产 = 整组替换（旧行不残留）。行不可变（updatable=false 全列——
 * 推迟清单是计划的组成，不是进度状态）。删除真删级联随项目（软引用显式清，
 * 同轨道片行惯例）。</p>
 */
@Entity
@Table(name = "prj_generation_plan_deferrals")
@Aggregate
@Getter
public class GenerationPlanDeferral extends Auditable implements AggregateRoot<GenerationPlanDeferral, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 推迟项序（1..M，主智能体产出序）。 */
    @Column(name = "ord", nullable = false, updatable = false)
    private int ord;

    /** 推迟项（本期不做的功能面＋示意级边界，用户语言）。 */
    @Column(name = "item", nullable = false, updatable = false)
    private String item;

    /** 计划落库时的 PRD 版本锚（与片行同锚；锚不一致 = 计划过期）。 */
    @Column(name = "prd_produced_at", nullable = false, updatable = false)
    private LocalDateTime prdProducedAt;

    protected GenerationPlanDeferral() {
    }

    /** 推迟行（计划落库/重产的整组替换式插入；不可变，无状态推进）。 */
    public static GenerationPlanDeferral of(Long projectId, int ord, String item,
            LocalDateTime prdProducedAt) {
        GenerationPlanDeferral deferral = new GenerationPlanDeferral();
        deferral.projectId = projectId;
        deferral.ord = ord;
        deferral.item = item;
        deferral.prdProducedAt = prdProducedAt;
        return deferral;
    }

    /**
     * 现行推迟集判定（PRD 版本锚门，同 {@link GenerationSegment#planMatchesPrd}
     * 口径）：推迟集非空且锚一致（首行锚 = 落库时的项目 prd_produced_at）＝现行
     * 计划的推迟清单；空集或锚漂（PRD 已演进）= false——读面消费。
     */
    public static boolean planMatchesPrd(List<GenerationPlanDeferral> deferrals,
            LocalDateTime prdProducedAt) {
        return !deferrals.isEmpty()
                && deferrals.get(0).getPrdProducedAt().equals(prdProducedAt);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
