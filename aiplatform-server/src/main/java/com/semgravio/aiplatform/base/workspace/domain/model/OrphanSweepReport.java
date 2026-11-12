package com.semgravio.aiplatform.base.workspace.domain.model;

import java.time.LocalDateTime;
import java.util.List;

import com.cartisan.core.domain.BaseEnum;

/**
 * 无主收编逐项处置报告（#489 手动清扫面；#486/#179 收编面同一实现的返回形）：
 * 进了收编面的每项主命名资源（{@code ws-<id>} 容器／{@code vol-ws-<id>} 卷）各自
 * 落一项——收编了什么、跳过什么、为什么（跳过原因：在册〔保留集准绳〕/宽限窗内
 * 〔新建窗口保护〕/时刻解析不出〔无从判宽限，宁可漏删〕）。「收编结果」由报告
 * 承载：扫描轮消费 {@link #sweptCount()} 记日志，后台手动清扫面（resources/sweep）
 * 呈现全量逐项。快照容器（{@code ws-*-snap-*}）与非工作区命名不在本面、不进报告
 * （无主面只及工作区命名资源）。
 *
 * <p>判定与在 {@code DockerEnvironmentBackend} 的收编实现里逐项同生（清单求差、
 * 宽限线、点名删同 #486 准绳）；准绳红线不变：注册表唯一正本、点名删、无 prune
 * 类通配，「可回收」口径永不进判定。</p>
 *
 * @param items 逐项处置（主命名资源全量；顺序＝docker 清单序）
 */
public record OrphanSweepReport(List<Item> items) {

    /** 资源类别：无主面只及容器/卷两类工作区命名资源（容器先卷后——占卷关系）。 */
    public enum Kind implements BaseEnum<Kind> {
        CONTAINER(1, "容器"),
        VOLUME(2, "卷");

        private final Integer code;
        private final String name;

        Kind(Integer code, String name) {
            this.code = code;
            this.name = name;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /**
     * 处置结论：SWEPT＝已收编（点名删除成功）；SKIPPED＝跳过不动（{@link SkipReason}
     * 给因）；FAILED＝删除未成（docker rm 失败——占卷容器等，如实报败不虚报收编，
     * 下轮扫描再收）。
     */
    public enum Disposition implements BaseEnum<Disposition> {
        SWEPT(1, "已收编"),
        SKIPPED(2, "跳过"),
        FAILED(3, "删除未成");

        private final Integer code;
        private final String name;

        Disposition(Integer code, String name) {
            this.code = code;
            this.name = name;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /** 跳过原因（#489 AC：宽限窗/在册/〔时刻解析不出〕）。 */
    public enum SkipReason implements BaseEnum<SkipReason> {

        /** 在册：保留集（注册表全量编号）内——休眠保卷是用户数据（ADR-0016）。 */
        REGISTERED(1, "在册"),

        /** 宽限窗内：创建/启动晚于宽限线——「资源已建、记录未落」窗口保护。 */
        GRACE_WINDOW(2, "宽限窗内"),

        /** 时刻解析不出：docker 回执不可判宽限——宁可漏删不误杀窗口内新建资源。 */
        TIMESTAMP_UNREADABLE(3, "时刻解析不出");

        private final Integer code;
        private final String name;

        SkipReason(Integer code, String name) {
            this.code = code;
            this.name = name;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /**
     * @param kind             资源类别
     * @param identifier       资源名（与 docker 对账的锚点：ws-<id>/vol-ws-<id>）
     * @param disposition      处置结论
     * @param skipReason       跳过原因（SWEPT/FAILED 为 null）
     * @param resourceCreatedAt docker 回执的启动（容器）/创建（卷）时刻——判宽限所用
     *                          值；在册项不起 inspect 恒 null、解析不出亦 null
     */
    public record Item(Kind kind, String identifier, Disposition disposition,
            SkipReason skipReason, LocalDateTime resourceCreatedAt) {
    }

    public OrphanSweepReport {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 已收编数（扫描轮日志口径——原 int 返回值的等价物）。 */
    public int sweptCount() {
        return countOf(Disposition.SWEPT);
    }

    /** 单类已收编数（容器/卷分面计数——回执与留痕行的计数一源）。 */
    public int sweptCountOf(Kind kind) {
        return (int) itemsOf(kind).stream()
                .filter(item -> item.disposition() == Disposition.SWEPT).count();
    }

    /** 跳过数（在册/宽限窗/时刻解析不出——逐项明细在 items）。 */
    public int skippedCount() {
        return countOf(Disposition.SKIPPED);
    }

    /** 删除未成数（点名删失败——下轮扫描再收）。 */
    public int failedCount() {
        return countOf(Disposition.FAILED);
    }

    /** 单类资源的逐项切片（容器/卷分面呈现）。 */
    public List<Item> itemsOf(Kind kind) {
        return items.stream().filter(item -> item.kind() == kind).toList();
    }

    private int countOf(Disposition disposition) {
        return (int) items.stream()
                .filter(item -> item.disposition() == disposition).count();
    }
}
