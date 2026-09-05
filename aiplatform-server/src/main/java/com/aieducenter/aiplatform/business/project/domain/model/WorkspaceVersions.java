package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 容器内 git 管道（#91 版本层地基）的规则单点，照 {@link ProjectFiles} 形制：
 * 命令构造归本类纯函数、执行归 exec 通道（{@code EnvironmentBackend.exec}）、
 * 输出解析归本类。收拢五件事——幂等仓库初始化（懒初始化：存量工作区与容器重建
 * 自愈同覆盖，repo-local 身份配置随卷持久）、收口成版提交（摘要主题 + Run-Id
 * trailer 锚定收尾卡，{@code --allow-empty} 保每轮收口必成版）、版本序列读取
 * （git log 即正本）、单版本元数据读取、回滚到此（#93 复位工作树到目标 commit
 * 并追加新版本——Rollback-From trailer 锚定源版本，历史只追加不改写）。
 *
 * <p>版本序列只收带 {@value #RUN_ID_TRAILER} 或 {@value #ROLLBACK_TRAILER} trailer
 * 的成版 commit：智能体 shell 面技术上可跑 git，野 commit 不进版本序列。git
 * 跟踪面 = 交付物全集——
 * .gitignore 由 {@link WorkspaceLayout#NON_DELIVERABLE_DIRS} + {@link WorkspaceLayout#ENV_FILE}
 * 单一事实派生（数据/平台产物/可重建依赖/机密不入版，与源码包、文件树同口径）；
 * {@code .git} 本身由 git 天然排除。</p>
 *
 * <p>退出码约定（读命令三守卫）：0 = 正常；3 = 零版本 / 版本不存在（待期口径，
 * 调用方映射空列表 / 404）；其余非 0 = 真实 git 故障（调用方映射环境故障）。</p>
 */
public final class WorkspaceVersions {

    /** 成版 commit 的收尾卡锚定 trailer（版本序列的成员判据）。 */
    public static final String RUN_ID_TRAILER = "Run-Id: ";

    /** 回滚 commit 的源版本锚定 trailer（#93 回滚到此——版本序列的第二成员判据）。 */
    public static final String ROLLBACK_TRAILER = "Rollback-From: ";

    /** 「零版本 / 版本不存在」守卫退出码（与真实 git 故障分道）。 */
    public static final int EXIT_NO_VERSION = 3;

    /** 版本引用的合法形态：hex 短/长 hash（API 用户可控入参的 shell 注入防线）。 */
    private static final Pattern VALID_REF = Pattern.compile("[0-9a-f]{4,40}");

    /** git log 记录格式：hash / 成版时刻 / 主题 / 正文四字段（%x1f 分隔），记录间 %x1e。 */
    private static final String LOG_FORMAT = "%H%x1f%at%x1f%s%x1f%b%x1e";

    private static final String FIELD_SEPARATOR = "\u001f";
    private static final String RECORD_SEPARATOR = "\u001e";

    /** 成版 commit 的 repo-local 身份（容器内提交者，非用户可见面）。 */
    private static final String COMMITTER_NAME = "aiplatform";
    private static final String COMMITTER_EMAIL = "aiplatform@workspace.local";

    private WorkspaceVersions() {
    }

    /**
     * 幂等仓库初始化：无 .git 才 init（容器重建后卷内仓库原样续用，存量工作区懒
     * 初始化兼容），repo-local 提交身份随卷持久（git 全局配置不进卷）。非交付名单
     * 写入 {@code .git/info/exclude}（repo-local 且不在工作树——不与应用脚手架自带
     * 的 .gitignore 冲突，也随卷持久；每次重写，名单演进即随下次成版生效）。
     */
    public static String ensureRepoCommand() {
        String excludeEntries = Stream.concat(
                        WorkspaceLayout.NON_DELIVERABLE_DIRS.stream(),
                        Stream.of(WorkspaceLayout.ENV_FILE))
                .map(name -> "'" + name + "'")
                .collect(Collectors.joining(" "));
        return "cd " + WorkspaceLayout.ROOT
                + " && if ! test -d .git; then git init -q -b main ."
                + " && git config user.name '" + COMMITTER_NAME + "'"
                + " && git config user.email '" + COMMITTER_EMAIL + "'; fi"
                + " && printf '%s\\n' " + excludeEntries + " > .git/info/exclude";
    }

    /**
     * 收口成版提交：全量暂存 → 提交（主题 = 收口摘要，正文 = Run-Id trailer；
     * {@code --allow-empty}——「本轮系统无需改动」轮也成版，版本 ↔ 收尾卡 1:1
     * 锚定不缺位）→ 回读 HEAD hash（成版锚点，随收口扩载回传）。stdout = commit
     * hash（exitCode 0 时）。subject 经单引号转义；runId 为平台生成的 TSID
     * 数字串（非用户输入）。
     */
    public static String commitCommand(String subject, String runId) {
        return "cd " + WorkspaceLayout.ROOT
                + " && git add -A"
                + " && " + commitTail(subject, RUN_ID_TRAILER + runId);
    }

    /**
     * 版本序列读取：零版本（无 HEAD）exit {@value #EXIT_NO_VERSION} 与真实故障
     * 分道；否则 git log 全量（原生序 = 新→旧 = 版本序列）。
     */
    public static String listCommand() {
        return "cd " + WorkspaceLayout.ROOT
                + " && if ! git rev-parse --verify HEAD >/dev/null 2>&1; then exit "
                + EXIT_NO_VERSION + "; fi"
                + " && git log --format='" + LOG_FORMAT + "'";
    }

    /**
     * 回滚到此（#93）：把工作树与索引复位到目标 commit 的树（{@code git restore
     * --source --staged --worktree}——只动 tracked 文件：数据 / node_modules /
     * .env / .platform 等非交付物不在跟踪面，原样保留），再提交为一个<b>新</b>
     * commit（HEAD 不动，历史只追加不改写——无 rebase/force 类操作），携
     * Rollback-From trailer 锚定源版本。{@code --allow-empty} 兜「回滚到当前
     * HEAD」（树同形、空提交）的边界。stdout = 新版本 commit hash。
     *
     * @throws IllegalArgumentException ref 非 hex hash 形态（命令构造拒绝——用户
     *                                  可控入参不进 shell）
     */
    public static String rollbackCommand(String ref, String subject) {
        if (ref == null || !VALID_REF.matcher(ref).matches()) {
            throw new IllegalArgumentException("版本引用必须是 commit hash（hex）: " + ref);
        }
        return "cd " + WorkspaceLayout.ROOT
                + " && git restore --source='" + ref + "' --staged --worktree ."
                + " && " + commitTail(subject, ROLLBACK_TRAILER + ref);
    }

    /**
     * 单版本元数据读取：ref 不存在 exit {@value #EXIT_NO_VERSION}；否则该 commit
     * 单条 log（格式同 {@link #listCommand()}）。
     *
     * @throws IllegalArgumentException ref 非 hex hash 形态（命令构造拒绝——用户
     *                                  可控入参不进 shell）
     */
    public static String showCommand(String ref) {
        if (ref == null || !VALID_REF.matcher(ref).matches()) {
            throw new IllegalArgumentException("版本引用必须是 commit hash（hex）: " + ref);
        }
        return "cd " + WorkspaceLayout.ROOT
                + " && if ! git rev-parse --verify '" + ref + "^{commit}' >/dev/null 2>&1;"
                + " then exit " + EXIT_NO_VERSION + "; fi"
                + " && git log -1 --format='" + LOG_FORMAT + "' '" + ref + "'";
    }

    /**
     * 解析 git log 输出为版本序列（原生序新→旧保持）。只收带 Run-Id 或
     * Rollback-From trailer 的成版 commit（野 commit 不是版本）；畸形记录跳过
     * 不炸整列。
     */
    public static List<WorkspaceVersion> parseLog(String stdout) {
        List<WorkspaceVersion> versions = new ArrayList<>();
        for (String record : stdout.split(RECORD_SEPARATOR)) {
            String[] fields = record.trim().split(FIELD_SEPARATOR, -1);
            if (fields.length != 4) {
                continue;
            }
            String runId = extractTrailer(fields[3], RUN_ID_TRAILER);
            String rollbackFrom = extractTrailer(fields[3], ROLLBACK_TRAILER);
            if (runId == null && rollbackFrom == null) {
                continue;
            }
            try {
                versions.add(new WorkspaceVersion(fields[0],
                        Long.parseLong(fields[1]), fields[2], runId, rollbackFrom));
            } catch (NumberFormatException malformed) {
                // 时刻段不是数字 = 畸形记录，跳过
            }
        }
        return versions;
    }

    /** 提交尾（成版 / 回滚共用）：提交携 trailer（Run-Id 或 Rollback-From）→ 回读 HEAD hash。 */
    private static String commitTail(String subject, String trailer) {
        return "git commit -q --allow-empty -m '" + escapeSingleQuote(subject) + "'"
                + " -m '" + trailer + "'"
                + " && git rev-parse HEAD";
    }

    /** 提交正文（%b）取指定 trailer 值；无该 trailer = null（野 commit / 异类版本）。 */
    private static String extractTrailer(String body, String trailer) {
        for (String line : body.split("\n")) {
            if (line.startsWith(trailer)) {
                return line.substring(trailer.length()).trim();
            }
        }
        return null;
    }

    /** 单引号转义（sh：{@code '} → {@code '\''}）。 */
    private static String escapeSingleQuote(String text) {
        return text.replace("'", "'\\''");
    }
}
