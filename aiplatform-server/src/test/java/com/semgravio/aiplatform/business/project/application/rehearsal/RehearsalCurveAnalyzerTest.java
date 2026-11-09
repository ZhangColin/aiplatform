package com.semgravio.aiplatform.business.project.application.rehearsal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 单会话曲线分析器（#434 排练的数据面）：从 executor 计量行序列还原上下文增长
 * 曲线与压缩触发事件——压缩无落库锚点（压缩链在框架 jar 内，平台侧只有装配），
 * 观测口径＝单调用上下文（input＋cache_read，对偶 LLM 段效率画像报告的口径）
 * 的锯齿判读：持续增长后出现陡降即判一次压缩触发。
 */
class RehearsalCurveAnalyzerTest {

    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    /** 平直曲线（无增长无压缩——空转或快速收口形态）：零压缩事件、峰＝末值。 */
    @Test
    void given_flat_curve_then_no_compaction_and_peak_is_final() {
        List<RehearsalCurveAnalyzer.UsagePoint> points = List.of(
                point(17_000, 1), point(17_500, 2), point(18_000, 3));

        RehearsalCurveAnalyzer.CurveReport report = RehearsalCurveAnalyzer.analyze(points);

        assertThat(report.compactions()).isEmpty();
        assertThat(report.peakContextTokens()).isEqualTo(18_000);
        assertThat(report.finalContextTokens()).isEqualTo(18_000);
        assertThat(report.callCount()).isEqualTo(3);
    }

    /** 锯齿曲线（长会话压缩触发形态）：增长到峰后陡降——一次压缩事件、位置与前后值锚定。 */
    @Test
    void given_growth_then_sharp_drop_then_one_compaction_detected() {
        List<RehearsalCurveAnalyzer.UsagePoint> points = List.of(
                point(100_000, 1), point(200_000, 2), point(380_000, 3),
                point(120_000, 4), // 压缩触发：380K→120K（＜50%）
                point(160_000, 5));

        RehearsalCurveAnalyzer.CurveReport report = RehearsalCurveAnalyzer.analyze(points);

        assertThat(report.compactions()).hasSize(1);
        RehearsalCurveAnalyzer.CompactionEvent event = report.compactions().get(0);
        assertThat(event.atIndex()).isEqualTo(3);
        assertThat(event.contextBeforeTokens()).isEqualTo(380_000);
        assertThat(event.contextAfterTokens()).isEqualTo(120_000);
        assertThat(report.peakContextTokens()).isEqualTo(380_000);
        assertThat(report.finalContextTokens()).isEqualTo(160_000);
    }

    /** 双峰双谷（多次压缩）：事件按触发先后各就各位。 */
    @Test
    void given_two_sawteeth_then_two_compactions_in_order() {
        List<RehearsalCurveAnalyzer.UsagePoint> points = List.of(
                point(300_000, 1), point(100_000, 2),
                point(350_000, 3), point(90_000, 4),
                point(140_000, 5));

        RehearsalCurveAnalyzer.CurveReport report = RehearsalCurveAnalyzer.analyze(points);

        assertThat(report.compactions()).extracting(RehearsalCurveAnalyzer.CompactionEvent::atIndex)
                .containsExactly(1, 3);
        assertThat(report.peakContextTokens()).isEqualTo(350_000);
    }

    /** 微降（缓存抖动/输入面自然收缩，非压缩）：不过半不降判——零事件。 */
    @Test
    void given_shallow_dip_then_no_compaction() {
        List<RehearsalCurveAnalyzer.UsagePoint> points = List.of(
                point(200_000, 1), point(140_000, 2), point(180_000, 3));

        RehearsalCurveAnalyzer.CurveReport report = RehearsalCurveAnalyzer.analyze(points);

        assertThat(report.compactions()).isEmpty();
    }

    private static RehearsalCurveAnalyzer.UsagePoint point(long contextTokens, int minute) {
        // 上下文口径＝input＋cache_read：构造行让两列之和落在指定值
        return new RehearsalCurveAnalyzer.UsagePoint("deepseek-v4-flash-ga-260731",
                contextTokens * 2 / 3, contextTokens - contextTokens * 2 / 3, 800,
                T0.plusSeconds(minute * 60L));
    }
}
