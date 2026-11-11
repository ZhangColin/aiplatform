package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.util.Locale;
import java.util.Optional;

import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;

/**
 * 沉淀自荐写入参数（#460）：内容四件（kind/title/summary/正文——载荷 JSONB 一期
 * 不开工具参数，列随档生长）＋血统三件（来源项目/run 标识/来源槽位——照技能草稿
 * 先例：project_id 软引用、run_id 为 RuntimeContext 透传的本轮平台标识、slot 为
 * 三职能槽位稳定键）。kind 紧凑构造归一（trim＋大写键），校验正本在
 * {@link #contentViolation()}——违例返回给模型可读的拒因（可修正重提），合规返回空。
 *
 * @param kind      沉淀物类别（CASE/MODULE/RULE，大小写不敏感，归一大写键）
 * @param title     标题（≤200）
 * @param summary   一句话摘要：这是什么、何时复用（≤1024）
 * @param content   正文 markdown：沉淀物的完整陈述（≤100k）
 * @param projectId 来源项目（项目删除清理入口的血统锚）
 * @param runId     来源 run 标识（RuntimeContext 透传的本轮平台标识）
 * @param slot      来源槽位（血统：哪类智能体自荐的——main/executor/subagent 稳定键）
 */
public record SedimentDraftProposal(
        String kind,
        String title,
        String summary,
        String content,
        Long projectId,
        String runId,
        String slot) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_SUMMARY_LENGTH = 1024;
    public static final int MAX_CONTENT_CHARS = 100_000;

    public SedimentDraftProposal {
        kind = kind == null ? null : kind.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 校验正本（内容四件）：违例返回给模型可读的拒因（可修正重提），合规返回空。
     * 静态入口——血统未定的调用面（工具面先校验）与完整提案（应用服务）共用。
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
