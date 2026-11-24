package com.semgravio.aiplatform.business.project.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 工作区真实变更管道（#619 跑前快照链 + 收口 diff）：规则单点，照
 * {@link WorkspaceVersions} 形制——命令构造归本类纯函数、执行归 exec 通道、
 * 输出解析归本类。四件事：
 *
 * <ol>
 *   <li><b>跑前快照</b>（{@link #snapshotCommand}）：每场编码 run 起跑前对工作区
 *       做一次幂等仓库初始化＋全量提交，返回 commit hash 作为本场 diff 基线。提交
 *       信息只用平台标识（{@code snapshot <runId>}），<b>不带</b> Rollback-From
 *       trailer——快照不是用户面版本、不进版本序列（成员判据仍只认 Rollback-From、
 *       {@link WorkspaceVersions#parseLog}）。用户无感、无确认门。</li>
 *   <li><b>文件级行数</b>（{@link #numstatCommand}）：先 {@code git add -A} 再对基线
 *       取 {@code --numstat}——命令行改的文件也计入，行数反映真实结果（不是工具
 *       参数推算的活动量）。</li>
 *   <li><b>单文件只读 patch</b>（{@link #patchCommand}）：迭代模式对基线取完整
 *       patch 文本，本类按文件切分（{@link #splitPatch}）；首建不取 patch（目录
 *       汇总与首建不携 patch 归 #620，本票先不造无用载荷）。</li>
 *   <li><b>大小上限与降级</b>（{@link #assemble}）：单文件 patch 上限 300 行 /
 *       20KB、整卡上限 128KB——超限只省略 patch、保留行数并标注（前端呈现
 *       「diff 过大」）。快照或 diff 执行失败由应用层降级为
 *       {@link ChangeSet#unavailable()}（生成照常成功，卡上标注「变更清单不可用」）。</li>
 * </ol>
 *
 * <p>排除清单不新造：快照复用 {@link WorkspaceVersions#ensureRepoCommand()} 的
 * {@code .git/info/exclude}（非交付名单＋机密＋物料/导出），与源码包、文件树同
 * 口径。路径在载荷中统一为工作区锚定形（{@code /src/App.jsx}），与
 * {@code closing.files.path} 既有口径一致；git 的仓库相对形只活在解析入口。</p>
 */
public final class WorkspaceChanges {

    /** 快照提交主题前缀（平台标识；不带 Rollback-From——不进用户面版本序列）。 */
    public static final String SNAPSHOT_SUBJECT_PREFIX = "snapshot ";

    /** 单文件 patch 行数上限（超限省略 patch、保留行数并标注）。 */
    public static final int MAX_PATCH_LINES = 300;

    /** 单文件 patch 字节上限。 */
    public static final int MAX_PATCH_BYTES = 20 * 1024;

    /** 整卡 patch 总量字节上限（超出预算的后序文件省略 patch）。 */
    public static final int MAX_TOTAL_PATCH_BYTES = 128 * 1024;

    /** 基线引用的合法形态：hex 短/长 hash（命令构造的 shell 注入防线）。 */
    private static final Pattern VALID_REF = Pattern.compile("[0-9a-f]{4,40}");

    /** 完整 patch 的文件块头（{@code diff --git a/<path> b/<path>}）。 */
    private static final String PATCH_BLOCK_PREFIX = "diff --git ";

    private WorkspaceChanges() {
    }

    // ---------- 命令构造 ----------

    /**
     * 跑前快照：幂等仓库初始化（身份与 exclude 自愈复用版本层单点）→ 全量入索引
     * → 提交（{@code --allow-empty} 兜「上一轮收口后无新改动」的空快照）→ 回读
     * HEAD hash（本场 diff 基线）。提交信息只用 {@code snapshot <runId>}。
     */
    public static String snapshotCommand(String runId) {
        return WorkspaceVersions.ensureRepoCommand()
                + " && git add -A"
                + " && git commit -q --allow-empty -m '"
                + escapeSingleQuote(SNAPSHOT_SUBJECT_PREFIX + (runId == null ? "" : runId)) + "'"
                + " && git rev-parse HEAD";
    }

    /**
     * 文件级 ± 行数：先 {@code git add -A}（新文件/命令行产出进索引），再对基线取
     * staged numstat。{@code --no-renames} 让改名拆成删＋增，numstat 路径与 patch
     * 块一一对应，不产生 {@code old => new} 复合路径。
     *
     * @throws IllegalArgumentException baseline 非 hex hash 形态
     */
    public static String numstatCommand(String baseline) {
        requireRef(baseline);
        return "cd " + WorkspaceLayout.ROOT
                + " && git add -A"
                // core.quotePath=false：中文/非 ASCII 路径原样输出（默认会 C 转义，
                // 路径既不可读也与 patch 块头对不上）
                + " && git -c core.quotePath=false diff --cached --no-renames --numstat '"
                + baseline + "'";
    }

    /**
     * 完整 patch：先 {@code git add -A}，再对基线取 staged patch（只读文本，落
     * 收尾载荷；不写工作区）。调用方按文件切分（{@link #splitPatch}）后受大小
     * 上限约束。
     *
     * @throws IllegalArgumentException baseline 非 hex hash 形态
     */
    public static String patchCommand(String baseline) {
        requireRef(baseline);
        return "cd " + WorkspaceLayout.ROOT
                + " && git add -A"
                + " && git -c core.quotePath=false diff --cached --no-renames '"
                + baseline + "'";
    }

    /** 是否 hex commit hash 形态（快照回执校验 / 基线注入防线）。 */
    public static boolean isCommitRef(String text) {
        return text != null && VALID_REF.matcher(text).matches();
    }

    private static void requireRef(String baseline) {
        if (!isCommitRef(baseline)) {
            throw new IllegalArgumentException("diff 基线必须是 commit hash（hex）: " + baseline);
        }
    }

    // ---------- 输出解析 ----------

    /**
     * numstat 解析：每行 {@code added<TAB>removed<TAB>path}。二进制文件行数为
     * {@code -}（按 0 计）；畸形行跳过不炸整列。路径归一为工作区锚定形
     * （去 git 的包裹引号、补前导 {@code /}）。
     */
    public static List<Numstat> parseNumstat(String stdout) {
        List<Numstat> entries = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return entries;
        }
        for (String line : stdout.split("\n")) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) {
                continue;
            }
            entries.add(new Numstat(anchorPath(fields[2]), count(fields[0]), count(fields[1])));
        }
        return entries;
    }

    /**
     * 完整 patch 按文件切分：以 {@code diff --git } 块头为界，取 {@code b/} 侧路径
     * （{@code --no-renames} 下 a/b 同路径）归一为工作区锚定形。无法解析块头的
     * 碎块丢弃（不出坏 diff）。
     */
    public static Map<String, String> splitPatch(String patchText) {
        Map<String, String> byFile = new LinkedHashMap<>();
        if (patchText == null || patchText.isEmpty()) {
            return byFile;
        }
        StringBuilder current = null;
        String currentPath = null;
        for (String line : patchText.split("\n", -1)) {
            if (line.startsWith(PATCH_BLOCK_PREFIX)) {
                if (current != null && currentPath != null) {
                    byFile.put(currentPath, current.toString());
                }
                current = new StringBuilder(line);
                currentPath = headerPath(line);
                continue;
            }
            if (current != null) {
                current.append("\n").append(line);
            }
        }
        if (current != null && currentPath != null) {
            byFile.put(currentPath, current.toString());
        }
        return byFile;
    }

    /**
     * 装配变更清单：按路径排序（呈现稳定，非时间序）；{@code includePatches=false}
     * （首建）只出行数；逐文件上限（{@link #MAX_PATCH_LINES}/{@link #MAX_PATCH_BYTES}）
     * 与整卡总预算（{@link #MAX_TOTAL_PATCH_BYTES}）在本方法内兑现。
     */
    public static ChangeSet assemble(List<Numstat> entries, Map<String, String> patches,
            boolean includePatches) {
        Map<String, Numstat> sorted = new TreeMap<>();
        for (Numstat entry : entries) {
            sorted.merge(entry.path(), entry,
                    (left, right) -> new Numstat(left.path(), left.added() + right.added(),
                            left.removed() + right.removed()));
        }
        List<FileDiff> files = new ArrayList<>();
        int totalBytes = 0;
        for (Numstat entry : sorted.values()) {
            String patch = includePatches && patches != null ? patches.get(entry.path()) : null;
            if (patch != null && patch.isEmpty()) {
                patch = null;
            }
            boolean omitted = false;
            if (patch != null && exceedsPerFileLimit(patch)) {
                patch = null;
                omitted = true;
            }
            if (patch != null) {
                int bytes = patch.getBytes(StandardCharsets.UTF_8).length;
                if (totalBytes + bytes > MAX_TOTAL_PATCH_BYTES) {
                    patch = null;
                    omitted = true;
                }
                else {
                    totalBytes += bytes;
                }
            }
            files.add(new FileDiff(entry.path(), entry.added(), entry.removed(), patch, omitted));
        }
        return new ChangeSet(List.copyOf(files), false);
    }

    private static boolean exceedsPerFileLimit(String patch) {
        return patch.lines().count() > MAX_PATCH_LINES
                || patch.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES;
    }

    /** 块头路径：{@code diff --git a/x b/x} → 取 b 侧，去包裹引号，归一锚定形。 */
    private static String headerPath(String headerLine) {
        String rest = headerLine.substring(PATCH_BLOCK_PREFIX.length());
        int marker = rest.lastIndexOf(" b/");
        if (marker < 0) {
            return null;
        }
        return anchorPath(rest.substring(marker + 3));
    }

    /** 仓库相对路径 → 工作区锚定形（去 git 包裹引号、补前导 /）。 */
    private static String anchorPath(String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static int count(String field) {
        try {
            return Integer.parseInt(field);
        }
        catch (NumberFormatException binaryOrMalformed) {
            return 0; // 二进制文件 numstat 为 "-"
        }
    }

    /** 单引号转义（sh：{@code '} → {@code '\''}）。 */
    private static String escapeSingleQuote(String text) {
        return text.replace("'", "'\\''");
    }

    // ---------- 事实载体 ----------

    /** 单文件真实行数（numstat 一行；路径为工作区锚定形）。 */
    public record Numstat(String path, int added, int removed) {
    }

    /**
     * 收尾卡变更清单的单文件条目：行数恒在；patch 可缺省（首建不携 / 超限省略）；
     * {@code patchOmitted=true} 表示超限省略（前端标注「diff 过大」）。
     */
    public record FileDiff(String path, int added, int removed, String patch,
            boolean patchOmitted) {
    }

    /**
     * 收口 diff 结果：{@code unavailable=true} 表示快照或 diff 执行失败——生成
     * 照常成功，变更清单标注「变更清单不可用」，其余字段照常（不把 git 副作用
     * 升级成生成失败）。
     */
    public record ChangeSet(List<FileDiff> files, boolean unavailable) {

        public static ChangeSet degraded() {
            return new ChangeSet(List.of(), true);
        }
    }
}
