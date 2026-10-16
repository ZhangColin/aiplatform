package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.aieducenter.aiplatform.business.project.domain.model.ReadinessDimension;

/**
 * 「验收与待确认」章分级解析（#331 待定项两级门，spec #320 实施序③；清单章解析
 * {@link DesignChecklist} 同构先例）：PRD 第七章「验收与待确认」（原「待定项」章
 * 升级）分两级——<b>阻碍核心流程</b>（须补齐才能确认）vs <b>次要未知</b>（保留＋
 * 写明假设）。判定语义归主智能体提示词（prd-writing 技能钉分级写法），本类只在
 * 确认门时点解析为分级条目集＋维度态（对偶 DesignChecklist：不另存正本、随
 * PRD 演进重产）。
 *
 * <p><b>解析口径</b>（与 DesignChecklist 同源的有限宽容）：章标题行（任意层级，
 * 可带中文/阿拉伯序号前缀——#304 同源教训：PRD 技能只钉章节名不钉标题行格式）
 * 定界、下一章止；章内级分组子标题（标题行含「阻碍」→ 阻碍组、含「次要」→
 * 次要组）分组；编号条目行（1. / 1、等）取<b>首行全文</b>作条目，前导
 * 【维度】标注命中 {@link ReadinessDimension} 五维词表则挂维并剥离标注、未命中
 * 保留原文不挂维（条目仍进未解决清单——标注缺失只是维度态不计，门判定不受损）。
 * 维度态按五维固定序聚合：该维有阻碍级缺项→{@link Status#BLOCKING}（灰）、
 * 仅有次要→{@link Status#MINOR}（⚡）、无缺项→{@link Status#READY}（✓）；
 * 「n/5 维度就绪」＝READY 计数（呈现面自数）。</p>
 *
 * <p><b>fail-open</b>（票面口径：解析缺失不阻断确认——拦截尽力而为）：无 PRD、
 * 无该章（含存量项目旧「待定项」章——无分级无维度，解析为空＝放行）、章内
 * 「暂无」、章在但级分组子标题形态漂移（对不上「阻碍/次要」锚）→ 条目全归
 * 次要级（进未解决清单、门放行）。误拦（把能确认的拦住）即用户死锁——门用户
 * 不可绕过；漏拦（多放行一个带缺项的 PRD）可经迭代补救，方向安全。</p>
 */
public record PrdReadiness(List<PendingItem> blocking, List<PendingItem> minor,
        List<DimensionState> dimensions) {

    /** 维度态三值（呈现映射：READY→✓、MINOR→⚡、BLOCKING→灰）。 */
    public enum Status {
        READY, MINOR, BLOCKING
    }

    /** 分级条目（dimension 可空——前导【维度】标注缺失/未命中时只进清单不挂维）。 */
    public record PendingItem(ReadinessDimension dimension, String text) {
    }

    /** 单维聚合态（五维固定序产出，无缺项维度照出 READY——n/5 分母恒五）。 */
    public record DimensionState(ReadinessDimension dimension, Status status) {
    }

    /** 章名（PRD 固定七章节之末——「待定项」章升级形）。 */
    static final String CHAPTER_TITLE = "验收与待确认";

    /** 章内任意层级标题行（章界与级分组锚的候选形态）。 */
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
     * 解析 PRD markdown 的「验收与待确认」章：级分组条目＋五维聚合态。
     * fail-open 面（无 PRD/无该章/形态漂移）产出零阻碍级条目（门放行）＋
     * 全 READY 维度态。
     */
    public static PrdReadiness parse(String prdMarkdown) {
        List<PendingItem> blocking = new ArrayList<>();
        List<PendingItem> minor = new ArrayList<>();
        boolean inChapter = false;
        int chapterLevel = Integer.MAX_VALUE;
        // null=未分组（章标题后直接条目/未识别子节——形态漂移，fail-open 归次要）；
        // 分组锚随子标题切换
        Boolean blockingGroup = null;
        if (prdMarkdown != null && !prdMarkdown.isBlank()) {
            for (String line : prdMarkdown.split("\n", -1)) {
                String trimmed = line.trim();
                if (isChapterHeading(trimmed)) {
                    inChapter = true;
                    chapterLevel = headingLevel(trimmed);
                    blockingGroup = null;
                    continue;
                }
                if (!inChapter) {
                    continue;
                }
                if (ANY_HEADING.matcher(trimmed).matches()) {
                    Optional<Boolean> group = groupOf(trimmed);
                    if (group.isPresent()) {
                        blockingGroup = group.get();
                        continue;
                    }
                    if (headingLevel(trimmed) > chapterLevel) {
                        // 章内未识别子节（分组子标题措辞漂移——fail-open 不止章：
                        // 条目照收归次要，未解决事项不因漂移丢失）
                        blockingGroup = null;
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
                    PendingItem item = toPendingItem(text);
                    if (Boolean.TRUE.equals(blockingGroup)) {
                        blocking.add(item);
                    } else {
                        // 次要组＋未分组（fail-open：形态漂移不产阻碍级——门放行）
                        minor.add(item);
                    }
                }
            }
        }
        return new PrdReadiness(blocking, minor, aggregateDimensions(blocking, minor));
    }

    /** 章标题行判定（章名精确＋行尾锚定）。 */
    private static boolean isChapterHeading(String trimmedLine) {
        return CHAPTER_HEADING.matcher(trimmedLine).matches();
    }

    /** 级分组子标题锚：标题行含「阻碍」→ 阻碍组、含「次要」→ 次要组；空＝非分组标题。 */
    private static Optional<Boolean> groupOf(String headingLine) {
        String text = headingLine.replaceFirst("^#{1,6}\\s*", "");
        if (text.contains("阻碍")) {
            return Optional.of(Boolean.TRUE);
        }
        if (text.contains("次要")) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
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

    /** 五维固定序聚合（阻碍压过次要；未标注条目不进任何维度——仍计未解决清单）。 */
    private static List<DimensionState> aggregateDimensions(List<PendingItem> blocking,
            List<PendingItem> minor) {
        Map<ReadinessDimension, Status> states = new EnumMap<>(ReadinessDimension.class);
        for (ReadinessDimension dimension : ReadinessDimension.values()) {
            states.put(dimension, Status.READY);
        }
        for (PendingItem item : minor) {
            if (item.dimension() != null) {
                states.put(item.dimension(), Status.MINOR);
            }
        }
        for (PendingItem item : blocking) {
            if (item.dimension() != null) {
                states.put(item.dimension(), Status.BLOCKING);
            }
        }
        List<DimensionState> aggregated = new ArrayList<>();
        for (ReadinessDimension dimension : ReadinessDimension.values()) {
            aggregated.add(new DimensionState(dimension, states.get(dimension)));
        }
        return aggregated;
    }
}
