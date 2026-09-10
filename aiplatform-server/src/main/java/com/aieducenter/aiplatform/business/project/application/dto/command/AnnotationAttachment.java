package com.aieducenter.aiplatform.business.project.application.dto.command;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 圈注附件（#97 圈注 B 档）：随对话区发言发送的消息附件部件——用户在预览上指认
 * 位置的结构化锚（选择 / 圈选两键；评论仅历史兼容——#135 起 UI 不再产生），随
 * 下一句自然语言一起发送、被主智能体精确读取（结构化定位而非猜图）。载荷形状与
 * SSE 事件清单「消息附件部件锚载荷 schema」同源（附件种类 attachmentType + 标
 * 注体 annotation）。
 *
 * <p>指认是对话输入的<b>增强不是替代</b>：附件部件与自然语言同句发送，字段只定
 * 要点——结构化定位（anchor）+ 标注类型（kind）；note（评语）历史兼容、UI 不再
 * 产生。多条圈注 = 多个附件部件可叠加。</p>
 *
 * @param attachmentType 附件种类（v1 唯一 = {@code "annotation"}，后续上传物料等另立）
 * @param annotation     标注体（类型 + 结构化锚 + note 历史兼容）
 */
public record AnnotationAttachment(

        @NotBlank(message = "附件种类不能为空")
        @Size(max = 40, message = "附件种类长度不能超过40")
        String attachmentType,

        @Valid AnnotationBody annotation
) {

    /** 附件种类正本（v1 唯一成员；渲染与校验同源）。 */
    public static final String ATTACHMENT_TYPE_ANNOTATION = "annotation";

    /** 该附件是否为圈注（非圈注附件本票不消费——防御性丢弃不误读）。
     *  命名避开 {@code is} 前缀——Jackson 会把 {@code isXxx()} 当布尔 getter、
     *  与记录组件 {@code annotation} 撞名（序列化成布尔覆盖标注体）。 */
    public boolean hasAnnotation() {
        return ATTACHMENT_TYPE_ANNOTATION.equals(attachmentType) && annotation != null;
    }

    /** 标注体：类型（选择=点选锚定 / 圈选=拖框圈区域；评论仅历史兼容——#135 起
     *  UI 不再产生）+ 结构化锚 + note（评语历史兼容，UI 不再产生）。 */
    public record AnnotationBody(

            @NotBlank(message = "标注类型不能为空")
            @Size(max = 20, message = "标注类型长度不能超过20")
            String kind,

            @Valid AnnotationAnchor anchor,

            @Size(max = 500, message = "评语长度不能超过500")
            String note
    ) {
    }

    /** 结构化定位（预览与前端跨源，postMessage 回传）：选择器/文本引用 + 圈选矩形。 */
    public record AnnotationAnchor(

            @Size(max = 2000, message = "选择器长度不能超过2000")
            String selector,

            @Size(max = 500, message = "元素文本长度不能超过500")
            String text,

            @Valid AnnotationRegion region
    ) {
    }

    /** 圈选专用：页面级矩形区域（选择器可缺省）。 */
    public record AnnotationRegion(
            double x,
            double y,
            double width,
            double height
    ) {
    }

    /** 空附件集合常量（缺省参）。 */
    public static final List<AnnotationAttachment> NONE = List.of();
}
