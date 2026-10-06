package com.aieducenter.aiplatform.business.project.domain.aggregate;

import java.time.LocalDateTime;
import java.util.List;

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

import com.aieducenter.aiplatform.business.project.domain.enums.DesignScopeType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.DesignScope;

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

    /**
     * 终点类型（#285，ADR-0024「项目不分型、终点是属性」）：下单前可变（切换
     * 编排归 {@link #switchEndpoint}，项目内唯一变更位＝设置 tab 控件）、下单即
     * 冻结（守卫归编排）。入口两档显式选择定初值（门面票落地前一律缺省系统）。
     * PRD 清单章形态跟本属性走（技能 prd-writing 双形态选择键）。
     */
    @Column(name = "endpoint_type", nullable = false)
    private ProjectEndpointType endpointType;

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
     * 「PRD 已写出过」这一事实（成果区长出判据）——NULL = 未产出；写入方是主智能体的
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

    /**
     * 设计范围作用域（#285）：仅系统＋设计项目落值（设计范围＝功能清单页面集的
     * 锚定口径——全部/勾选）；设计主线（计划对应物＝PRD 设计物清单章）与系统/
     * 设计主线出身不由页面锚定时为 NULL。
     */
    @Column(name = "design_scope_type")
    private DesignScopeType designScopeType;

    /**
     * 设计范围勾选页标签（#285）：功能清单条目原文 jsonb（建议性锚——PRD 是模型
     * 独笔演进的正本，标签不构成稳定标识）；作用域为全部页面或无范围时 NULL。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "design_scope_pages", columnDefinition = "jsonb")
    private List<String> designScopePages;

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
        this.endpointType = ProjectEndpointType.SYSTEM;
        this.workspaceId = workspaceId;
        this.ownerAccountId = ownerAccountId;
    }

    /**
     * 建项目（编排在工作区副作用落定后调用，短事务落库）。终点类型入口两档显式
     * 选择定初值（门面票 #299 落地前的机制位）：建项目恒缺省系统，选「做设计」
     * 进项目后经设置 tab 切换（#285 测试期口径）。
     */
    public static Project create(String name, ProjectType type,
                                 Long workspaceId, Long ownerAccountId) {
        return new Project(name, type, workspaceId, ownerAccountId);
    }

    /**
     * 切换终点类型（#285，设置 tab 控件＝项目内唯一变更位）：纯属性落位——冻结
     * （未终结订单）/关闭（归档）守卫与「同目标即无操作」判定归编排；设计范围
     * 只随系统＋设计落库（其余目标清空——设计主线的范围由 PRD 设计物清单章承载，
     * 不另存正本防两处漂移）。
     */
    public void switchEndpoint(ProjectEndpointType target, DesignScope scope) {
        if (target == null || (scope != null && target != ProjectEndpointType.SYSTEM_DESIGN)) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        this.endpointType = target;
        if (target == ProjectEndpointType.SYSTEM_DESIGN && scope != null) {
            this.designScopeType = scope.type();
            this.designScopePages = scope.type() == DesignScopeType.SELECTED_PAGES
                    ? List.copyOf(scope.pages()) : null;
        }
        else {
            this.designScopeType = null;
            this.designScopePages = null;
        }
    }

    /**
     * 设计范围读面（{@link DesignScope} 组装）：无锚定（非系统＋设计或不由页面
     * 锚定）返回 null。
     */
    public DesignScope designScope() {
        if (designScopeType == null) {
            return null;
        }
        if (designScopeType == DesignScopeType.ALL_PAGES) {
            return DesignScope.allPages();
        }
        return DesignScope.selected(designScopePages == null ? List.of() : designScopePages);
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

    /**
     * owner 的智能体会话寻址 userId（cat_agent_state 槽位 (userId, sessionId) 的
     * userId 腿）：owner 未落的占位形（测试/无会话上下文）容忍 null——与各编排
     * 处自行展开的三元同义，收拢为聚合单点。
     */
    public String ownerUserId() {
        return ownerAccountId != null ? ownerAccountId.toString() : null;
    }

    /**
     * 系统已生成（迭代期判据——受理轮 / 修正 run 守卫与访谈期〔纯追问轮〕的分界）：
     * {@link #markGenerated} 首次落值后恒真（幂等单向，无清位路径）。
     */
    public boolean isGenerated() {
        return generatedAt != null;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
