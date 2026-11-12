package com.semgravio.aiplatform.base.workspace.domain.aggregate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;

import com.semgravio.aiplatform.base.workspace.domain.model.EnvironmentUsage;

/**
 * 主机资源观测采样行（{@code wsp_host_observation}，#489 资源面大盘）：主机磁盘＋
 * docker 四类用量（镜像/容器/本地卷/build cache）＋无主资源计数的扫描轮采样快照
 * ——<b>全机一行</b>（单机部署口径，id 恒 {@link #SINGLETON_ID}），带采样时刻
 * （{@code sampledAt}）即新鲜度标注，资源大盘（resources/overview）纯读本表零
 * docker 调用、秒级返回。是「采样的观测」而非实态正本（同
 * {@link WorkspaceObservationSample} 口径）。
 *
 * <p>写口唯一：{@code WorkspaceObservationSamplingAppService}（#487 采样轮末尾
 * 追加主机帧——同一后台旁路面，扩既有采样面而非另立通道）。docker 不可达的轮
 * docker 列如实 NULL（「零」与「缺测」在观测面上必须可分）；主机盘列来自 JVM
 * statfs（无 docker 依赖，随轮照采）。无 FK：主机级观测不锚工作区。复采同行
 * 覆盖（每机一行）。</p>
 *
 * <p>无主计数口径（ADR-0045 准绳）：docker 侧工作区命名资源（在跑 {@code
 * ws-<id>} 容器/{@code vol-ws-<id>} 卷）对注册表全量编号求差——与无主收编判定
 * 同一准绳；docker system df 的「可回收」口径不入本表（判定红线，休眠卷被一并
 * 计入）。</p>
 */
@Entity
@Table(name = "wsp_host_observation")
@Aggregate
@Getter
public class HostObservationSample extends Auditable
        implements AggregateRoot<HostObservationSample, Long> {

    /** 单机一行锚（全机唯一行 id，非 TSID——主机级观测无工作区锚）。 */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 主机盘测量锚路径（平台进程所在文件系统——与 df 对账的路径锚）。 */
    @Column(name = "host_path", length = 1024)
    private String hostPath;

    /** 主机盘总字节（statfs；测不出 NULL 容缺）。 */
    @Column(name = "host_total_bytes")
    private Long hostTotalBytes;

    /** 主机盘可用字节（statfs；测不出 NULL 容缺）。 */
    @Column(name = "host_usable_bytes")
    private Long hostUsableBytes;

    /** 镜像计数（docker system df 采样；docker 不可达 NULL）。 */
    @Column(name = "image_count")
    private Long imageCount;

    /** 镜像体量字节（十进制单位换算，与 docker 显示同基；缺测 NULL）。 */
    @Column(name = "image_size_bytes")
    private Long imageSizeBytes;

    /** 容器计数（缺测 NULL）。 */
    @Column(name = "container_count")
    private Long containerCount;

    /** 容器体量字节（缺测 NULL）。 */
    @Column(name = "container_size_bytes")
    private Long containerSizeBytes;

    /** 本地卷计数（缺测 NULL）。 */
    @Column(name = "volume_count")
    private Long volumeCount;

    /** 本地卷体量字节（缺测 NULL）。 */
    @Column(name = "volume_size_bytes")
    private Long volumeSizeBytes;

    /** build cache 计数（缺测 NULL）。 */
    @Column(name = "build_cache_count")
    private Long buildCacheCount;

    /** build cache 体量字节（缺测 NULL）。 */
    @Column(name = "build_cache_size_bytes")
    private Long buildCacheSizeBytes;

    /** 无主容器计数（在跑 ws-<id> 对注册表求差；缺测 NULL）。 */
    @Column(name = "orphan_container_count")
    private Long orphanContainerCount;

    /** 无主卷计数（vol-ws-<id> 对注册表求差；缺测 NULL）。 */
    @Column(name = "orphan_volume_count")
    private Long orphanVolumeCount;

    /** 采样时刻（新鲜度标注的唯一正本——主机盘与 docker 各列同帧）。 */
    @Column(name = "sampled_at", nullable = false)
    private LocalDateTime sampledAt;

    protected HostObservationSample() {
    }

    private HostObservationSample(Long id) {
        this.id = id;
    }

    /** 全机唯一行的首采（此前无行时）。 */
    public static HostObservationSample first() {
        return new HostObservationSample(SINGLETON_ID);
    }

    /**
     * 覆盖采样（一轮一帧：各列同帧刷新，时刻一并拨新）。docker 面（四类用量＋无主
     * 计数）整由 {@code usage} 塌缩而来——null＝缺测轮，docker 各列如实写 NULL
     * （「零」与「缺测」两态必须可分，旧值不顶包）。
     *
     * @param usage docker 面采样帧（四类用量＋工作区命名清单）；缺测轮为 null
     */
    public HostObservationSample resample(String hostPath, Long hostTotalBytes,
            Long hostUsableBytes, EnvironmentUsage usage,
            Long orphanContainerCount, Long orphanVolumeCount, LocalDateTime sampledAt) {
        this.hostPath = hostPath;
        this.hostTotalBytes = hostTotalBytes;
        this.hostUsableBytes = hostUsableBytes;
        this.imageCount = usage != null ? usage.images().count() : null;
        this.imageSizeBytes = usage != null ? usage.images().sizeBytes() : null;
        this.containerCount = usage != null ? usage.containers().count() : null;
        this.containerSizeBytes = usage != null ? usage.containers().sizeBytes() : null;
        this.volumeCount = usage != null ? usage.localVolumes().count() : null;
        this.volumeSizeBytes = usage != null ? usage.localVolumes().sizeBytes() : null;
        this.buildCacheCount = usage != null ? usage.buildCache().count() : null;
        this.buildCacheSizeBytes = usage != null ? usage.buildCache().sizeBytes() : null;
        this.orphanContainerCount = orphanContainerCount;
        this.orphanVolumeCount = orphanVolumeCount;
        this.sampledAt = sampledAt;
        return this;
    }

    @Override
    public Long getId() {
        return id;
    }
}
