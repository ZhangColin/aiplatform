package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.Set;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 出图转存面（#288 出图工具件内核，ADR-0026/0027）的规则单点，照
 * {@link ProjectMaterials}/{@link WorkspaceRenders} 形制：供应商生成的图片转存落盘
 * 产物目录的三件事——落点命名（{@code design/{tsid}-{净化词干}.{扩展名}}，TSID
 * 前缀防撞名：产物目录是候选各代的累积面，同名片不可覆盖）、转存命令构造（两形：
 * URL 形容器内 {@code curl} 拉取——egress 落在沙箱侧〔ADR-0019〕、字节不过平台
 * JVM；base64 形字节经 stdin 灌入。两形同走 {@code .part} 暂存＋原子改名：失败
 * 清理痕迹，<b>不留半张稿</b>〔ADR-0026 技术失败不产新候选〕、瞬态件不占图片
 * 扩展名不误入点看面）、扩展名收口（URL/格式参数推不出即 png，只认点看面同集
 * 四格式＋gif）。命令执行归 exec 通道。纯函数无依赖。
 */
public final class ImageTransfers {

    /** 下载硬超时（秒）：供应商 URL 出图侧一般 10~20s 内就绪，30s 是下载段的
     *  兜底上限（生成段超时归适配器 HTTP 超时，两段各自有限）。 */
    public static final int DOWNLOAD_TIMEOUT_SECONDS = 30;

    /** 落盘扩展名许可集（与 {@link ProjectFiles#isImagePath} 点看面同集减 svg——
     *  位图生成模型不出 svg；不在此集的扩展名提示回落 png）。 */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "webp", "gif");

    private ImageTransfers() {
    }

    /**
     * 落点路径：{@code design/{tsid}-{净化词干}.{扩展名}}——镜像
     * {@link ProjectMaterials#storedPath} 命名形（TSID 防撞、词干人读）。
     *
     * @param tsid      平台生成的数字标识（非用户输入）
     * @param nameStem  净化后的词干（调用方经 {@link ProjectMaterials#sanitizeName}
     *                  消毒；可空＝调用方已回落缺省词干）
     * @param extension 扩展名（小写不带点；经 {@link #extensionOf} 收口）
     */
    public static String storedPath(String tsid, String nameStem, String extension) {
        return WorkspaceLayout.DESIGN_DIR + "/" + tsid + "-" + nameStem + "." + extension;
    }

    /**
     * 扩展名收口：小写化；不在许可集（含空）回落 {@code png}——URL 形按地址路径段
     * 推、推不出（查询串直出无扩展名等）不猜格式。
     */
    public static String extensionOf(String hint) {
        if (hint == null || hint.isBlank()) {
            return "png";
        }
        String normalized = hint.toLowerCase();
        return ALLOWED_EXTENSIONS.contains(normalized) ? normalized : "png";
    }

    /**
     * URL 形扩展名提示：取地址路径段末段的扩展名（剥查询串），经
     * {@link #extensionOf} 收口——供应商图片 URL 一般带真扩展名；解析异常或无
     * 扩展名回落 png（火山等出图格式可另由响应字段补，归适配器）。
     */
    public static String extensionFromUrl(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            int dot = lastSegment.lastIndexOf('.');
            return dot < 0 ? "png" : extensionOf(lastSegment.substring(dot + 1));
        }
        catch (RuntimeException e) {
            return "png";
        }
    }

    /**
     * URL 形下载转存命令（path 须平台生成形——{@link #storedPath} 产出，此处过
     * {@link ProjectFiles#isViewable} 防御性拒绝）：{@code curl} 拉供应商 URL 落
     * {@code .part} 暂存（父目录幂等落位、{@code -f} HTTP 错如实非零退出、
     * {@code --max-time} 硬超时），非空守卫后原子改名到正式落点，stdout = stat
     * 字节回执（回执口径同 {@link WorkspaceRenders}）。失败清理 {@code .part} 后
     * 非零退出——不留半张稿（调用方有限重试，每次尝试各拿新落点）。URL 经单引号
     * 包裹 + 转义，无注入面。
     */
    public static String downloadCommand(String relativePath, String url) {
        if (!ProjectFiles.isViewable(relativePath)) {
            throw new IllegalArgumentException("非可浏览路径，命令构造拒绝: " + relativePath);
        }
        return partGuardAndRun(relativePath,
                "curl -fsSL --max-time " + DOWNLOAD_TIMEOUT_SECONDS
                        + " -o \"$q\" " + ContainerCommands.quotedLiteral(url));
    }

    /**
     * base64 形写入转存命令（字节经 stdin 灌 {@code .part} 暂存，形制同
     * {@link ProjectMaterials#uploadCommand} 的 stdin 写面＋.part 暂存语义）：
     * 回执口径与失败清理同 {@link #downloadCommand}，调用方核对字节数。
     */
    public static String writeCommand(String relativePath) {
        if (!ProjectFiles.isViewable(relativePath)) {
            throw new IllegalArgumentException("非可浏览路径，命令构造拒绝: " + relativePath);
        }
        return partGuardAndRun(relativePath, "cat > \"$q\"");
    }

    /** 两形公共骨架：p＝正式落点、q＝{@code .part} 暂存；载荷写入 q → 非空守卫 →
     *  原子改名 → stat 字节回执；任一失败清理 q 后退出 1（不留半张稿）。 */
    private static String partGuardAndRun(String relativePath, String payloadWrite) {
        return "p=" + ContainerCommands.quoted(relativePath)
                + "; q=\"$p.part\"; d=${p%/*}; mkdir -p \"$d\""
                + " && { " + payloadWrite + " && test -s \"$q\" && mv \"$q\" \"$p\""
                + " && stat -c %s \"$p\" || { rm -f -- \"$q\"; exit 1; }; }";
    }
}
