package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * 基座模板 next 配置回归（#395，ADR-0034）：新起 workspace 从 baseline 模板继承
 * {@code allowedDevOrigins: ['*.preview.semgravio.com']}——Next 15.2+ dev 服务器
 * 自带跨源闸门，带非白名单 Origin 的 {@code /_next/} 请求一律 403；不经网关直连
 * 应用口（容器内排查等场景）时，带预览域 Origin 的行为须与经网关一致。
 * 条目语义：不带协议/端口、{@code *} 恰匹配一个 label（Next 匹配口径只看 host，
 * 带 {@code https://} 或端口反而匹配不上）。
 * 定位是语义自文档而非修复主力（ADR-0034：修复主力是网关收口）——本断言防模板
 * 回归（删条目／改成带协议端口的形态）；端到端行为由 live 车道容器实测覆盖
 * （{@link DockerEnvironmentBackendTest} 基座起服用例带预览域 Origin 直打 chunk）。
 */
class BaselineNextConfigTest {

    private static final Pattern ALLOWED_DEV_ORIGINS = Pattern.compile(
            "allowedDevOrigins\\s*:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL);
    private static final Pattern QUOTED_ENTRY = Pattern.compile("'([^']+)'|\"([^\"]+)\"");

    @Test
    void given_baseline_next_config_when_parsed_then_preview_domain_origin_allowed_without_protocol_or_port()
            throws Exception {
        String config = readTemplate();

        Matcher array = ALLOWED_DEV_ORIGINS.matcher(config);
        assertThat(array.find())
                .as("baseline next 配置应声明 allowedDevOrigins（#395：不经网关直连时预览域 Origin 不被 dev 闸门 403）")
                .isTrue();

        List<String> entries = quotedEntriesOf(array.group(1));
        assertThat(entries)
                .as("allowedDevOrigins 应含预览域条目（* 恰匹配一个 label）")
                .contains("*.preview.semgravio.com");
        assertThat(entries)
                .as("条目不带协议/端口（Next 匹配只看 host，带了反而匹配不上）")
                .allSatisfy(entry -> assertThat(entry).doesNotContain(":"));
    }

    /** classpath 读模板正本（与镜像 build context 所拷贝的同源文件）。 */
    private static String readTemplate() throws Exception {
        try (InputStream in = BaselineNextConfigTest.class.getClassLoader()
                .getResourceAsStream("docker/workspace/baseline/next.config.ts")) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 docker/workspace/baseline/next.config.ts");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 数组字面量里的引号条目（单双引号都收，空数组＝无条目）。 */
    private static List<String> quotedEntriesOf(String arrayLiteral) {
        List<String> entries = new ArrayList<>();
        Matcher m = QUOTED_ENTRY.matcher(arrayLiteral);
        while (m.find()) {
            entries.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return entries;
    }
}
