package com.aieducenter.aiplatform.base.skills.infrastructure.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aieducenter.aiplatform.base.skills.domain.model.ParsedSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillPackageSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scripts/ 资源采集（#253 安装内容面补课，ADR-0021）：随 SKILL.md 同快照进
 * {@link ParsedSkill#resources()}——键＝技能目录相对路径（{@code scripts/…}），
 * 值＝文本内容（非 UTF-8 按框架 {@code base64:} 前缀约定）。fixture 为本地真
 * git 仓库（clone --depth 1 真链路，不依赖外网）。
 *
 * <p>钉死五面：</p>
 * <ul>
 * <li><b>正形</b>：scripts/ 子树（含嵌套目录）随技能入快照，键序稳定（路径序）；</li>
 * <li><b>无 scripts 目录</b>：空 map（纯 Markdown 技能不受影响——票触发器口径）；</li>
 * <li><b>隐藏段与符号链接</b>：不入快照（隐藏与仓库扫描同律；symlink 不跟——防
 * 逃出克隆目录读宿主文件入库）；</li>
 * <li><b>二进制</b>：非 UTF-8 按 base64: 前缀约定存（框架物化面原生解码）；</li>
 * <li><b>超限</b>：单文件 &gt;1MiB（框架 SkillManageTool 同上限）跳过不入快照。</li>
 * </ul>
 */
class GitSkillPackageFetcherTest {

    private final GitSkillPackageFetcher fetcher = new GitSkillPackageFetcher();

    @TempDir
    Path workDir;

    @Test
    void given_scripts_tree_when_fetch_then_resources_ride_snapshot() throws Exception {
        Path repo = newRepo("scripts-plain");
        writeSkill(repo, "engineering/tdd", "tdd", "测试先行。");
        write(repo, "engineering/tdd/scripts/run-tests.sh", "#!/bin/bash\nset -e\n./mvnw test\n");
        write(repo, "engineering/tdd/scripts/lib/parse.py", "import sys\nprint(sys.argv)\n");
        commitAll(repo);

        SkillPackageSnapshot snapshot = fetcher.fetch(repo.toString(), List.of());

        assertThat(snapshot.skills()).hasSize(1);
        ParsedSkill skill = snapshot.skills().get(0);
        assertThat(skill.name()).isEqualTo("tdd");
        // 键＝技能目录相对路径（正斜杠），嵌套子目录同收，路径序稳定
        assertThat(skill.resources().keySet())
                .containsExactly("scripts/lib/parse.py", "scripts/run-tests.sh");
        assertThat(skill.resources().get("scripts/run-tests.sh"))
                .isEqualTo("#!/bin/bash\nset -e\n./mvnw test\n");
        assertThat(skill.resources().get("scripts/lib/parse.py"))
                .isEqualTo("import sys\nprint(sys.argv)\n");
    }

    @Test
    void given_no_scripts_dir_when_fetch_then_empty_resources() throws Exception {
        Path repo = newRepo("scripts-absent");
        writeSkill(repo, "engineering/tdd", "tdd", "测试先行。");
        commitAll(repo);

        SkillPackageSnapshot snapshot = fetcher.fetch(repo.toString(), List.of());

        assertThat(snapshot.skills()).hasSize(1);
        assertThat(snapshot.skills().get(0).resources()).isEmpty();
    }

    @Test
    void given_hidden_and_symlink_entries_when_fetch_then_skipped() throws Exception {
        Path repo = newRepo("scripts-hidden");
        writeSkill(repo, "engineering/tdd", "tdd", "测试先行。");
        write(repo, "engineering/tdd/scripts/run.sh", "#!/bin/bash\necho ok\n");
        write(repo, "engineering/tdd/scripts/.hidden.sh", "#!/bin/bash\necho hidden\n");
        write(repo, "engineering/tdd/scripts/.git/hooks/x.sh", "#!/bin/bash\necho hook\n");
        // 符号链接指向仓库外宿主文件：不跟（逃出克隆目录的读取通道结构性关闭）
        Path hostage = workDir.resolve("host-secret.txt");
        Files.writeString(hostage, "宿主机密不应入库");
        Files.createSymbolicLink(repo.resolve("engineering/tdd/scripts/escape.sh"), hostage);
        commitAll(repo);

        SkillPackageSnapshot snapshot = fetcher.fetch(repo.toString(), List.of());

        assertThat(snapshot.skills().get(0).resources().keySet())
                .containsExactly("scripts/run.sh");
    }

    @Test
    void given_binary_script_when_fetch_then_base64_prefixed() throws Exception {
        Path repo = newRepo("scripts-binary");
        writeSkill(repo, "engineering/tdd", "tdd", "测试先行。");
        // 非 UTF-8 字节（0xFF/0xFE 在 UTF-8 恒非法）
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x02};
        Files.createDirectories(repo.resolve("engineering/tdd/scripts"));
        Files.write(repo.resolve("engineering/tdd/scripts/blob.bin"), binary);
        commitAll(repo);

        SkillPackageSnapshot snapshot = fetcher.fetch(repo.toString(), List.of());

        assertThat(snapshot.skills().get(0).resources().get("scripts/blob.bin"))
                .isEqualTo("base64:" + Base64.getEncoder().encodeToString(binary));
    }

    @Test
    void given_oversized_script_when_fetch_then_skipped() throws Exception {
        Path repo = newRepo("scripts-oversize");
        writeSkill(repo, "engineering/tdd", "tdd", "测试先行。");
        write(repo, "engineering/tdd/scripts/ok.sh", "#!/bin/bash\necho ok\n");
        // 1MiB＋1：框架 SkillManageTool 同上限之外即不入快照
        Files.createDirectories(repo.resolve("engineering/tdd/scripts"));
        Files.write(repo.resolve("engineering/tdd/scripts/huge.sh"),
                new byte[1024 * 1024 + 1]);
        commitAll(repo);

        SkillPackageSnapshot snapshot = fetcher.fetch(repo.toString(), List.of());

        assertThat(snapshot.skills().get(0).resources().keySet())
                .containsExactly("scripts/ok.sh");
    }

    // ---------- 夹具（git CLI 现建本地仓库，与 BackofficeSkillSeamTest 同形） ----------

    private Path newRepo(String name) throws Exception {
        Path repo = Files.createTempDirectory(name + "-");
        git(repo, "-c", "init.defaultBranch=main", "init", "-q");
        return repo;
    }

    private static void writeSkill(Path repo, String dir, String name, String description)
            throws IOException {
        write(repo, dir + "/SKILL.md", """
                ---
                name: %s
                description: %s
                ---

                正文占位。
                """.formatted(name, description));
    }

    private static void write(Path repo, String relative, String content) throws IOException {
        Path target = repo.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void commitAll(Path repo) throws Exception {
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=Skill Fixture", "-c", "user.email=fixture@aiplatform.local",
                "commit", "-q", "-m", "fixture");
    }

    private static String git(Path workingDir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(workingDir.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        byte[] stderr = process.getErrorStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("fixture git 超时: " + command);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("fixture git 失败 (%d): %s — %s".formatted(
                    process.exitValue(), command, new String(stderr, StandardCharsets.UTF_8).trim()));
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
