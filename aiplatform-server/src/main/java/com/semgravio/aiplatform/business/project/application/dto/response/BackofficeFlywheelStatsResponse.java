package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * 后台飞轮三个数统计响应（#643，阶段六-6；父票 #637，ADR-0058）：只读、只看趋势
 * ——每个项目一行，另给有命中／无命中两组的汇总。数据来自命中留痕与事后提炼处理
 * 记录，不新增埋点表；空数据返回零值，不报错。
 *
 * <p>三个数的大白话定义：<b>修改轮数</b>＝第一次生成之后用户面版本 v2、v3……的
 * 条数；<b>没做对轮数</b>＝这些修改轮里事后提炼判为「需求写明了但没做对」的轮数
 * （同一轮有一条以上就算一轮）；<b>命中差异</b>＝有命中项目与无命中项目在以上两个
 * 数上的平均值。不打分、不设目标、不做排名。</p>
 *
 * @param items   当页项目行（项目 id 倒序，新项目在前）
 * @param total   项目总行数（全量，非当页）
 * @param page    页码（1 基，原样回显）
 * @param size    每页条数（原样回显）
 * @param summary 有命中／无命中分组汇总（全量项目口径，不随分页变化）
 */
public record BackofficeFlywheelStatsResponse(
        List<ProjectRow> items,
        long total,
        int page,
        int size,
        Summary summary) {

    public BackofficeFlywheelStatsResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 项目行：项目标识、项目名快照、修改轮数、没做对轮数、命中次数、最近命中时间、
     * 是否有命中。没有版本或没有提炼记录的项目按 0／无命中呈现。
     *
     * @param projectId          项目标识（TSID 十进制字符串）
     * @param projectName        项目名快照（有提炼记录取记录快照，无记录回落当前项目名）
     * @param modificationRounds 修改轮数（版本号大于 1 的版本条数）
     * @param missedRounds       没做对轮数（同一轮多条只算一轮）
     * @param hitCount           命中次数（含命中条目的留痕条数）
     * @param latestHitAt        最近命中时间（从未命中为 null）
     * @param hasHit             是否有命中（至少一条留痕含命中条目；成功 0 条与降级
     *                           都算无命中）
     */
    public record ProjectRow(
            String projectId,
            String projectName,
            int modificationRounds,
            int missedRounds,
            int hitCount,
            Instant latestHitAt,
            boolean hasHit) {
    }

    /**
     * 分组汇总：有命中项目数与无命中项目数；两组各自的平均修改轮数、平均没做对
     * 轮数（组内项目数求和相除；组内零项目时平均值为 0）。
     */
    public record Summary(
            int withHitProjectCount,
            int withoutHitProjectCount,
            double withHitAverageModificationRounds,
            double withHitAverageMissedRounds,
            double withoutHitAverageModificationRounds,
            double withoutHitAverageMissedRounds) {
    }
}
