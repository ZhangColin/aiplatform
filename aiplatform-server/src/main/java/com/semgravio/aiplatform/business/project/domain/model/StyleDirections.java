package com.semgravio.aiplatform.business.project.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风格方向库（#543，ADR-0052 切片①——平台常量正本，随发版维护、不进基座镜像）：
 * 平台侧预置的风格方向集合，首批 8 套含缺省套（领域气质谱系覆盖）。每套＝
 * <b>token 值集</b>（色板/字体对/圆角/阴影/密度——基座语义变量名 {@code --*}
 * 的确定性值映射，物化时直刷 {@code :root}）＋<b>人读规则句</b>（密度/阴影/
 * 字体气质 guidance，随 DESIGN.md 规则面交付）——双件同构设计规范。模型只在
 * 库内选/微调、不从零配色（防生成式规范质量不可靠）；扩容＝运营动作（改本
 * 常量随发版）；准入＝人审＋WCAG 对比度一次性机械校验（
 * {@code StyleDirectionLibraryValidationTest} 建库工具、可重复跑、不进运行时）。
 *
 * <p>选取＝模型代选内嵌 PRD 撰写同一轮：主智能体 systemPrompt 每轮经
 * {@link #catalogMarkdown()} 动态装配注入目录视图（正本单源在此，不双写），
 * 写 PRD 时在「关键约束」章落「风格方向：&lt;名称&gt;」；物化通道经
 * {@link #directionOfPrd(String)} 从 PRD 正本确定性解析回方向（字段缺失/库无
 * 此名＝落缺省套兜底，不卡生成）。</p>
 *
 * <p>纯函数无依赖；token 名集合规（基座语义变量）与 WCAG 对比度归测试面
 * （结构测试＋建库校验工具）。</p>
 */
public final class StyleDirections {

    /** 缺省套稳定键（兜底方向——物化失败/字段缺失落它）。 */
    public static final String DEFAULT_KEY = "default";

    /** PRD「关键约束」章风格方向行定位（列表符/加粗容忍〔含 `**:**` 包裹形〕、中英文冒号、行尾截停）。 */
    private static final Pattern PRD_FIELD = Pattern.compile(
            "(?m)^[ \\t]*(?:[-*•][ \\t]*)?\\*{0,2}[ \\t]*风格方向[ \\t]*\\*{0,2}[ \\t]*[:：][ \\t]*(.+?)[ \\t]*$");

    /**
     * 「关键约束」章标题定位（#～###### 级容忍；行包含即锚——真实模型产出带中文/
     * 阿拉伯数字序号〔「## 五、关键约束」「## 5. 关键约束」〕与可能尾缀，整行精确
     * 匹配会漏锚致全量落缺省套，项目 380985400431490033 实录）；章体止于下一
     * 同形标题行。特异词在固定模板其余章名中不出现，正文提及不带 heading 前缀不匹配。
     */
    private static final Pattern CONSTRAINTS_CHAPTER = Pattern.compile(
            "(?m)^#{1,6}[ \\t]+.*关键约束");

    /**
     * 一套风格方向（双件同构设计规范＋代选目录元数据）。
     *
     * @param key     稳定键（平台侧锚定——库表/URL 安全形；人读呈现用 {@link #name}）
     * @param name    人读方向名（PRD 字段值形——「风格方向：&lt;name&gt;」）
     * @param domains 领域气质谱系一句（目录视图给模型代选参考）
     * @param tokens  token 值集（有序：基座语义变量名 → 值；色板/字体对/圆角/阴影/密度）
     * @param rules   人读规则句（DESIGN.md 规则面的方向段正文）
     */
    public record Direction(String key, String name, String domains,
            Map<String, String> tokens, String rules) {

        public Direction {
            // 保序不可变（Map.copyOf 不保序——物化刷值序即库序，确定性要求保序）
            tokens = Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
        }
    }

    /** 缺省套（清透中性——基座近义的友好化微调，兜底永远在场）。 */
    private static final Direction DEFAULT = direction(DEFAULT_KEY, "清透中性",
            "通用/缺省——拿不准领域气质时的稳妥起点（基座近义微调）",
            tokens(
                    "--background", "oklch(1 0 0)",
                    "--foreground", "oklch(0.21 0.01 255)",
                    "--card", "oklch(1 0 0)",
                    "--card-foreground", "oklch(0.21 0.01 255)",
                    "--popover", "oklch(1 0 0)",
                    "--popover-foreground", "oklch(0.21 0.01 255)",
                    "--primary", "oklch(0.46 0.03 255)",
                    "--primary-foreground", "oklch(0.985 0 0)",
                    "--secondary", "oklch(0.96 0.005 255)",
                    "--secondary-foreground", "oklch(0.31 0.02 255)",
                    "--muted", "oklch(0.96 0.005 255)",
                    "--muted-foreground", "oklch(0.50 0.01 255)",
                    "--accent", "oklch(0.94 0.01 255)",
                    "--accent-foreground", "oklch(0.31 0.02 255)",
                    "--border", "oklch(0.91 0.005 255)",
                    "--input", "oklch(0.91 0.005 255)",
                    "--ring", "oklch(0.55 0.03 255)",
                    "--radius", "0.625rem",
                    "--font-sans", "'Inter', 'SF Pro Text', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"),
            """
                    整体清透中性：白底、深墨蓝灰主色，饱和度克制，与基座同源微調。
                    圆角走基座中档（--radius 0.625rem），阴影只用默认轻档（shadow-sm/md）。
                    密度适中：列表与卡片间距用 Tailwind 默认 scale，不刻意挤压也不铺张。
                    标题字重 600、正文 400；主色只给主按钮与关键状态，不大面积铺色。
                    """);

    /** 清新教育（teal 族——教育/学习/知识气质）。 */
    private static final Direction FRESH_EDUCATION = direction("fresh-education", "清新教育",
            "教育/学习/知识/课程——清透蓝绿、大圆角、疏朗留白",
            tokens(
                    "--background", "oklch(0.995 0.005 180)",
                    "--foreground", "oklch(0.25 0.03 210)",
                    "--card", "oklch(1 0 0)",
                    "--card-foreground", "oklch(0.25 0.03 210)",
                    "--popover", "oklch(1 0 0)",
                    "--popover-foreground", "oklch(0.25 0.03 210)",
                    "--primary", "oklch(0.52 0.11 180)",
                    "--primary-foreground", "oklch(0.99 0.005 180)",
                    "--secondary", "oklch(0.95 0.03 180)",
                    "--secondary-foreground", "oklch(0.35 0.06 195)",
                    "--muted", "oklch(0.95 0.03 180)",
                    "--muted-foreground", "oklch(0.50 0.04 200)",
                    "--accent", "oklch(0.93 0.05 165)",
                    "--accent-foreground", "oklch(0.35 0.06 195)",
                    "--border", "oklch(0.90 0.02 190)",
                    "--input", "oklch(0.90 0.02 190)",
                    "--ring", "oklch(0.60 0.10 180)",
                    "--radius", "0.875rem",
                    "--font-sans", "'Inter', 'SF Pro Text', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"),
            """
                    整体清新明亮：薄荷白底、蓝绿主色，饱和度克制不荧光。
                    圆角偏大（--radius 0.875rem），卡片轻阴影（shadow-sm），不用重投影。
                    密度偏疏：页面留白充裕、段落透气，列表行间给足呼吸感。
                    标题字重 600、正文 400；蓝绿只用于主按钮、进度与选中态等关键点。
                    """);

    /** 沉稳金融（深靛族——金融/财务/企业气质）。 */
    private static final Direction CALM_FINANCE = direction("calm-finance", "沉稳金融",
            "金融/财务/企业/数据看板——深靛蓝、直角小圆角、紧凑密度",
            tokens(
                    "--background", "oklch(0.995 0.003 270)",
                    "--foreground", "oklch(0.22 0.02 270)",
                    "--card", "oklch(1 0 0)",
                    "--card-foreground", "oklch(0.22 0.02 270)",
                    "--popover", "oklch(1 0 0)",
                    "--popover-foreground", "oklch(0.22 0.02 270)",
                    "--primary", "oklch(0.42 0.10 275)",
                    "--primary-foreground", "oklch(0.985 0.003 270)",
                    "--secondary", "oklch(0.95 0.015 275)",
                    "--secondary-foreground", "oklch(0.32 0.04 275)",
                    "--muted", "oklch(0.95 0.015 275)",
                    "--muted-foreground", "oklch(0.50 0.025 275)",
                    "--accent", "oklch(0.93 0.025 280)",
                    "--accent-foreground", "oklch(0.32 0.04 275)",
                    "--border", "oklch(0.89 0.012 275)",
                    "--input", "oklch(0.89 0.012 275)",
                    "--ring", "oklch(0.55 0.08 275)",
                    "--radius", "0.25rem",
                    "--font-sans", "'Inter', 'SF Pro Text', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"),
            """
                    整体沉稳专业：近白冷灰底、深靛蓝主色，传达可信赖感。
                    圆角偏小（--radius 0.25rem）、阴影克制（shadow-sm 封顶）——边界利落、信息密而不挤。
                    密度偏紧凑：表格与看板行高压榨到可读下限，数字右对齐、金额加粗。
                    标题字重 600-700；深靛只给主按钮与核心数据强调，警示用 destructive 不动主色。
                    """);

    /** 活力消费（珊瑚橙族——电商/消费/活动气质）。 */
    private static final Direction VIBRANT_COMMERCE = direction("vibrant-commerce", "活力消费",
            "电商/消费/活动/营销——暖橙珊瑚、大圆角、促销感强调色",
            tokens(
                    "--background", "oklch(0.995 0.005 60)",
                    "--foreground", "oklch(0.25 0.02 40)",
                    "--card", "oklch(1 0 0)",
                    "--card-foreground", "oklch(0.25 0.02 40)",
                    "--popover", "oklch(1 0 0)",
                    "--popover-foreground", "oklch(0.25 0.02 40)",
                    "--primary", "oklch(0.50 0.14 35)",
                    "--primary-foreground", "oklch(0.99 0.005 40)",
                    "--secondary", "oklch(0.95 0.03 50)",
                    "--secondary-foreground", "oklch(0.35 0.05 35)",
                    "--muted", "oklch(0.95 0.03 50)",
                    "--muted-foreground", "oklch(0.50 0.03 45)",
                    "--accent", "oklch(0.92 0.06 60)",
                    "--accent-foreground", "oklch(0.35 0.05 35)",
                    "--border", "oklch(0.90 0.02 50)",
                    "--input", "oklch(0.90 0.02 50)",
                    "--ring", "oklch(0.58 0.12 40)",
                    "--radius", "0.75rem",
                    "--font-sans", "'Inter', 'SF Pro Text', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"),
            """
                    整体温暖有行动力：奶白底、珊瑚橙主色，促销与主按钮一眼可点。
                    圆角大（--radius 0.75rem），价格与 CTA 加粗放大——转化导向。
                    密度适中：商品卡网格紧凑但留白不断气，标签胶囊化。
                    强调色克制：橙只给主按钮/价签/角标，辅助信息用中性 muted，不全场铺橙。
                    """);

    /** 自然健康（草木绿族——健康/医疗/生活方式气质）。 */
    private static final Direction NATURAL_WELLNESS = direction("natural-wellness", "自然健康",
            "健康/医疗/生活方式/有机——草木绿、大地米、柔和圆角",
            tokens(
                    "--background", "oklch(0.985 0.01 120)",
                    "--foreground", "oklch(0.26 0.02 150)",
                    "--card", "oklch(0.995 0.008 110)",
                    "--card-foreground", "oklch(0.26 0.02 150)",
                    "--popover", "oklch(0.995 0.008 110)",
                    "--popover-foreground", "oklch(0.26 0.02 150)",
                    "--primary", "oklch(0.48 0.10 155)",
                    "--primary-foreground", "oklch(0.99 0.005 140)",
                    "--secondary", "oklch(0.94 0.03 130)",
                    "--secondary-foreground", "oklch(0.34 0.05 150)",
                    "--muted", "oklch(0.94 0.03 130)",
                    "--muted-foreground", "oklch(0.50 0.03 140)",
                    "--accent", "oklch(0.93 0.04 100)",
                    "--accent-foreground", "oklch(0.34 0.05 150)",
                    "--border", "oklch(0.89 0.02 130)",
                    "--input", "oklch(0.89 0.02 130)",
                    "--ring", "oklch(0.56 0.08 155)",
                    "--radius", "0.75rem",
                    "--font-sans", "'Inter', 'SF Pro Text', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"),
            """
                    整体自然疗愈：大地米底、草木绿主色，低刺激、呼吸感强。
                    圆角柔和（--radius 0.75rem），阴影极轻（shadow-sm）或不用——干净有机。
                    密度偏疏：图文排布留足空白，长文行宽控制在舒适区间。
                    绿只用于主按钮与健康正向状态；警示/异常一律 destructive，不用绿表达负面。
                    """);

    /** 科技前沿（电紫族——工具/SaaS/开发者气质）。 */
    private static final Direction TECH_FORWARD = direction("tech-forward", "科技前沿",
            "工具/SaaS/开发者/AI——电紫蓝、暗夜可用、锐利小圆角",
            tokens(
                    "--background", "oklch(0.995 0.005 300)",
                    "--foreground", "oklch(0.23 0.02 300)",
                    "--card", "oklch(1 0 0)",
                    "--card-foreground", "oklch(0.23 0.02 300)",
                    "--popover", "oklch(1 0 0)",
                    "--popover-foreground", "oklch(0.23 0.02 300)",
                    "--primary", "oklch(0.45 0.16 295)",
                    "--primary-foreground", "oklch(0.985 0.005 300)",
                    "--secondary", "oklch(0.95 0.02 295)",
                    "--secondary-foreground", "oklch(0.32 0.05 295)",
                    "--muted", "oklch(0.95 0.02 295)",
                    "--muted-foreground", "oklch(0.50 0.03 300)",
                    "--accent", "oklch(0.93 0.04 290)",
                    "--accent-foreground", "oklch(0.32 0.05 295)",
                    "--border", "oklch(0.89 0.015 295)",
                    "--input", "oklch(0.89 0.015 295)",
                    "--ring", "oklch(0.55 0.14 295)",
                    "--radius", "0.375rem",
                    "--font-sans", "'Inter', 'SF Pro Text', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"),
            """
                    整体锐利现代：冷白底、电紫主色——科技感的来源是克制的 chroma 对比。
                    圆角小（--radius 0.375rem），阴影少而准（hover 态 shadow-sm）。
                    密度偏紧凑：仪表盘式信息层级，等宽数字（tabular-nums）用于指标。
                    紫只给主按钮与激活态；代码/终端面用 mono 字体族，不混入展示字体。
                    """);

    /** 文艺内容（纸墨暖族——内容/出版/博客气质）。 */
    private static final Direction LITERARY_EDITORIAL = direction("literary-editorial", "文艺内容",
            "内容/出版/博客/杂志——暖纸墨、衬线标题、编辑版式",
            tokens(
                    "--background", "oklch(0.975 0.012 90)",
                    "--foreground", "oklch(0.27 0.02 60)",
                    "--card", "oklch(0.99 0.008 85)",
                    "--card-foreground", "oklch(0.27 0.02 60)",
                    "--popover", "oklch(0.99 0.008 85)",
                    "--popover-foreground", "oklch(0.27 0.02 60)",
                    "--primary", "oklch(0.42 0.07 50)",
                    "--primary-foreground", "oklch(0.98 0.01 80)",
                    "--secondary", "oklch(0.94 0.02 80)",
                    "--secondary-foreground", "oklch(0.35 0.04 55)",
                    "--muted", "oklch(0.94 0.02 80)",
                    "--muted-foreground", "oklch(0.51 0.025 65)",
                    "--accent", "oklch(0.92 0.04 70)",
                    "--accent-foreground", "oklch(0.35 0.04 55)",
                    "--border", "oklch(0.88 0.02 75)",
                    "--input", "oklch(0.88 0.02 75)",
                    "--ring", "oklch(0.55 0.06 60)",
                    "--radius", "0.125rem",
                    "--font-sans", "'Georgia', 'Songti SC', 'SimSun', 'Noto Serif CJK SC', serif"),
            """
                    整体纸墨书卷气：暖纸底、深棕墨——像一本排版精良的杂志。
                    衬线字体贯穿（--font-sans 已指向衬线栈）：标题与正文同族、靠字重与字号分层。
                    圆角近乎直角（--radius 0.125rem），基本无阴影——靠线与留白分版。
                    密度随内容走：正文行长与行高优先舒适（行高 1.7 档），图片留白致敬纸面。
                    """);

    /** 温暖服务（陶土暖族——本地服务/社区/餐饮气质）。 */
    private static final Direction WARM_SERVICE = direction("warm-service", "温暖服务",
            "本地服务/社区/餐饮/家政——陶土暖棕、圆角亲和、生活气",
            tokens(
                    "--background", "oklch(0.99 0.008 70)",
                    "--foreground", "oklch(0.27 0.02 40)",
                    "--card", "oklch(1 0 0)",
                    "--card-foreground", "oklch(0.27 0.02 40)",
                    "--popover", "oklch(1 0 0)",
                    "--popover-foreground", "oklch(0.27 0.02 40)",
                    "--primary", "oklch(0.48 0.10 40)",
                    "--primary-foreground", "oklch(0.99 0.005 60)",
                    "--secondary", "oklch(0.94 0.03 60)",
                    "--secondary-foreground", "oklch(0.35 0.04 40)",
                    "--muted", "oklch(0.94 0.03 60)",
                    "--muted-foreground", "oklch(0.51 0.025 50)",
                    "--accent", "oklch(0.93 0.04 80)",
                    "--accent-foreground", "oklch(0.35 0.04 40)",
                    "--border", "oklch(0.90 0.02 60)",
                    "--input", "oklch(0.90 0.02 60)",
                    "--ring", "oklch(0.56 0.08 45)",
                    "--radius", "0.875rem",
                    "--font-sans", "'Inter', 'SF Pro Text', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"),
            """
                    整体温暖亲和：奶油底、陶土棕主色——社区小店式的熟络感。
                    圆角大（--radius 0.875rem），卡片软阴影（shadow-sm）——像贴纸一样的轻快感。
                    密度适中：服务卡片大图大字，按钮文案直白（「立即预约」而非「提交」）。
                    陶土色只给主按钮与标签；成功/提示态用 muted 中性，避免彩虹式多色。
                    """);

    /** 库全量（首批 8 套——首为缺省套；顺序即目录呈现序）。 */
    private static final List<Direction> ALL = List.of(DEFAULT, FRESH_EDUCATION, CALM_FINANCE,
            VIBRANT_COMMERCE, NATURAL_WELLNESS, TECH_FORWARD, LITERARY_EDITORIAL, WARM_SERVICE);

    private StyleDirections() {
    }

    /** 库全量（不可变——扩容＝改本类常量随发版）。 */
    public static List<Direction> all() {
        return ALL;
    }

    /** 缺省套（兜底方向）。 */
    public static Direction defaultDirection() {
        return DEFAULT;
    }

    /**
     * 方向解析（人读名精确匹配为主、稳定键为辅）：库无此名/空＝{@code null}
     * （调用方落缺省套——解析不猜）。名称匹配去空白、剥 markdown 加粗。
     */
    public static Direction byName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.strip().replace("**", "").strip();
        if (normalized.isEmpty()) {
            return null;
        }
        for (Direction direction : ALL) {
            if (direction.name().equals(normalized)) {
                return direction;
            }
        }
        for (Direction direction : ALL) {
            if (direction.key().equals(normalized)) {
                return direction;
            }
        }
        return null;
    }

    /**
     * PRD 正本 → 选定方向（物化通道读腿，#543）：「关键约束」章风格方向行确定性
     * 解析——章标题 #～###### 级容忍、heading 行包含「关键约束」即锚（序号/前后缀
     * 容忍，模型实产带「五、」类序号）、章体止于下一标题行（他章提及「风格方向：」
     * 不算数〔如功能清单备注〕，字段单义锚在关键约束章）；行定位容忍列表符/加粗/
     * 中英文冒号；值取行尾、截停于（或（（微调注释不入场内名）。章缺失/字段缺失/
     * 解析不出/库无此名＝缺省套兜底（生成不被风格层卡死）。纯函数：同一 PRD
     * 文本同一方向。
     */
    public static Direction directionOfPrd(String prdContent) {
        if (prdContent == null || prdContent.isBlank()) {
            return DEFAULT;
        }
        Matcher chapter = CONSTRAINTS_CHAPTER.matcher(prdContent);
        if (!chapter.find()) {
            return DEFAULT;
        }
        String body = prdContent.substring(chapter.end());
        Matcher nextHeading = Pattern.compile("(?m)^#{1,6}[ \\t]").matcher(body);
        if (nextHeading.find()) {
            body = body.substring(0, nextHeading.start());
        }
        Matcher matcher = PRD_FIELD.matcher(body);
        if (!matcher.find()) {
            return DEFAULT;
        }
        String field = matcher.group(1).strip();
        int paren = firstIndexOfAny(field, '（', '(');
        if (paren >= 0) {
            field = field.substring(0, paren).strip();
        }
        Direction direction = byName(field);
        return direction != null ? direction : DEFAULT;
    }

    /**
     * 目录注入块（主智能体 systemPrompt 每轮动态装配——正本单源在此）：
     * 方向名＋领域气质一句的清单视图＋选取协议句（写 PRD 时落「风格方向：&lt;名&gt;」
     * 于关键约束节、可按领域气质微调、不从零配色、不占访谈提问额度）。
     */
    public static String catalogMarkdown() {
        StringBuilder block = new StringBuilder(
                "\n\n【风格方向库·目录】（平台预置，属工作协议一部分）写 PRD 时从下列方向中")
                .append("选定其一，在「关键约束」章写明「风格方向：<方向名>」——必须选与项目")
                .append("领域气质最匹配的方向（如教育类选清新教育、企业/工具类选科技前沿或沉稳")
                .append("金融），缺省套仅当其余方向无一贴近领域气质时才兜底，不得因赶工/省事选")
                .append("缺省（2026-11-18 收口赶工落缺省套实案修复，ADR-0052 补记；可按领域气质")
                .append("微调参数写在同句括号内、不从零自造配色；本选取不占访谈提问额度）：");
        for (Direction direction : ALL) {
            block.append("\n- ").append(direction.name()).append("：").append(direction.domains());
        }
        return block.toString();
    }

    /** 首个命中字符的下标（无＝-1）。 */
    private static int firstIndexOfAny(String text, char... candidates) {
        int found = -1;
        for (char candidate : candidates) {
            int index = text.indexOf(candidate);
            if (index >= 0 && (found < 0 || index < found)) {
                found = index;
            }
        }
        return found;
    }

    /** 方向构造（token 字面有序定型——调用侧名值对平铺）。 */
    private static Direction direction(String key, String name, String domains,
            Map<String, String> tokens, String rules) {
        return new Direction(key, name, domains, tokens, rules.strip());
    }

    /** token 字面 → 有序不可变 Map（LinkedHashMap 定型——物化刷值序即此序）。 */
    private static Map<String, String> tokens(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("token 名值须成对");
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            tokens.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return Collections.unmodifiableMap(tokens);
    }
}
