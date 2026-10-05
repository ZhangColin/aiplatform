package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.business.project.application.dto.command.MessageAttachment;
import com.aieducenter.aiplatform.business.project.application.dto.command.MessageAttachment.AnnotationAnchor;
import com.aieducenter.aiplatform.business.project.application.dto.command.MessageAttachment.AnnotationBody;
import com.aieducenter.aiplatform.business.project.application.dto.command.MessageAttachment.AnnotationRegion;

/**
 * 消息附件 → 主智能体 prompt 的渲染（#97 圈注起立、#286 扩图片物料，纯函数）：
 * 标注类型（选择 / 圈选；评论仅历史兼容）各渲染成主智能体可精确读取的自然语言段，
 * 逐条编号（用户以「第 N 条」指代——chip 序号与本渲染同构）；图片物料渲染成
 * 名称＋工作区路径引用段（载荷不带字节，路径即事实）。宽容——未知形态附件丢弃、
 * 空锚/缺字段不抛、未知 kind 回落「圈注」。无评语的条目不渲染评语段（#135 起
 * UI 不再产生评语，历史带评语件照常回显）。契约字段形状与 SSE 事件清单「消息
 * 附件部件锚载荷 schema」同源。
 */
class AttachmentPromptTest {

    @Test
    void given_select_attachment_when_render_then_selector_and_text_line() {
        var attachment = attachment(new AnnotationBody("select",
                new AnnotationAnchor("button.submit-btn", "提交订单", null), null));

        String suffix = AttachmentPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("【圈注（用户在预览上指认的位置）】");
        assertThat(suffix).contains("1. 选择：选择器 `button.submit-btn`，文本「提交订单」");
        assertThat(suffix).doesNotContain("评语"); // 无评语段省略
    }

    @Test
    void given_circle_attachment_when_render_then_region_with_whole_pixel_numbers() {
        var attachment = attachment(new AnnotationBody("circle",
                new AnnotationAnchor(null, null, new AnnotationRegion(120.0, 340.0, 300.0, 80.0)),
                null));

        String suffix = AttachmentPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("1. 圈选：页面区域 (x=120, y=340, 宽=300, 高=80)");
    }

    @Test
    void given_circle_with_dom_reference_when_render_then_reference_included() {
        // 圈选锚补 DOM 参照（选择器/文本）时一并渲染——主智能体可读「圈住哪个元素」
        var attachment = attachment(new AnnotationBody("circle",
                new AnnotationAnchor("div.card:nth-of-type(2)", "订单卡片",
                        new AnnotationRegion(120.0, 340.0, 300.0, 80.0)),
                null));

        String suffix = AttachmentPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("页面区域 (x=120, y=340, 宽=300, 高=80)");
        assertThat(suffix).contains("选择器 `div.card:nth-of-type(2)`");
        assertThat(suffix).contains("文本「订单卡片」");
    }

    @Test
    void given_comment_with_note_when_render_then_note_quoted() {
        var attachment = attachment(new AnnotationBody("comment",
                new AnnotationAnchor("div.banner", "欢迎横幅", null), "改成红色"));

        String suffix = AttachmentPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("1. 评论：选择器 `div.banner`，文本「欢迎横幅」，评语「改成红色」");
    }

    @Test
    void given_multiple_annotations_when_render_then_numbered_in_order() {
        var select = attachment(new AnnotationBody("select",
                new AnnotationAnchor("button.a", "甲", null), null));
        var circle = attachment(new AnnotationBody("circle",
                new AnnotationAnchor(null, null, new AnnotationRegion(1, 2, 3, 4)), null));

        String suffix = AttachmentPrompt.renderSuffix(List.of(select, circle));

        assertThat(suffix).contains("1. 选择：选择器 `button.a`，文本「甲」");
        assertThat(suffix).contains("2. 圈选：页面区域 (x=1, y=2, 宽=3, 高=4)");
    }

    // ---------- 图片物料（#286） ----------

    @Test
    void given_image_material_when_render_then_name_and_workspace_path_line() {
        var image = new MessageAttachment(MessageAttachment.ATTACHMENT_TYPE_IMAGE,
                null, "logo.png", "materials/3897654321098765432-logo.png");

        String suffix = AttachmentPrompt.renderSuffix(List.of(image));

        assertThat(suffix).contains("【图片物料（用户随话上传的参考图，已落工作区）】");
        assertThat(suffix).contains("1. logo.png（工作区 materials/3897654321098765432-logo.png）");
    }

    @Test
    void given_image_material_without_name_when_render_then_path_basename_as_name() {
        var image = new MessageAttachment(MessageAttachment.ATTACHMENT_TYPE_IMAGE,
                null, null, "materials/123-海报.png");

        String suffix = AttachmentPrompt.renderSuffix(List.of(image));

        assertThat(suffix).contains("1. 123-海报.png（工作区 materials/123-海报.png）");
    }

    @Test
    void given_both_forms_when_render_then_separate_sections_each_numbered() {
        var select = attachment(new AnnotationBody("select",
                new AnnotationAnchor("button.a", "甲", null), null));
        var image = new MessageAttachment(MessageAttachment.ATTACHMENT_TYPE_IMAGE,
                null, "logo.png", "materials/123-logo.png");

        String suffix = AttachmentPrompt.renderSuffix(List.of(image, select));

        // 圈注段在前（#97 既有序）、物料段随后，各自独立编号
        assertThat(suffix).contains("【圈注（用户在预览上指认的位置）】");
        assertThat(suffix).contains("【图片物料（用户随话上传的参考图，已落工作区）】");
        assertThat(suffix).contains("1. 选择：选择器 `button.a`，文本「甲」");
        assertThat(suffix).contains("1. logo.png（工作区 materials/123-logo.png）");
        assertThat(suffix.indexOf("【圈注")).isLessThan(suffix.indexOf("【图片物料"));
    }

    @Test
    void given_image_without_path_when_render_then_dropped() {
        // path 是 image 形态的载荷本体——缺失即无效形态，防御性丢弃不误读
        var broken = new MessageAttachment(MessageAttachment.ATTACHMENT_TYPE_IMAGE,
                null, "logo.png", null);

        String suffix = AttachmentPrompt.renderSuffix(List.of(broken));

        assertThat(suffix).isEmpty();
    }

    @Test
    void given_image_with_malformed_path_when_render_then_dropped() {
        // 形态收口（hasImage 单点）：逃逸/非图片扩展名的 path 按无效形态丢弃，
        // 不进 prompt、不落对话史（直连调用的恶意路径不触达工作区）
        for (String path : new String[] {"../escape.png", "data/secret.png", "docs/PRD.md", "materials/readme.txt"}) {
            var malformed = new MessageAttachment(MessageAttachment.ATTACHMENT_TYPE_IMAGE,
                    null, "伪物料", path);
            assertThat(AttachmentPrompt.renderSuffix(List.of(malformed))).as(path).isEmpty();
        }
    }

    // ---------- 宽容口径 ----------

    @Test
    void given_no_attachments_when_render_then_empty_suffix() {
        assertThat(AttachmentPrompt.renderSuffix(null)).isEmpty();
        assertThat(AttachmentPrompt.renderSuffix(List.of())).isEmpty();
    }

    @Test
    void given_unknown_attachment_type_when_render_then_dropped() {
        var other = new MessageAttachment("file", null, null, null);
        var select = attachment(new AnnotationBody("select",
                new AnnotationAnchor("button.a", "甲", null), null));

        String suffix = AttachmentPrompt.renderSuffix(List.of(other, select));

        assertThat(suffix).contains("1. 选择：选择器 `button.a`，文本「甲」");
        assertThat(suffix).doesNotContain("file");
    }

    @Test
    void given_empty_anchor_when_render_then_skipped_not_crashing() {
        var blank = attachment(new AnnotationBody("select",
                new AnnotationAnchor(null, null, null), null));

        String suffix = AttachmentPrompt.renderSuffix(List.of(blank));

        assertThat(suffix).isEmpty();
    }

    @Test
    void given_unknown_kind_when_render_then_generic_label() {
        var attachment = attachment(new AnnotationBody("pin",
                new AnnotationAnchor("div.x", "某块", null), null));

        String suffix = AttachmentPrompt.renderSuffix(List.of(attachment));

        assertThat(suffix).contains("1. 圈注：选择器 `div.x`，文本「某块」");
    }

    private static MessageAttachment attachment(AnnotationBody body) {
        return new MessageAttachment(MessageAttachment.ATTACHMENT_TYPE_ANNOTATION, body, null, null);
    }
}
