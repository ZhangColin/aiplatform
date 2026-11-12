package com.semgravio.aiplatform.base.workspace.domain.port;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Collection;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.BinaryExecResult;
import com.semgravio.aiplatform.base.workspace.domain.model.EnvironmentUsage;
import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;
import com.semgravio.aiplatform.base.workspace.domain.model.OrphanSweepReport;
import com.semgravio.aiplatform.base.workspace.domain.model.SnapshotHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;

/**
 * 环境后端端口（CONTEXT.md「环境」六条能力面的 Phase A 子集）：薄接口只归一化，
 * 不塞业务语义。后端可替换：本地 Docker（Docker CLI 子进程）→ 上云 TKE/远端
 * （配置切换适配器，接口不动）。
 *
 * <p>本片实现六条：createWorkspace（单容器 all-in-one 沙箱：容器入口脚本自愈起
 * pg/redis，{@code /workspace/.env} 连接串注入）/ destroyWorkspace（容器→快照→卷
 * 级联清理）/ exec（容器内跑命令取结果）/ exposePort（预览 URL）/
 * startSnapshot + stopSnapshot（#92「查看当时」快照容器：同卷只读 + 数据副本，
 * 用完即销毁）。#170 唤醒底座补两条：容器实态探查（唤醒触发判据，#176 起与
 * 观测面共用 {@link #containerState} 四态直判，布尔探查已删）/ startApp（8081
 * 应用拉起——平台职责）。#173 观测面补两条：
 * containerState（实态一瞥）/ volumeSizeBytes（卷用量）；#485 销毁三清校验补一条
 * 轻量直查 volumeExists（卷在否）。#171 休眠器补一条：
 * hibernate（删容器保卷——唤醒走既有幂等重建）。#172 封存与深度唤醒补三条：
 * packVolume（卷瘦身快照打包）/ restoreVolume（封存包回卷）/ deleteVolume
 * （封存后删卷）。restore（#93 回滚）与 attachResource 按需随各自切片扩。
 * #489 资源面改两条补一条：无主收编两面返回值扩为逐项处置报告（手动清扫面
 * 消费全量、扫描轮消费计数）；environmentUsage（环境用量一瞥——资源大盘的
 * 采样源）。</p>
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
     * 派生工作区副本（#351 切片并行）：从主工作区当前 HEAD 克隆一个可写的 fork
     * 容器——代码（git clone，含 .git 供收口成版）+ node_modules 复用 + PGDATA 数据
     * 副本（独立 pg/redis）+ .env 指向副本库（main 库名——库随数据而来）。返回句柄
     * 与资源清单。并行切片各拿一 fork 隔离同跑、收口 git commit 后按序合并回主工作区。
     * fork 是短命工作区，用完经 {@link #destroyWorkspace} 级联清理。
     */
    WorkspaceProvision forkWorkspace(WorkspaceHandle main, WorkspaceId forkId);

    /**
     * 销毁工作区：级联清理容器 → 数据卷（尽力而为，失败不抛——记录清理由调用方负责）。
     */
    void destroyWorkspace(WorkspaceHandle handle);

    /**
     * 在工作区内执行一条命令，取 stdout/stderr/exitCode。
     */
    ExecResult exec(WorkspaceHandle handle, String command);

    /**
     * 在工作区内执行一条命令，取 stdout 的<strong>原始字节</strong>/stderr/exitCode
     * （#283 图片 raw 直出）：{@link #exec} 的字节形——stdout 不经字符集解释
     * （String 形对二进制有损），读侧专用（无 stdin 面）。同一 docker exec 通道，
     * 退出码语义与错误口径同 {@link #exec}。
     */
    BinaryExecResult execBinary(WorkspaceHandle handle, String command);

    /**
     * 在工作区内执行一条命令，经 stdin 灌入字节（#286 物料上传）：{@link #exec}
     * 的写面——{@code cat > 路径} 形命令的落盘通道（上传字节不经 shell 参数面，
     * 也不落平台文件系统）。stdout 是文本回执（如 stat 字节），读原始字节是
     * {@link #execBinary} 的事。同一 docker exec 通道（{@code -i} 开 stdin），
     * 退出码语义与错误口径同 {@link #exec}。
     */
    ExecResult execWithStdin(WorkspaceHandle handle, String command, byte[] stdin);

    /**
     * 暴露容器端口为可访问的预览 URL（本地 = Docker 端口映射；线上 = Ingress/负载均衡）。
     * 渐进预览口径（#45）：映射置备时已落定、URL 确定，本调用只做探活——应用首起
     * 归 run 执行体（#44「一开工就跑起来」），死而复起归 {@link #startApp}（#170 平台
     * 职责）；探活通过才返回 URL（调用方以此作「应用可访问」判据），短窗未就绪抛
     * WSP_012（待期，非故障）。
     *
     * <p>探针口径（#397，ADR-0034 防线 3）：经网关路径＋浏览器真实头形态（带预览域
     * Origin/Referer/Sec-Fetch 打 {@code /} 断言 2xx/3xx；HTML 引用 {@code /_next/static}
     * 时对一个 chunk 复测）——观察点与用户浏览器一致，「探针 200、真浏览器 403」的
     * 盲区由此补上；网关不可达＝如实待期，不误报绿。实现不再以容器内回环裸 curl
     * 作判据（#397 勘误：回环直连带 Origin 打 {@code /_next} 在网关收口后仍 403——
     * 闸门按请求头不按来源地址）。</p>
     */
    URI exposePort(WorkspaceHandle handle, int containerPort);

    /**
     * 容器实态一瞥（唯一诚实探查，#173 观测面＋#176 唤醒判定共用）：运行中/已停止/
     * 无容器/探查失败（未知）四态如实分示——docker 宕不伪装成全员漂移。只读探查，
     * 不抛。判定与观测各自消费：观测面如实分示给人看；唤醒判定按
     * {@link ContainerState#confidentlyNotRunning()} 行使重建权（UNKNOWN 让路，
     * 下轮收敛——#176：探查失败≠容器不在，盲重建的预清 rm -f 会杀健康容器在途 run）。
     */
    ContainerState containerState(WorkspaceHandle handle);

    /**
     * 卷用量探查（#173 观测面，字节；#487 起消费方＝扫描轮采样，观测读面零
     * docker 调用）：旁路容器 du 全卷——含可重建缓存（存储大头），与封存包口径
     * （{@link #packVolume} 排缓存）有意不同：运营要看的是实际占用。卷不在
     * （封存已删/外部漂移）与探查失败一律返回 null（观测容缺，不抛）。先确认
     * 卷在再起旁路容器（{@code docker run -v} 对缺失卷会自动创建——只读探查
     * 不能有 resurrect 副作用）。
     */
    Long volumeSizeBytes(WorkspaceHandle handle);

    /**
     * 卷在否（#485 销毁三清校验）：{@code docker volume inspect} 直判，存在 =
     * true；不在（已删/外部漂移）与探查失败（daemon 不可达等）一律 false——校验
     * 是观测面，容缺不抛（残留对账兜底归扫描轮/#473 收编通道）。轻量直查，不起
     * 旁路容器（与 {@link #volumeSizeBytes} 的 du 成本有意分工）。
     */
    boolean volumeExists(WorkspaceHandle handle);

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
     * 长窗超时（抛 WSP_012），调用方在自愈异步任务内执行。起服等待用与
     * {@link #exposePort} 同款收口探针（#397 经网关＋浏览器真实头——起服即以
     * 浏览器形态验收）。
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
     * #141 网关化：进 previewnet（别名 {@code snap-{viewId}}、无宿主端口映射）；
     * #397 起，起服等待用与主预览 {@link #exposePort} 同款收口判据（经网关＋浏览器
     * 真实头），起服后返回句柄（含网关子域预览 URL）。
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

    /**
     * 清扫无主工作区容器（#179 触发器，运行期无主兜底；#489 返回值扩为逐项处置
     * 报告）：「记录照删、物理尽力而为」的销毁（{@link #destroyWorkspace} 撞
     * daemon 抖动窗）会把容器留在 Docker 侧而库中无记录——休眠扫描只遍历注册表，
     * 无主容器永久不可见、永不休眠。按命名扫清在跑的 {@code ws-<id>} 主容器
     * （保留集 = 注册表全量编号；快照容器 {@code ws-*-snap-*} 归
     * {@link #sweepSnapshotContainersExcept}，不在本面）。只动启动时刻早于宽限线
     * 的容器——新建资源可能尚在「容器已建、记录未落」窗口（fork 先建容器后落
     * 记录），误杀窗口内容器＝杀在途 run。在册项不动但进报告（跳过原因＝在册）。
     *
     * @param keepWorkspaceIds 注册表全量工作区编号（保留集，含非 DEV 行）
     * @param startedBefore    宽限线：启动时刻早于本线的无主容器才删（调用方时钟）
     * @return 逐项处置报告（扫描轮消费 {@link OrphanSweepReport#sweptCount()}）
     */
    OrphanSweepReport sweepOrphanWorkspaceContainers(Collection<Long> keepWorkspaceIds,
            LocalDateTime startedBefore);

    /**
     * 收编无主工作区卷（#486，ADR-0045：#179 容器侧准绳对称扩卷侧；#489 返回值
     * 扩为逐项处置报告）：销毁级联删不动的卷（占卷容器等）留在 Docker 侧而注册行
     * 已删——即无主。按命名点名删保留集外的 {@code vol-ws-<id>}（保留集 = 注册表
     * 全量编号——在册一律不动，含「在册但项目行已删」形态〔归人工/后台处置面〕
     * 与休眠保卷〔ADR-0016 用户数据〕）。只动创建时刻早于宽限线的卷（与容器侧
     * 同一 10 分钟口径——新建资源可能尚在「卷已建、记录未落」窗口，fork 先建卷
     * 后落记录）。在册项不动但进报告（跳过原因＝在册）。
     *
     * <p>准绳＝注册表唯一正本，绝无 prune 类通配回收（红线）：docker system df
     * 的「可回收」口径把休眠卷一并计入，不可作判定；本面只按保留集求差点名删。
     * docker volume prune 永不进任何自动通道。
     *
     * @param keepWorkspaceIds 注册表全量工作区编号（保留集，含非 DEV 行）
     * @param createdBefore    宽限线：创建时刻早于本线的无主卷才删（调用方时钟）
     * @return 逐项处置报告（扫描轮消费 {@link OrphanSweepReport#sweptCount()}）
     */
    OrphanSweepReport sweepOrphanWorkspaceVolumes(Collection<Long> keepWorkspaceIds,
            LocalDateTime createdBefore);

    /**
     * 环境用量一瞥（#489 资源面大盘的采样源）：docker system df（非 verbose，daemon
     * 侧计数秒级回）四类用量（镜像/容器/本地卷/build cache 的计数与体量）＋工作区
     * 命名资源清单（在跑 {@code ws-<id>} 容器与 {@code vol-ws-<id>} 卷的编号——
     * 无主计数＝清单对注册表求差，由调用方判定）。只读探查：system df 的「可回收」
     * 口径不入本面（判定红线，休眠卷被一并计入不可作准绳）。
     *
     * <p>docker CLI 自身失败（daemon 不可达等）抛环境操作失败——调用方（采样轮/
     * 手动清扫）容错或呈报，不吞成「零资源」假象。</p>
     */
    EnvironmentUsage environmentUsage();
}
