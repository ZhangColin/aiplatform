package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 平面稿（代码出图路）的规则单点（#292，ADR-0026/0027）：设计执行体对文字排版
 * 为主的平面设计物（海报、卡片、横幅——图片模型画长文案易错字漏字）写单文件
 * HTML/CSS 稿，平台在设计会话收口时渲成 PNG（chromium 保真渲染，命令归
 * {@link WorkspaceRenders}）——HTML 源与 PNG 同名双形态同存（stitch 同构，
 * ADR-0027 位图出口触发点「平面类出稿即渲」的代码出图路）。
 *
 * <p>平面/界面分类是语义判断、<b>住提示词层</b>（ADR-0026 不做代码级硬路由
 * 分类器）：执行体在稿 HTML 的<b>首行</b>以画幅声明注释宣告平面身份——
 * {@code <!-- print: 宽x高 -->}（如 {@code <!-- print: 800x1200 -->}，画幅以
 * PRD 设计物条目的尺寸为准）；界面类稿不带声明、零渲染（界面类 v1 零截图，
 * ADR-0027）。平台确定性直提（对偶 ADR-0028「token 集中放稿的 :root、平台
 * 结构化直读」先例——协议保证形态、平台直读，不经模型判读）。纯函数无依赖；
 * 渲染保真归容器活体测试。</p>
 */
public final class DesignPrints {

    /**
     * 画幅声明形（稿 HTML 首行、整行匹配）：BOM 容忍、冒号半全角皆收、宽高分隔
     * x/X/× 皆收——宽容只收字形变体（协议原文是半角冒号＋半角 x），结构性变形
     * （声明不在首行、非整行、宽高非数字）即非声明。宽高上限（chromium 单边纹理
     * 16384）的边界权威在 {@link WorkspaceRenders} 命令构造层早拒，本层只解析。
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\uFEFF?\\s*<!--\\s*print\\s*[:：]\\s*(\\d{1,5})\\s*[xX×]\\s*(\\d{1,5})\\s*-->\\s*$");

    private DesignPrints() {
    }

    /** 平面稿画幅（像素，＝渲 PNG 的固定画幅帧）。 */
    public record PrintSize(int width, int height) {
    }

    /**
     * 首行 → 画幅声明：声明在场即平面稿（渲 PNG 的依据）；不在场/解析不出＝界面稿
     * 或协议偏离（零渲染，稿保持 HTML 形态如实）。输入是稿 HTML 首行原文（首行
     * 读取命令的 stdout）。
     */
    public static Optional<PrintSize> declaredSizeOf(String firstLine) {
        if (firstLine == null || firstLine.isBlank()) {
            return Optional.empty();
        }
        String line = firstLine.lines().findFirst().map(String::strip).orElse("");
        var matcher = DECLARATION.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new PrintSize(
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
    }

    /**
     * HTML 稿判定（渲前集合的过滤器）：按扩展名（大小写不敏感）——设计稿目录里
     * 的 HTML 才可能是代码出图路（位图稿扩展名天然不在场）。
     */
    public static boolean htmlDraft(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".html");
    }

    /**
     * HTML 稿路径 → 同名 PNG 落点（双形态同存的位图形态）：换扩展名、目录与词干
     * 不动（{@code /design/poster-1.html} → {@code /design/poster-1.png}——PNG 即
     * 稿的位图正身，稿清单/画布以此呈现）。无扩展名形态 defensive 拒收（设计稿
     * 路径恒带扩展名）。
     */
    public static String pngPathOf(String htmlPath) {
        if (!htmlDraft(htmlPath)) {
            throw new IllegalArgumentException("平面稿路径须是 .html 形态: " + htmlPath);
        }
        return htmlPath.substring(0, htmlPath.length() - ".html".length()) + ".png";
    }

    /**
     * 稿首行读取命令（画幅声明的直提通道）：存在守卫占退出码 1（源不在——同
     * {@link WorkspaceRenders} 命令口径），stdout＝首行原文（可能带 BOM／尾换行，
     * 解析层容忍）。
     */
    public static String headFirstLineCommand(String relativePath) {
        return ContainerCommands.existenceGuard(relativePath) + " head -n 1 \"$p\"";
    }
}
