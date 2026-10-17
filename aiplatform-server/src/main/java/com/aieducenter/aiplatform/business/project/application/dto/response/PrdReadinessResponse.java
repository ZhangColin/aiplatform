package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.util.List;
import java.util.Locale;

import com.aieducenter.aiplatform.business.project.application.PrdReadiness;

/**
 * PRD 收敛度读面（#331，spec #320 实施序③「收敛度呈现」的解析与判定面——呈现
 * 归 #346）：「验收与待确认」章分级解析的 REST 投影，随草稿（PRD 读端点）与
 * 已确认版本（确认回执/版本详情）暴露。两段式摘要层（#332 呈现面）的未解决
 * 事项数据面＝{@link #minor}；「n/5 维度就绪」＝{@link #dimensions} 中
 * status=ready 的计数（呈现面自数，不冗余计数字段）。
 *
 * @param blocking   阻碍核心流程缺项（有则确认被平台门拦截——此面只读观测，门判定在确认动作）
 * @param minor      次要未知（保留＋写明假设——两段式摘要层的未解决事项源）
 * @param dimensions 五维聚合态固定序（status：ready=✓ / minor=⚡ / blocking=灰）
 */
public record PrdReadinessResponse(
        List<PendingItem> blocking,
        List<PendingItem> minor,
        List<DimensionStatus> dimensions) {

    /** 分级条目（dimension＝生成条件五要素呈现词，可空——条目前导【维度】标注缺失/未命中）。 */
    public record PendingItem(String dimension, String text) {
    }

    /** 单维聚合态（dimension＝五要素呈现词、封闭五值）。 */
    public record DimensionStatus(String dimension, String status) {
    }

    /** 解析器产出的字符串化投影（dimension→呈现词、status→小写稳定串）。 */
    public static PrdReadinessResponse from(PrdReadiness readiness) {
        return new PrdReadinessResponse(
                readiness.blocking().stream()
                        .map(item -> new PendingItem(
                                item.dimension() == null ? null : item.dimension().label(), item.text()))
                        .toList(),
                readiness.minor().stream()
                        .map(item -> new PendingItem(
                                item.dimension() == null ? null : item.dimension().label(), item.text()))
                        .toList(),
                readiness.dimensions().stream()
                        .map(state -> new DimensionStatus(state.dimension().label(),
                                state.status().name().toLowerCase(Locale.ROOT)))
                        .toList());
    }
}
