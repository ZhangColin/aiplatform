package com.aieducenter.aiplatform.business.project.domain.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.BinaryExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 位图出口内核容器活体（#284，B0 §5 副作用以真实状态为准）：真 dev 容器（0.11
 * 镜像，chromium＋resvg 已内置）里跑 {@link WorkspaceRenders} 命令——HTML→PNG
 * 保真（几何/配色/文字逐像素核对，fixture 留存 test/resources/render）、SVG→PNG
 * 旁路（同款保真核对＋零浏览器证明：chromium 二进制挪走仍渲染成）、源不在退出码
 * 1。daemon 不在则跳过（CI 无 docker 时不红）；「与浏览器呈现保真」的人眼活体
 * 走查归用户（绿测≠能跑）。
 */
class WorkspaceRendersLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 600;

    /** 像素采样容差（纯色块内部是确定色，容差只吸收 PNG 编码的极小抖动）。 */
    private static final int COLOR_TOLERANCE = 16;

    private final DockerEnvironmentBackend backend = new DockerEnvironmentBackend();

    private WorkspaceProvision provision;

    @AfterEach
    void tearDown() {
        if (provision != null) {
            backend.destroyWorkspace(provision.handle());
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_chinese_html_when_render_png_then_geometry_color_text_faithful() throws Exception {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        writeWorkspaceFile("design/fidelity.html", fixture("render/fidelity.html"));

        ExecResult rendered = exec(WorkspaceRenders.htmlToPngCommand(
                "design/fidelity.html", "design/fidelity.png", 800, 600));
        assertThat(rendered.exitCode()).as("HTML 渲染应成功：%s", rendered.stderr()).isZero();

        BufferedImage png = readRenderedPng("design/fidelity.png");
        // 回执诚实：stdout 首行字节大小 = 实际取回的 PNG 字节数
        assertThat(Long.parseLong(rendered.stdout().trim()))
                .isEqualTo(readRenderedPngBytes("design/fidelity.png").length);

        // 几何＋配色：画幅＝viewport（固定画幅帧）、色块/圆形/底色逐像素钉死
        assertThat(png.getWidth()).isEqualTo(800);
        assertThat(png.getHeight()).isEqualTo(600);
        assertPixel(png, 100, 100, 0xe60012);   // 红矩形内部
        assertPixel(png, 590, 190, 0x0055cc);   // 蓝圆心（远离抗锯齿边）
        assertPixel(png, 790, 590, 0xffffff);   // 白底角落
        // 文字（中文排版）：标题带内深色像素成片（非豆腐块空洞、非空白）
        assertThat(darkPixelsIn(png, 30, 405, 770, 470)).isGreaterThan(300);
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_native_svg_when_render_png_then_bypass_faithful() throws Exception {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        writeWorkspaceFile("design/fidelity.svg", fixture("render/fidelity.svg"));

        ExecResult rendered = exec(WorkspaceRenders.svgToPngCommand(
                "design/fidelity.svg", "exports/fidelity.svg.png"));
        assertThat(rendered.exitCode()).as("SVG 旁路渲染应成功：%s", rendered.stderr()).isZero();

        BufferedImage png = readRenderedPng("exports/fidelity.svg.png");
        // 原生尺寸衍生（无画幅载荷）
        assertThat(png.getWidth()).isEqualTo(400);
        assertThat(png.getHeight()).isEqualTo(300);
        assertPixel(png, 100, 70, 0xe60012);    // 红矩形内部
        assertPixel(png, 300, 180, 0x0055cc);   // 蓝圆心
        assertPixel(png, 10, 285, 0xffffff);    // 白底
        assertThat(darkPixelsIn(png, 10, 180, 390, 235)).isGreaterThan(100);
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_chromium_binaries_moved_when_render_svg_then_still_succeeds() throws Exception {
        // 零浏览器证明：chromium 二进制整目录挪走（executablePath 指向的 /opt/chrome），
        // SVG 旁路照样渲成——resvg 路径不触浏览器（HTML 路径在此态会渲染失败，
        // 对照语义见命令退出码非 0）
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        writeWorkspaceFile("design/fidelity.svg", fixture("render/fidelity.svg"));
        assertThat(exec("test -x \"$RENDER_CHROMIUM\" && mv /opt/chrome /opt/chrome.moved"
                + " && echo moved").exitCode()).as("chromium 目录应可挪走").isZero();
        try {
            ExecResult rendered = exec(WorkspaceRenders.svgToPngCommand(
                    "design/fidelity.svg", "exports/fidelity.svg.png"));
            assertThat(rendered.exitCode()).as("无 chromium 时 SVG 旁路应照常渲成：%s",
                    rendered.stderr()).isZero();
        } finally {
            exec("mv /opt/chrome.moved /opt/chrome");
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_missing_source_when_render_then_exit_1() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        ExecResult html = exec(WorkspaceRenders.htmlToPngCommand(
                "design/gone.html", "design/gone.png", 800, 600));
        assertThat(html.exitCode()).as("源不在应退出码 1（守卫壳，渲染器零触达）").isEqualTo(1);

        ExecResult svg = exec(WorkspaceRenders.svgToPngCommand(
                "design/gone.svg", "exports/gone.png"));
        assertThat(svg.exitCode()).isEqualTo(1);
    }

    // ---------- 工具 ----------

    private ExecResult exec(String command) {
        return backend.exec(provision.handle(), command);
    }

    /** fixture 内容（classpath，UTF-8）。 */
    private static String fixture(String resource) throws IOException {
        try (InputStream in = WorkspaceRendersLiveTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到保真用例 fixture " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 经 heredoc 写工作区文件（引号定界＝字面量，内容不经 shell 解释）。 */
    private ExecResult writeWorkspaceFile(String path, String content) {
        return exec("cat > /workspace/" + path + " <<'FIDELITY_EOF'\n"
                + content + "\nFIDELITY_EOF");
    }

    /** 取回渲成的 PNG 字节（复用点看 raw 命令——同一条容器读通道，顺带核通路）。 */
    private byte[] readRenderedPngBytes(String path) {
        BinaryExecResult read = backend.execBinary(provision.handle(),
                ProjectFiles.rawImageCommand(path));
        assertThat(read.exitCode()).as("渲成的 PNG 应可经 raw 通道取回：%s", read.stderr()).isZero();
        return read.stdout();
    }

    private BufferedImage readRenderedPng(String path) throws IOException {
        byte[] bytes = readRenderedPngBytes(path);
        BufferedImage png = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(png).as("取回的应是可解码 PNG").isNotNull();
        return png;
    }

    /** 单像素色核对（低 24 位 RGB，容差逐通道）。 */
    private static void assertPixel(BufferedImage image, int x, int y, int expectedRgb) {
        int actual = image.getRGB(x, y) & 0xffffff;
        int expected = expectedRgb & 0xffffff;
        int delta = Math.max(
                Math.abs(((actual >> 16) & 0xff) - ((expected >> 16) & 0xff)),
                Math.max(
                        Math.abs(((actual >> 8) & 0xff) - ((expected >> 8) & 0xff)),
                        Math.abs((actual & 0xff) - (expected & 0xff))));
        assertThat(delta).as("像素 (%d,%d) 应接近 #%06x，实际 #%06x", x, y, expected, actual)
                .isLessThanOrEqualTo(COLOR_TOLERANCE);
    }

    /** 带内深色像素计数（文字存在的诚实判据：非空白、非全深）。 */
    private static int darkPixelsIn(BufferedImage image, int xFrom, int yFrom,
            int xTo, int yTo) {
        int dark = 0;
        for (int y = yFrom; y < yTo; y++) {
            for (int x = xFrom; x < xTo; x++) {
                int rgb = image.getRGB(x, y) & 0xffffff;
                if (((rgb >> 16) & 0xff) < 100 && ((rgb >> 8) & 0xff) < 100
                        && (rgb & 0xff) < 100) {
                    dark++;
                }
            }
        }
        return dark;
    }

    private static void requireDockerDaemon() {
        Assumptions.assumeTrue(dockerOk(), "本机 docker daemon 不在，跳过真实链路");
    }

    private static boolean dockerOk() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format",
                    "{{.Server.Version}}").start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return p.exitValue() == 0 && !out.isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}
