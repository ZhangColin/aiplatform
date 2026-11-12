package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 基座预置入口件回归（#471 P1-b）：{@code src/lib/db.ts} 与 {@code src/lib/http.ts}
 * 是习惯入口位——run 执行体三次实测（11-11 两项目＋11-12 易记单词本）找不到入口
 * 即自建裸客户端文件、收口 lint 强制档拒绝后按失败信号升档烧 pro（每次升档＝整段
 * 重落在强档）。预置 re-export 入口后模型没有自写空间。本测防三面回归：
 * <ul>
 * <li>入口件在场（删文件＝回到自写诱因——本类是入口件的「在场即断言」）；</li>
 * <li>入口只 re-export 健壮件、导出名与健壮件实现面一致（改名错位＝入口空悬，
 * 只在工作区侧编译期才炸）；</li>
 * <li>入口零裸原语——收口 lint 扫的是工作区文件（{@code --exclude-dir=robust}
 * 不豁免入口件），入口自身必须两档全清，否则新基座工作区起跑即带违规。</li>
 * </ul>
 * 件行为契约（超时/重试/降级）由健壮件同目录测试承载，入口件不另设测试面
 * （纯 re-export 无行为；工作区侧入口断言只会诱惑执行体「修」基座文件）。
 */
class BaselineEntryFilesTest {

    /** 强制档＋预置档的裸原语字面（与 {@code RobustKitLintAppService} 两档 ERE 的
     * 命中面同轴；入口注释措辞也不得含这些 token——防「文档式违规」）。 */
    private static final List<String> BARE_PRIMITIVES = List.of(
            "new Pool(", "new Client(", "new Redis(", "createClient(",
            "fetch(", "cookies(", "scrypt", "bcrypt", "pbkdf2");

    @Test
    void given_baseline_db_entry_when_inspected_then_reexports_robust_singleton_names() throws Exception {
        String entry = readTemplate("src/lib/db.ts");
        String robust = readTemplate("src/lib/robust/data-access.ts");

        assertThat(entry).as("入口 re-export 目标是健壮件（src/lib/robust/data-access）")
                .contains("./robust/data-access");
        for (String name : List.of("query", "queryOne", "withTransaction",
                "cacheGet", "cacheSet", "cacheDelete", "DataAccessError")) {
            assertThat(entry).as("入口应导出健壮件单例 %s", name).contains(name);
            assertThat(robust).as("健壮件实现面应实际导出 %s（入口改名错位＝空悬）", name)
                    .containsPattern("export (const|class|function|async function) " + name + "\\b");
        }
        assertZeroBarePrimitives("src/lib/db.ts", entry);
    }

    @Test
    void given_baseline_http_entry_when_inspected_then_reexports_robust_http_names() throws Exception {
        String entry = readTemplate("src/lib/http.ts");
        String robust = readTemplate("src/lib/robust/http.ts");

        assertThat(entry).as("入口 re-export 目标是健壮件（src/lib/robust/http）")
                .contains("./robust/http");
        for (String name : List.of("httpFetch", "httpJson", "HttpError")) {
            assertThat(entry).as("入口应导出健壮件 %s", name).contains(name);
            assertThat(robust).as("健壮件实现面应实际导出 %s（入口改名错位＝空悬）", name)
                    .containsPattern("export (const|class|function|async function) " + name + "\\b");
        }
        assertZeroBarePrimitives("src/lib/http.ts", entry);
    }

    @Test
    void given_baseline_auth_entry_when_inspected_then_reexports_robust_session_names() throws Exception {
        String entry = readTemplate("src/lib/auth.ts");
        String robust = readTemplate("src/lib/robust/session.ts");

        assertThat(entry).as("入口 re-export 目标是健壮件（src/lib/robust/session）")
                .contains("./robust/session");
        for (String name : List.of("createSession", "getSession", "destroySession",
                "hashPassword", "verifyPassword")) {
            assertThat(entry).as("入口应导出健壮件 %s", name).contains(name);
            assertThat(robust).as("健壮件实现面应实际导出 %s（入口改名错位＝空悬）", name)
                    .containsPattern("export (const|class|function|async function) " + name + "\\b");
        }
        assertZeroBarePrimitives("src/lib/auth.ts", entry);
    }

    private static void assertZeroBarePrimitives(String path, String content) {
        for (String primitive : BARE_PRIMITIVES) {
            assertThat(content)
                    .as("预置入口 %s 自身必须 lint 两档全清（收口扫描不豁免入口件），不得含 %s",
                            path, primitive)
                    .doesNotContain(primitive);
        }
    }

    /** classpath 读基线模板正本（与镜像 build context 所拷贝的同源文件）。 */
    private static String readTemplate(String relativePath) throws Exception {
        String resource = "docker/workspace/baseline/" + relativePath;
        try (InputStream in = BaselineEntryFilesTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
