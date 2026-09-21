package com.aieducenter.aiplatform.base.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.agentscope.core.event.TextBlockDeltaEvent;

/**
 * 解说文本切段单点（部件 part-text 的生产内核）：文本增量 → 完整段序列（一段一
 * 文本，非增量）。段切分口径（解说部件「完整段非增量」契约）：
 * <ul>
 *   <li>句读（。！？；…换行及西文对应符号）落定即出段；</li>
 *   <li>文本块变（blockId 变化）先出余段——段与段有序不串；</li>
 *   <li>长度上限切段（时延有界，不等句读）；</li>
 *   <li>来源切换（#95 委派位：执行体 ↔ 子智能体）也先出余段——段带来源归属，不跨
 *       角色串段；</li>
 *   <li>机器语法守卫（#234 叙事段堵漏）：模型以原生工具参数语法（DSML 工具调用标记
 *       族）直接发射、引擎未识别为工具调用的文本，识别即丢弃——不产生解说部件，不
 *       转译、不挪位到其他面。标记起点即段边界（人话余段先出）；标记体吞至外包裹
 *       闭合，无闭合 = 模型脱轨、吞至 run 尾（堵漏优先）；跨增量 split 的标记头按住
 *       不入段（防句读缺位时的硬切 / 边界 drain 漏出残头），后续增量落定成真标记
 *       （进吞段）或死文本（照常出段，不丢字）；</li>
 *   <li>run 收尾 / 挂起 / 步骤与动作边界由调用方 {@link #drain()} 出尾段（幂等，
 *       空白不出段；吞段中无尾段）。</li>
 * </ul>
 */
final class NarrationSegments {

    /** 单段长度上限（字符）：超限即出段，解说时延有界。 */
    private static final int MAX_SEGMENT_LENGTH = 160;

    /** 句读符（段切分依据；中西文并列收全）。 */
    private static final String SENTENCE_ENDERS = "。！？；…!?;\n";

    /**
     * 机器语法起点（DSML 工具调用标记族）：canonical `<｜DSML｜` 与全角竖线连打变体
     * （活体留痕 2026-09-21：`<｜｜DSML｜｜tool_calls`，定案见 #234 票评）——竖线
     * 数自适应，族内同吞。其余语法族（如旧版 tool▁calls 包装）暂不识别——走查再见
     * 到即扩表（触发器）。
     */
    private static final Pattern MACHINE_START = Pattern.compile("<｜+DSML｜+");

    /** 机器语法终点（tool_calls 外包裹闭合）；吞段中找不到即脱轨——吞至 run 尾。 */
    private static final Pattern MACHINE_END = Pattern.compile("</｜+DSML｜+tool_calls\\s*>");

    /**
     * 活标记头（跨增量 split 的标记前缀）：`<` 起、全角竖线与 DSML 字母部分到达——
     * 是 {@link #MACHINE_START} 的真前缀；缓冲尾巴命中即按住不出段，等后续增量落定
     * （成真标记进吞段 / 成死文本照常出段）。
     */
    private static final Pattern LIVE_MARKER_HEAD =
            Pattern.compile("^<(｜+(D(S(M(L(｜*)?)?)?)?)?)?$");

    /** 活标记头扫描窗（字符）：canonical 标记头 7 字符、连打变体更宽，留足余量。 */
    private static final int MARKER_HEAD_WINDOW = 16;

    /** 一段解说：文本 + 来源归属（source 可空 = 执行体自身）。 */
    record Segment(String text, String source) {
    }

    private final StringBuilder text = new StringBuilder();
    private String blockId = "";
    /** 当前缓冲的来源归属（#95：段随来源切换切分，不跨角色串段）。 */
    private String source;
    /** 机器语法吞段标志（#234）：true = 缓冲只装标记体，等外包裹闭合或 run 尾。 */
    private boolean machineSwallowing;

    /** 文本增量 → 落定段（0..n：一次到达的长增量含多句时逐句出段）；source 为该增量来源。 */
    List<Segment> offer(TextBlockDeltaEvent delta, String source) {
        String deltaBlock = nvl(delta.getBlockId());
        List<Segment> segments = new ArrayList<>();
        if (!Objects.equals(source, this.source)) {
            // 来源切换即段边界（执行体 ↔ 子智能体）：先出上一来源余段
            drainInto(segments);
            this.source = source;
        }
        if (!deltaBlock.equals(blockId)) {
            // 块变即段边界：先出上一块余段
            drainInto(segments);
            blockId = deltaBlock;
        }
        text.append(nvl(delta.getDelta()));
        // 机器语法守卫先于句读切段：标记体不成段，人话余段按边界先行落定
        exciseMachineSyntax(segments);
        // 句读落定即逐句出段（含句读即出，不要求恰好收尾在句读上）
        settleSentences(segments);
        if (text.length() >= MAX_SEGMENT_LENGTH) {
            // 超长无句读残留（长句）：硬切整段，时延有界
            drainInto(segments);
        }
        return segments;
    }

    /** 出余段（边界/收尾用，幂等；空白不出段；吞段中缓冲恒空、无尾段）。 */
    List<Segment> drain() {
        List<Segment> segments = new ArrayList<>();
        drainInto(segments);
        return segments;
    }

    // ---------- 内部 ----------

    /**
     * 机器语法段切除（#234）：识别即丢弃，不转译、不挪位。起点即段边界（人话余段
     * 先出，段尾活标记头随标记一并切除——断头残片是机器族不是人话）；标记体吞至
     * 外包裹闭合，闭合后余文继续扫（可能紧跟下一块或人话）。
     */
    private void exciseMachineSyntax(List<Segment> segments) {
        while (true) {
            if (machineSwallowing) {
                Matcher end = MACHINE_END.matcher(text);
                if (!end.find()) {
                    text.setLength(0);
                    return;
                }
                text.delete(0, end.end());
                machineSwallowing = false;
                continue;
            }
            Matcher start = MACHINE_START.matcher(text);
            if (!start.find()) {
                return;
            }
            drainInto(segments, start.start() - liveMarkerHeadLength(text, start.start()));
            text.delete(0, start.end());
            machineSwallowing = true;
        }
    }

    /**
     * 全量出段（句读硬切 / 边界 / 收尾 drain 用）：缓冲尾巴的活标记头按住不出——
     * 残头入段即机器语法漏出，按住等落定（真标记进吞段 / 死文本下拍随人话出）。
     */
    private void drainInto(List<Segment> segments) {
        drainInto(segments, text.length() - liveMarkerHeadLength(text, text.length()));
    }

    /** 限量出段：出 text[0, limit)（空白不出），余文留缓冲。 */
    private void drainInto(List<Segment> segments, int limit) {
        if (limit <= 0) {
            return;
        }
        String out = text.substring(0, limit);
        if (!out.isBlank()) {
            segments.add(new Segment(out, source));
        }
        text.delete(0, limit);
    }

    /** text[0, end) 尾巴上的活标记头长度（0 = 无）：只认末个 `<` 起的尾串。 */
    private static int liveMarkerHeadLength(CharSequence s, int end) {
        int from = Math.max(0, end - MARKER_HEAD_WINDOW);
        for (int i = end - 1; i >= from; i--) {
            if (s.charAt(i) == '<' && LIVE_MARKER_HEAD.matcher(s.subSequence(i, end)).matches()) {
                return end - i;
            }
        }
        return 0;
    }

    /**
     * 逐句出段：每个句读符（含）各成一段（一次到达的长增量含多句时不憋整块——
     * 「句读落定即出段」对粗粒度增量同样成立）；无句读的尾部留在缓冲等下一边界
     * 或收尾 drain。
     */
    private void settleSentences(List<Segment> segments) {
        int start = 0;
        int settled = 0;
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_ENDERS.indexOf(text.charAt(i)) >= 0) {
                String sentence = text.substring(start, i + 1);
                if (!sentence.isBlank()) {
                    segments.add(new Segment(sentence, source));
                }
                start = i + 1;
                settled = start;
            }
        }
        if (settled > 0) {
            text.delete(0, settled);
        }
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
