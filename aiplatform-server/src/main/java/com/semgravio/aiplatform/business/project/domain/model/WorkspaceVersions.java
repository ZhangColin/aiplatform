package com.semgravio.aiplatform.business.project.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 容器内 git 管道（#91 版本层地基）的规则单点，照 {@link ProjectFiles} 形制：
 * 命令构造归本类纯函数、执行归 exec 通道（{@code EnvironmentBackend.exec}）、
 * 输出解析归本类。收拢保留四件事——幂等仓库初始化（懒初始化：存量工作区与
 * 容器重建自愈同覆盖，repo-local 身份配置随卷持久；<b>跑前快照链的阶段四施工
 * 留存工具</b>）、版本序列读取（git log 即正本）、单版本元数据读取、回滚代码复位
 * （{@link #restoreCommand}：#627 回滚只复位 tracked 文件，成版由回滚编排在
 * build＋起服＋探活全过后经 {@link #commitVersionCommand} 落定——历史只追加不改写）。
 *
 * <p><b>版本语义（#605 退役→#626 重建，ADR-0056）</b>：旧 Run-Id trailer 成版随
 * 审查与收口组出局（#605）；阶段四重建为<b>用户面版本</b>——每轮生成探活通过后
 * 平台自动成版（{@link #commitVersionCommand}：{@code Platform-Version}＋
 * {@code Run-Id} trailer），版本序列＝按标记过滤的 git log。旧回滚版本带
 * {@code Rollback-From} trailer（历史兼容；#627 起新回滚成功后经
 * {@link #commitVersionCommand} 以 {@code Platform-Version} 成版）。段起点／段失败
 * 回滚／fork 补丁等旧生成栈 git 辅助件随切片与轨道机器（#602）一并清场。</p>
 *
 * <p>git 跟踪面 = 交付物全集——
 * .gitignore 由 {@link WorkspaceLayout#NON_DELIVERABLE_DIRS} + {@link WorkspaceLayout#ENV_FILE}
 * 单一事实派生（数据/平台产物/可重建依赖/机密不入版，与源码包、文件树同口径），
 * 外加物料目录（输入面不入 git，ADR-0027——文件区/源码包仍含物料，仅版本流不跟）；
 * {@code .git} 本身由 git 天然排除。</p>
 *
 * <p>退出码约定（读命令三守卫）：0 = 正常；3 = 零版本 / 版本不存在（待期口径，
 * 调用方映射空列表 / 404）；其余非 0 = 真实 git 故障（调用方映射环境故障）。</p>
 */
public final class WorkspaceVersions {

    /** 旧回滚 commit 的源版本锚定 trailer（#93 历史成员判据；#627 起新回滚改用
     * {@link #VERSION_TRAILER} 成版——本 trailer 只用于识别存量回滚版本）。 */
    public static final String ROLLBACK_TRAILER = "Rollback-From: ";

    /** 用户面版本标记 trailer（#626 自动成版——版本序列的成员判据，值为 vN）。 */
    public static final String VERSION_TRAILER = "Platform-Version: ";

    /** 成版 run 标识 trailer（#626：版本提交带 run 标识，随版本标记一并携带）。 */
    public static final String RUN_TRAILER = "Run-Id: ";

    /** 「零版本 / 版本不存在」守卫退出码（与真实 git 故障分道）。 */
    public static final int EXIT_NO_VERSION = 3;

    /** 版本引用的合法形态：hex 短/长 hash（API 用户可控入参的 shell 注入防线）。 */
    private static final Pattern VALID_REF = Pattern.compile("[0-9a-f]{4,40}");

    /** git log 记录格式：hash / 成版时刻 / 主题 / 正文四字段（%x1f 分隔），记录间 %x1e。 */
    private static final String LOG_FORMAT = "%H%x1f%at%x1f%s%x1f%b%x1e";

    private static final String FIELD_SEPARATOR = "\u001f";
    private static final String RECORD_SEPARATOR = "\u001e";

    /** 成版 commit 的 repo-local 身份（容器内提交者，非用户可见面）。 */
    private static final String COMMITTER_NAME = "semgravio-builder";
    private static final String COMMITTER_EMAIL = "builder@semgravio.com";

    private WorkspaceVersions() {
    }

    /**
     * 幂等仓库初始化：无 .git 才 init（容器重建后卷内仓库原样续用，存量工作区懒
     * 初始化兼容），repo-local 提交身份随卷持久（git 全局配置不进卷）。<b>身份
     * 写入无条件执行</b>（#430——生产实测「Author identity unknown」致片级收口成版
     * 必败的根因）：身份原本藏在 init-if 内，而 fork 克隆（#351 {@code git clone}
     * 不携 repo-local config）与存量仓库的 .git 早已存在，if 内配置被整体跳过；
     * 移出 if 后每条写路径前置的本命令天然自愈。平台统一署名
     * {@code semgravio-builder}（可审计）。排除名单写入 {@code .git/info/exclude}
     * （repo-local 且不在工作树——不与应用脚手架自带的 .gitignore 冲突，也随卷
     * 持久；每次重写，名单演进即随下次成版生效）：非交付名单＋机密之外，物料
     * 目录与导出物目录不入版本流（ADR-0027 版本化规则——刀＝可再生性：上传物料
     * 是输入面、exports/ 是可再生衍生〔#294 下载图位图化落点，#297 设计资产包
     * 同面〕，git 不跟踪；代价如实：回滚不触两者〔restore 只动 tracked 文件〕，
     * 均以工作区卷为唯一正本随封存保全）。
     *
     * <p>#605 起收口成版提交退役，本命令作为阶段四跑前快照链的工具层留存件
     * （界面侧每轮 run 开始前自动 commit 的仓库初始化前置）。</p>
     */
    public static String ensureRepoCommand() {
        String excludeEntries = Stream.concat(
                        WorkspaceLayout.NON_DELIVERABLE_DIRS.stream(),
                        Stream.of(WorkspaceLayout.ENV_FILE, WorkspaceLayout.MATERIALS_DIR,
                                WorkspaceLayout.EXPORTS_DIR))
                .map(name -> "'" + name + "'")
                .collect(Collectors.joining(" "));
        return "cd " + WorkspaceLayout.ROOT
                + " && if ! test -d .git; then git init -q -b main .; fi"
                + " && git config user.name '" + COMMITTER_NAME + "'"
                + " && git config user.email '" + COMMITTER_EMAIL + "'"
                + " && printf '%s\\n' " + excludeEntries + " > .git/info/exclude";
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
     * 用户面版本提交（#626 自动成版）：把当时工作区全量提交为一个用户面版本——
     * 提交主题＝「用户那句话」，正文携平台版本标记（{@code Platform-Version: vN}）
     * 与成版 run 标识（{@code Run-Id:}）；仓库初始化（幂等）随命令前置，保证提交
     * 身份在场。stdout = 新版本 commit hash。
     *
     * <p>成版失败降级归调用方：提交失败不阻断生成，下一轮成功交付的提交覆盖
     * 累计后的工作区现状。subject 已由调用方清洗为单行（换行会污染提交主题）。</p>
     *
     * @param versionNo 版本号（v1/v2/v3 递增——调用方按已有版本最大值 +1 计算）
     * @param runId     成版 run 标识（用户面 runId）
     * @param subject   提交主题＝「用户那句话」（单行、已清洗）
     */
    public static String commitVersionCommand(int versionNo, String runId, String subject) {
        return ensureRepoCommand()
                + " && git add -A"
                + " && git commit -q --allow-empty -m '" + escapeSingleQuote(subject) + "'"
                + " -m '" + VERSION_TRAILER + versionNo + "'"
                + " -m '" + RUN_TRAILER + escapeSingleQuote(runId == null ? "" : runId) + "'"
                + " && git rev-parse HEAD";
    }

    /**
     * 回滚代码复位（#627 异步回滚的第一步）：把工作树与索引复位到目标 commit 的
     * 树（{@code git restore --source --staged --worktree}——只动 tracked 文件：
     * 数据 / node_modules / .env / .platform 等非交付物不在跟踪面，原样保留）。
     * 不提交——回滚的「记为新版本」要等停旧进程、重新 build、起服、探活全过后由
     * {@link #commitVersionCommand} 落定（成功才成版，失败不记版本）。untracked
     * 文件不在 restore 面，失败 run 留下的未提交 tracked 改动被这份代码覆盖
     * （有意语义：#627 只拦 run 在途，不拦脏树）。
     *
     * @throws IllegalArgumentException ref 非 hex hash 形态（命令构造拒绝——用户
     *                                  可控入参不进 shell）
     */
    public static String restoreCommand(String ref) {
        if (ref == null || !VALID_REF.matcher(ref).matches()) {
            throw new IllegalArgumentException("版本引用必须是 commit hash（hex）: " + ref);
        }
        return "cd " + WorkspaceLayout.ROOT
                + " && git restore --source='" + ref + "' --staged --worktree .";
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
     * 解析 git log 输出为版本序列（原生序新→旧保持）。成员判据两种：带
     * {@code Platform-Version} trailer 的用户面版本（#626 自动成版，携版本号与
     * run 标识）、带 {@code Rollback-From} trailer 的回滚版本（#93 历史兼容）。
     * 跑前快照与智能体 shell 面的野 commit 无 trailer——不入列。畸形记录跳过不炸
     * 整列。
     */
    public static List<WorkspaceVersion> parseLog(String stdout) {
        List<WorkspaceVersion> versions = new ArrayList<>();
        for (String record : stdout.split(RECORD_SEPARATOR)) {
            String[] fields = record.trim().split(FIELD_SEPARATOR, -1);
            if (fields.length != 4) {
                continue;
            }
            String versionRaw = extractTrailer(fields[3], VERSION_TRAILER);
            String rollbackFrom = extractTrailer(fields[3], ROLLBACK_TRAILER);
            if (versionRaw == null && rollbackFrom == null) {
                continue;
            }
            Integer versionNo = parseVersionNo(versionRaw);
            if (versionRaw != null && versionNo == null) {
                continue; // 版本标记非数字 = 畸形记录
            }
            String runId = extractTrailer(fields[3], RUN_TRAILER);
            try {
                versions.add(new WorkspaceVersion(fields[0],
                        Long.parseLong(fields[1]), fields[2], versionNo,
                        runId == null || runId.isBlank() ? null : runId, rollbackFrom));
            } catch (NumberFormatException malformed) {
                // 时刻段不是数字 = 畸形记录，跳过
            }
        }
        return versions;
    }

    /** 版本标记值解析（"3" → 3；空/非数字 → null）。 */
    private static Integer parseVersionNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException malformed) {
            return null;
        }
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
