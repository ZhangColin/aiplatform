package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.ArrayList;
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
 * 纯函数无依赖；提炼保真（token 与稿内 :root 一致、参数与物化草稿一致）与遵守面
 * 落盘保真（:root 刷值/收窄块/lint 回执）归容器活体测试。
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
     * 〔design/ 物料侧车 vs materials/〕、载荷同为 stdin 灌入，命令体归
     * {@link ContainerCommands#stdinWriteCommand} 单点）。
     */
    public static String sidecarWriteCommand(String relativePath) {
        if (!ProjectFiles.isViewable(relativePath)) {
            throw new IllegalArgumentException("非可浏览路径，命令构造拒绝: " + relativePath);
        }
        return ContainerCommands.stdinWriteCommand(relativePath);
    }

    // ===== 遵守面（#296 消费侧：结构物化的确定性改写——与提炼面对偶，平台非模型动作） =====

    /** 基座样式文件（ADR-0013 固定栈模板位；token 生效位＝其 :root 与 @theme）。 */
    public static final String BASELINE_GLOBALS_CSS = "src/app/globals.css";

    /** 平台 @theme 块边界标记（幂等改写的锚；内容平台维护，执行体勿手改）。 */
    public static final String SPEC_THEME_BEGIN =
            "/* ===== 平台设计规范·开始（平台维护，随定稿刷新，勿手改）===== */";
    public static final String SPEC_THEME_END = "/* ===== 平台设计规范·结束 ===== */";

    /** 既有标记块的定位（整块替换——随定稿刷新）。 */
    private static final Pattern SPEC_THEME_BLOCK = Pattern.compile(
            Pattern.quote(SPEC_THEME_BEGIN) + ".*?" + Pattern.quote(SPEC_THEME_END),
            Pattern.DOTALL);

    /** @import 行定位（无标记块时的插入位＝最后一条 @import 之后，先于一切 @theme）。 */
    private static final Pattern IMPORT_LINE = Pattern.compile("(?m)^@import[^;]*;");

    /** 品牌附加色命名约定（写稿协议与遵守面共用：--brand-N 经 @theme 暴露为工具类）。 */
    private static final Pattern BRAND_TOKEN = Pattern.compile("^--brand-[\\w-]+$");

    /**
     * {@code #refreshRootValues} 的 CSS 值替换正则（逐 token 现拼，name 经
     * {@link Pattern#quote} 无正则元字符面）：声明值至分号——与提炼
     * {@link #DECLARATION} 同域（对称往返）。
     */
    private static final Pattern valueDeclarationOf(String name) {
        return Pattern.compile(Pattern.quote(name) + "\\s*:\\s*[^;]+");
    }

    /**
     * globals.css 的 :root <b>值刷换</b>（#296 遵守三件套①：中途更新＝平台先确定性
     * 刷值——语义 token 引用不变时刷值即全局换肤，裸色残留交执行体修＝lint 面）：
     * spec 名集内已声明的 token 原位换值（同名多次出现全换——同值一致，无 CSS
     * 覆盖歧义）、未声明的追加进块尾，<b>块内其他内容（执行体自加 token、注释、
     * 空白）原样保留</b>。无 :root 块（执行体重构了样式文件）＝整块追加在文件尾
     * ——token 生效位由平台落定，不猜不弃。幂等：同输入同输出、重复刷值稳定。
     */
    public static String refreshRootValues(String css, Map<String, String> tokens) {
        if (css == null || css.isBlank() || tokens == null || tokens.isEmpty()) {
            return css;
        }
        Matcher block = ROOT_BLOCK.matcher(css);
        if (!block.find()) {
            StringBuilder appended = new StringBuilder(css).append("\n:root {\n");
            tokens.forEach((name, value) -> appended
                    .append("  ").append(name).append(": ").append(value).append(";\n"));
            return appended.append("}\n").toString();
        }
        String inner = block.group(1);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            Matcher declared = valueDeclarationOf(entry.getKey()).matcher(inner);
            StringBuffer rewritten = new StringBuffer();
            boolean found = false;
            while (declared.find()) {
                found = true;
                declared.appendReplacement(rewritten, Matcher.quoteReplacement(
                        entry.getKey() + ": " + entry.getValue()));
            }
            if (found) {
                declared.appendTail(rewritten);
                inner = rewritten.toString();
            }
            else {
                missing.add(entry.getKey());
            }
        }
        if (!missing.isEmpty()) {
            StringBuilder tail = new StringBuilder(
                    inner.isBlank() || inner.endsWith("\n") ? inner : inner + "\n");
            for (String name : missing) {
                tail.append("  ").append(name).append(": ").append(tokens.get(name)).append(";\n");
            }
            inner = tail.toString();
        }
        return css.substring(0, block.start(1)) + inner + css.substring(block.end(1));
    }

    /**
     * 平台 @theme 块（调色板收窄＋品牌色暴露）幂等改写（#296 ADR-0028：Tailwind v4
     * {@code --color-*: initial} 默认调色板工具类移除、只余语义 token——硬约束仅带
     * 规范项目写入）：既有标记块整块替换（随定稿刷新——色板演进不残留旧映射）；
     * 无块＝插在最后一条 @import 之后——<b>必须先于 {@code @theme inline}</b>（实测
     * 序即语义：重置块在后会连 @theme inline 的语义映射一并清掉）。
     */
    public static String upsertSpecTheme(String css, String themeBlock) {
        Matcher existing = SPEC_THEME_BLOCK.matcher(css);
        if (existing.find()) {
            return existing.replaceFirst(Matcher.quoteReplacement(themeBlock));
        }
        Matcher importLine = IMPORT_LINE.matcher(css);
        int insertAt = 0;
        while (importLine.find()) {
            insertAt = importLine.end();
        }
        if (insertAt == 0) {
            return themeBlock + "\n\n" + css;
        }
        return css.substring(0, insertAt) + "\n\n" + themeBlock + "\n" + css.substring(insertAt);
    }

    /**
     * 平台 @theme 块正文：调色板收窄行＋品牌附加色暴露（{@code --brand-N} →
     * {@code --color-brand-N: var(--brand-N)}——工具类 {@code bg-brand-1} 等可用，
     * 值单源在 :root）。brand 集＝token 集中的 {@code --brand-*}（参数面色板先经
     * {@link #brandTokensOf} 物化为同名 token 进 :root——两面同源）；无品牌色＝
     * 仅收窄行。
     */
    public static String specThemeBlockOf(Map<String, String> rootTokens) {
        StringBuilder block = new StringBuilder(SPEC_THEME_BEGIN).append('\n')
                .append("@theme {\n  --color-*: initial;\n");
        rootTokens.keySet().stream()
                .filter(name -> BRAND_TOKEN.matcher(name).matches())
                .forEach(name -> block.append("  --color").append(name.substring(1))
                        .append(": var(").append(name).append(");\n"));
        return block.append("}\n").append(SPEC_THEME_END).toString();
    }

    /**
     * 参数面色板 → 品牌附加色 token 集（{@code --brand-N} 名序即色板序——确定性
     * 映射不猜语义：色板是无角色色集，指派 primary/secondary 即模型判读）。
     * 空白剔除；色板规模正本已截 {@link #MAX_PALETTE_ENTRIES}（防御同律）。
     */
    public static Map<String, String> brandTokensOf(List<String> palette) {
        Map<String, String> brands = new LinkedHashMap<>();
        if (palette != null) {
            List<String> colors = palette.stream()
                    .filter(color -> color != null && !color.isBlank())
                    .map(String::strip)
                    .limit(MAX_PALETTE_ENTRIES)
                    .toList();
            for (int i = 0; i < colors.size(); i++) {
                brands.put("--brand-" + (i + 1), colors.get(i));
            }
        }
        return brands;
    }

    /**
     * DESIGN.md 规则文件正文（#296 ADR-0028 双件之规则面——人读正本落工作区、经
     * AGENTS.md 指路每轮读；#297 设计资产包规范文件同一物）：平台确定性拼装＝
     * 正本事实直陈（token 表 / 品牌色板 / 风格短语）＋遵守条目常量，非模型动作。
     * token 面出 token 表；参数面出品牌色板＋风格；各按在场呈现。
     */
    public static String designRulesMarkdown(String sourceDraftPath, Map<String, String> tokens,
            List<String> palette, String style) {
        StringBuilder md = new StringBuilder("# 设计规范\n\n")
                .append("本项目的设计一致性正本（各设计物共用、随设计定稿刷新，平台维护）。")
                .append("系统实现每轮开工先读本文件：界面的颜色、字体、圆角与组件风格")
                .append("与定稿设计稿保持一致。\n\n")
                .append("- 视觉正源（定稿设计稿，先读稿再动手）：").append(sourceDraftPath).append('\n')
                .append("- 机器正本：").append(BASELINE_GLOBALS_CSS)
                .append("（:root token 值与调色板收窄由平台随定稿刷新）\n");
        if (tokens != null && !tokens.isEmpty()) {
            md.append("\n## 设计 token\n\n| token | 值 |\n| --- | --- |\n");
            tokens.forEach((name, value) -> md
                    .append("| `").append(name).append("` | `").append(value).append("` |\n"));
        }
        Map<String, String> brands = brandTokensOf(palette);
        if (!brands.isEmpty()) {
            md.append("\n## 品牌色板\n\n");
            brands.forEach((name, value) -> md.append("- `").append(name).append("`：")
                    .append(value).append("（工具类 ").append("bg").append(name.substring(1))
                    .append(" 等）\n"));
        }
        if (style != null && !style.isBlank()) {
            md.append("\n## 风格\n\n- ").append(style.strip()).append('\n');
        }
        md.append("""

                ## 遵守条目（系统实现一律照此）

                1. 样式 token 优先：颜色、圆角、字体引用上表 token（如 `bg-primary`、`text-muted-foreground`、`rounded-lg`）；禁止裸色（`bg-red-500`、`bg-[#fff]`）、禁止任意值（`p-[13px]`）、禁止 inline style。
                2. 默认调色板工具类已收窄移除（Tailwind `--color-*: initial`），可用颜色＝上表 token 与品牌色；需要新颜色时在 `:root` 定义 token 并在 `@theme` 暴露，不在样式里写死色值。
                3. 间距与版式用 Tailwind 默认 scale（`p-4`、`text-sm` 等）。
                4. 收口时平台做样式合规扫描（@shadcn/lint），违规项会被要求修正。
                """);
        return md.toString();
    }
}
