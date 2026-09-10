package com.aieducenter.aiplatform.business.project.application;

import java.util.List;

import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment.AnnotationAnchor;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment.AnnotationBody;

/**
 * 圈注锚载荷 → 主智能体 prompt 的渲染（#97 圈注 B 档）：把用户在预览上指认的结构化
 * 锚（选择 / 圈选；评论仅历史兼容）渲染成主智能体可精确读取的自然语言段，逐条
 * 编号——用户 chip 与主输入框以「第 N 条」指代，与本渲染序号同构——随用户发言
 * 一并喂入（结构化定位而非猜图）。
 *
 * <p>纯函数（无状态、无 IO），正本字段形状与 SSE 事件清单「消息附件部件锚载荷
 * schema」同源。渲染宽容：未知 kind / 空锚 / 缺字段都不抛——缺什么不渲染什么
 * （无评语的条目不出评语段——#135 起 UI 不再产生评语，历史带评语件照常回显），
 * 非圈注附件整体丢弃（防御性不误读）。</p>
 */
public final class AnnotationPrompt {

    /** 渲染段标题（前缀，正本于本处）。 */
    static final String HEADER = "【圈注（用户在预览上指认的位置）】";

    private AnnotationPrompt() {
    }

    /**
     * 圈注附件 → prompt 后缀：无有效圈注返回空串（调用方拼接后即原发言）。
     */
    public static String renderSuffix(List<AnnotationAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (AnnotationAttachment attachment : attachments) {
            if (attachment == null || !attachment.hasAnnotation()) {
                continue;
            }
            String line = renderLine(attachment.annotation());
            if (line == null) {
                continue;
            }
            if (index == 0) {
                sb.append('\n').append('\n').append(HEADER).append('\n');
            }
            sb.append(++index).append(". ").append(line).append('\n');
        }
        return index == 0 ? "" : sb.toString();
    }

    /** 单条圈注 → 一行（空锚返回 null——无内容不出行）。 */
    private static String renderLine(AnnotationBody body) {
        if (body == null) {
            return null;
        }
        String anchorDesc = renderAnchor(body.anchor());
        if (anchorDesc == null) {
            return null;
        }
        StringBuilder line = new StringBuilder();
        line.append(kindLabel(body.kind())).append("：").append(anchorDesc);
        if (body.note() != null && !body.note().isBlank()) {
            line.append("，评语「").append(body.note().trim()).append("」");
        }
        return line.toString();
    }

    /** 结构化锚 → 可读描述（选择器/文本为点选与评论；矩形为圈选）。 */
    private static String renderAnchor(AnnotationAnchor anchor) {
        if (anchor == null) {
            return null;
        }
        if (anchor.region() != null) {
            var region = anchor.region();
            StringBuilder sb = new StringBuilder("页面区域 (x=" + num(region.x()) + ", y=" + num(region.y())
                    + ", 宽=" + num(region.width()) + ", 高=" + num(region.height()) + ")");
            // 圈选锚补 DOM 参照（选择器/文本）时一并渲染——主智能体可读「圈住哪个元素」
            if (anchor.selector() != null && !anchor.selector().isBlank()) {
                sb.append("，选择器 `").append(anchor.selector().trim()).append('`');
            }
            if (anchor.text() != null && !anchor.text().isBlank()) {
                sb.append("，文本「").append(anchor.text().trim()).append("」");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        if (anchor.selector() != null && !anchor.selector().isBlank()) {
            sb.append("选择器 `").append(anchor.selector().trim()).append('`');
        }
        if (anchor.text() != null && !anchor.text().isBlank()) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("文本「").append(anchor.text().trim()).append("」");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 标注类型 → 用户面标签（与工具条四键口径一致：选择 / 圈选 / 评论；未知类型
     * 回落「圈注」——渲染宽容不抛）。 */
    private static String kindLabel(String kind) {
        return switch (kind) {
            case "select" -> "选择";
            case "circle" -> "圈选";
            case "comment" -> "评论";
            default -> "圈注";
        };
    }

    /** 双精度 → 无尾零的整数/小数串（区域坐标为整数像素，避免 .0 噪音）。 */
    private static String num(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? Long.toString((long) value)
                : Double.toString(value);
    }
}
