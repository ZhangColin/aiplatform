package com.aieducenter.aiplatform.business.project.domain.aggregate;

import java.time.LocalDateTime;

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
 * PRD 确认版本行（{@code prj_prd_versions}，#330「确认即成版」）：确认动作把
 * 当时工作区 PRD（{@code docs/PRD.md} 活草稿）全文拷贝为不可变快照——＋确认
 * 时间＋项目内递增整数版本号（v1/v2/v3…）。工作区 PRD 仍是活草稿照旧覆盖写
 * （草稿不编号、确认即分配版本号），已确认版本不受后续 savePrd 影响。
 *
 * <p><b>不可变＝只插不改</b>：全列 {@code updatable=false}、聚合零变更方法
 * ——历史行无任何 UPDATE 路径（「重复确认不产生新版本」归应用层成版判定，
 * 不改写既有行）。版本表模式先例＝切片计划落库（{@link GenerationSegment}
 * V14）：{@code prdProducedAt} 记成版时的 PRD 产出锚（谱系观测）；「无新草稿
 * 不成新版」的成版判定正本是快照全文比对（内容未变＝同一版），不靠锚——锚随
 * 每次 savePrd 刷新，同文重存锚漂不成新版。删除真删级联随项目（软引用显式清，
 * 同轨道表惯例）。</p>
 */
@Entity
@Table(name = "prj_prd_versions")
@Aggregate
@Getter
public class PrdVersion extends Auditable implements AggregateRoot<PrdVersion, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属项目（软引用 prj_projects，级联清理由编排负责）。 */
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 项目内递增整数版本号（1 起——确认即分配，草稿不编号）。 */
    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    /** 确认时工作区 PRD 全文快照（markdown 原样冻结）。 */
    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    /** 确认时间（确认动作落库时点）。 */
    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private LocalDateTime confirmedAt;

    /** 成版时的 PRD 产出锚（与项目 prd_produced_at 同源——谱系观测，非成版判定）。 */
    @Column(name = "prd_produced_at", nullable = false, updatable = false)
    private LocalDateTime prdProducedAt;

    protected PrdVersion() {
    }

    private PrdVersion(Long projectId, int versionNo, String content,
            LocalDateTime confirmedAt, LocalDateTime prdProducedAt) {
        this.projectId = projectId;
        this.versionNo = versionNo;
        this.content = content;
        this.confirmedAt = confirmedAt;
        this.prdProducedAt = prdProducedAt;
    }

    /** 确认成版（唯一写面：插入新行；版本号分配与「无新草稿不成新版」判定归应用层）。 */
    public static PrdVersion confirmed(Long projectId, int versionNo, String content,
            LocalDateTime confirmedAt, LocalDateTime prdProducedAt) {
        return new PrdVersion(projectId, versionNo, content, confirmedAt, prdProducedAt);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
