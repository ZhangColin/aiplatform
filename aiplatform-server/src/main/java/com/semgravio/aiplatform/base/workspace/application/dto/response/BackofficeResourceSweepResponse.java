package com.semgravio.aiplatform.base.workspace.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.semgravio.aiplatform.base.workspace.domain.model.OrphanSweepReport;

/**
 * 后台手动清扫回执（#489，POST /api/backoffice/resources/sweep）：无主收编面的
 * 逐项处置报告——收编了什么（SWEPT）、跳过什么（SKIPPED：REGISTERED＝在册/
 * GRACE_WINDOW＝宽限窗内/TIMESTAMP_UNREADABLE＝时刻解析不出）、删除未成什么
 * （FAILED——下轮扫描再收）。与扫描轮自动收编<b>同一实现同一准绳</b>（#486：
 * 注册表全量保留集＋10 分钟宽限双闸、点名删非 prune）。库不可达轮
 * （{@code registryReachable}＝false）一刀未动——准绳无从构成时绝不按「全部
 * 无主」动手，如实呈报跳过原因（库不可达）。操作者两列回显即留痕落库值
 * （append-only 行另落 {@code wsp_sweep_actions}，报告 JSON 全量留档）。
 *
 * @param sweptAt           清扫时刻
 * @param operatorId        操作者标识（admin 侧；缺透传头为 null——落空口径）
 * @param operatorName      操作者昵称（同上）
 * @param registryReachable 注册表可达否（false＝库不可达整体跳过轮）
 * @param graceLine         宽限线（早于本线创建的无主资源才收——新建窗口保护）
 * @param sweptContainers   已收编容器数
 * @param sweptVolumes      已收编卷数
 * @param skippedCount      跳过数
 * @param failedCount       删除未成数
 * @param items             逐项处置（主命名资源全量：容器＋卷）
 */
public record BackofficeResourceSweepResponse(
        LocalDateTime sweptAt,
        String operatorId,
        String operatorName,
        boolean registryReachable,
        LocalDateTime graceLine,
        int sweptContainers,
        int sweptVolumes,
        int skippedCount,
        int failedCount,
        List<SweepItem> items) {

    /**
     * 逐项处置行：kind＝CONTAINER/VOLUME；disposition＝SWEPT/SKIPPED/FAILED；
     * skipReason＝REGISTERED/GRACE_WINDOW/TIMESTAMP_UNREADABLE（SWEPT/FAILED 为
     * null）；resourceCreatedAt＝docker 回执的启动/创建时刻（在册项未起 inspect
     * 恒 null）。命名 {@code SweepItem}（非 {@code Item}）——swagger 组件名以
     * 内嵌 record 简名落全局命名空间，泛型名会被后来者撞号。
     */
    public record SweepItem(String kind, String identifier, String disposition,
            String skipReason, LocalDateTime resourceCreatedAt) {
    }

    /** 域报告＋留痕参数 → 回执（容器/卷两报告合流；计数一源＝域报告的计数面）。 */
    public static BackofficeResourceSweepResponse of(OrphanSweepReport report,
            LocalDateTime sweptAt, String operatorId, String operatorName,
            boolean registryReachable, LocalDateTime graceLine) {
        return new BackofficeResourceSweepResponse(
                sweptAt, operatorId, operatorName, registryReachable, graceLine,
                report.sweptCountOf(OrphanSweepReport.Kind.CONTAINER),
                report.sweptCountOf(OrphanSweepReport.Kind.VOLUME),
                report.skippedCount(), report.failedCount(),
                report.items().stream().map(BackofficeResourceSweepResponse::itemOf).toList());
    }

    private static SweepItem itemOf(OrphanSweepReport.Item item) {
        return new SweepItem(item.kind().name(), item.identifier(), item.disposition().name(),
                item.skipReason() != null ? item.skipReason().name() : null,
                item.resourceCreatedAt());
    }
}
