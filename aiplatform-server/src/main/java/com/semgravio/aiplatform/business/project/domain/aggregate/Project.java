package com.semgravio.aiplatform.business.project.domain.aggregate;

import java.time.LocalDateTime;
import java.util.Objects;

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

import com.semgravio.aiplatform.business.project.domain.enums.ForkState;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectType;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;

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
     * 不刷新）——Demo 首版成版与 Demo 确认门可用性的推导锚、项目列表「进行中」推导
     * 口径的锚点。生成编排
     * 落位归生成环（#22），本聚合只保证置位语义。
     */
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    /**
     * 岔路口状态位（#542 访谈②，ADR-0048/0049）：试用态访谈自然收口（非快道）时
     * 置 {@link ForkState#PENDING}——岔路口三选卡的在场事实（持久可回：未选择前
     * 关页回来卡仍在）。NULL = 不在场（未收口／快道直生成不出卡）；点火后不回清
     * ——卡呈现由 {@link #generationRequestedAt} 派生隐藏（详见 {@link ForkState}）。
     * 落库 Integer code（BaseEnumConverter autoApply——同 {@link ProjectType}/
     * {@link ProjectEndpointType} 先例，防 ORDINAL 错位）。
     */
    @Column(name = "fork_state")
    private ForkState forkState;

    /**
     * 首个构建派发点火时点（#542，ADR-0049「两轨唯一差异＝触发源」的读点）：
     * 岔路口「立即生成」／付费轨 PRD 确认／快道直生成的共同落位（单向置位，
     * {@link #markFirstBuildRequested}）——岔路口卡与确认按钮「首建未派发」呈现
     * 条件的事实源（#604 失败新语义：终态失败/停止收口清位——{@link
     * #reopenFirstBuildRequest}，卡与确认按钮复现，用户在点火入口重跑即恢复）。
     */
    @Column(name = "generation_requested_at")
    private LocalDateTime generationRequestedAt;

    /**
     * 确认用于签约的用户面版本号（#629 改义；单指针，NULL = 未确认任何版本）：
     * 「确认此版本，准备签约」＝标记当前用户面版本（git 版本号，非旧 Demo/PRD
     * 版本号）——选定动作、不产生新版本号；换选＝指针移动、旧标记随移，重复确认
     * 同版幂等（时间刷新）。下单前置「存在已确认用户面版本」与订单冻结的版本引用
     * 消费本位。列名 {@code confirmed_demo_version_no} 为历史沿用，#630 迁移改义
     * 为 {@code confirmed_version_no}。
     */
    @Column(name = "confirmed_demo_version_no")
    private Integer confirmedDemoVersionNo;

    /** 确认标记落位时点（与 {@link #confirmedDemoVersionNo} 同刻写，随指针移动刷新）。 */
    @Column(name = "confirmed_demo_at")
    private LocalDateTime confirmedDemoAt;

    /**
     * 改需求回路停驻的意见原文（#334；<b>#462 去确认门：停驻读法整段退役</b>——
     * parkDraftChange/clearPrdDraftChange 写方法与全部消费路径已注销，本列留库不删
     *〔迁移只增不改〕，既有停驻值自然滞留不再被读取）。
     */
    @Column(name = "prd_draft_opinion")
    private String prdDraftOpinion;

    /** 改需求回路停驻的差异摘要（#334；同 prd_draft_opinion——#462 起留库不删、无人读）。 */
    @Column(name = "prd_draft_summary")
    private String prdDraftSummary;

    /**
     * 「需用户补充」项目级标志（#338 前台六态源之一）：生产承接期间需用户补
     * 资料/确认事项时后台置位、处理完清零——六态推导以本标志盖帽（非终态订单
     * 最高优先级），置位依据归后台人工口径不落库。
     */
    @Column(name = "needs_user_supplement", nullable = false)
    private boolean needsUserSupplement;

    protected Project() {
    }

    private Project(String name, ProjectType type,
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
        this.workspaceId = workspaceId;
        this.ownerAccountId = ownerAccountId;
        this.ownerGuestId = ownerGuestId;
    }

    /**
     * 建项目（#326 收编单一形）：归属 owner 双列至多一方落值（互斥——聚合守卫与
     * 库层 CHECK 双保险，账户随会话、访客随凭证惰性创建后挂列，皆可空＝无主
     * 占位形）。#612 终点收窄为系统单值——建项目不再接受终点初值（恒系统）。
     */
    public static Project create(String name, ProjectType type,
                                 Long workspaceId, Long ownerAccountId, Long ownerGuestId) {
        return new Project(name, type, workspaceId, ownerAccountId, ownerGuestId);
    }

    /**
     * 终点类型读面（#612 收窄为系统单值）：终点概念收窄后恒为 {@link
     * ProjectEndpointType#SYSTEM}——列已拆除，本方法只为读面/契约保持「系统」
     * 这一唯一事实。
     */
    public ProjectEndpointType getEndpointType() {
        return ProjectEndpointType.SYSTEM;
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
     * 岔路口置位（#542）：自然收口观测点调用（试用态 ∧ 系统终点 ∧ PRD 在 ∧ 首建
     * 未点火——前置归编排），幂等——已置不拒（意见修订轮再收口照旧在场）。聚合只
     * 落位，无清位路径（呈现隐藏由派生读承担）。
     */
    public void markForkPending() {
        this.forkState = ForkState.PENDING;
    }

    /**
     * 首个构建派发点火置位（#542）：已置不刷新（一场在途/成功的首建只记首次点火
     * 时点）；对偶 {@link #markGenerated} 的置位语义，两者相差整场生成在途窗口。
     * #604 起终态失败/停止收口清位（{@link #reopenFirstBuildRequest}），重跑可再置。
     */
    public void markFirstBuildRequested() {
        if (generationRequestedAt == null) {
            this.generationRequestedAt = LocalDateTime.now();
        }
    }

    /**
     * 首建点火位复位（#604 失败新语义——明确展示＋重跑即恢复）：终态失败/停止
     * 收口时清 {@code generation_requested_at}——岔路口卡/确认按钮「首建未派发」
     * 呈现条件复现，用户在点火入口重跑即恢复（「继续生成」断点续跑入口随续跑
     * 机器退役；不携错误现场、不自动重跑，ADR-0055 #580-13）。成功收口不清
     * （已生成，入口本就不在场）。优雅关停中断不清位（关停期不落库，ADR-0043
     * ——重启后由 POST /generate 重跑入口恢复）。
     */
    public void reopenFirstBuildRequest() {
        this.generationRequestedAt = null;
    }

    /**
     * 确认用于签约的用户面版本标记移动（#333 起「确认此版本，准备签约」；#629
     * 改义为用户面版本号）：不产生新版本号，标记归本聚合单指针；换选＝指针随移
     * （旧标记自然失效），重复确认同版幂等（时间刷新）。目标版本存在性守卫归应用层
     * （#629 起＝用户面版本序列的 PRJ_028）。
     */
    public void confirmDemoVersion(int versionNo) {
        this.confirmedDemoVersionNo = versionNo;
        this.confirmedDemoAt = LocalDateTime.now();
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
