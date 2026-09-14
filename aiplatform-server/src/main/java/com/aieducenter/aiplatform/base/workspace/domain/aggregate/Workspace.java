package com.aieducenter.aiplatform.base.workspace.domain.aggregate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;

import cn.hutool.core.collection.CollUtil;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;

import com.aieducenter.aiplatform.base.workspace.domain.entity.MiddlewareResource;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;

/**
 * 工作区聚合根（{@code wsp_workspaces}）：环境后端句柄的持久化形态。
 *
 * <p>置备状态机（CONTEXT.md「置备状态」）：{@code PROVISIONING}（置备中）
 * ——成功回填资源→ {@code READY}；——失败级联回滚→ {@code FAILED}（带失败原因）。
 * {@code FAILED} 不可直接回 {@code READY}——需 {@link #retry()} 先回到
 * {@code PROVISIONING}（重试清空失败原因）再经 {@link #complete(WorkspaceProvision)}。
 * 注册 = 环境后端把真实副作用（容器/中间件）落定后，将句柄与资源清单记录入库；
 * 销毁 = 级联清理物理资源后删除记录。生命周期与记录同生共死，不软删除
 * （Auditable 只取审计字段）。重启接回 = {@link #toHandle()} 从记录重建运行时句柄。
 * 预览网关化（#128）后无宿主端口——预览 URL 是 workspaceId 子域，不落库。</p>
 *
 * <p>ID 显式赋值（workspaceId 先于副作用存在——容器/库命名要用它），
 * 主键即 {@link WorkspaceId} 的数值形（TSID）。containerName 按
 * {@link WorkspaceNaming} 确定性派生（与 docker 后端同源），networkName 即共享
 * 预览网络 {@link WorkspaceNaming#PREVIEW_NETWORK}。</p>
 */
@Entity
@Table(name = "wsp_workspaces")
@Aggregate
@Getter
public class Workspace extends Auditable implements AggregateRoot<Workspace, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "kind", nullable = false, updatable = false)
    private EnvKind kind;

    @Column(name = "container_name", nullable = false, updatable = false)
    private String containerName;

    @Column(name = "network_name", nullable = false, updatable = false)
    private String networkName;

    @Column(name = "provisioning_status", nullable = false)
    private ProvisioningStatus status;

    @Column(name = "provision_error")
    private String provisionError;

    /**
     * 期望态（ADR-0016 意图/实态分离）：DB 只记意图，不镜像 docker 实态——
     * 变更方是休眠器/封存（后续票），唤醒编排以容器实态探查为准，不读它决策。
     */
    @Column(name = "desired_state", nullable = false)
    private DesiredState desiredState = DesiredState.RUNNING;

    /** 最近触碰（#170）：项目域 API 每次触碰拨动，闲置计时（#171 休眠器）的输入。 */
    @Column(name = "last_touch_at", nullable = false)
    private LocalDateTime lastTouchAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "workspace_id", nullable = false)
    private final Set<MiddlewareResource> resources = CollUtil.newLinkedHashSet();

    protected Workspace() {
    }

    private Workspace(WorkspaceId workspaceId, EnvKind kind, String containerName,
                      String networkName, ProvisioningStatus status) {
        if (workspaceId == null || kind == null || containerName == null || containerName.isBlank()
                || networkName == null || networkName.isBlank() || status == null) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_FIELDS_INCOMPLETE);
        }
        this.id = workspaceId.id();
        this.kind = kind;
        this.containerName = containerName;
        this.networkName = networkName;
        this.status = status;
        // 创建即活跃：last-touch 起点与记录同生（NOT NULL 列，构造内落定）
        this.lastTouchAt = LocalDateTime.now();
    }

    /**
     * 注册 dev 工作区（环境后端 createWorkspace 落定副作用后调用，带句柄命名锚点 workspaceId）。
     */
    public static Workspace dev(WorkspaceId workspaceId, String containerName, String networkName) {
        return new Workspace(workspaceId, EnvKind.DEV, containerName, networkName,
                ProvisioningStatus.READY);
    }

    /**
     * 注册 runtime 工作区（test/prod 纯运行占位，无端口；供给能力随后续切片落位）。
     */
    public static Workspace runtime(WorkspaceId workspaceId, EnvKind kind,
                                    String containerName, String networkName) {
        if (kind == EnvKind.DEV) {
            // 工厂误用（编程错误），非用户可触发的领域规则
            throw new IllegalArgumentException("dev 工作区走 Workspace.dev 注册");
        }
        return new Workspace(workspaceId, kind, containerName, networkName,
                ProvisioningStatus.READY);
    }

    /**
     * 从环境供给注册工作区（副作用落定后，句柄 + 资源清单一并入库，直接 READY）。
     */
    public static Workspace register(WorkspaceProvision provision) {
        WorkspaceHandle handle = provision.handle();
        Workspace workspace = handle.kind() == EnvKind.DEV
                ? dev(handle.workspaceId(), handle.containerName(), handle.networkName())
                : runtime(handle.workspaceId(), handle.kind(), handle.containerName(),
                        handle.networkName());
        workspace.attachResources(provision);
        return workspace;
    }

    /**
     * 登记置备中的工作区（异步化入口）：确定性命名落位，等待后台 docker 置备
     * 完成后经 {@link #complete(WorkspaceProvision)} 回填资源转 READY。
     */
    public static Workspace registerPending(WorkspaceId workspaceId, EnvKind kind) {
        return new Workspace(workspaceId, kind,
                WorkspaceNaming.containerName(workspaceId),
                WorkspaceNaming.PREVIEW_NETWORK,
                ProvisioningStatus.PROVISIONING);
    }

    /**
     * 置备完成回填（PROVISIONING → READY）：中间件资源回填。供给的句柄必须对应
     * 本工作区（同 id / 同 kind）。{@code FAILED} 不可直接转 READY——需先 {@link #retry()}
     * 回到 PROVISIONING（#63）。
     */
    public Workspace complete(WorkspaceProvision provision) {
        if (status != ProvisioningStatus.PROVISIONING) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
        WorkspaceHandle handle = provision.handle();
        if (handle.workspaceId().id() != id || handle.kind() != kind) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
        attachResources(provision);
        this.status = ProvisioningStatus.READY;
        return this;
    }

    /**
     * 置备失败回滚（PROVISIONING → FAILED）：记录标记失败态并落失败原因（归一化错误码
     * + 文案，失败呈现与阻塞依据），物理资源回收归调用方（#57 级联回滚
     * 口径）。仅 PROVISIONING 可转 FAILED。
     */
    public Workspace markFailed(String reason) {
        if (status != ProvisioningStatus.PROVISIONING) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
        this.provisionError = reason;
        this.status = ProvisioningStatus.FAILED;
        return this;
    }

    /**
     * 重试（FAILED → PROVISIONING，#63）：置备失败的入口——回到置备中并清空失败原因，
     * 后台重新置备成功后经 {@link #complete(WorkspaceProvision)} 转 READY。仅 FAILED
     * 可重试（PROVISIONING / READY 无需也无权重试）。
     */
    public Workspace retry() {
        if (status != ProvisioningStatus.FAILED) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
        this.provisionError = null;
        this.status = ProvisioningStatus.PROVISIONING;
        return this;
    }

    /**
     * 唤醒迁移（#170，READY/FAILED → PROVISIONING）：容器实态已缺失/被杀（#168 型
     * 漂移）时回置备中，走幂等重建路径（删容器保卷、卷内数据原样续用）收敛回 READY。
     * 与 {@link #retry()} 同形但入口不同：retry 是置备失败的手动重试，rewake 是触碰
     * 自愈的自动迁移。PROVISIONING 已在途，无需也无权再迁移。旧资源清单随迁移清出
     * （orphanRemoval 在本事务内先删旧行），complete 事务随后干净回填——否则同事务
     * 先插后删撞 uq_wsp_resources_workspace_kind（唤醒是首次「带旧行 complete」的
     * 路径，活体验收 #170 实证）。
     */
    public Workspace rewake() {
        if (status != ProvisioningStatus.READY && status != ProvisioningStatus.FAILED) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
        this.provisionError = null;
        this.resources.clear();
        this.status = ProvisioningStatus.PROVISIONING;
        return this;
    }

    /**
     * 拨动 last-touch（#170）：项目域 API 每次触碰调用；时刻由调用方传入（纯迁移，
     * 时钟归应用层），闲置计时（#171）以本字段为输入。
     */
    public void markTouched(LocalDateTime at) {
        this.lastTouchAt = at;
    }

    /**
     * 登记随环境供给的中间件资源（幂等：同种类只留最新一条）。
     */
    public void registerResource(MiddlewareResource resource) {
        resources.remove(resource);
        resources.add(resource);
    }

    private void attachResources(WorkspaceProvision provision) {
        provision.resources().forEach(resource -> registerResource(
                new MiddlewareResource(getId(), resource.kind(),
                        resource.containerName(), resource.hostPort(), resource.internalUrl())));
    }

    public Set<MiddlewareResource> getResources() {
        return Collections.unmodifiableSet(resources);
    }

    /**
     * 中性标识（对外寻址键；主键的 TSID 形）。
     */
    public WorkspaceId workspaceId() {
        return new WorkspaceId(id);
    }

    /**
     * 从记录重建运行时句柄（服务重启后接回环境后端操作的唯一入口）。
     */
    public WorkspaceHandle toHandle() {
        return new WorkspaceHandle(workspaceId(), kind, containerName, networkName);
    }
}
