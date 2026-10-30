package com.semgravio.aiplatform.business.order.domain.model;

import java.util.Locale;
import java.util.Set;

/**
 * 付款凭证上传面（#337）的规则单点，照 {@code ProjectMaterials}（#286 物料
 * 上传）形制：格式与大小校验（pdf/png/jpg/jpeg 四格式＋10MB，超限如实报错
 * 不静默压缩）、原始文件名净化（用户可控输入进库与响应头前的最小消毒）、
 * MIME 映射（后台点看直出用）。纯函数无依赖；字节落库归应用层（凭证与节点
 * 状态同事务）。
 */
public final class PaymentVouchers {

    /** 单文件上传上限（10MB，对齐物料上传 #286 定档）：超限如实报错，不静默压缩。 */
    public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    /** 净化后文件名的字节长度上限（词干段；扩展名不截——MIME 判定靠它）。 */
    private static final int MAX_NAME_BYTES = 120;

    /** 可上传扩展名面（银行转账电子回单/水单常见形态）。 */
    private static final Set<String> UPLOADABLE_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg");

    private PaymentVouchers() {
    }

    /**
     * 上传格式判定：按扩展名（大小写不敏感）。无内容嗅探面；空名（part 无
     * filename 形）即格式不符。
     */
    public static boolean isUploadableVoucher(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return false;
        }
        return UPLOADABLE_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * 扩展名 → MIME（后台点看直出与展示用；非四格式回落
     * {@code application/octet-stream}——格式判定已在上传口拦截，此处仅展示映射）。
     */
    public static String contentTypeOf(String filename) {
        if (!isUploadableVoucher(filename)) {
            return "application/octet-stream";
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            default -> "image/jpeg";
        };
    }

    /**
     * 原始文件名净化：口径同 {@code ProjectMaterials#sanitizeName}（剥客户端
     * 路径形、剔控制字符与引号、压缩空白、超长截词干、空词干回落
     * {@code voucher}）。
     */
    public static String sanitizeName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "voucher";
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
            return "voucher" + extension;
        }
        return truncateUtf8(stem, MAX_NAME_BYTES) + extension;
    }

    /** UTF-8 字节长度内截断（不劈多字节字符；口径同物料上传）。 */
    private static String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }
}
