package com.aieducenter.aiplatform.base.skills.infrastructure.git;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * git CLI 子进程快照拉取（#248 快照安装＋#250 更新检查＋#253 scripts 资源）：
 * {@code git clone} 到临时目录 → {@code rev-parse HEAD} 取装时版本 → 扫描全部
 * SKILL.md 经 agentscope {@link SkillUtil} 解析（与内置目录同一管道——审核面所见
 * 即运行时注入面）＋逐技能采集 {@code scripts/} 子树进资源面（
 * {@link ParsedSkill#resources()}，ADR-0021 内容面 c），拉完即删工作副本；远端
 * 前进探查走 {@code ls-remote} 只读不落盘。照 {@code DockerEnvironmentBackend}
 * 先例走 CLI 子进程（弱化实现起步）；超时 destroyForcibly 强杀——网络挂起不拖死
 * 请求线程（容器 clone 卡 TCP 的教训，#168）。
 *
 * <p>认证走宿主 git 全局配置（HTTPS credential helper／SSH agent）；
 * {@code GIT_TERMINAL_PROMPT=0} 禁交互提示——需凭据即速败，不挂等到超时。</p>
 */
@Adapter(PortType.CLIENT)
@Component
public class GitSkillPackageFetcher implements SkillPackageFetcher {

    private static final Logger logger = LoggerFactory.getLogger(GitSkillPackageFetcher.class);

    private static final String SKILL_FILE_NAME = "SKILL.md";

    /** scripts 资源目录名（agentskills 规范位——技能目录下 scripts/ 子树）。 */
    private static final String SCRIPTS_DIR_NAME = "scripts";

    /** 单资源上限：1MiB（框架 SkillManageTool 同款上限——超限跳过＋告警，不拦安装）。 */
    private static final long MAX_RESOURCE_BYTES = 1024 * 1024;

    /** 二进制资源存值前缀（框架物化面 SkillBox/MarketplaceStager 原生解码的约定）。 */
    private static final String BASE64_PREFIX = "base64:";

    /** 克隆超时：技能仓库量级小（42 技能的 matt 包在 MB 级），两分钟足够宽。 */
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(2);

    /** 本地 git 查询超时（rev-parse）：进程已起、无网络往返，秒级即异常。 */
    private static final Duration GIT_QUERY_TIMEOUT = Duration.ofSeconds(15);

    /** ls-remote 超时：走网络（远端可达性未知），30 秒兜死。 */
    private static final Duration LS_REMOTE_TIMEOUT = Duration.ofSeconds(30);

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
     * 只读探查远端 HEAD（#250 更新检查）：{@code git ls-remote <url> HEAD}——
     * 不 clone 不落盘，输出形如 {@code <sha>\tHEAD}；空输出（空仓等）与不可达
     * 同语义 SKL_004（调用方自持静默降级）。失败语义与 {@link #fetch} 同码同因
     * （仓库不可达），复用既有子进程机制（禁交互提示、超时强杀）。
     */
    @Override
    public String remoteHead(String repoUrl) {
        String output = runGit(LS_REMOTE_TIMEOUT, null, "ls-remote", repoUrl, "HEAD");
        for (String line : output.split("\\R")) {
            String head = line.trim().split("\\s+")[0];
            if (!head.isEmpty()) {
                return head;
            }
        }
        logger.warn("ls-remote 无 HEAD 输出（空仓？）: {}", repoUrl);
        throw new ApplicationException(SkillMessage.SKILL_REPOSITORY_CLONE_FAILED);
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
                skill.getMetadata(), skill.getSkillContent(), scanScripts(skillMd.getParent()));
    }

    /**
     * scripts/ 资源采集（#253 安装内容面补课，ADR-0021 内容面 c）：技能目录
     * {@code scripts/} 子树 → 框架 {@code AgentSkill.resources} 同形 map（键＝
     * 技能目录相对路径、正斜杠；路径序稳定同 SKILL.md 扫描律）。无 scripts 目录
     * 即空 map（纯 Markdown 技能不受影响）。三面纪律：隐藏段跳过（与仓库扫描
     * 同律）；符号链接不跟（NOFOLLOW——防链接逃出克隆目录把宿主文件读进库）；
     * 单文件超 {@link #MAX_RESOURCE_BYTES}（框架 SkillManageTool 同上限）跳过
     * ＋告警（巨型文件几近误放的二进制资产，不拦安装——审核面可见实收集）。非
     * UTF-8 按框架 {@code base64:} 前缀约定存（物化面原生解码）。技能目录被
     * 排除即整支不入快照（排除语义与 SKILL.md 条目同轨，无需在此复读）。
     */
    private static Map<String, String> scanScripts(Path skillDir) {
        Path scriptsDir = skillDir.resolve(SCRIPTS_DIR_NAME);
        if (!Files.isDirectory(scriptsDir)) {
            return Map.of();
        }
        Map<String, String> resources = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(scriptsDir)) {
            for (Path path : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !hiddenSegment(scriptsDir, path))
                    .sorted()
                    .toList()) {
                if (Files.size(path) > MAX_RESOURCE_BYTES) {
                    logger.warn("scripts 资源超限跳过（>{} 字节）: {}", MAX_RESOURCE_BYTES, path);
                    continue;
                }
                String key = SCRIPTS_DIR_NAME + "/"
                        + scriptsDir.relativize(path).toString().replace(File.separatorChar, '/');
                resources.put(key, encodeResource(Files.readAllBytes(path)));
            }
        }
        catch (IOException e) {
            logger.warn("scripts 资源扫描失败: {} ({})", scriptsDir, e.getMessage());
            throw new ApplicationException(SkillMessage.SKILL_REPOSITORY_CLONE_FAILED);
        }
        return resources;
    }

    /** 隐藏段（.git、.hidden.sh 等）命中即不入快照——与仓库扫描同律。 */
    private static boolean hiddenSegment(Path scriptsDir, Path path) {
        for (Path segment : scriptsDir.relativize(path)) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /** 资源内容编码：UTF-8 严格解码成功即文本直存；否则框架 base64: 前缀约定。 */
    private static String encodeResource(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
        catch (CharacterCodingException e) {
            return BASE64_PREFIX + Base64.getEncoder().encodeToString(bytes);
        }
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
