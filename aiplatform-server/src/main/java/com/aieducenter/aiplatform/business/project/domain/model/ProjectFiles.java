package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 文件树浏览面（#27）的规则单点：交付文件视图 = 工作区剔除非交付物（数据 /
 * 平台产物 / 可重建依赖 / 机密，名单归 {@link WorkspaceLayout} 正本）后的只读
 * 文件面。排除作用域一律<strong>工作区根级</strong>（与源码包 tar 排除严格同
 * 口径）——嵌套同名目录（如应用自己的 {@code src/data/}）是交付物，不误伤。
 * 收拢四件事——可浏览路径判定（内容端点的用户可控入参防线）、树列表命令构造
 * （find 剪枝在源头排除，不进 node_modules 巨树）、内容读取命令构造（大小限读
 * 在容器侧先行，巨文件不进内存）、find 输出解析。纯函数无依赖。
 */
public final class ProjectFiles {

    /** 在线查看的文件大小上限（1 MiB）：容器侧 cat 前拦截，超限不读取。 */
    public static final long MAX_CONTENT_BYTES = 1024 * 1024;

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
        String quoted = "'" + WorkspaceLayout.absolute(path).replace("'", "'\\''") + "'";
        return "p=" + quoted + "; if ! test -f \"$p\"; then exit 1; fi;"
                + " s=$(stat -c %s \"$p\");"
                + " if [ \"$s\" -gt " + MAX_CONTENT_BYTES + " ]; then exit 2; fi;"
                + " printf '%s\\n' \"$s\"; cat \"$p\"";
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
