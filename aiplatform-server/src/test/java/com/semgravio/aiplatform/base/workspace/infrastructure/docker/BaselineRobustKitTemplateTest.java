package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 基座模板结构断言·健壮件三层（#421，{@link BaselineNextConfigTest} 同缝扩展）：
 * 厚模板六层中的健壮件层（四件套）与测试跑道层（Vitest 配置＋自测骨架）在
 * classpath 正本（与镜像 build context 同源文件）的结构性在场——文件面缺失即
 * 红在本车道、不必等镜像构建。行为契约（超时/重试/降级/不挂死/文案可覆盖）由
 * 模板自带的 Vitest 测试承担，构建期随 {@code pnpm test} 验证（dev-image.Dockerfile
 * 基座层狗粮门——本类另断言该门在场）。栈规则集层（AGENTS.md 节）是平台侧拼入、
 * 归 {@code GenerationAppServiceTest} 用例族。
 */
class BaselineRobustKitTemplateTest {

    /** 四件套源文件＋各自行为契约测试（缺一即结构残缺）。 */
    private static final List<String> COMPONENT_FILES = List.of(
            "src/lib/robust/data-access.ts",
            "src/lib/robust/data-access.test.ts",
            "src/lib/robust/http.ts",
            "src/lib/robust/http.test.ts",
            "src/lib/robust/session.ts",
            "src/lib/robust/session.test.ts",
            "src/components/robust/AsyncSection.tsx",
            "src/components/robust/AsyncSection.test.tsx",
            "src/components/robust/ErrorBoundary.tsx");

    /** 测试跑道层：配置＋自测骨架（执行体写/跑测试零 setup 的物理基础）。 */
    private static final List<String> RUNWAY_FILES = List.of(
            "vitest.config.ts",
            "vitest.setup.ts");

    @Test
    void given_baseline_template_when_inspected_then_robust_kit_and_runway_present() {
        for (String file : COMPONENT_FILES) {
            assertThat(resource("docker/workspace/baseline/" + file))
                    .as("基座健壮件/行为契约测试应在场: %s", file)
                    .isNotNull();
        }
        for (String file : RUNWAY_FILES) {
            assertThat(resource("docker/workspace/baseline/" + file))
                    .as("测试跑道文件应在场: %s", file)
                    .isNotNull();
        }
    }

    @Test
    void given_baseline_package_json_when_parsed_then_test_script_and_runway_deps_declared() {
        String packageJson = readTemplate("package.json");
        // 跑道入口：执行体 pnpm test 直跑（零 setup 承诺的入口面）
        assertThat(packageJson).contains("\"test\": \"vitest run\"");
        // 跑道依赖面（缺声明＝镜像构建装不上、模板自测无以为继）
        assertThat(packageJson).contains("vitest");
        assertThat(packageJson).contains("happy-dom");
        assertThat(packageJson).contains("@vitejs/plugin-react");
        assertThat(packageJson).contains("@testing-library/react");
        // src/lib/db.ts＝#471 P1-b 复置（覆盖 #421 当时的「模板不内置」裁决）：
        // #421 移除的是绕过健壮件的裸客户端出口；#471 复置的是纯 re-export 入口
        // ——三项目实测 run 执行体找不到习惯入口位即自建裸客户端文件、收口 lint
        // 强制档拒绝后按失败信号升档烧 pro。在场＋零裸原语断言归
        // {@link BaselineEntryFilesTest} 单点（本类不重复）。
    }

    @Test
    void given_baseline_session_component_when_read_then_uses_scrypt_and_cookie_semantics() {
        // 行为契约面的轻量语义锚（非全文断言）：密码面走 node:crypto scrypt＋
        // 常时比较（不裸存、不用可逆加密）；cookie 面 httpOnly＋sameSite。
        String session = readTemplate("src/lib/robust/session.ts");
        assertThat(session).contains("scrypt");
        assertThat(session).contains("timingSafeEqual");
        assertThat(session).contains("httpOnly");
        assertThat(session).contains("sameSite");
    }

    @Test
    void given_dev_image_dockerfile_when_read_then_baseline_layer_runs_template_tests() {
        // 狗粮门：基座层 install 后接 pnpm test——模板行为契约随镜像构建验证，
        // 红＝镜像构建失败（spec #416 测试决议）。deploy 变体（deploy/ 目录、
        // 非 classpath）同口径由部署流程人工核对。
        String dockerfile = readDockerfile();
        assertThat(dockerfile)
                .as("基座层应 install 后跑模板测试（pnpm test 狗粮门）")
                .contains("pnpm install --store-dir /workspace/.pnpm-store && pnpm test");
    }

    @Test
    void given_preset_dir_list_when_inspected_then_every_dir_has_template_files()
            throws Exception {
        // #541 基座件不改约的机械清单锚定：WorkspaceLayout.BASELINE_PRESET_DIRS
        //（写保护判据面，DockerExecFilesystem）的每个目录在基座模板正本都有预置
        // 件文件——预置目录增减而清单未同步（或反之）即红（写保护拦错面/漏面），
        // 漂移守卫归本缝单点（告知面正本＝栈规则集，不在本测范围）
        URL root = BaselineRobustKitTemplateTest.class.getClassLoader()
                .getResource("docker/workspace/baseline");
        assertThat(root).as("基座模板根应在 classpath").isNotNull();
        Path rootDir = Path.of(root.toURI());
        Set<String> templateFiles = Files.walk(rootDir)
                .filter(Files::isRegularFile)
                .map(path -> rootDir.relativize(path).toString().replace('\\', '/'))
                .collect(Collectors.toSet());
        for (String dir : WorkspaceLayout.BASELINE_PRESET_DIRS) {
            assertThat(templateFiles.stream()
                    .anyMatch(file -> file.startsWith(dir + "/")))
                    .as("预置件目录 %s 在基座模板应有预置件文件（清单与模板不漂移）", dir)
                    .isTrue();
        }
    }

    /** classpath 读模板文件字节（在场判定；缺位 null）。 */
    private static InputStream resource(String path) {
        return BaselineRobustKitTemplateTest.class.getClassLoader().getResourceAsStream(path);
    }

    /** classpath 读模板正本文本（与镜像 build context 所拷贝的同源文件）。 */
    private static String readTemplate(String relative) {
        try (InputStream in = resource("docker/workspace/baseline/" + relative)) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 docker/workspace/baseline/" + relative);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取模板资源失败: " + relative, e);
        }
    }

    private static String readDockerfile() {
        try (InputStream in = BaselineRobustKitTemplateTest.class.getClassLoader()
                .getResourceAsStream("docker/workspace/dev-image.Dockerfile")) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 docker/workspace/dev-image.Dockerfile");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取 dev-image.Dockerfile 失败", e);
        }
    }
}
