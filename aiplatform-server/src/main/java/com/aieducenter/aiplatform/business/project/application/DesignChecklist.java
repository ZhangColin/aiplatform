package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.model.DesignScope;

/**
 * 设计清单（#289 首产推进序的清单源，ADR-0024/0025「防两处正本」）：设计主线
 * 的计划对应物＝PRD「设计物清单」章、系统＋设计的计划对应物＝PRD「功能清单」
 * 章按设计范围圈定——清单正本住 PRD（模型独笔演进），本类只在派发时点解析为
 * 有序条目集（随 PRD 落库演进重产，对偶切片计划的生命周期、不另存正本）。
 *
 * <p>解析口径与前端 {@code feature-list.ts}（#285 设置 tab 勾选源）同源：章标题
 * 行（任意层级）定界、下一章止；编号条目行（1. / 1、等）取<b>首行全文</b>作
 * 条目——条目文本是建议性锚不是稳定标识（PRD 演进措辞漂移由重产对照自然
 * 消化，对照不上按待跑重做，降级方向安全）。无 PRD／无该章／无编号条目 → 空
 * 清单（调用侧如实不派发，不造假清单）。</p>
 *
 * <p>系统＋设计的范围圈定（ADR-0025 设计先行）：全部页面（含无范围记录）＝功能
 * 清单全量；勾选形＝清单条目按标签<b>精确匹配</b>过滤（标签＝切换时功能清单条目
 * 原文），对照不上的标签<b>照录不漏做</b>（标签锚漂移时宁可多做——用户勾过的页面
 * 不静默消失），照录条目缀于匹配条目之后。</p>
 */
public record DesignChecklist(List<String> items) {

    /** 章内任意层级标题行（章节边界）。 */
    private static final Pattern ANY_HEADING = Pattern.compile("^#{1,6}\\s+");

    /** 编号条目行（1. / 1、等，取全行文本）。 */
    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\s*(\\d{1,3})[.、．]\\s*(\\S.*)$");

    /** 条目长度上限（与轨道表 title 列对齐——超长截断保落库可行）。 */
    private static final int ITEM_MAX_LENGTH = 500;

    public DesignChecklist {
        items = List.copyOf(items);
    }

    /**
     * 解析 PRD markdown 的设计清单：按终点类型选章（设计主线＝「设计物清单」、
     * 系统＋设计＝「功能清单」按范围圈定）、编号条目按出现序即推进序。
     */
    public static DesignChecklist parse(String prdMarkdown, ProjectEndpointType endpointType,
            DesignScope scope) {
        if (prdMarkdown == null || prdMarkdown.isBlank()) {
            return new DesignChecklist(List.of());
        }
        List<String> chapterItems = chapterItems(prdMarkdown, chapterTitleOf(endpointType));
        if (endpointType == ProjectEndpointType.SYSTEM_DESIGN && scope != null
                && !scope.pages().isEmpty()) {
            return new DesignChecklist(scopeAnchored(chapterItems, scope.pages()));
        }
        return new DesignChecklist(chapterItems);
    }

    /** 清单章章名（按终点类型：仅设计主线用设计形，ADR-0024）。 */
    private static String chapterTitleOf(ProjectEndpointType endpointType) {
        return endpointType == ProjectEndpointType.DESIGN ? "设计物清单" : "功能清单";
    }

    /** 章内编号条目（章标题行定界、下一章止；条目＝首行全文截长度上限）。 */
    private static List<String> chapterItems(String prdMarkdown, String chapterTitle) {
        Pattern chapterHeading = Pattern.compile("^#{1,6}\\s*" + Pattern.quote(chapterTitle)
                + "\\s*$");
        List<String> items = new ArrayList<>();
        boolean inChapter = false;
        for (String line : prdMarkdown.split("\n", -1)) {
            String trimmed = line.trim();
            if (chapterHeading.matcher(trimmed).matches()) {
                inChapter = true;
                continue;
            }
            if (inChapter && ANY_HEADING.matcher(trimmed).find()) {
                break; // 下一章开始
            }
            if (!inChapter) {
                continue;
            }
            var match = NUMBERED_ITEM.matcher(line);
            if (match.find()) {
                String text = match.group(2).trim();
                if (!text.isEmpty()) {
                    items.add(text.length() > ITEM_MAX_LENGTH
                            ? text.substring(0, ITEM_MAX_LENGTH) : text);
                }
            }
        }
        return items;
    }

    /**
     * 勾选范围圈定（系统＋设计）：清单条目按标签精确匹配保留（清单序即推进序），
     * 对照不上的标签照录缀后（标签锚漂移不静默丢页面——多跑不漏做）。
     */
    private static List<String> scopeAnchored(List<String> chapterItems, List<String> pages) {
        List<String> anchored = new ArrayList<>(chapterItems.stream()
                .filter(pages::contains).toList());
        for (String page : pages) {
            if (!chapterItems.contains(page)) {
                anchored.add(page);
            }
        }
        return anchored;
    }
}
