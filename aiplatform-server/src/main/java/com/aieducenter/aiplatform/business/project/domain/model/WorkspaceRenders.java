package com.aieducenter.aiplatform.business.project.domain.model;

/**
 * 位图出口内核（#284，ADR-0026/0027）的规则单点：容器内把设计稿渲成 PNG 的
 * 命令构造。两条路——HTML→PNG 走 chromium 保真渲染（「LLM 产任意 HTML→PNG 保真」
 * 的唯一解，Playwright 驱动、画幅随载荷＝固定画幅帧语义）；SVG→PNG 走 resvg
 * 零浏览器旁路（vector 原生物件的特价路径，不启浏览器）。与画布呈现分家：设计稿
 * tab 呈现＝用户浏览器 iframe live 渲染零 chromium，服务端渲染只管出口（出稿即渲、
 * 下载位图化、导出衍生都由本内核供血，触发点各自接线）。
 *
 * <p>收拢三件事——出入路径守卫（渲染面＝交付文件面，{@link ProjectFiles#isViewable}
 * 同一道防线）、画幅边界（chromium 单边纹理上限内早拒）、命令构造（存在守卫与
 * 引号转义归 {@link ContainerCommands} 公共骨架）。退出码契约：0 = 渲染成（stdout
 * 首行＝PNG 字节数回执）、1 = 源文件不存在、其余（渲染器自身的 2/3、回执失败
 * 归一的 4）＝渲染失败——调用方按「非 0 非 1 即渲染失败」归一，不细分。渲染器
 * 本体在 dev 镜像 {@code /opt/render}（随 DEV_IMAGE 版本走）。纯函数无依赖；
 * 容器活体保真归 WorkspaceRendersLiveTest。</p>
 */
public final class WorkspaceRenders {

    /** 渲染器在镜像内的安装位（build 期随 render/ 工程就位，DEV_IMAGE 版本承载）。 */
    private static final String RENDER_HOME = "/opt/render";

    /**
     * 画幅单边上界：chromium 离屏渲染的单边纹理上限（16384）——超界画幅渲染必败，
     * 命令构造层诚实早拒（不进容器空跑）；下界 1（零/负画幅无意义）。
     */
    public static final int MAX_VIEWPORT_SIDE = 16384;

    private WorkspaceRenders() {
    }

    /**
     * HTML→PNG 渲染命令（chromium 保真渲染）：{@code source}（设计稿 HTML）与
     * {@code target}（PNG 衍生落点）为工作区相对路径，画幅（像素）＝固定画幅帧。
     * 退出码契约见类注释（0 = 成＋字节回执、1 = 源不在、其余 = 渲染失败——含
     * stat 回执失败归一的 4，不与「源不在」的 1 混淆）。
     */
    public static String htmlToPngCommand(String sourcePath, String targetPath,
            int width, int height) {
        requireViewable(sourcePath, targetPath);
        if (width < 1 || height < 1
                || width > MAX_VIEWPORT_SIDE || height > MAX_VIEWPORT_SIDE) {
            throw new IllegalArgumentException(
                    "画幅须在 1~" + MAX_VIEWPORT_SIDE + " 像素内: " + width + "x" + height);
        }
        return guardAndRun(sourcePath, targetPath,
                "node " + RENDER_HOME + "/render-html.mjs \"$p\" \"$o\" " + width + " " + height);
    }

    /**
     * SVG→PNG 渲染命令（resvg 零浏览器旁路，#284）：vector 原生物件的 PNG 衍生特价
     * 路径——不启浏览器、原生尺寸渲染（多分辨率衍生 2x/4x 是备案项不预埋）。退出码
     * 与回执口径同 {@link #htmlToPngCommand}。
     */
    public static String svgToPngCommand(String sourcePath, String targetPath) {
        requireViewable(sourcePath, targetPath);
        return guardAndRun(sourcePath, targetPath,
                "node " + RENDER_HOME + "/render-svg.mjs \"$p\" \"$o\"");
    }

    /** 出入路径同守卫（渲染面＝交付文件面，源与产物都不是非交付目录的客人）。 */
    private static void requireViewable(String sourcePath, String targetPath) {
        if (!ProjectFiles.isViewable(sourcePath) || !ProjectFiles.isViewable(targetPath)) {
            throw new IllegalArgumentException(
                    "渲染出入路径必须是工作区锚定的可浏览路径: " + sourcePath + " → " + targetPath);
        }
    }

    /**
     * 守卫 + 渲染 + 字节回执（两路命令的公共骨架）：存在守卫占退出码 1（源不在），
     * 渲染与 stat 回执任一失败归一为 4（渲染失败——不与「源不在」混淆），成功时
     * stdout 首行＝PNG 字节数。
     */
    private static String guardAndRun(String sourcePath, String targetPath, String renderer) {
        return ContainerCommands.existenceGuard(sourcePath)
                + " o=" + ContainerCommands.quoted(targetPath) + ";"
                + " " + renderer
                + " && stat -c %s \"$o\" || exit 4";
    }
}
