package com.semgravio.aiplatform.base.knowledge.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link SedimentDraftProposal#violation()}：候选内容校验正本（#460 起立；#638 拆
 * 自荐写入后无写入面，#641 起候选经验由事后提炼产出——提炼侧候选校验经静态入口
 * {@link SedimentDraftProposal#contentViolation} 共用本正本）。内容四件的违例面：
 * kind 三类归一与非法拒、title/summary/content 空白与超长。
 */
class SedimentDraftProposalTest {

    private static SedimentDraftProposal proposal(String kind, String title, String summary,
            String content) {
        return new SedimentDraftProposal(kind, title, summary, content);
    }

    @Test
    void given_valid_content_when_violation_then_empty() {
        assertThat(proposal("CASE", "培训机构排课冲突", "K12 机构排课的核心冲突场景。",
                "家长临时调课与教室占用冲突时，先锁教室再看老师。").violation()).isEmpty();
    }

    @Test
    void given_lowercase_kind_when_construct_then_normalized_to_upper_key() {
        assertThat(proposal("case", "标题", "摘要", "正文").kind()).isEqualTo("CASE");
    }

    @Test
    void given_invalid_kinds_when_violation_then_kind_reason() {
        assertThat(proposal(null, "标题", "摘要", "正文").violation()).isPresent()
                .hasValueSatisfying(reason -> assertThat(reason).contains("kind"));
        assertThat(proposal(" ", "标题", "摘要", "正文").violation()).isPresent();
        assertThat(proposal("PRD", "标题", "摘要", "正文").violation()).isPresent()
                .hasValueSatisfying(reason -> assertThat(reason).contains("CASE"));
    }

    @Test
    void given_blank_or_overlong_fields_when_violation_then_field_reason() {
        assertThat(proposal("CASE", " ", "摘要", "正文").violation()).isPresent()
                .hasValueSatisfying(reason -> assertThat(reason).contains("title"));
        assertThat(proposal("CASE", "t".repeat(201), "摘要", "正文").violation()).isPresent();
        assertThat(proposal("CASE", "标题", " ", "正文").violation()).isPresent()
                .hasValueSatisfying(reason -> assertThat(reason).contains("summary"));
        assertThat(proposal("CASE", "标题", "s".repeat(1025), "正文").violation()).isPresent();
        assertThat(proposal("CASE", "标题", "摘要", " ").violation()).isPresent()
                .hasValueSatisfying(reason -> assertThat(reason).contains("正文"));
        assertThat(proposal("CASE", "标题", "摘要", "b".repeat(100_001)).violation())
                .isPresent();
    }
}
