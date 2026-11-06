package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.LoggerFactory;

/**
 * {@link ParallelWaveTelemetry}（#419 fork 文件集落档＋宽度遥测）：纯函数面——
 * fork diff 的合并文件集解析、执行时距的实际并发扫掠、留档 payload 装配。
 * 轨道级「档案字段齐全」断言在 GenerationAppServiceTest（编排缝）。
 */
class ParallelWaveTelemetryTest {

    /** 常态多文件 diff（改/增/删三形）——b 侧路径全集、按路径稳定排序。 */
    private static final String MULTI_FILE_DIFF = """
            diff --git a/src/app/page.tsx b/src/app/page.tsx
            index 1111111..2222222 100644
            --- a/src/app/page.tsx
            +++ b/src/app/page.tsx
            @@ -1,3 +1,5 @@
             old
            +new
            diff --git a/src/lib/auth.ts b/src/lib/auth.ts
            new file mode 100644
            index 0000000..3333333
            --- /dev/null
            +++ b/src/lib/auth.ts
            @@ -0,0 +1,2 @@
            +export const x = 1;
            diff --git a/legacy/api.ts b/legacy/api.ts
            deleted file mode 100644
            index 4444444..0000000
            --- a/legacy/api.ts
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -old
            """;

    @Test
    void given_multi_file_diff_when_merged_files_then_b_side_paths_sorted() {
        assertThat(ParallelWaveTelemetry.mergedFilesOf(MULTI_FILE_DIFF)).containsExactly(
                "legacy/api.ts", "src/app/page.tsx", "src/lib/auth.ts");
    }

    @Test
    void given_path_with_space_when_merged_files_then_symmetric_tail_resolves() {
        // 含空格路径（无引号形）：a/P b/P 尾部对称截取——不能按首个 " b/" 切（会把
        // 路径内的 " b/" 误当分隔符）；CRLF 行尾（exec 通道理论外的防呆）不进路径
        String diff = "diff --git a/components/My Card.tsx b/components/My Card.tsx\r\n"
                + "index 1111111..2222222 100644\r\n"
                + "--- a/components/My Card.tsx\r\n"
                + "+++ b/components/My Card.tsx\r\n"
                + "@@ -1 +1 @@\r\n"
                + "-old\r\n";
        assertThat(ParallelWaveTelemetry.mergedFilesOf(diff))
                .containsExactly("components/My Card.tsx");
    }

    @Test
    void given_rename_and_binary_when_merged_files_then_b_side_taken() {
        // rename 形（a/Q b/P 非对称）取 b 侧新路径；二进制无 +++ 行、文件头在场即收
        String diff = """
                diff --git a/old.ts b/new.ts
                similarity index 90%
                rename from old.ts
                rename to new.ts
                diff --git a/public/logo.png b/public/logo.png
                index 1111111..2222222 100644
                Binary files a/public/logo.png and b/public/logo.png differ
                """;
        assertThat(ParallelWaveTelemetry.mergedFilesOf(diff))
                .containsExactly("new.ts", "public/logo.png");
    }

    @Test
    void given_blank_diff_when_merged_files_then_empty() {
        // no-op 合并（fork 无改动）：空文件集如实
        assertThat(ParallelWaveTelemetry.mergedFilesOf("")).isEmpty();
        assertThat(ParallelWaveTelemetry.mergedFilesOf(null)).isEmpty();
    }

    @Test
    void given_disjoint_spans_when_actual_concurrency_then_one() {
        // 串行衔接（前片收尾即后片起跑）：半开区间 [start, end)——同刻衔接不算并发
        List<ParallelWaveTelemetry.SliceSpan> spans = List.of(
                new ParallelWaveTelemetry.SliceSpan(2, "r2", 100, 200, true),
                new ParallelWaveTelemetry.SliceSpan(3, "r3", 200, 300, true));
        assertThat(ParallelWaveTelemetry.actualConcurrencyOf(spans)).isEqualTo(1);
    }

    @Test
    void given_overlapping_spans_when_actual_concurrency_then_max_overlap() {
        // 三片两两错峰：[0,10) [5,20) [15,30) → 峰值 2（1/2 重叠、2/3 重叠）
        List<ParallelWaveTelemetry.SliceSpan> spans = List.of(
                new ParallelWaveTelemetry.SliceSpan(2, "r2", 0, 10, true),
                new ParallelWaveTelemetry.SliceSpan(3, "r3", 5, 20, true),
                new ParallelWaveTelemetry.SliceSpan(4, "r4", 15, 30, false));
        assertThat(ParallelWaveTelemetry.actualConcurrencyOf(spans)).isEqualTo(2);
    }

    @Test
    void given_nested_spans_or_none_when_actual_concurrency_then_depth_or_zero() {
        // 全包含：峰值为外层起跑时刻的深度；空集（单片波前的空采样）＝0
        List<ParallelWaveTelemetry.SliceSpan> nested = List.of(
                new ParallelWaveTelemetry.SliceSpan(2, "r2", 0, 100, true),
                new ParallelWaveTelemetry.SliceSpan(3, "r3", 10, 90, true),
                new ParallelWaveTelemetry.SliceSpan(4, "r4", 20, 80, true));
        assertThat(ParallelWaveTelemetry.actualConcurrencyOf(nested)).isEqualTo(3);
        assertThat(ParallelWaveTelemetry.actualConcurrencyOf(List.of())).isZero();
    }

    // ---------- 留档装配（#419：波档案＋合并档案的字段契约） ----------

    @Test
    void given_wave_facts_when_wave_payload_then_fields_complete() {
        // 波档案字段契约：检索键（projectId/trackRunId/wave）＋宽度对照（理论宽度
        // vs 实际并发）＋波边界时序（起止墙钟与跨距）＋逐片时距＋波内收口/失败面
        List<ParallelWaveTelemetry.SliceSpan> spans = List.of(
                new ParallelWaveTelemetry.SliceSpan(2, "r2", 1_000, 4_000, true),
                new ParallelWaveTelemetry.SliceSpan(3, "r3", 2_000, 3_000, false));
        Map<String, Object> payload = ParallelWaveTelemetry.wavePayload(
                new ParallelWaveTelemetry.WaveFacts(376L, "track-run", 1, "parallel",
                        List.of(2, 3), 2, 1_000, 4_500, spans, List.of(2), List.of(3)));
        assertThat(payload).containsOnlyKeys("projectId", "trackRunId", "wave", "mode",
                "ords", "theoreticalWidth", "actualConcurrency", "startedAtMs", "endedAtMs",
                "durationMs", "slices", "closed", "failed");
        assertThat(payload.get("projectId")).isEqualTo(376L);
        assertThat(payload.get("trackRunId")).isEqualTo("track-run");
        assertThat(payload.get("wave")).isEqualTo(1);
        assertThat(payload.get("mode")).isEqualTo("parallel");
        assertThat(payload.get("ords")).isEqualTo(List.of(2, 3));
        assertThat(payload.get("theoreticalWidth")).isEqualTo(2);
        assertThat(payload.get("actualConcurrency")).isEqualTo(2);
        assertThat(payload.get("startedAtMs")).isEqualTo(1_000L);
        assertThat(payload.get("endedAtMs")).isEqualTo(4_500L);
        assertThat(payload.get("durationMs")).isEqualTo(3_500L);
        assertThat(payload.get("closed")).isEqualTo(List.of(2));
        assertThat(payload.get("failed")).isEqualTo(List.of(3));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slices = (List<Map<String, Object>>) payload.get("slices");
        assertThat(slices).hasSize(2);
        assertThat(slices.get(0)).containsOnlyKeys("ord", "runId", "startedAtMs",
                "endedAtMs", "durationMs", "succeeded");
        assertThat(slices.get(0).get("durationMs")).isEqualTo(3_000L);
    }

    @Test
    void given_merge_facts_when_merge_payload_then_fields_complete() {
        // 合并档案字段契约：检索键＋文件集（并行安全证据）＋冲突与结局＋成版锚
        Map<String, Object> payload = ParallelWaveTelemetry.mergePayload(
                new ParallelWaveTelemetry.MergeFacts(376L, "track-run", 1, 2, "登录",
                        "r2", List.of("src/app/page.tsx", "src/lib/auth.ts"),
                        /* conflicted= */ false, /* merged= */ true, "abc123"));
        assertThat(payload).containsOnlyKeys("projectId", "trackRunId", "wave", "ord",
                "slice", "runId", "files", "conflicted", "merged", "commit");
        assertThat(payload.get("files"))
                .isEqualTo(List.of("src/app/page.tsx", "src/lib/auth.ts"));
        assertThat(payload.get("conflicted")).isEqualTo(false);
        assertThat(payload.get("merged")).isEqualTo(true);
        assertThat(payload.get("commit")).isEqualTo("abc123");
    }

    @Test
    void given_archives_when_emit_then_single_line_json_with_tag() throws Exception {
        // 留档形态：INFO 单行、检索标签打头、正文可回放（JSON 可解析回同构 map
        // ——按 run／波检索回放的档案口径）
        Logger logger = (Logger) LoggerFactory.getLogger(ParallelWaveTelemetry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ParallelWaveTelemetry.waveArchive(new ParallelWaveTelemetry.WaveFacts(376L,
                    "track-run", 1, ParallelWaveTelemetry.MODE_PARALLEL, List.of(2, 3), 2,
                    1_000, 4_500,
                    List.of(new ParallelWaveTelemetry.SliceSpan(2, "r2", 1_000, 4_000, true),
                            new ParallelWaveTelemetry.SliceSpan(3, "r3", 2_000, 4_500, true)),
                    List.of(2, 3), List.of()));
            ParallelWaveTelemetry.mergeArchive(new ParallelWaveTelemetry.MergeFacts(376L,
                    "track-run", 1, 2, "登录", "r2", List.of("src/app/page.tsx"),
                    false, true, null));
        }
        finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getLevel()).isEqualTo(Level.INFO));
        ObjectMapper json = new ObjectMapper();

        String waveLine = appender.list.get(0).getFormattedMessage();
        assertThat(waveLine).startsWith("[parallel-wave] ");
        @SuppressWarnings("unchecked")
        Map<String, Object> wavePayload = json.readValue(
                waveLine.substring("[parallel-wave] ".length()), Map.class);
        assertThat(wavePayload).containsEntry("trackRunId", "track-run").containsEntry("wave", 1)
                .containsEntry("theoreticalWidth", 2).containsEntry("actualConcurrency", 2);

        String mergeLine = appender.list.get(1).getFormattedMessage();
        assertThat(mergeLine).startsWith("[parallel-merge] ");
        @SuppressWarnings("unchecked")
        Map<String, Object> mergePayload = json.readValue(
                mergeLine.substring("[parallel-merge] ".length()), Map.class);
        assertThat(mergePayload).containsEntry("ord", 2)
                .containsEntry("files", List.of("src/app/page.tsx"))
                .containsEntry("merged", true)
                .doesNotContainKey("commit");
    }
}
