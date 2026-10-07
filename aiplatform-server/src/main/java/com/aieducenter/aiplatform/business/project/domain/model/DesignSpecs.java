package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设计规范提炼的规则单点（#295，ADR-0028「伴随产出＋确定性提取、不经模型
 * 判读」——对偶 {@link DesignPrints} 画幅声明的协议保证形态＋平台结构化直读）：
 * <ul>
 * <li><b>界面类直提</b>：写稿协议保证 token 集中放稿的 <b>唯一 {@code :root}</b>
 * 块（{@link com.aieducenter.aiplatform.business.project.domain.model.AgentProfile#DESIGNER}
 * 工作协议条款），平台在定稿时结构化直读——{@link #rootTokensOf}（同稿同产出
 * 的确定性，v0 Figma token 直读同构）；</li>
 * <li><b>平面类物化</b>：出图工具件的设计参数（色板/风格——参数即设计意图
 * 正身）随稿物化为侧车草稿 {@code design/{词干}.spec.json}
 * （{@link #sidecarPathOf} 同词干派生＋{@link #sidecarPayload} 载荷单点＋
 * {@link #sidecarWriteCommand} 写入命令），定稿时转正进项目正本；代码出图路
 * 的稿面是渲成 PNG，:root 在同名 HTML 源里——{@link #htmlSourcePathOf}
 * 同词干回落。</li>
 * </ul>
 * 纯函数无依赖；提炼保真（token 与稿内 :root 一致、参数与物化草稿一致）归
 * 容器活体测试。
 */
public final class DesignSpecs {

    /** :root 块定位（首个；token 块内无嵌套规则，值域不含 {@code }}）。 */
    private static final Pattern ROOT_BLOCK = Pattern.compile(":root\\s*\\{([^}]*)\\}");

    /** 块内声明（值至分号或块尾——容忍末声明缺分号；只认 {@code --} 名）。 */
    private static final Pattern DECLARATION = Pattern.compile("(--[\\w-]+)\\s*:\\s*([^;]+)");

    /** CSS 注释剥离（注释里的伪声明不是声明）。 */
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** 侧车扩展名（design/ 同词干——出图参数随稿物化的落点形）。 */
    public static final String SIDECAR_SUFFIX = ".spec.json";

    /** HTML 稿扩展名（代码出图路源稿——PNG 正身的同名回落取件锚）。 */
    public static final String HTML_SUFFIX = ".html";

    /** 色板规模上限（品牌附加色量级；超出截断——超规模色板进系统主题是噪音）。 */
    public static final int MAX_PALETTE_ENTRIES = 8;

    /** 风格短语上限（项目正本列宽同律——超出截断）。 */
    public static final int MAX_STYLE_LENGTH = 500;

    private DesignSpecs() {
    }

    /**
     * 稿 HTML 内容 → :root token 集（界面类确定性直提）：首个 {@code :root} 块内
     * 全部 {@code --名: 值} 声明按源序产出（非 token 声明不入集、注释剥离、末声明
     * 缺分号容忍、空值如实不入集、块内同名后者覆盖——CSS 语义）。无块/协议偏离
     * ＝空集（零提取不猜，提炼无所得不刷新正本）。
     */
    public static Map<String, String> rootTokensOf(String htmlContent) {
        Map<String, String> tokens = new LinkedHashMap<>();
        if (htmlContent == null || htmlContent.isBlank()) {
            return tokens;
        }
        Matcher block = ROOT_BLOCK.matcher(CSS_COMMENT.matcher(htmlContent).replaceAll(""));
        if (!block.find()) {
            return tokens;
        }
        Matcher declaration = DECLARATION.matcher(block.group(1));
        while (declaration.find()) {
            String value = declaration.group(2).strip();
            if (!value.isEmpty()) {
                tokens.put(declaration.group(1), value);
            }
        }
        return tokens;
    }

    /**
     * 位图稿路径 → 参数侧车路径（同词干派生）：{@code design/x.png} →
     * {@code design/x.spec.json}——图片模型路每张落盘稿各带一份参数草稿（悬卡
     * 删除删稿不删侧车：参数是出图事实，正本转正另随定稿锚定）。无扩展名形态
     * defensive 拒收（落点名恒带扩展名）。
     */
    public static String sidecarPathOf(String imagePath) {
        return swapSuffix(imagePath, SIDECAR_SUFFIX);
    }

    /**
     * 位图稿路径 → 同名 HTML 源路径（代码出图路回落：{@link DesignPrints#pngPathOf}
     * 的反向派生——{@code design/x.png} → {@code design/x.html}）。源不在（图片
     * 模型路无 HTML 孪生）＝读取层存在守卫如实空。
     */
    public static String htmlSourcePathOf(String imagePath) {
        return swapSuffix(imagePath, HTML_SUFFIX);
    }

    /** 换尾扩展名（词干不动；无点 defensive 拒收——稿路径恒带扩展名）。 */
    private static String swapSuffix(String path, String suffix) {
        if (path == null) {
            throw new IllegalArgumentException("稿路径不能为空: " + path);
        }
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot <= slash) {
            throw new IllegalArgumentException("稿路径须带扩展名: " + path);
        }
        return path.substring(0, dot) + suffix;
    }

    /**
     * 出图参数 → 侧车载荷（JSON 形单点——生产面〔出图物化〕与消费面〔定稿转正〕
     * 同一形状）：{@code palette} 色板（空白剔除、截 {@link #MAX_PALETTE_ENTRIES}）
     * ＋{@code style} 风格短语（截 {@link #MAX_STYLE_LENGTH}），两面各按在场呈现；
     * 皆空＝无设计意图，空 Map（调用方不物化空壳）。
     */
    public static Map<String, Object> sidecarPayload(List<String> palette, String style) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (palette != null) {
            List<String> colors = palette.stream()
                    .filter(color -> color != null && !color.isBlank())
                    .map(String::strip)
                    .limit(MAX_PALETTE_ENTRIES)
                    .toList();
            if (!colors.isEmpty()) {
                payload.put("palette", colors);
            }
        }
        if (style != null && !style.isBlank()) {
            payload.put("style", style.strip().length() <= MAX_STYLE_LENGTH
                    ? style.strip() : style.strip().substring(0, MAX_STYLE_LENGTH));
        }
        return payload;
    }

    /**
     * 侧车写入命令（对偶 {@link ProjectMaterials#uploadCommand} 形制——落点不同
     * 〔design/ 物料侧车 vs materials/〕、载荷同为 stdin 灌入）：父目录幂等落位、
     * {@code cat >} 接 stdin、stdout = stat 字节回执。路径经单引号包裹＋转义，
     * 无注入面。
     */
    public static String sidecarWriteCommand(String relativePath) {
        if (!ProjectFiles.isViewable(relativePath)) {
            throw new IllegalArgumentException("非可浏览路径，命令构造拒绝: " + relativePath);
        }
        return "p=" + ContainerCommands.quoted(relativePath)
                + "; d=${p%/*}; mkdir -p \"$d\" && cat > \"$p\" && stat -c %s \"$p\"";
    }
}
