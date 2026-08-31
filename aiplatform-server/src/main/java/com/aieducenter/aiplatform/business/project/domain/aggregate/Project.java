package com.aieducenter.aiplatform.business.project.domain.aggregate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 项目聚合根（{@code prj_projects}）：用户一次定制需求的全程载体——业务字段 +
 * dev 工作区引用 + 归属账号 + 归档终点。归档是单向终点动作（archived_at 落定）；
 * 「进行中/已归档」即其全部派生态。一个项目 = 一个 dev 环境（workspaceId 软引用
 * wsp 表，级联清理由编排负责）。删除真删级联，无软删除——继承 Auditable 只取
 * 审计字段。
 */
@Entity
@Table(name = "prj_projects")
@Aggregate
@Getter
public class Project extends Auditable implements AggregateRoot<Project, Long> {

    /**
     * 占位名（#39）：创建即落的 LLM 取名未完成/失败回落——取名后台完成后经
     * {@link #rename} 落位，用户经改名端点（#43）亦可改。
     */
    public static final String PLACEHOLDER_NAME = "未命名项目";

    /**
     * 名称长度上限（#43 收口单一事实源）：建/改名单一守门口径——命令层 @Size、
     * 取名净化（#39）与本列长共用（超限弃用/拒绝，不截断）。
     */
    public static final int NAME_MAX_LENGTH = 100;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "type", nullable = false, updatable = false)
    private ProjectType type;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 归属账号（A2：创建时填、v1 不过滤；测试/无会话上下文可空）。 */
    @Column(name = "owner_account_id", updatable = false)
    private Long ownerAccountId;

    /** 归档时间（单向终点；NULL = 未归档，归档动作归片5c）。 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    /**
     * 「PRD 已产出」状态位：PRD 事实源是工作区 {@code docs/PRD.md}，本位只记
     * 「BA 已写出过」这一事实（成果区长出判据）——NULL = 未产出；写入方是 BA 的
     * savePrd（写文件成功即置位）。时间戳随每次写出刷新（产出/更新共用，
     * v1 无版本链）。
     */
    @Column(name = "prd_produced_at")
    private LocalDateTime prdProducedAt;

    /**
     * 首次生成时点：单向置位（{@link #markGenerated} 只在首次落值，后续生成/迭代
     * 不刷新）——「确认下单」可见性与项目列表「进行中」推导口径的锚点。生成编排
     * 落位归生成环（#22），本聚合只保证置位语义。
     */
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    protected Project() {
    }

    private Project(String name, ProjectType type, Long workspaceId, Long ownerAccountId) {
        if (name == null || name.isBlank()) {
            throw new DomainException(ProjectMessage.PROJECT_NAME_BLANK);
        }
        if (workspaceId == null) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        this.name = name;
        this.type = ProjectType.orDefault(type);
        this.workspaceId = workspaceId;
        this.ownerAccountId = ownerAccountId;
    }

    /**
     * 建项目（编排在工作区副作用落定后调用，短事务落库）。
     */
    public static Project create(String name, ProjectType type,
                                 Long workspaceId, Long ownerAccountId) {
        return new Project(name, type, workspaceId, ownerAccountId);
    }

    /**
     * 改名（#39 LLM 取名落位 / #43 改名端点共用）：名称可后改（占位名 → 生成名 /
     * 用户改名），空白拒绝（PRJ_005，与建项目同口径——长度上限归调用方命令校验）。
     */
    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new DomainException(ProjectMessage.PROJECT_NAME_BLANK);
        }
        this.name = name;
    }

    /**
     * 占位名落位（#39 LLM 取名专用守卫）：仅当当前仍是占位名时改名并返回 true——
     * 取名在飞时用户已改名（#43）或取名已完成则不动（返回 false，调用方不覆写）。
     */
    public boolean renameIfPlaceholder(String name) {
        if (!PLACEHOLDER_NAME.equals(this.name)) {
            return false;
        }
        rename(name);
        return true;
    }

    /**
     * 归档（单向终点——「收起来不再活跃」的真实动作）。重复归档拒绝
     * （409 PRJ_013）；归档不清工作区。
     */
    public void archive() {
        if (archivedAt != null) {
            throw new DomainException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        this.archivedAt = LocalDateTime.now();
    }

    /**
     * PRD 已产出置位（#49 savePrd 写工作区文件成功后调用）：幂等单向——首次 = 产出，
     * 修订再执行 = 刷新更新时间（只前进，无清位路径；删除项目与工作区同亡）。
     */
    public void markPrdProduced() {
        this.prdProducedAt = LocalDateTime.now();
    }

    /**
     * 首次生成置位：幂等单向——首次落值后不再刷新（与 {@link #markPrdProduced}
     * 的「随写刷新」相对：生成时点只认第一次，迭代不重置）。
     */
    public void markGenerated() {
        if (generatedAt == null) {
            this.generatedAt = LocalDateTime.now();
        }
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
