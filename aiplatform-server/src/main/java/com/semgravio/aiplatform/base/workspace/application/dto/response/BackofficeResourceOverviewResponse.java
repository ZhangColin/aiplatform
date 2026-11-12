package com.semgravio.aiplatform.base.workspace.application.dto.response;

import java.time.LocalDateTime;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.HostObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceSweepAction;

/**
 * 后台资源大盘（#489，GET /api/backoffice/resources/overview）：主机磁盘＋docker
 * 四类用量（镜像/容器/本地卷/build cache）＋无主资源计数＋在册失联计数——全部
 * 来自扫描轮采样落库（{@code wsp_host_observation} 全机一行）与失联标记表
 * （{@code wsp_lost_marks}），<b>纯读库零 docker 调用</b>、秒级返回。新鲜度由
 * {@code sampledAt} 标注（未采样的首个扫描窗内各面如实 null——「没有帧」≠「零」；
 * docker 不可达轮 docker 面各列为 null——「缺测」≠「零」，两态在观测面上必须
 * 可分）。另带最近一次手动清扫（收编结果面——{@code wsp_sweep_actions} 留痕行）。
 *
 * <p>无主计数口径（ADR-0045 准绳）：docker 侧工作区命名资源对注册表全量求差；
 * docker system df 的「可回收」口径不进本面（休眠卷被一并计入，不可作准绳）。</p>
 *
 * @param host               主机盘面（null＝从未采样）
 * @param docker             docker 四类用量面（null＝从未采样；列内 null＝缺测轮）
 * @param orphans            无主资源计数面（null＝从未采样；缺测轮列 null）
 * @param lostWorkspaceCount 在册失联工作区计数（项目行已删而物理仍在，只标记待裁决）
 * @param sampledAt          采样时刻（新鲜度标注；未采样 null）
 * @param lastSweep          最近一次手动清扫（从未手动清扫为 null）
 */
public record BackofficeResourceOverviewResponse(
        HostFace host,
        DockerFace docker,
        OrphanFace orphans,
        long lostWorkspaceCount,
        LocalDateTime sampledAt,
        LastSweep lastSweep) {

    /**
     * 主机盘面：平台进程所在文件系统（{@code hostPath}＝与 df 对账的路径锚）。
     *
     * @param path        测量锚路径
     * @param totalBytes  总字节（statfs）
     * @param usableBytes 可用字节（statfs）
     * @param usedBytes   已用字节（total−usable 派生；任一缺测为 null）
     */
    public record HostFace(String path, Long totalBytes, Long usableBytes, Long usedBytes) {
    }

    /**
     * docker 四类用量面（计数/体量字节——体量按十进制单位换算，与 docker 自身
     * 显示同基）。
     */
    public record DockerFace(
            Long imageCount, Long imageSizeBytes,
            Long containerCount, Long containerSizeBytes,
            Long volumeCount, Long volumeSizeBytes,
            Long buildCacheCount, Long buildCacheSizeBytes) {
    }

    /** 无主资源计数面（在跑 {@code ws-<id>} 容器/{@code vol-ws-<id>} 卷对注册表求差）。 */
    public record OrphanFace(Long containerCount, Long volumeCount) {
    }

    /** 最近一次手动清扫（留痕行直读——大盘的「收编结果」面）。 */
    public record LastSweep(
            LocalDateTime sweptAt,
            String operatorId, String operatorName,
            Boolean registryReachable,
            Integer sweptContainers, Integer sweptVolumes,
            Integer skippedCount, Integer failedCount) {
    }

    /** 采样帧＋失联计数＋最近清扫留痕 → 大盘响应（帧可缺——未采样各面 null）。 */
    public static BackofficeResourceOverviewResponse of(HostObservationSample sample,
            long lostWorkspaceCount, WorkspaceSweepAction lastSweep) {
        return new BackofficeResourceOverviewResponse(
                sample != null ? new HostFace(sample.getHostPath(),
                        sample.getHostTotalBytes(), sample.getHostUsableBytes(),
                        usedBytesOf(sample)) : null,
                sample != null ? new DockerFace(
                        sample.getImageCount(), sample.getImageSizeBytes(),
                        sample.getContainerCount(), sample.getContainerSizeBytes(),
                        sample.getVolumeCount(), sample.getVolumeSizeBytes(),
                        sample.getBuildCacheCount(), sample.getBuildCacheSizeBytes()) : null,
                sample != null ? new OrphanFace(
                        sample.getOrphanContainerCount(), sample.getOrphanVolumeCount()) : null,
                lostWorkspaceCount,
                sample != null ? sample.getSampledAt() : null,
                lastSweep != null ? new LastSweep(
                        lastSweep.getSweptAt(),
                        lastSweep.getOperatorId(), lastSweep.getOperatorName(),
                        lastSweep.isRegistryReachable(),
                        lastSweep.getSweptContainers(), lastSweep.getSweptVolumes(),
                        lastSweep.getSkippedCount(), lastSweep.getFailedCount()) : null);
    }

    private static Long usedBytesOf(HostObservationSample sample) {
        if (sample.getHostTotalBytes() == null || sample.getHostUsableBytes() == null) {
            return null;
        }
        return sample.getHostTotalBytes() - sample.getHostUsableBytes();
    }
}
