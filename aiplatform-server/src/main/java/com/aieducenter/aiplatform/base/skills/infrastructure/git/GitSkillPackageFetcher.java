package com.aieducenter.aiplatform.base.skills.infrastructure.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.skills.domain.error.SkillMessage;
import com.aieducenter.aiplatform.base.skills.domain.model.ParsedSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillPackageSnapshot;
import com.aieducenter.aiplatform.base.skills.domain.port.SkillPackageFetcher;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;

/**
 * git CLI 子进程快照拉取（#248 快照安装）：{@code git clone} 到临时目录 →
 * {@code rev-parse HEAD} 取装时版本 → 扫描全部 SKILL.md 经 agentscope
 * {@link SkillUtil} 解析（与内置目录同一管道——审核面所见即运行时注入面），
 * 拉完即删工作副本。照 {@code DockerEnvironmentBackend} 先例走 CLI 子进程
 * （弱化实现起步）；超时 destroyForcibly 强杀——网络挂起不拖死请求线程
 * （容器 clone 卡 TCP 的教训，#168）。
 *
 * <p>认证走宿主 git 全局配置（HTTPS credential helper／SSH agent）；
 * {@code GIT_TERMINAL_PROMPT=0} 禁交互提示——需凭据即速败，不挂等到超时。</p>
 */
@Adapter(PortType.CLIENT)
@Component
public class GitSkillPackageFetcher implements SkillPackageFetcher {

    private static final Logger logger = LoggerFactory.getLogger(GitSkillPackageFetcher.class);

    private static final String SKILL_FILE_NAME = "SKILL.md";

    /** 克隆超时：技能仓库量级小（42 技能的 matt 包在 MB 级），两分钟足够宽。 */
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(2);

    /** 本地 git 查询超时（rev-parse）：进程已起，秒级即异常。 */
    private static final Duration GIT_QUERY_TIMEOUT = Duration.ofSeconds(15);

    @Override
    public SkillPackageSnapshot fetch(String repoUrl, List<String> excludeDirs) {
        Set<String> excludes = Set.copyOf(excludeDirs);
        Path workDir;
        try {
            workDir = Files.createTempDirectory("skill-install-");
        }
        catch (IOException e) {
            throw new UncheckedIOException("安装临时目录创建失败", e);
        }
        try {
            // --depth 1：快照只需 HEAD（本地路径克隆会告警忽略、照常全量——无害）
            runGit(CLONE_TIMEOUT, null, "clone", "--depth", "1", repoUrl, workDir.toString());
            String version = runGit(GIT_QUERY_TIMEOUT, workDir, "rev-parse", "HEAD").trim();
            return new SkillPackageSnapshot(version, scanSkills(workDir, excludes));
        }
        finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * 扫描快照内全部 SKILL.md：路径序稳定（同包条目序可复现）；隐藏目录
     * （.git 等）结构性跳过；相对路径任一段命中排除名单即不入快照
     * （deprecated 类目目录整支排除）。畸形 SKILL.md fail-fast（SKL_006）。
     */
    private List<ParsedSkill> scanSkills(Path repoRoot, Set<String> excludes) {
        try (Stream<Path> paths = Files.walk(repoRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> SKILL_FILE_NAME.equals(path.getFileName().toString()))
                    .filter(path -> !excluded(repoRoot, path.getParent(), excludes))
                    .sorted()
                    .map(GitSkillPackageFetcher::parseSkill)
                    .toList();
        }
        catch (IOException e) {
            logger.warn("技能仓库扫描失败: {}", e.getMessage());
            throw new ApplicationException(SkillMessage.SKILL_REPOSITORY_CLONE_FAILED);
        }
    }

    /** 隐藏段（.git 等）或排除段命中即不入快照。 */
    private static boolean excluded(Path repoRoot, Path skillDir, Set<String> excludes) {
        for (Path segment : repoRoot.relativize(skillDir)) {
            String name = segment.toString();
            if (name.startsWith(".") || excludes.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** 单技能解析：agentscope 同管道（name/description/frontmatter 全量＋正文）。 */
    private static ParsedSkill parseSkill(Path skillMd) {
        AgentSkill skill;
        try {
            skill = SkillUtil.createFrom(Files.readString(skillMd, StandardCharsets.UTF_8), null);
        }
        catch (IllegalArgumentException e) {
            logger.warn("SKILL.md 不合格: {} ({})", skillMd, e.getMessage());
            throw new ApplicationException(SkillMessage.SKILL_MD_INVALID);
        }
        catch (IOException e) {
            logger.warn("SKILL.md 读取失败: {} ({})", skillMd, e.getMessage());
            throw new ApplicationException(SkillMessage.SKILL_MD_INVALID);
        }
        return new ParsedSkill(skill.getName(), skill.getDescription(),
                skill.getMetadata(), skill.getSkillContent());
    }

    /**
     * git 子进程执行：stdout/stderr 异步双读（同步顺序读会死锁管道缓冲）、
     * 超时强杀；非零退出码即克隆失败（stderr 进日志，URL 调用方自知）。
     */
    private String runGit(Duration timeout, Path workingDir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        if (workingDir != null) {
            command.add("-C");
            command.add(workingDir.toString());
        }
        command.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            // 禁交互凭据提示：需认证的仓库即速败，不挂到超时
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            CompletableFuture<String> stdout = drainAsync(process.getInputStream());
            CompletableFuture<String> stderr = drainAsync(process.getErrorStream());
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.warn("git 子进程超时强杀: {} {}", command, timeout);
                throw new ApplicationException(SkillMessage.SKILL_REPOSITORY_CLONE_FAILED);
            }
            if (process.exitValue() != 0) {
                logger.warn("git 子进程失败 (exit {}): {} — {}", process.exitValue(), command,
                        stderr.join().trim());
                throw new ApplicationException(SkillMessage.SKILL_REPOSITORY_CLONE_FAILED);
            }
            return stdout.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationException(SkillMessage.SKILL_REPOSITORY_CLONE_FAILED);
        }
        catch (IOException e) {
            logger.warn("git 子进程启动失败: {} ({})", command, e.getMessage());
            throw new ApplicationException(SkillMessage.SKILL_REPOSITORY_CLONE_FAILED);
        }
    }

    /** 异步吸干流（防管道写满死锁；读失败给空串——退出码才是判据）。 */
    private static CompletableFuture<String> drainAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                return "";
            }
        });
    }

    /** 递归删除临时工作副本（尽力而为：失败仅告警——temp 目录随系统清理）。 */
    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                }
                catch (IOException e) {
                    logger.warn("安装临时目录清理失败: {} ({})", path, e.getMessage());
                }
            });
        }
        catch (IOException e) {
            logger.warn("安装临时目录清理失败: {} ({})", dir, e.getMessage());
        }
    }
}
