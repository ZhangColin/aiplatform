package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.util.Locale;
import java.util.Optional;

import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;

/**
 * 候选内容校验正本（#460 起立为自荐写入参数；#638 拆自荐写入后写入面退役、血统
 * 三件随写入入口移除——#641 起候选经验由事后提炼产出，血统由提炼侧直写
 * {@link SedimentDraftRecord}）：内容四件（kind/title/summary/正文）。kind 紧凑
 * 构造归一（trim＋大写键），校验正本在 {@link #violation()}——违例返回给模型可读
 * 的拒因，合规返回空。提炼侧候选校验（血统未定、逐字段校验）经静态入口
 * {@link #contentViolation} 共用同一正本。
 *
 * @param kind    沉淀物类别（CASE/MODULE/RULE，大小写不敏感，归一大写键）
 * @param title   标题（≤200）
 * @param summary 一句话摘要：这是什么、何时复用（≤1024）
 * @param content 正文 markdown：沉淀物的完整陈述（≤100k）
 */
public record SedimentDraftProposal(
        String kind,
        String title,
        String summary,
        String content) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_SUMMARY_LENGTH = 1024;
    public static final int MAX_CONTENT_CHARS = 100_000;

    public SedimentDraftProposal {
        kind = kind == null ? null : kind.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 校验正本（内容四件）：违例返回给模型可读的拒因（可修正重提），合规返回空。
     * 静态入口——血统未定的调用面（提炼侧候选校验）与完整提案（本记录）共用。
     */
    public static Optional<String> contentViolation(String kind, String title, String summary,
            String content) {
        if (kind == null || kind.isBlank()) {
            return Optional.of("kind 不能为空：传 CASE（需求案例）/MODULE（功能模块）"
                    + "/RULE（业务规则）之一。");
        }
        if (SedimentKind.byKey(kind).isEmpty()) {
            return Optional.of("kind 只支持 CASE/MODULE/RULE（当前：" + kind + "）——"
                    + "需求案例请传 CASE，可复用的功能形态传 MODULE，业务规则口径传 RULE。");
        }
        if (title == null || title.isBlank()) {
            return Optional.of("title 不能为空：一句话标题（沉淀物是什么）。");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return Optional.of("title 超长：长度 ≤" + MAX_TITLE_LENGTH
                    + "（当前 " + title.length() + "）");
        }
        if (summary == null || summary.isBlank()) {
            return Optional.of("summary 不能为空：一句话说明这是什么、何时复用。");
        }
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            return Optional.of("summary 超长：长度 ≤" + MAX_SUMMARY_LENGTH
                    + "（当前 " + summary.length() + "）");
        }
        if (content == null || content.isBlank()) {
            return Optional.of("正文不能为空：沉淀物的完整陈述（markdown）。");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return Optional.of("正文超长：长度 ≤" + MAX_CONTENT_CHARS
                    + "（当前 " + content.length() + "）");
        }
        return Optional.empty();
    }

    /** 校验正本的实例入口（本提案内容四件）。 */
    public Optional<String> violation() {
        return contentViolation(kind, title, summary, content);
    }
}
