package com.aieducenter.aiplatform.base.workspace.domain.port;

import java.net.URI;
import java.util.Collection;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.SnapshotHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;

/**
 * 环境后端端口（CONTEXT.md「环境」六条能力面的 Phase A 子集）：薄接口只归一化，
 * 不塞业务语义。后端可替换：本地 Docker（Docker CLI 子进程）→ 上云 TKE/远端
 * （配置切换适配器，接口不动）。
 *
 * <p>本片实现六条：createWorkspace（单容器 all-in-one 沙箱：容器入口脚本自愈起
 * pg/redis，{@code /workspace/.env} 连接串注入）/ destroyWorkspace（容器→快照→卷
 * 级联清理）/ exec（容器内跑命令取结果）/ exposePort（预览 URL）/
 * startSnapshot + stopSnapshot（#92「查看当时」快照容器：同卷只读 + 数据副本，
 * 用完即销毁）。#170 唤醒底座补两条：isContainerRunning（容器实态探查——唤醒
 * 触发判据）/ startApp（8081 应用拉起——平台职责）。#173 观测面补两条：
 * containerState（实态一瞥）/ volumeSizeBytes（卷用量）。#171 休眠器补一条：
 * hibernate（删容器保卷——唤醒走既有幂等重建）。#172 封存与深度唤醒补三条：
 * packVolume（卷瘦身快照打包）/ restoreVolume（封存包回卷）/ deleteVolume
 * （封存后删卷）。restore（#93 回滚）与 attachResource 按需随各自切片扩。</p>
 */
@Port(PortType.CLIENT)
public interface EnvironmentBackend {

    /** 工作区应用服务的约定容器端口（run 执行体起服、平台预览取流量的单一事实，#44）。 */
    int DEV_APP_CONTAINER_PORT = 8081;

    /**
     * 创建工作区并落定全部真实副作用（容器/中间件/.env），返回句柄与资源清单。
     * 幂等倾向：对同名残留容器先清理再建（卷保留，重建即自愈、数据不丢）。
     */
    WorkspaceProvision createWorkspace(WorkspaceId workspaceId, EnvKind kind);

    /**
     * 销毁工作区：级联清理容器 → 数据卷（尽力而为，失败不抛——记录清理由调用方负责）。
     */
    void destroyWorkspace(WorkspaceHandle handle);

    /**
     * 在工作区内执行一条命令，取 stdout/stderr/exitCode。
     */
    ExecResult exec(WorkspaceHandle handle, String command);

    /**
     * 暴露容器端口为可访问的预览 URL（本地 = Docker 端口映射；线上 = Ingress/负载均衡）。
     * 渐进预览口径（#45）：映射置备时已落定、URL 确定，本调用只做探活——应用首起
     * 归 run 执行体（#44「一开工就跑起来」），死而复起归 {@link #startApp}（#170 平台
     * 职责）；探活通过才返回 URL（调用方以此作「应用可访问」判据），短窗未就绪抛
     * WSP_012（待期，非故障）。
     */
    URI exposePort(WorkspaceHandle handle, int containerPort);

    /**
     * 容器实态探查（#170 唤醒触发判据）：容器在且 Running 才 true——不存在、已停止、
     * 被杀（#168 型漂移）一律 false。只读探查，不抛（探查失败视同不在，由唤醒编排
     * 幂等重建收敛）。
     */
    boolean isContainerRunning(WorkspaceHandle handle);

    /**
     * 容器实态一瞥（#173 观测面）：运行中/已停止/无容器/探查失败（未知）——比
     * {@link #isContainerRunning} 细一档：后者服务唤醒判定（失败视同不在的收敛
     * 口径），本方法服务人看（如实分示——探查失败与容器不在区分，docker 宕不
     * 伪装成全员漂移）。只读探查，不抛。
     */
    ContainerState containerState(WorkspaceHandle handle);

    /**
     * 卷用量探查（#173 观测面，字节）：旁路容器 du 全卷——含可重建缓存（存储
     * 大头），与封存包口径（{@link #packVolume} 排缓存）有意不同：运营要看的是
     * 实际占用。卷不在（封存已删/外部漂移）与探查失败一律返回 null（观测容缺，
     * 不抛）。先确认卷在再起旁路容器（{@code docker run -v} 对缺失卷会自动创建
     * ——只读探查不能有 resurrect 副作用）。
     */
    Long volumeSizeBytes(WorkspaceHandle handle);

    /**
     * 休眠（#171，ADR-0016 删容器保卷）：删掉沙箱容器、完整保留卷（数据不动）——
     * 唤醒走既有幂等重建路径。幂等（容器已不在 no-op）、尽力而为（失败不抛——
     * 意图落库与否归编排方，删失败则下轮扫描收敛）。
     */
    void hibernate(WorkspaceHandle handle);

    /**
     * 卷瘦身快照打包（#172 封存前半，ADR-0016）：整卷 tar.gz 字节流——仅排除
     * {@link WorkspaceLayout#REBUILDABLE_CACHE_DIRS 可重建缓存}，数据库（PGDATA）
     * 与全部用户产物随包（与 {@link #packSource} 的交付口径不同：机密/数据不排）。
     * 经临时旁路容器读卷（入口旁路，不起中间件）。卷不在（已删/外部漂移）返回
     * null——调用方按「无包可记」收敛；打包失败抛（意图不翻，下轮重试）。
     * 只在休眠态（容器已删、卷静默）上调用——活卷上的 pg 一致性无保障。
     */
    byte[] packVolume(WorkspaceHandle handle);

    /**
     * 封存包回卷（#172 深度唤醒前半）：重建卷（先删后建——残留/上次失败半解包
     * 干净落位）并解包封存内容、顺手清 PGDATA 陈旧 postmaster.pid（封存自
     * {@code docker rm -f} 的静默卷打包而来，pid 必陈旧；容器 PID 命名空间更迭后
     * 同号进程可能占位，pg 会拒起）。解包失败抛（意图不动，下次触碰再试）。
     * 须在重建容器（createWorkspace）之前调用——卷就位后入口脚本对既有 PGDATA
     * 幂等自愈，数据完整恢复。
     */
    void restoreVolume(WorkspaceHandle handle, byte[] archive);

    /**
     * 删卷（#172 封存后半）：封存包安全落盘后回收卷存储。卷不在为 no-op 返回
     * false；删失败（如仍被容器占用）也返回 false——调用方以返回值观测、下轮
     * 扫描收敛，不抛。
     */
    boolean deleteVolume(WorkspaceHandle handle);

    /**
     * 拉起工作区应用进程至 8081 起服（#170：8081 应用拉起自此是平台职责——run 执行体
     * exec 常驻的应用进程容器重启后无人拉，#168 遗留缺口由唤醒编排统一收口）。
     * 幂等：已在服直接返回；起服入口与快照同款判据（server.js → node server.js、
     * package.json → npm start），无入口则不拉（恢复到未生成态）。阻塞直至起服或
     * 长窗超时（抛 WSP_012），调用方在自愈异步任务内执行。
     */
    void startApp(WorkspaceHandle handle);

    /**
     * 打包工作区源码为 tar.gz 字节流（「取走工作区内容」的能力面：调用方拿去做
     * 下载交付，业务语义归调用方）。实现负责排除平台生成的机密
     * （/workspace/.env 连接串）与可重建的重物（node_modules）——包里是源码
     * 事实，不是环境镜像。
     */
    byte[] packSource(WorkspaceHandle handle);

    /**
     * 起「查看当时」快照容器（#92，ADR 0007 解路二）：同镜像、同工作区卷挂
     * {@code :ro}、入口脚本旁路；平台经 exec 确定性驱动——复制 PGDATA 到容器本地
     * → 清 pid → 起 pg → 起 redis → 检出 {@code ref} 当时代码到容器本地 → 起应用
     * （DATABASE_URL 指容器内 localhost）。数据只落副本、卷只读，主容器零扰动。
     * #141 网关化：进 previewnet（别名 {@code snap-{viewId}}、无宿主端口映射），
     * 探活走容器内回环，起服后返回句柄（含网关子域预览 URL）。
     *
     * @param ref 成版 commit hash（hex，调用方已校验存在性；本层只做命令引用）
     */
    SnapshotHandle startSnapshot(WorkspaceHandle mainHandle, String viewId, String ref);

    /**
     * 销毁快照容器（#92）：{@code docker rm -f}，无销毁协调（副本随容器可写层
     * 消失，卷内零残留）；幂等——容器已不在时为 no-op。
     */
    void stopSnapshot(SnapshotHandle snapshot);

    /**
     * 清扫全部孤儿快照容器（#92 启动自愈）：快照容器是临时视图、不承载持久状态，
     * 平台重启后注册表丢账、在途查看会话即孤儿——启动期按命名扫清所有
     * {@code ws-*-snap-*} 容器，不留常驻孤儿（「不留孤儿容器」验收的兜底面）。
     */
    void sweepSnapshotContainers();

    /**
     * 清扫保留集之外的快照容器（#171 运行期孤儿兜底）：孤儿清扫原只在启动期跑，
     * 运行期同样有漏网——关窗销毁删失败、注册表丢账等——保留集（在用会话容器名）
     * 之外按命名扫清。返回清扫数。
     */
    int sweepSnapshotContainersExcept(Collection<String> keepContainerNames);
}
