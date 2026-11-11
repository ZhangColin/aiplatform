package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.semgravio.aiplatform.business.project.domain.model.ReadinessDimension;

/**
 * 「验收与待确认」章解析（#331；<b>#462 去门化：阻碍级语义删除</b>——ADR-0039/
 * #450 决议：待定项不设替代门，本章保留为纯呈现面＝记录已做的关键假设与待确认
 * 事项，不再分级拦截；清单章解析 {@link DesignChecklist} 同构先例）：PRD 第七章
 * 「验收与待确认」解析为条目集＋维度态（对偶 DesignChecklist：不另存正本、随
 * PRD 演进重产）。新产 PRD 的技能模板已同步改单级（条目直列、无级分组子标题，
 * prd-writing 技能 #462 起）——存量 PRD 里的「阻碍/次要」级分组子标题仍被容忍
 * 解析（条目照收，不再分级）。
 *
 * <p><b>解析口径</b>（与 DesignChecklist 同源的有限宽容）：章标题行（任意层级，
 * 可带中文/阿拉伯序号前缀——#304 同源教训：PRD 技能只钉章节名不钉标题行格式）
 * 定界、下一章止；章内子标题仅作界标（级分组语义已删——「阻碍」「次要」锚只用于
 * 跳过标题行本身）；编号条目行（1. / 1、等）取<b>首行全文</b>作条目，前导
 * 【维度】标注命中 {@link ReadinessDimension} 五维词表则挂维并剥离标注、未命中
 * 保留原文不挂维（条目仍进未解决清单——标注缺失只是维度态不计）。维度态按五维
 * 固定序聚合：该维有缺项→{@link Status#MINOR}（⚡）、无缺项→{@link Status#READY}
 * （✓）；「n/5 维度就绪」＝READY 计数（呈现面自数）。</p>
 *
 * <p><b>fail-open</b>（票面口径：解析缺失不丢呈现）：无 PRD、无该章（含存量
 * 项目旧「待定项」章）、章内「暂无」、章内子标题形态漂移 → 零条目全就绪。
 * {@code blocking} 列表保留在响应形状里（前端消费面兼容）但去门化后恒空。</p>
 */
public record PrdReadiness(List<PendingItem> blocking, List<PendingItem> minor,
        List<DimensionState> dimensions) {

    /**
     * 维度态三值（呈现映射：READY→✓、MINOR→⚡、BLOCKING→灰）。<b>#462 起
     * BLOCKING 不再产出</b>（阻碍级语义删除）——枚举值随响应形状保留（swagger/
     * 前端词表兼容），解析与聚合永不置位。
     */
    public enum Status {
        READY, MINOR, BLOCKING
    }

    /** 条目（dimension 可空——前导【维度】标注缺失/未命中时只进清单不挂维）。 */
    public record PendingItem(ReadinessDimension dimension, String text) {
    }

    /** 单维聚合态（五维固定序产出，无缺项维度照出 READY——n/5 分母恒五）。 */
    public record DimensionState(ReadinessDimension dimension, Status status) {
    }

    /** 章名（PRD 固定七章节之末——「待定项」章升级形）。 */
    static final String CHAPTER_TITLE = "验收与待确认";

    /** 章内任意层级标题行（章界与子标题界标的候选形态）。 */
    private static final Pattern ANY_HEADING = Pattern.compile("^#{1,6}\\s+.*");

    /**
     * 章标题行的可选序号前缀（中文/阿拉伯序号＋可选分隔符——与 DesignChecklist
     * 同形，#304 序号前缀容忍票先例）。
     */
    private static final String OPTIONAL_CHAPTER_ORDINAL =
            "(?:[一二三四五六七八九十百零〇]+|\\d{1,3})?\\s*[、.．:：)）]?\\s*";

    /** 编号条目行（1. / 1、等，取全行文本）。 */
    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\s*(\\d{1,3})[.、．]\\s*(\\S.*)$");

    /** 条目前导【维度】标注（内容命中五维词表才剥离挂维）。 */
    private static final Pattern DIMENSION_TAG = Pattern.compile("^【([^】]*)】\\s*(\\S.*)$");

    /** 章标题行（章名精确＋行尾锚定，序号前缀容忍；子串形「验收与待确认说明」拒）。 */
    private static final Pattern CHAPTER_HEADING = Pattern.compile("^#{1,6}\\s*"
            + OPTIONAL_CHAPTER_ORDINAL + Pattern.quote(CHAPTER_TITLE) + "\\s*$");

    public PrdReadiness {
        blocking = List.copyOf(blocking);
        minor = List.copyOf(minor);
        dimensions = List.copyOf(dimensions);
    }

    /**
     * 解析 PRD markdown 的「验收与待确认」章：条目清单＋五维聚合态。
     * fail-open 面（无 PRD/无该章/形态漂移）产出零条目＋全 READY 维度态；
     * blocking 恒空（阻碍级语义已删，字段保留兼容读面）。
     */
    public static PrdReadiness parse(String prdMarkdown) {
        List<PendingItem> minor = new ArrayList<>();
        boolean inChapter = false;
        int chapterLevel = Integer.MAX_VALUE;
        if (prdMarkdown != null && !prdMarkdown.isBlank()) {
            for (String line : prdMarkdown.split("\n", -1)) {
                String trimmed = line.trim();
                if (isChapterHeading(trimmed)) {
                    inChapter = true;
                    chapterLevel = headingLevel(trimmed);
                    continue;
                }
                if (!inChapter) {
                    continue;
                }
                if (ANY_HEADING.matcher(trimmed).matches()) {
                    if (headingLevel(trimmed) > chapterLevel) {
                        // 章内子标题＝纯界标（#462：级分组语义删除——存量「阻碍/次要」
                        // 锚只跳过标题行本身，条目不再分级）
                        continue;
                    }
                    break; // 下一章开始（不深于章标题的标题行＝章界）
                }
                var match = NUMBERED_ITEM.matcher(line);
                if (match.find()) {
                    String text = match.group(2).trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    minor.add(toPendingItem(text));
                }
            }
        }
        return new PrdReadiness(List.of(), minor, aggregateDimensions(minor));
    }

    /** 章标题行判定（章名精确＋行尾锚定）。 */
    private static boolean isChapterHeading(String trimmedLine) {
        return CHAPTER_HEADING.matcher(trimmedLine).matches();
    }

    /** 标题行层级（# 数；非标题行不在此判）。 */
    private static int headingLevel(String headingLine) {
        int level = 0;
        while (level < headingLine.length() && headingLine.charAt(level) == '#') {
            level++;
        }
        return level;
    }

    /** 条目成形：前导【维度】标注命中词表则挂维剥离、未命中保留原文不挂维。 */
    private static PendingItem toPendingItem(String text) {
        var tag = DIMENSION_TAG.matcher(text);
        if (tag.matches()) {
            Optional<ReadinessDimension> dimension = ReadinessDimension.byLabel(tag.group(1));
            if (dimension.isPresent()) {
                return new PendingItem(dimension.get(), tag.group(2).trim());
            }
        }
        return new PendingItem(null, text);
    }

    /** 五维固定序聚合（未标注条目不进任何维度——仍计未解决清单）。 */
    private static List<DimensionState> aggregateDimensions(List<PendingItem> minor) {
        Map<ReadinessDimension, Status> states = new EnumMap<>(ReadinessDimension.class);
        for (ReadinessDimension dimension : ReadinessDimension.values()) {
            states.put(dimension, Status.READY);
        }
        for (PendingItem item : minor) {
            if (item.dimension() != null) {
                states.put(item.dimension(), Status.MINOR);
            }
        }
        List<DimensionState> aggregated = new ArrayList<>();
        for (ReadinessDimension dimension : ReadinessDimension.values()) {
            aggregated.add(new DimensionState(dimension, states.get(dimension)));
        }
        return aggregated;
    }
}
