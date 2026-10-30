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
 * Demo 构建版本行（{@code prj_demo_versions}，#333「Demo 版本化与确认标记」）：
 * 每轮 Demo 构建收口自动成版——首版随 {@code generated_at} 同刻落（生成收口），
 * 此后每轮迭代 run 收口（系统实动）递增成版，项目内整数递增（v1/v2/v3…）。
 * 每版锚定<b>起跑时</b>已确认 PRD 版本（{@code prdVersionNo}，「Demo vN（基于
 * PRD vM 快照）」式关联——在途 Demo 照跑完收口绑起跑时版本，不随后续确认漂移；
 * 无确认版项目/设计类起跑为 null）；{@code runId}＝收口 run 的用户面标识
 * （版本记录抽屉 #344「查看当时」的 git 成版 Run-Id 同锚）。
 *
 * <p><b>不可变＝只插不改</b>：全列 {@code updatable=false}、聚合零变更方法
 * ——历史行无任何 UPDATE 路径（版本表模式先例＝{@link PrdVersion} V32）。
 * 「确认此版本，准备签约」的确认标记不落版本行（行不可变），归项目聚合单指针
 * （{@code prj_projects.confirmed_demo_version_no}——选定动作、不产生新版本号，
 * 换确认版本＝指针移动）。删除真删级联随项目（软引用显式清，同轨道表惯例）。</p>
 */
@Entity
@Table(name = "prj_demo_versions")
@Aggregate
@Getter
public class DemoVersion extends Auditable implements AggregateRoot<DemoVersion, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属项目（软引用 prj_projects，级联清理由编排负责）。 */
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 项目内递增整数版本号（1 起——收口自动成版，无手工版本）。 */
    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    /** 起跑时已确认 PRD 版本锚（每版记录其使用的 PRD 版本；无确认版起跑 = null）。 */
    @Column(name = "prd_version_no", updatable = false)
    private Integer prdVersionNo;

    /** 收口 run 的用户面标识（git 成版 Run-Id 同锚——「查看当时」的接续缝）。 */
    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    protected DemoVersion() {
    }

    private DemoVersion(Long projectId, int versionNo, Integer prdVersionNo, String runId) {
        this.projectId = projectId;
        this.versionNo = versionNo;
        this.prdVersionNo = prdVersionNo;
        this.runId = runId;
    }

    /** 构建收口成版（唯一写面：插入新行；版本号分配归应用层——收口即递增）。 */
    public static DemoVersion built(Long projectId, int versionNo, Integer prdVersionNo,
            String runId) {
        return new DemoVersion(projectId, versionNo, prdVersionNo, runId);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
