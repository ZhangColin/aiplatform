package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;

import com.aieducenter.aiplatform.business.project.application.dto.command.MessageAttachment;
import com.aieducenter.aiplatform.business.project.application.dto.command.MessageAttachment.AnnotationAnchor;
import com.aieducenter.aiplatform.business.project.application.dto.command.MessageAttachment.AnnotationBody;

/**
 * 消息附件 → 主智能体 prompt 的渲染（#97 圈注起立、#286 扩图片物料）：把附件
 * 部件渲染成主智能体可精确读取的自然语言段——圈注（预览上指认的结构化锚）与
 * 图片物料（用户上传的参考图、工作区路径引用）各成一段、各自编号（用户 chip
 * 与主输入框以「第 N 条」指代，与本渲染序号同构），随用户发言一并喂入。
 *
 * <p>纯函数（无状态、无 IO），正本字段形状与 SSE 事件清单「消息附件部件锚载荷
 * schema」同源。渲染宽容：未知 kind / 空锚 / 缺字段都不抛——缺什么不渲染什么
 * （无评语的条目不出评语段——#135 起 UI 不再产生评语，历史带评语件照常回显），
 * 非已知形态附件整体丢弃（防御性不误读）。</p>
 */
public final class AttachmentPrompt {

    /** 圈注渲染段标题（前缀，正本于本处）。 */
    static final String ANNOTATION_HEADER = "【圈注（用户在预览上指认的位置）】";

    /** 图片物料渲染段标题（前缀，正本于本处——路径即工作区事实，智能体可读可嵌）。 */
    static final String MATERIAL_HEADER = "【图片物料（用户随话上传的参考图，已落工作区）】";

    private AttachmentPrompt() {
    }

    /**
     * 消息附件 → prompt 后缀：圈注段在前（#97 既有序）、图片物料段随后，各段
     * 独立编号；无有效附件返回空串（调用方拼接后即原发言）。
     */
    public static String renderSuffix(List<MessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendSection(sb, ANNOTATION_HEADER, annotationLinesOf(attachments));
        appendSection(sb, MATERIAL_HEADER, materialLinesOf(attachments));
        return sb.toString();
    }

    /** 编号段（两形态共用形）：标题 + 逐条编号行（序号与前端 chip 序号同构——
     *  「第 N 条」指代对得上）；空行集不出段。 */
    private static void appendSection(StringBuilder sb, String header, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append('\n').append('\n').append(header).append('\n');
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(". ").append(lines.get(i)).append('\n');
        }
    }

    /** 圈注附件 → 行集（空锚条目不出行——渲染宽容）。 */
    private static List<String> annotationLinesOf(List<MessageAttachment> attachments) {
        List<String> lines = new ArrayList<>();
        for (MessageAttachment attachment : attachments) {
            if (attachment == null || !attachment.hasAnnotation()) {
                continue;
            }
            String line = renderLine(attachment.annotation());
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** 图片物料附件 → 行集（名称 + 工作区路径——路径即载荷本体；名称缺省回落路径末段）。 */
    private static List<String> materialLinesOf(List<MessageAttachment> attachments) {
        List<String> lines = new ArrayList<>();
        for (MessageAttachment attachment : attachments) {
            if (attachment == null || !attachment.hasImage()) {
                continue;
            }
            String name = attachment.name() == null || attachment.name().isBlank()
                    ? basenameOf(attachment.path()) : attachment.name();
            lines.add(name + "（工作区 " + attachment.path().trim() + "）");
        }
        return lines;
    }

    /** 路径末段（name 缺省时的呈现回落）。 */
    private static String basenameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
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
