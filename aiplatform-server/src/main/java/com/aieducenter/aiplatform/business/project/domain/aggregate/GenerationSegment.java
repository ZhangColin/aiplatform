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

import com.aieducenter.aiplatform.business.project.domain.enums.GenerationSegmentStatus;

/**
 * 生成轨道片行（{@code prj_generation_segments}，#220 生成轨道表）：项目当前
 * 生成轨道的一片——阶段 0（先起服，ord=0）或切片计划的一片（ord=1..N）。切片
 * 计划与每片收口/失败状态落平台库（「run 无表、重启即清」口径的精确例外，
 * ADR-0020），进程重启后计划与片进度仍可查；断点 = 表中最深收口片。
 *
 * <p>计划生命周期跟 PRD 版本走：片行整组落库时记 {@code prdProducedAt} 版本锚
 * （= 项目 {@code prj_projects.prd_produced_at} 当时值）——PRD 演进即锚不一致，
 * 现行计划过期不沿用，重产 = 整组替换（旧片不残留续用）。片行除状态与 run 锚外
 * 不可变（updatable=false），状态是「最近一次尝试的结局」（重派后再收口即覆写）。
 * 删除真删级联随项目（软引用显式清，同对话史惯例）。</p>
 */
@Entity
@Table(name = "prj_generation_segments")
@Aggregate
@Getter
public class GenerationSegment extends Auditable implements AggregateRoot<GenerationSegment, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 片序（0 = 阶段 0 先起服；1..N = 切片计划逐片——与轨道会话/交接段号同口径）。 */
    @Column(name = "ord", nullable = false, updatable = false)
    private int ord;

    /** 片描述（阶段 0 固定题 / 切片句「用户能 X」——计划重产的整组替换单位）。 */
    @Column(name = "description", nullable = false, updatable = false)
    private String description;

    /** 最近一次尝试的结局（待跑 → 已收口 / 失败；重派覆写）。 */
    @Column(name = "status", nullable = false)
    private GenerationSegmentStatus status;

    /** 收口/失败的用户面 run 锚（SSE ?runId= 与收尾卡同锚；待跑恒 NULL）。 */
    @Column(name = "run_id", length = 100)
    private String runId;

    /** 计划落库时的 PRD 版本锚（与项目 prd_produced_at 比对——锚不一致 = 计划过期）。 */
    @Column(name = "prd_produced_at", nullable = false, updatable = false)
    private LocalDateTime prdProducedAt;

    protected GenerationSegment() {
    }

    private GenerationSegment(Long projectId, int ord, String description,
            LocalDateTime prdProducedAt) {
        this.projectId = projectId;
        this.ord = ord;
        this.description = description;
        this.status = GenerationSegmentStatus.PENDING;
        this.prdProducedAt = prdProducedAt;
    }

    /** 待跑片行（计划落库/重产的整组替换式插入；状态随后由收口/失败落位推进）。 */
    public static GenerationSegment pending(Long projectId, int ord, String description,
            LocalDateTime prdProducedAt) {
        return new GenerationSegment(projectId, ord, description, prdProducedAt);
    }

    /** 收口落位（幂等覆写——重派后再收口即刷新；runId = 该片的用户面 run 锚）。 */
    public void close(String runId) {
        this.status = GenerationSegmentStatus.CLOSED;
        this.runId = runId;
    }

    /** 失败落位（尝试环超限终态——续跑重做，再收口即覆写回已收口）。 */
    public void fail(String runId) {
        this.status = GenerationSegmentStatus.FAILED;
        this.runId = runId;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
