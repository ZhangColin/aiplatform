package com.aieducenter.aiplatform.business.project.domain.model;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 物料上传面（#286，ADR-0027 图片管道底座）的规则单点，照 {@link ProjectFiles}
 * 形制：用户经发送框回形针上传的图片物料——格式与大小校验（五格式＋10MB，超限
 * 如实报错不静默压缩）、原始文件名净化（用户可控输入进工作区文件系统与响应头
 * 前的最小消毒，口径同 raw 直出文件名消毒）、落点命名（TSID 前缀防撞名——物料
 * 目录是累积面，同名上传不可覆盖既有件）、写入命令构造（stdin 灌字节、stat
 * 字节回执——回执口径同 {@link WorkspaceRenders}）。命令执行归 exec 通道
 * （stdin 形）、字节归调用方。纯函数无依赖。
 */
public final class ProjectMaterials {

    /** 单文件上传上限（10MB，ADR-0027 定档）：超限如实报错，不静默压缩。 */
    public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    /** 净化后文件名的字节长度上限（词干段；扩展名不截——content-type 判定靠它）。 */
    private static final int MAX_NAME_BYTES = 120;

    private ProjectMaterials() {
    }

    /**
     * 上传格式判定：按扩展名（大小写不敏感），与图片点看的扩展名面同集
     * （{@link ProjectFiles#isImagePath}——#283 已注「与上传口径同集」，单一
     * 事实归彼处，这里只立上传面的消费语义）。无内容嗅探面；空名
     * （part 无 filename 形）即格式不符。
     */
    public static boolean isUploadableImage(String filename) {
        return filename != null && !filename.isBlank() && ProjectFiles.isImagePath(filename);
    }

    /**
     * 原始文件名净化：取末段（剥客户端路径形）、剔控制字符与引号（文件系统与
     * 响应头两面的注入字，口径同 raw 直出 fileNameOf 消毒）、压缩空白、超长截
     * 词干（扩展名保全）。净化后为空（整名皆非法字符/无词干）回落
     * {@code material}。净化是展示与存储面的卫生，不是格式判定——判定在
     * {@link #isUploadableImage}。
     */
    public static String sanitizeName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "material";
        }
        String basename = originalFilename;
        int slash = Math.max(basename.lastIndexOf('/'), basename.lastIndexOf('\\'));
        if (slash >= 0) {
            basename = basename.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < basename.length(); i++) {
            char c = basename.charAt(i);
            if (Character.isISOControl(c) || c == '"' || c == '\'') {
                continue;
            }
            sb.append(c == ' ' || Character.isWhitespace(c) ? ' ' : c);
        }
        String cleaned = sb.toString().trim().replaceAll(" {2,}", " ");
        int dot = cleaned.lastIndexOf('.');
        String stem = dot > 0 ? cleaned.substring(0, dot) : cleaned;
        String extension = dot > 0 ? cleaned.substring(dot) : "";
        if (stem.isBlank()) {
            return "material" + extension;
        }
        return truncateUtf8(stem, MAX_NAME_BYTES) + extension;
    }

    /**
     * 落点路径：{@code materials/{tsid}-{净化名}}——TSID 数字前缀保证不撞名不
     * 覆盖（物料累积面），词干保留原始名供文件区人读。
     *
     * @param tsid 平台生成的数字标识（非用户输入）
     */
    public static String storedPath(String tsid, String sanitizedFilename) {
        return WorkspaceLayout.MATERIALS_DIR + "/" + tsid + "-" + sanitizedFilename;
    }

    /**
     * 写入命令（path 须平台生成形——{@link #storedPath} 产出，此处过
     * {@link ProjectFiles#isViewable} 防御性拒绝）：stdin 字节灌入
     * {@code cat >} 落目标路径（父目录幂等落位），stdout = stat 字节回执
     * （调用方与上传字节数核对——回执口径同 {@link WorkspaceRenders}）。路径经
     * 单引号包裹 + 转义，无注入面。
     */
    public static String uploadCommand(String relativePath) {
        if (!ProjectFiles.isViewable(relativePath)) {
            throw new IllegalArgumentException("非可浏览路径，命令构造拒绝: " + relativePath);
        }
        return ContainerCommands.stdinWriteCommand(relativePath);
    }

    /** 词干按 UTF-8 字节截断（不切半个字符；上限内原样）。 */
    private static String truncateUtf8(String text, int maxBytes) {
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            int charBytes = String.valueOf(text.charAt(i))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + charBytes > maxBytes) {
                break;
            }
            sb.append(text.charAt(i));
            bytes += charBytes;
        }
        return sb.toString();
    }
}
