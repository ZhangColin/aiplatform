package com.semgravio.aiplatform.business.project.domain.aggregate;

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
 * PRD 版本行（{@code prj_prd_versions}）：当时工作区 PRD（{@code docs/PRD.md} 活草稿）
 * 全文的不可变快照＋项目内递增整数版本号（v1/v2/v3…）。<b>去门化（#462，ADR-0039）</b>：
 * 记行时机＝Demo 类 run（首构建/修正重建）起跑时自动记行（快照当时工作区 PRD 全文），
 * 版本号语义＝<b>构建基准序</b>（第 N 个构建基准快照）；记行幂等＝快照全文比对（内容未变
 * ＝同一基准，不产生新行——判定归 {@link com.semgravio.aiplatform.business.project.application.PrdVersionAppService}）。
 * 工作区 PRD 仍是活草稿照旧覆盖写（草稿不编号），历史版本行不受后续 savePrd 影响。
 *
 * <p><b>不可变＝只插不改</b>：全列 {@code updatable=false}、聚合零变更方法——历史行无任何
 * UPDATE 路径。「快照不可变」的行为面等价物＝改草稿再读历史版本，原文不动。版本表模式
 * 先例＝切片计划落库（{@link GenerationSegment} V14）：{@code prdProducedAt} 记成行时
 * 的 PRD 产出锚（谱系观测，非成行判定——锚随每次 savePrd 刷新，同文重存锚漂不成新行）。
 * {@code confirmedAt}（列名沿用 V32——迁移只增不改）语义随去门化顺延为「成行时点」。
 * {@code runId}＝来源 run（V60 起；确认制时代存量行为 NULL）。删除真删级联随项目
 * （软引用显式清，同轨道表惯例）。</p>
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

    /** 项目内递增整数版本号（1 起——构建基准序：第 N 个构建基准快照）。 */
    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    /** 成行时工作区 PRD 全文快照（markdown 原样冻结）。 */
    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    /** 成行时点（列名 confirmed_at 沿用 V32——语义随去门化顺延，迁移只增不改）。 */
    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private LocalDateTime confirmedAt;

    /** 成行时的 PRD 产出锚（与项目 prd_produced_at 同源——谱系观测，非成行判定）。 */
    @Column(name = "prd_produced_at", nullable = false, updatable = false)
    private LocalDateTime prdProducedAt;

    /** 来源 run（去门化后 run 起跑自动记行的来源锚；确认制时代存量行为 NULL）。 */
    @Column(name = "run_id", updatable = false)
    private String runId;

    protected PrdVersion() {
    }

    private PrdVersion(Long projectId, int versionNo, String content,
            LocalDateTime confirmedAt, LocalDateTime prdProducedAt, String runId) {
        this.projectId = projectId;
        this.versionNo = versionNo;
        this.content = content;
        this.confirmedAt = confirmedAt;
        this.prdProducedAt = prdProducedAt;
        this.runId = runId;
    }

    /** 成行（唯一写面：插入新行；版本号分配与「同文不成新行」幂等判定归应用层）。 */
    public static PrdVersion recorded(Long projectId, int versionNo, String content,
            LocalDateTime recordedAt, LocalDateTime prdProducedAt, String runId) {
        return new PrdVersion(projectId, versionNo, content, recordedAt, prdProducedAt, runId);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
