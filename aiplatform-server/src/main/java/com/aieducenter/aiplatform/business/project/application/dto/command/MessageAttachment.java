package com.aieducenter.aiplatform.business.project.application.dto.command;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;

/**
 * 消息附件部件（#97 圈注起立、#286 扩 image 形态）：随对话区发言发送的附件
 * ——{@code POST /{id}/messages} 的 attachments 元素，载荷只定要点、不带字节。
 * 两形态（附件种类 attachmentType 分示）：
 *
 * <ul>
 *   <li><b>圈注 {@code "annotation"}</b>（#97）：用户在预览上指认位置的结构化锚
 *       （选择/圈选两键；评论仅历史兼容）——结构化定位而非猜图。</li>
 *   <li><b>图片物料 {@code "image"}</b>（#286，ADR-0027）：用户经发送框回形针
 *       上传的参考图——载荷＝工作区路径引用（上传端点落物料目录后的 path 原样
 *       回传）＋原始名；字节不走载荷（存储正本＝工作区，取件走 raw 直出）。</li>
 * </ul>
 *
 * <p>载荷形状与 SSE 事件清单「消息附件部件锚载荷 schema」同源。非本二形态的
 * 附件消费侧防御性丢弃不误读（渲染宽容）。</p>
 *
 * @param attachmentType 附件种类（{@code "annotation"} / {@code "image"}）
 * @param annotation     标注体（annotation 形态；类型 + 结构化锚 + note 历史兼容）
 * @param name           原始文件名（image 形态的 chip 呈现用；缺省回落路径末段）
 * @param path           工作区相对路径（image 形态的载荷本体——materials/ 下落点）
 */
public record MessageAttachment(

        @NotBlank(message = "附件种类不能为空")
        @Size(max = 40, message = "附件种类长度不能超过40")
        String attachmentType,

        @Valid AnnotationBody annotation,

        @Size(max = 200, message = "附件文件名长度不能超过200")
        String name,

        @Size(max = 500, message = "附件路径长度不能超过500")
        String path
) {

    /** 附件种类：圈注（#97 起立，v1 首个成员）。 */
    public static final String ATTACHMENT_TYPE_ANNOTATION = "annotation";

    /** 附件种类：图片物料（#286 扩值——工作区路径引用、不带字节）。 */
    public static final String ATTACHMENT_TYPE_IMAGE = "image";

    /** 该附件是否为圈注（非圈注不渲染圈注段——防御性丢弃不误读）。
     *  命名避开 {@code is} 前缀——Jackson 会把 {@code isXxx()} 当布尔 getter、
     *  与记录组件 {@code annotation} 撞名（序列化成布尔覆盖标注体）。 */
    public boolean hasAnnotation() {
        return ATTACHMENT_TYPE_ANNOTATION.equals(attachmentType) && annotation != null;
    }

    /**
     * 该附件是否为图片物料：path 是载荷本体，形态收口在此单点——非空且过
     * {@link ProjectFiles#isViewable}（可浏览形：无逃逸/非交付面）与
     * {@link ProjectFiles#isImagePath}（图片扩展名）。失形/恶意路径按无效形态
     * 防御丢弃（不误读、不触达工作区），与上传落点（物料目录）同口径。
     */
    public boolean hasImage() {
        return ATTACHMENT_TYPE_IMAGE.equals(attachmentType)
                && path != null && !path.isBlank()
                && ProjectFiles.isViewable(path)
                && ProjectFiles.isImagePath(path);
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
    public static final List<MessageAttachment> NONE = List.of();
}
