package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * 基座模板 lint 与图标回归（#633，阶段五-1）：模板原先有 {@code lint} 脚本却无
 * ESLint 9 flat config——{@code pnpm lint} 直接失败，实验中模型自己补配置烧了
 * 10 轮／66 秒；多数新应用也没有网站图标。本类在 classpath 正本（与镜像 build
 * context 同源文件）上钉住三面，文件缺失/回潮不必等镜像构建就红在本车道：
 *
 * <ul>
 * <li>flat config 在场且引用模板已装的 {@code eslint-config-next}；</li>
 * <li>{@code src/app/icon.svg} 在场（Next App Router icon 约定，自动成为页面图标）；</li>
 * <li>{@code start}／{@code dev} 保持统一开发服务器形态（ADR-0056），{@code lint}
 * 脚本保持 {@code eslint}。</li>
 * </ul>
 *
 * 行为面（模板 {@code pnpm lint} 实际退出 0、起服后 icon 路由可访问）由镜像 live
 * 类 {@link DevImageFoundationLiveTest} 在真容器里验证。
 */
class BaselineLintAndIconTest {

    @Test
    void given_baseline_template_when_inspected_then_eslint_flat_config_present_and_uses_next_preset()
            throws Exception {
        String config = readTemplate("eslint.config.mjs");

        assertThat(config)
                .as("模板应含 ESLint 9 flat config（pnpm lint 不再「找不到 eslint.config.*」）")
                .contains("eslint-config-next");
        assertThat(config)
                .as("flat config 应导出配置数组")
                .contains("export default");
    }

    @Test
    void given_baseline_template_when_inspected_then_app_icon_present() {
        assertThat(resource("docker/workspace/baseline/src/app/icon.svg"))
                .as("模板应含中性网站图标 src/app/icon.svg（Next App Router icon 约定）")
                .isNotNull();
    }

    @Test
    void given_baseline_package_json_when_parsed_then_lint_script_eslint_and_unified_dev_server()
            throws Exception {
        String packageJson = readTemplate("package.json");

        assertThat(packageJson)
                .as("lint 脚本保持 eslint（flat config 在场后即可用）")
                .contains("\"lint\": \"eslint\"");
        assertThat(packageJson)
                .as("start 保持统一开发服务器形态（ADR-0056）")
                .contains("\"start\": \"next dev -H 0.0.0.0 -p 8081\"");
        assertThat(packageJson)
                .as("dev 保持统一开发服务器形态（ADR-0056）")
                .contains("\"dev\": \"next dev -H 0.0.0.0 -p 8081\"");
    }

    @Test
    void given_dev_image_dockerfile_when_read_then_baseline_layer_runs_lint_after_test()
            throws Exception {
        String dockerfile = readDockerfile();

        assertThat(dockerfile)
                .as("基座层应在 pnpm test 之后追加 pnpm lint——配置缺失或腐烂在构建期就红")
                .containsPattern("pnpm test\\s*&&\\s*pnpm lint");
    }

    /** classpath 读模板正本（与镜像 build context 所拷贝的同源文件）。 */
    private static String readTemplate(String relativePath) throws Exception {
        String resource = "docker/workspace/baseline/" + relativePath;
        try (InputStream in = resource(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readDockerfile() throws Exception {
        try (InputStream in = resource("docker/workspace/dev-image.Dockerfile")) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 docker/workspace/dev-image.Dockerfile");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InputStream resource(String path) {
        return BaselineLintAndIconTest.class.getClassLoader().getResourceAsStream(path);
    }
}
