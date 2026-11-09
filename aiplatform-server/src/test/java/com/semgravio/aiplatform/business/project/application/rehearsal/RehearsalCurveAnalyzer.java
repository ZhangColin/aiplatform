package com.semgravio.aiplatform.business.project.application.rehearsal;

import java.time.Instant;
import java.util.List;

/**
 * 单会话曲线分析器（#434 排练的数据面，纯函数）：把 executor 计量行序列（按调用
 * 先后）还原成「上下文增长曲线＋压缩触发事件」报告。压缩链在框架 jar 内、平台侧
 * 无落库锚点（{@code CompactionConfig} 无监听口），观测口径＝单调用上下文
 * （input＋cache_read——对偶 docs/research/2026-11-06-llm-segment-efficiency-profile
 * 的口径）的锯齿判读：持续增长后陡降＝一次压缩触发。
 *
 * @param points 计量行（按调用先后排序）
 */
public final class RehearsalCurveAnalyzer {

    /** 锯齿判读阈值：单调用上下文面积较前调用腰斩（{@code < previous * 0.5}）即判一次触发。 */
    static final double DROP_FRACTION = 0.5;

    private RehearsalCurveAnalyzer() {
    }

    /** 单调用计量点：模型串＋五档用量中的 input/cache_read/output＋时刻。 */
    public record UsagePoint(String model, long inputTokens, long cacheReadTokens,
            long outputTokens, Instant at) {

        /** 单调用上下文面积＝input＋cache_read（全 prompt 重发口径）。 */
        public long contextTokens() {
            return inputTokens + cacheReadTokens;
        }
    }

    /** 一次压缩触发：{@code atIndex}（points 下标，压缩后第一调）与触发前后面积。 */
    public record CompactionEvent(int atIndex, long contextBeforeTokens,
            long contextAfterTokens, Instant at) {
    }

    /** 曲线报告：调用数／首末峰面积／压缩事件清单（按触发先后）。 */
    public record CurveReport(int callCount, long firstContextTokens, long finalContextTokens,
            long peakContextTokens, List<CompactionEvent> compactions) {
    }

    public static CurveReport analyze(List<UsagePoint> points) {
        if (points.isEmpty()) {
            return new CurveReport(0, 0, 0, 0, List.of());
        }
        long first = points.get(0).contextTokens();
        long peak = first;
        long previous = first;
        List<CompactionEvent> compactions = new java.util.ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            long current = points.get(i).contextTokens();
            peak = Math.max(peak, current);
            // 锯齿判读：陡降过半即判压缩触发（压缩＝旧历史被摘要替换、面积腰斩）
            if (current < previous * DROP_FRACTION) {
                compactions.add(new CompactionEvent(i, previous, current, points.get(i).at()));
            }
            previous = current;
        }
        return new CurveReport(points.size(), first, previous, peak, List.copyOf(compactions));
    }
}
