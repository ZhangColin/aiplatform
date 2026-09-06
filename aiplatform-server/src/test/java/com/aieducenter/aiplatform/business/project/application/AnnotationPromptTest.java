package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment.AnnotationAnchor;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment.AnnotationBody;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment.AnnotationRegion;

/**
 * 圈注锚载荷 → 主智能体 prompt 的渲染（#97 圈注 B 档，纯函数）：三能力（点选 /
 * 圈选 / 评论）各渲染成主智能体可精确读取的自然语言段；宽容——非圈注附件丢弃、
 * 空锚/缺字段不抛、未知 kind 回落「圈注」。契约字段形状与 SSE 事件清单
 * 「消息附件部件锚载荷 schema」同源。
 */
class AnnotationPromptTest {

    @Test
    void renders_select_with_selector_and_text() {
        var attachment = attachment(new AnnotationBody("select",
                new AnnotationAnchor("button.submit-btn", "提交订单", null), null));

        String suffix = AnnotationPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("【圈注（用户在预览上指认的位置）】");
        assertThat(suffix).contains("1. 点选：选择器 `button.submit-btn`，文本「提交订单」");
    }

    @Test
    void renders_circle_region_with_whole_pixel_numbers() {
        var attachment = attachment(new AnnotationBody("circle",
                new AnnotationAnchor(null, null, new AnnotationRegion(120.0, 340.0, 300.0, 80.0)),
                null));

        String suffix = AnnotationPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("1. 圈选：页面区域 (x=120, y=340, 宽=300, 高=80)");
    }

    @Test
    void renders_circle_with_dom_reference_when_present() {
        // 圈选锚补 DOM 参照（选择器/文本）时一并渲染——主智能体可读「圈住哪个元素」
        var attachment = attachment(new AnnotationBody("circle",
                new AnnotationAnchor("div.card:nth-of-type(2)", "订单卡片",
                        new AnnotationRegion(120.0, 340.0, 300.0, 80.0)),
                null));

        String suffix = AnnotationPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("页面区域 (x=120, y=340, 宽=300, 高=80)");
        assertThat(suffix).contains("选择器 `div.card:nth-of-type(2)`");
        assertThat(suffix).contains("文本「订单卡片」");
    }

    @Test
    void renders_comment_with_note() {
        var attachment = attachment(new AnnotationBody("comment",
                new AnnotationAnchor("div.banner", "欢迎横幅", null), "改成红色"));

        String suffix = AnnotationPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("1. 评论：选择器 `div.banner`，文本「欢迎横幅」，评语「改成红色」");
    }

    @Test
    void stacks_multiple_annotations_numbered_in_order() {
        var select = attachment(new AnnotationBody("select",
                new AnnotationAnchor("button.a", "甲", null), null));
        var circle = attachment(new AnnotationBody("circle",
                new AnnotationAnchor(null, null, new AnnotationRegion(1, 2, 3, 4)), null));

        String suffix = AnnotationPrompt.renderSuffix(List.of(select, circle));

        assertThat(suffix).contains("1. 点选：选择器 `button.a`，文本「甲」");
        assertThat(suffix).contains("2. 圈选：页面区域 (x=1, y=2, 宽=3, 高=4)");
    }

    @Test
    void empty_or_null_returns_empty() {
        assertThat(AnnotationPrompt.renderSuffix(null)).isEmpty();
        assertThat(AnnotationPrompt.renderSuffix(List.of())).isEmpty();
    }

    @Test
    void non_annotation_attachments_are_dropped() {
        var other = new AnnotationAttachment("file", null);
        var select = attachment(new AnnotationBody("select",
                new AnnotationAnchor("button.a", "甲", null), null));

        String suffix = AnnotationPrompt.renderSuffix(List.of(other, select));

        assertThat(suffix).contains("1. 点选：选择器 `button.a`，文本「甲」");
        assertThat(suffix).doesNotContain("file");
    }

    @Test
    void empty_anchor_is_skipped_not_crashing() {
        var blank = attachment(new AnnotationBody("select",
                new AnnotationAnchor(null, null, null), null));

        String suffix = AnnotationPrompt.renderSuffix(List.of(blank));

        assertThat(suffix).isEmpty();
    }

    @Test
    void unknown_kind_falls_back_to_generic_label() {
        var attachment = attachment(new AnnotationBody("pin",
                new AnnotationAnchor("div.x", "某块", null), null));

        String suffix = AnnotationPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("1. 圈注：选择器 `div.x`，文本「某块」");
    }

    private static AnnotationAttachment attachment(AnnotationBody body) {
        return new AnnotationAttachment(AnnotationAttachment.ATTACHMENT_TYPE_ANNOTATION, body);
    }
}
