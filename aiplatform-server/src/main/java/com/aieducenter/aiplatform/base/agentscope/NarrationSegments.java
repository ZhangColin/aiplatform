package com.aieducenter.aiplatform.base.agentscope;

import java.util.ArrayList;
import java.util.List;

import io.agentscope.core.event.TextBlockDeltaEvent;

/**
 * 解说文本切段单点（直播 live-text 与部件 part-text 的共用生产内核，#77 提取）：
 * 文本增量 → 完整段序列（一段一文本，非增量）。段切分口径（CONTEXT.md「直播」）：
 * <ul>
 *   <li>句读（。！？；…换行及西文对应符号）落定即出段；</li>
 *   <li>文本块变（blockId 变化）先出余段——段与段有序不串；</li>
 *   <li>长度上限切段（时延有界，不等句读）；</li>
 *   <li>run 收尾 / 挂起 / 步骤与动作边界由调用方 {@link #drain()} 出尾段（幂等，
 *       空白不出段）。</li>
 * </ul>
 */
final class NarrationSegments {

    /** 单段长度上限（字符）：超限即出段，解说时延有界。 */
    private static final int MAX_SEGMENT_LENGTH = 160;

    /** 句读符（段切分依据；中西文并列收全）。 */
    private static final String SENTENCE_ENDERS = "。！？；…!?;\n";

    private final StringBuilder text = new StringBuilder();
    private String blockId = "";

    /** 文本增量 → 落定段（0..n：一次到达的长增量含多句时逐句出段）。 */
    List<String> offer(TextBlockDeltaEvent delta) {
        String deltaBlock = nvl(delta.getBlockId());
        List<String> segments = new ArrayList<>();
        if (!deltaBlock.equals(blockId)) {
            // 块变即段边界：先出上一块余段
            drainInto(segments);
            blockId = deltaBlock;
        }
        text.append(nvl(delta.getDelta()));
        // 句读落定即逐句出段（含句读即出，不要求恰好收尾在句读上）
        settleSentences(segments);
        if (text.length() >= MAX_SEGMENT_LENGTH) {
            // 超长无句读残留（长句）：硬切整段，时延有界
            drainInto(segments);
        }
        return segments;
    }

    /** 出余段（边界/收尾用，幂等；空白不出段）。 */
    List<String> drain() {
        List<String> segments = new ArrayList<>();
        drainInto(segments);
        return segments;
    }

    // ---------- 内部 ----------

    private void drainInto(List<String> segments) {
        if (text.length() == 0 || text.toString().isBlank()) {
            text.setLength(0);
            return;
        }
        segments.add(text.toString());
        text.setLength(0);
    }

    /**
     * 逐句出段：每个句读符（含）各成一段（一次到达的长增量含多句时不憋整块——
     * 「句读落定即出段」对粗粒度增量同样成立）；无句读的尾部留在缓冲等下一边界
     * 或收尾 drain。
     */
    private void settleSentences(List<String> segments) {
        int start = 0;
        int settled = 0;
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_ENDERS.indexOf(text.charAt(i)) >= 0) {
                String sentence = text.substring(start, i + 1);
                if (!sentence.isBlank()) {
                    segments.add(sentence);
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
