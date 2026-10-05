package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 文件树浏览面（#27）的规则单点：交付文件视图 = 工作区剔除非交付物（数据 /
 * 平台产物 / 可重建依赖 / 机密，名单归 {@link WorkspaceLayout} 正本）后的只读
 * 文件面。排除作用域一律<strong>工作区根级</strong>（与源码包 tar 排除严格同
 * 口径）——嵌套同名目录（如应用自己的 {@code src/data/}）是交付物，不误伤。
 * 收拢四件事——可浏览路径判定（内容端点的用户可控入参防线）、树列表命令构造
 * （find 剪枝在源头排除，不进 node_modules 巨树）、内容读取命令构造（大小限读
 * 在容器侧先行，巨文件不进内存）、find 输出解析。#283 起点看判定对图片放行
 * （ADR-0027）：图片扩展名走 raw 直出（inline 大图），判定与命令构造同收此处。
 * 纯函数无依赖。
 */
public final class ProjectFiles {

    /** 在线查看的文件大小上限（1 MiB）：容器侧 cat 前拦截，超限不读取。 */
    public static final long MAX_CONTENT_BYTES = 1024 * 1024;

    /**
     * 图片点看（raw 直出）的大小上限（25 MiB，ADR-0027「≤25MB 量级」裁量）：
     * 容器侧 cat 前拦截，超限不读取——与文本上限各自独立（图片以张计，量级放宽）。
     */
    public static final long MAX_RAW_IMAGE_BYTES = 25L * 1024 * 1024;

    /**
     * 图片点看的扩展名面（#283）：png/jpg/webp/gif/svg（与上传口径同集，ADR-0027）
     * → 真实 content-type（raw 直出按扩展名映射，无内容嗅探面）。键即判定面、
     * 值即响应头，一张表两用。
     */
    private static final Map<String, String> IMAGE_CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp",
            "gif", "image/gif",
            "svg", "image/svg+xml");

    /** 文件树条目：工作区相对路径 + 字节大小（目录由前端按路径段合成，不出端点）。 */
    public record Entry(String path, long size) {
    }

    private ProjectFiles() {
    }

    /**
     * 路径是否可浏览：工作区锚定形（相对根、无 {@code ..} 逃逸、非空白）且首段
     * 不是根级非交付目录、路径不是根级 {@code .env} 机密。
     */
    public static boolean isViewable(String path) {
        if (path == null || path.isBlank() || path.startsWith("/")) {
            return false;
        }
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment.isBlank() || "..".equals(segment)) {
                return false;
            }
        }
        return !WorkspaceLayout.NON_DELIVERABLE_DIRS.contains(segments[0])
                && !WorkspaceLayout.ENV_FILE.equals(path);
    }

    /**
     * 树列表命令：一次 find 取全部交付文件——根级非交付目录在遍历源头
     * {@code -prune}（不进 node_modules 巨树），根级 {@code .env} 排除；每行
     * {@code 大小\t相对路径}（{@code %P} = 去掉起锚前缀的工作区相对形）。命令
     * 全常量，无用户可控片段。
     */
    public static String listCommand() {
        String prunes = WorkspaceLayout.NON_DELIVERABLE_DIRS.stream()
                .map(dir -> "-path " + WorkspaceLayout.ROOT + "/" + dir)
                .collect(java.util.stream.Collectors.joining(" -o "));
        return "find " + WorkspaceLayout.ROOT + " \\( " + prunes + " \\)"
                + " -prune -o -type f ! -path " + WorkspaceLayout.ROOT + "/" + WorkspaceLayout.ENV_FILE
                + " -printf '%s\\t%P\\n'";
    }

    /**
     * 内容读取命令（path 须先过 {@link #isViewable}，此处不代偿）：三段守卫各占
     * 退出码——1 = 不存在或不是文件、2 = 超 {@link #MAX_CONTENT_BYTES}（cat 前
     * 拦截，巨文件不进内存）、0 = 首行字节大小 + 余文正文（PRD 读同构）。路径经
     * 单引号包裹 + 转义，无注入面。
     */
    public static String contentCommand(String path) {
        if (!isViewable(path)) {
            throw new IllegalArgumentException("非可浏览路径，命令构造拒绝: " + path);
        }
        return ContainerCommands.existenceGuard(path)
                + " s=$(stat -c %s \"$p\");"
                + " if [ \"$s\" -gt " + MAX_CONTENT_BYTES + " ]; then exit 2; fi;"
                + " printf '%s\\n' \"$s\"; cat \"$p\"";
    }

    /**
     * 图片点看判定（#283，ADR-0027 点看对图片放行）：按扩展名（大小写不敏感）。
     * 放行即走 raw 直出 inline 大图；文本照旧走内容端点、含 NUL 的真二进制非图片
     * 件仍由内容端点如实拒收（PRJ_023 语义保留）。
     */
    public static boolean isImagePath(String path) {
        return IMAGE_CONTENT_TYPES.containsKey(extensionOf(path));
    }

    /**
     * 图片 raw 直出命令（path 须先过 {@link #isViewable} 与 {@link #isImagePath}，
     * 此处只代偿前者）：守卫结构同 {@link #contentCommand}（1 = 不存在、
     * 2 = 超 {@link #MAX_RAW_IMAGE_BYTES}），但 stdout 是文件<strong>原始字节</strong>
     * （无「大小首行 + 正文」文本形——二进制不经文本通道，走 exec 字节形）。
     */
    public static String rawImageCommand(String path) {
        if (!isViewable(path)) {
            throw new IllegalArgumentException("非可浏览路径，命令构造拒绝: " + path);
        }
        return ContainerCommands.existenceGuard(path)
                + " s=$(stat -c %s \"$p\");"
                + " if [ \"$s\" -gt " + MAX_RAW_IMAGE_BYTES + " ]; then exit 2; fi;"
                + " cat \"$p\"";
    }

    /**
     * 单文件下载命令（#287 通用下载，ADR-0027 支付门；path 须先过
     * {@link #isViewable}，此处不代偿）：存在守卫（1 = 不存在或不是文件）+
     * {@code cat} 原始字节——<strong>无大小上限</strong>：下载＝带走，不是点看式
     * 的限读面；同通道先例＝源码包整卷 tar（工作区全量打包亦无上限，巨文件护面
     * 不在命令层）。路径经单引号包裹 + 转义，无注入面。
     */
    public static String downloadCommand(String path) {
        if (!isViewable(path)) {
            throw new IllegalArgumentException("非可浏览路径，命令构造拒绝: " + path);
        }
        return ContainerCommands.existenceGuard(path) + " cat \"$p\"";
    }

    /**
     * 扩展名 → content-type（raw 直出的响应头依据）：图片扩展名给真实 MIME，
     * 其余兜底 {@code application/octet-stream}（调用侧图片判定先行，此处不代偿）。
     */
    public static String contentTypeOf(String path) {
        return IMAGE_CONTENT_TYPES.getOrDefault(extensionOf(path), "application/octet-stream");
    }

    /** 小写扩展名（无扩展名即空串——点须在最后一段名内，整名恰为扩展词不算）。 */
    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash
                ? path.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }

    /**
     * 解析 find 输出为按路径稳定排序的条目列表（find 是目录序非字典序）。畸形行
     * （文件名含换行等产生的碎行）跳过不炸整树；空输出 = 空工作区。
     */
    public static List<Entry> parseEntries(String stdout) {
        List<Entry> entries = new ArrayList<>();
        for (String line : stdout.split("\n", -1)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            try {
                entries.add(new Entry(line.substring(tab + 1),
                        Long.parseLong(line.substring(0, tab))));
            } catch (NumberFormatException notALine) {
                // 大小段不是数字 = 碎行（文件名含换行等），跳过
            }
        }
        entries.sort(Comparator.comparing(Entry::path));
        return entries;
    }
}
