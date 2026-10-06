package com.aieducenter.aiplatform.business.project.domain.aggregate;

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

import com.aieducenter.aiplatform.business.project.domain.enums.DesignItemStatus;

/**
 * 设计轨道件行（{@code prj_design_items}，#289 设计轨道表）：项目当前设计清单
 * 的一件设计物——PRD 清单章条目（设计主线＝「设计物清单」、系统＋设计＝「功能
 * 清单」按设计范围圈定）的执行事实载体。清单与每件收口/失败状态落平台库（对偶
 * 生成轨道表 #220——「run 无表、重启即清」口径的又一精确例外），进程重启后清单
 * 与推进进度仍可查。
 *
 * <p>清单生命周期跟 PRD 版本走（改序＝改 PRD）：件行整组落库时记
 * {@code prdProducedAt} 版本锚——PRD 演进即锚不一致、现行清单过期，重产=
 * 整组替换，已收口条目按<b>标题精确对照</b>保留（推进序随新清单、已收口不重做
 * ——条目文本是建议性锚，措辞漂移即对照不上、按待跑重做，降级方向安全：多跑
 * 不漏做）。件行除状态与 run 锚外不可变（updatable=false），状态是「最近一次
 * 尝试的结局」（重派后再收口即覆写）。删除真删级联随项目（软引用显式清，同
 * 对话史惯例）。</p>
 */
@Entity
@Table(name = "prj_design_items")
@Aggregate
@Getter
public class DesignItem extends Auditable implements AggregateRoot<DesignItem, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 件序（1..N＝清单条目序——首产推进序，与轨道会话寻址同口径；无阶段 0 对偶物）。 */
    @Column(name = "ord", nullable = false, updatable = false)
    private int ord;

    /** 件描述（清单章条目首行原文——设计会话的任务锚与重产的对照键）。 */
    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    /** 最近一次尝试的结局（待跑 → 已收口 / 失败；重派覆写）。 */
    @Column(name = "status", nullable = false)
    private DesignItemStatus status;

    /** 收口/失败的用户面 run 锚（SSE ?runId= 与收尾卡同锚；待跑恒 NULL）。 */
    @Column(name = "run_id", length = 100)
    private String runId;

    /** 清单落库时的 PRD 版本锚（与项目 prd_produced_at 比对——锚不一致 = 清单过期）。 */
    @Column(name = "prd_produced_at", nullable = false, updatable = false)
    private LocalDateTime prdProducedAt;

    protected DesignItem() {
    }

    private DesignItem(Long projectId, int ord, String title, LocalDateTime prdProducedAt) {
        this.projectId = projectId;
        this.ord = ord;
        this.title = title;
        this.status = DesignItemStatus.PENDING;
        this.prdProducedAt = prdProducedAt;
    }

    /** 待跑件行（清单落库/重产的整组替换式插入；状态随后由收口/失败落位推进）。 */
    public static DesignItem pending(Long projectId, int ord, String title,
            LocalDateTime prdProducedAt) {
        return new DesignItem(projectId, ord, title, prdProducedAt);
    }

    /**
     * 现行清单判定（PRD 版本锚门单点，对偶生成轨道表）：件集非空且锚一致 = 现行
     * 清单；空件集或锚漂（PRD 已演进、旧清单过期）= false。
     */
    public static boolean checklistMatchesPrd(List<DesignItem> items, LocalDateTime prdProducedAt) {
        return !items.isEmpty() && items.get(0).getPrdProducedAt().equals(prdProducedAt);
    }

    /** 收口落位（幂等覆写——重派后再收口即刷新；runId = 该件的用户面 run 锚）。 */
    public void close(String runId) {
        this.status = DesignItemStatus.CLOSED;
        this.runId = runId;
    }

    /** 失败落位（尝试环超限终态——重派重做，再收口即覆写回已收口）。 */
    public void fail(String runId) {
        this.status = DesignItemStatus.FAILED;
        this.runId = runId;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
