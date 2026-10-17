package com.aieducenter.aiplatform.business.project.domain.aggregate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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
 * dev 工作区引用 + 归属（owner 双列：账户/访客，逻辑互斥）+ 归档终点。归档是单向终点动作（archived_at 落定）；
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
     * 冻结（守卫归编排）。入口两档显式选择定初值（#299：做设计＝DESIGN，缺省
     * 系统）。PRD 清单章形态跟本属性走（技能 prd-writing 双形态选择键）。
     */
    @Column(name = "endpoint_type", nullable = false)
    private ProjectEndpointType endpointType;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * 归属账号（A2 落列、#325 起进读面过滤与单项目守卫；测试/无会话上下文可空）。
     * 与 {@link #ownerGuestId} 逻辑互斥（聚合守卫＋库层 CHECK 兜底，至多一方落值）。
     * #328 起可更新——绑定平移（{@link #transferToAccount}）把访客项目过户到账户，
     * 唯一写路径即该动作（聚合无其他 setter；#325 的 updatable=false 曾使「创建即
     * 定格」，本票的平移是首个再入职责，故解禁）。
     */
    @Column(name = "owner_account_id")
    private Long ownerAccountId;

    /**
     * 归属访客（#325 建列、#326 挂载写入方——首条需求消息惰性建访客后随建项目
     * 落列）：访客独立表 idn_guests 的软引用；与 {@link #ownerAccountId} 逻辑
     * 互斥（聚合守卫＋库层 CHECK 双保险）；owner 过滤的访客维度（列表谓词与
     * SSE 路由键 ownerGuestId）同源本列。#328 平移时清空（{@link #transferToAccount}）。
     */
    @Column(name = "owner_guest_id")
    private Long ownerGuestId;

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
     * 确认标记所在 Demo 版本（#333 单指针，NULL = 未确认任何版本）：「确认此
     * 版本，准备签约」＝既有版本上打标记（选定动作、不产生新版本号——版本行
     * 不可变，标记归本指针；换确认版本＝指针移动，旧标记随移）。下单/合同冻结
     * 引用（#335）与签约门投影消费本位。
     */
    @Column(name = "confirmed_demo_version_no")
    private Integer confirmedDemoVersionNo;

    /** 确认标记落位时点（与 {@link #confirmedDemoVersionNo} 同刻写，随指针移动刷新）。 */
    @Column(name = "confirmed_demo_at")
    private LocalDateTime confirmedDemoAt;

    /**
     * 改需求回路停驻的意见原文（#334，NULL = 无范围级草稿待确认）：范围级意见轮
     * 收口停派更新 run 时，本轮意见原文停驻于此——确认新版时（新版本号落库）由
     * 平台取出随修正 run（下一版 Demo）下发。停驻取终值（新一轮覆盖写），确认成功
     * 即清；载体归本聚合、漂移判定不在此（正本＝快照全文比对，见 PrdDraftBaseline）。
     */
    @Column(name = "prd_draft_opinion")
    private String prdDraftOpinion;

    /** 改需求回路停驻的差异摘要（#334）：savePrd 的 summary 终值＝改稿时产出的 markdown 变更说明。 */
    @Column(name = "prd_draft_summary")
    private String prdDraftSummary;

    /**
     * 「需用户补充」项目级标志（#338 前台六态源之一）：生产承接期间需用户补
     * 资料/确认事项时后台置位、处理完清零——六态推导以本标志盖帽（非终态订单
     * 最高优先级），置位依据归后台人工口径不落库。
     */
    @Column(name = "needs_user_supplement", nullable = false)
    private boolean needsUserSupplement;

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

    private Project(String name, ProjectType type, ProjectEndpointType endpointType,
                    Long workspaceId, Long ownerAccountId, Long ownerGuestId) {
        if (name == null || name.isBlank()) {
            throw new DomainException(ProjectMessage.PROJECT_NAME_BLANK);
        }
        if (workspaceId == null
                || (ownerAccountId != null && ownerGuestId != null)) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        this.name = name;
        this.type = ProjectType.orDefault(type);
        this.endpointType = ProjectEndpointType.orDefault(endpointType);
        this.workspaceId = workspaceId;
        this.ownerAccountId = ownerAccountId;
        this.ownerGuestId = ownerGuestId;
    }

    /**
     * 建项目（#326 收编单一形——owner 双列与终点初值全显形；#299 备案触发器「下一
     * 职责再入聚合时收编删重载」已燃，四参/五参重载删除、调用方逐自声明）：终点
     * 初值入口两档显式选择（#299，做设计携 {@link ProjectEndpointType#DESIGN}、
     * 缺省传 null 走系统）；归属 owner 双列至多一方落值（互斥——聚合守卫与库层
     * CHECK 双保险，账户随会话、访客随凭证惰性创建后挂列，皆可空＝无主占位形）。
     */
    public static Project create(String name, ProjectType type, ProjectEndpointType endpointType,
                                 Long workspaceId, Long ownerAccountId, Long ownerGuestId) {
        return new Project(name, type, endpointType, workspaceId, ownerAccountId, ownerGuestId);
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
     * Demo 版本确认标记移动（#333「确认此版本，准备签约」）：既有版本上打标记
     * ——不产生新版本号（版本行不可变，标记归本聚合单指针），换确认版本＝指针
     * 随移（旧标记自然失效）。目标版本存在性守卫归应用层（PRJ_054）。
     */
    public void confirmDemoVersion(int versionNo) {
        this.confirmedDemoVersionNo = versionNo;
        this.confirmedDemoAt = LocalDateTime.now();
    }

    /**
     * 范围级草稿停驻（#334 改需求回路）：意见轮收口判定为范围级（savePrd 事实＋
     * 草稿≠已确认版）时停派更新 run，意见原文与差异摘要停驻待确认——新一轮覆盖
     * 写（草稿单一，停驻取终值）。确认成功即经 {@link #clearPrdDraftChange} 清位。
     */
    public void parkPrdDraftChange(String opinion, String changeSummary) {
        if (opinion == null || opinion.isBlank()
                || changeSummary == null || changeSummary.isBlank()) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        this.prdDraftOpinion = opinion;
        this.prdDraftSummary = changeSummary;
    }

    /** 停驻清位（#334）：确认成功即消费——已生成项目携停驻交接物派修正 run、未生成项目由首个 Demo 全新构建消化。 */
    public void clearPrdDraftChange() {
        this.prdDraftOpinion = null;
        this.prdDraftSummary = null;
    }

    /**
     * 「需用户补充」置位/清零（#338 后台动作）：纯属性落位——置位语义（何时需要
     * 用户补充、补充什么）归后台人工判定，聚合只承载标志与幂等落值。
     */
    public void flagNeedsUserSupplement(boolean required) {
        this.needsUserSupplement = required;
    }

    /**
     * owner 的智能体会话寻址 userId（cat_agent_state 槽位 (userId, sessionId) 的
     * userId 腿）：谁的 owner 就 stringify 谁（账户或访客——访客项目恒有主，不再
     * 落 null）；无主占位形（测试/无会话上下文）容忍 null——与各编排处自行展开的
     * 三元同义，收拢为聚合单点。
     */
    public String ownerUserId() {
        return ownerAccountId != null ? ownerAccountId.toString()
                : ownerGuestId != null ? ownerGuestId.toString() : null;
    }

    /**
     * 归属判定（#325/#326 owner 双维数据面——列表过滤与单项目守卫共用谓词）：
     * 请求上下文有账户维度按账户等值、否则有访客维度按访客等值（账户会话优先
     * 于访客凭证——两维不同时判）；两维皆空＝无上下文，「无主对无主」放行（无主
     * × owner 双列皆空的占位聚合——测试直调/编排内部调用的容忍面），其余不匹配
     * 即 deny（deny-by-default：存量无归属行对任何账户/访客不可见，账户与访客
     * 项目互不可见）。
     */
    public boolean ownedBy(Long accountId, Long guestId) {
        if (accountId != null) {
            return Objects.equals(ownerAccountId, accountId);
        }
        if (guestId != null) {
            return Objects.equals(ownerGuestId, guestId);
        }
        return ownerAccountId == null && ownerGuestId == null;
    }

    /**
     * 归属平移（#328 绑定平移）：访客项目过户给账户——owner_account_id 落值、
     * owner_guest_id 清空（owner 双列逻辑互斥不变量在聚合；库层 CHECK 兜底）。
     * 守卫：目标账户非空、且当前确为访客归属（owner_guest_id 非空）——已平移过
     * （owner_guest_id 已清）不可再平移（幂等由调用方按 owner_guest_id 查询保证，
     * 聚合层拒绝二次平移）。
     */
    public void transferToAccount(Long accountId) {
        if (accountId == null || ownerGuestId == null) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        this.ownerAccountId = accountId;
        this.ownerGuestId = null;
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
