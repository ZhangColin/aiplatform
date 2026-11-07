package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 并行波可观测档案（#419 观测地基批）：fork 合并文件集落档＋宽度遥测留档——
 * 结构化日志单点（同路由决策日志先例，不立表）。纯观测面：装配与解析全是
 * 纯函数（本类单测面），轨道层只喂事实。
 */
@Slf4j
final class ParallelWaveTelemetry {

    /** 单行 JSON 序列化（留档可回放面）：静态单例够用——纯值对象无配置态。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 波形态词表（WaveFacts.mode，同 ModelRoutingAppService 档位来源词表先例）。 */
    public static final String MODE_PARALLEL = "parallel";
    public static final String MODE_SERIAL = "serial";

    private ParallelWaveTelemetry() {
    }

    /**
     * 一片切片的执行时距采样（#419 宽度遥测）：ord＋runId＋起止墙钟（epoch ms）
     * ＋成败。并行波＝fork 片在池上的实际执行窗；单片波＝主工作区串行窗。
     */
    record SliceSpan(int ord, String runId, long startedAtMs, long endedAtMs,
            boolean succeeded) {
    }

    /**
     * 实际并发（#419 调度侧事实）：执行时距半开区间 {@code [start, end)} 的峰值
     * 重叠深度——同刻衔接（前片收尾即后片起跑）不算并发。峰值必在某一片的起跑
     * 时刻达到，逐起跑点数在跑片即得（片数 ≤ 波宽，量级无虞）。
     */
    static int actualConcurrencyOf(List<SliceSpan> spans) {
        int max = 0;
        for (SliceSpan probe : spans) {
            int running = 0;
            for (SliceSpan other : spans) {
                if (other.startedAtMs() <= probe.startedAtMs()
                        && probe.startedAtMs() < other.endedAtMs()) {
                    running++;
                }
            }
            max = Math.max(max, running);
        }
        return max;
    }

    /**
     * 一场波的留档事实（#419 入参束——轨道层拼装、本类装配单点）：
     * {@code mode}＝{@link #MODE_PARALLEL}（多片 runnable fork 同跑）/
     * {@link #MODE_SERIAL}（单片 runnable 主工作区串行退化——续跑可把并行波退化
     * 成此形）；{@code ords}＝本场 runnable 片集；{@code theoreticalWidth}＝依赖图
     * 理论宽度（调度层波宽＝本波全部片数，不随续跑过滤收缩——宽度对照在续跑
     * 场景不失真）；{@code closed}/{@code failed}＝波内收口/失败片集（fork 失败
     * 与合并失败同归失败面）。
     */
    record WaveFacts(Long projectId, String trackRunId, int wave, String mode,
            List<Integer> ords, int theoreticalWidth, long startedAtMs, long endedAtMs,
            List<SliceSpan> slices, List<Integer> closed, List<Integer> failed) {
    }

    /**
     * 一次 fork 合并的留档事实（#419）：{@code files}＝合并文件集（diff b 侧
     * 路径全集）；{@code conflicted}＝机器查命中冲突（转子智能体裁决）；
     * {@code merged}＝合并终局（裁决成功也真）；{@code commit}＝合并成版锚
     * （成版失败 null）。
     */
    record MergeFacts(Long projectId, String trackRunId, int wave, int ord, String slice,
            String runId, List<String> files, boolean conflicted, boolean merged,
            String commit) {
    }

    /**
     * 波档案装配（#419 宽度遥测）：检索键（projectId／轨道锚 runId／波号）＋
     * 宽度对照（依赖图理论宽度 vs 调度实际并发）＋波边界时序（起止墙钟与跨距）
     * ＋逐片执行时距（起止/跨距/成败）＋波内收口/失败面。理论宽度是依赖图口径
     * （本波可跑片数）、实际并发是调度口径（峰值重叠）——两者之差即波栅栏与池
     * 调度的损耗面，第二批编排重构（ready-queue 去波栅栏）的灰度门槛数据。
     */
    static Map<String, Object> wavePayload(WaveFacts facts) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", facts.projectId());
        payload.put("trackRunId", facts.trackRunId());
        payload.put("wave", facts.wave());
        payload.put("mode", facts.mode());
        payload.put("ords", facts.ords());
        payload.put("theoreticalWidth", facts.theoreticalWidth());
        payload.put("actualConcurrency", actualConcurrencyOf(facts.slices()));
        payload.put("startedAtMs", facts.startedAtMs());
        payload.put("endedAtMs", facts.endedAtMs());
        payload.put("durationMs", facts.endedAtMs() - facts.startedAtMs());
        payload.put("slices", facts.slices().stream()
                .map(ParallelWaveTelemetry::sliceSpanPayload).toList());
        payload.put("closed", facts.closed());
        payload.put("failed", facts.failed());
        return payload;
    }

    /** 逐片时距条目：ord＋runId＋起止墙钟＋跨距＋成败。 */
    private static Map<String, Object> sliceSpanPayload(SliceSpan span) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ord", span.ord());
        if (span.runId() != null) {
            payload.put("runId", span.runId());
        }
        payload.put("startedAtMs", span.startedAtMs());
        payload.put("endedAtMs", span.endedAtMs());
        payload.put("durationMs", span.endedAtMs() - span.startedAtMs());
        payload.put("succeeded", span.succeeded());
        return payload;
    }

    /**
     * 合并档案装配（#419 fork 文件集落档）：检索键＋合并文件集（并行安全证据
     * ——同波各片动了哪些文件、跨片重叠面可持续审计）＋冲突与结局＋成版锚。
     */
    static Map<String, Object> mergePayload(MergeFacts facts) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", facts.projectId());
        payload.put("trackRunId", facts.trackRunId());
        payload.put("wave", facts.wave());
        payload.put("ord", facts.ord());
        payload.put("slice", facts.slice());
        payload.put("runId", facts.runId());
        payload.put("files", facts.files());
        payload.put("conflicted", facts.conflicted());
        payload.put("merged", facts.merged());
        if (facts.commit() != null) {
            payload.put("commit", facts.commit());
        }
        return payload;
    }

    /**
     * 波档案留档（#419）：INFO 单行 {@code [parallel-wave] {json}}——标签可 grep、
     * JSON 可回放（按 projectId／trackRunId／wave 检索）。
     */
    static void waveArchive(WaveFacts facts) {
        log.info("[parallel-wave] {}", toJson(wavePayload(facts)));
    }

    /**
     * 合并档案留档（#419）：INFO 单行 {@code [parallel-merge] {json}}——同上可
     * grep 可回放。
     */
    static void mergeArchive(MergeFacts facts) {
        log.info("[parallel-merge] {}", toJson(mergePayload(facts)));
    }

    /** 序列化降级（观测面不断流）：map 值域全是平台拼装的原语/列表，异常仅理论可能。 */
    private static String toJson(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        }
        catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    /**
     * fork diff 的合并文件集（#419 并行安全证据——每片动了哪些文件可持续审计）：
     * 解析统一 diff 的 {@code diff --git a/X b/X} 文件头，取 b 侧（合并后）路径，
     * 去重排序（呈现稳定）。删除断 b 侧同路径（离开树的那份）。
     */
    static List<String> mergedFilesOf(String diff) {
        if (diff == null || diff.isBlank()) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (String line : diff.split("\r?\n", -1)) {
            if (!line.startsWith("diff --git ")) {
                continue;
            }
            String path = bSidePath(line.substring("diff --git ".length()));
            if (path != null && !path.isEmpty()) {
                files.add(path);
            }
        }
        return files.stream().distinct().sorted().toList();
    }

    /**
     * 文件头的 b 侧路径：常态两侧同路径（{@code a/P b/P}，含空格路径按「尾部
     * 对称」精确截取）；rename 形（{@code a/Q b/P}）取最后 {@code " b/"} 之后。
     */
    private static String bSidePath(String body) {
        // 对称形：body = "a/" + P + " b/" + P → 长度推 P，回验尾部一致
        int total = body.length();
        if (total >= 5 && (total - 5) % 2 == 0) {
            int pathLength = (total - 5) / 2;
            String candidate = body.substring(2, 2 + pathLength);
            if (body.substring(2 + pathLength).equals(" b/" + candidate)) {
                return candidate;
            }
        }
        int separator = body.lastIndexOf(" b/");
        if (separator < 0) {
            return null;
        }
        return body.substring(separator + 3);
    }
}
