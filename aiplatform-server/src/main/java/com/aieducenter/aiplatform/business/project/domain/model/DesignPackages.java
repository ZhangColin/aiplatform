package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 设计资产包（#297，ADR-0023/0027——设计面交付物在下单冻结时选件式打包）的
 * 规则单点：包件命名（暂存/落名）、选件清单、容器命令构造。tar 流内核复用
 * 源码包先例（docker exec 容器内 GNU tar），<b>排除式改选件式</b>——源码包以
 * 排除清单圈「全树减非交付物」，本包以显式成员清单圈「选定稿自包含形态＋设计
 * 规范文件＋衍生资产、落选稿不入」。
 *
 * <p>统一部件容器（ADR-0023「图、设计稿、系统皆产物部件，打在一个包里、类型
 * 可区分」）：系统＋设计单在同一 tar 内并置两类部件——系统源码走 find 剪枝的
 * 整树选件面（源码包同款非交付名单＋设计线两目录剪去），设计部件（design/ 稿、
 * DESIGN.md 规范、exports/ 帧衍生）走显式成员追加；目录即类型边界。设计单只有
 * 选件面。</p>
 *
 * <p>冻结序（编排归 {@code DesignPackageAppService}）：规范文件落盘 → 帧衍生
 * 渲染 → 目录清单 → 选件 → <b>暂存打包</b>（项目名暂存件，落库前失败＝下单
 * 失败零残留）→ 订单落库 → <b>落名</b>（mv 原子改名定格为该单冻结件，取消
 * 残留不清理——后台取件正本）。纯函数无依赖。</p>
 */
public final class DesignPackages {

    /** 界面类稿帧衍生的固定画幅（#294 所见即所下同一帧）——单点在本，
     * {@code ProjectRenderAppService}（下载图位图化）引用之（application 引
     * domain 常量是合法方向）。 */
    public static final int FRAME_PNG_WIDTH = 1280;

    public static final int FRAME_PNG_HEIGHT = 800;

    private DesignPackages() {
    }

    /** 该单冻结件路径（工作区锚定形）：exports/design-package-{orderId}.tar.gz。 */
    public static String packagePathOf(Long orderId) {
        return WorkspaceLayout.EXPORTS_DIR + "/design-package-" + orderId + ".tar.gz";
    }

    /**
     * 暂存件路径（工作区锚定形）：项目名点前缀（文件区弱可见、语义即未定格），
     * 同项目重复下单覆盖（未终结单唯一性保证同时至多一次在途冻结）。
     */
    public static String stagingPathOf(Long projectId) {
        return WorkspaceLayout.EXPORTS_DIR + "/.design-package-" + projectId + ".staging.tar.gz";
    }

    /** 帧衍生件名（工作区锚定形）：exports/{词干}-1280x800.png（#294 下载图同律）。 */
    public static String framePngOf(String stem) {
        return WorkspaceLayout.EXPORTS_DIR + "/" + stem
                + "-" + FRAME_PNG_WIDTH + "x" + FRAME_PNG_HEIGHT + ".png";
    }

    /**
     * 设计线目录清单命令：design/ 与 exports/ 顶层文件一次列出（稿与衍生皆平铺
     * 落位）。目录缺席（find 报错）静默空清单——骨架目录常态在，缺席即空选件，
     * 打包面不因目录不在而失败。
     */
    public static String listingCommand() {
        return "find " + WorkspaceLayout.absolute(WorkspaceLayout.DESIGN_DIR)
                + " " + WorkspaceLayout.absolute(WorkspaceLayout.EXPORTS_DIR)
                + " -maxdepth 1 -type f 2>/dev/null || true";
    }

    /** 清单输出（绝对路径行）→ 工作区相对形列表（非 {@code /workspace/} 前缀行跳过）。 */
    public static List<String> listedRelativeOf(String stdout) {
        String prefix = WorkspaceLayout.ROOT + "/";
        List<String> files = new ArrayList<>();
        for (String line : stdout.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                files.add(trimmed.substring(prefix.length()));
            }
        }
        return files;
    }

    /**
     * 选件清单（内容集正本，灵魂函数）：对每个定稿稿（件序输入序）选入——
     * <ul>
     *   <li>design/ 内同词干的一切形态（界面类 HTML／平面类 HTML 源＋PNG 双形态
     *       同存——「自包含形态」两形都带走；词干精确匹配，规范侧车
     *       {@code {词干}.spec.json} 词干相异不入）；</li>
     *   <li>exports/ 内同词干帧衍生（在才入——衍生是可再生伴随物，缺席如实）。</li>
     * </ul>
     * 落选稿（词干不在定稿集）结构性不入。确定性＝同输入同产出（排序钉死）。
     *
     * @param finalizedPaths 定稿锚定形路径清单（件序；锚定/相对两形皆收）
     * @param listedFiles    设计线目录清单（工作区相对形，{@link #listedRelativeOf}）
     */
    public static List<String> selectionOf(List<String> finalizedPaths, List<String> listedFiles) {
        LinkedHashSet<String> stems = new LinkedHashSet<>();
        for (String path : finalizedPaths) {
            String relative = ProjectFiles.relativeFormOf(path);
            if (relative != null) {
                stems.add(stemOf(relative));
            }
        }
        List<String> designFiles = listedFiles.stream()
                .filter(file -> file.startsWith(WorkspaceLayout.DESIGN_DIR + "/"))
                .sorted()
                .toList();
        List<String> selection = new ArrayList<>();
        for (String stem : stems) {
            designFiles.stream()
                    .filter(file -> stem.equals(stemOf(file)))
                    .forEach(selection::add);
            String derivative = framePngOf(stem);
            if (listedFiles.contains(derivative)) {
                selection.add(derivative);
            }
        }
        return selection;
    }

    /**
     * 选件式打包命令（两形）：设计单＝{@code tar czf <暂存> -C /workspace -T /dev/null
     * 成员…}；系统＋设计＝统一部件容器——源码部件由 {@link #findSourceCommand()}
     * 剪枝清单经管道喂 tar（{@code --null -T -}），设计部件以显式成员追加（目录即
     * 类型边界）。成员一律 {@code ./}前缀相对形。{@code -T /dev/null}＝空选件基线
     * （零成员 tar 拒绝打包，设计单零定稿的退化输入仍成空包——如实冻结而非失败）。
     *
     * <p>系统＋设计不走 tar {@code --exclude}：GNU tar 的排除对祖先目录命中即
     * 剪枝成员（显式成员 {@code ./design/x.html} 会连坐 {@code ./design} 的排除，
     * 活体实测）——选件面全在 find 剪枝＋显式清单，tar 只管打包。</p>
     */
    public static String tarCommand(String stagingPath, List<String> members,
            boolean withSystemParts) {
        StringBuilder command = new StringBuilder("mkdir -p ")
                .append(ContainerCommands.quotedLiteral(
                        WorkspaceLayout.absolute(WorkspaceLayout.EXPORTS_DIR)))
                .append(" && ");
        if (withSystemParts) {
            command.append(findSourceCommand())
                    .append(" | tar czf ").append(ContainerCommands.quoted(stagingPath))
                    .append(" --null -T -");
        }
        else {
            command.append("tar czf ").append(ContainerCommands.quoted(stagingPath))
                    .append(" -C ").append(WorkspaceLayout.ROOT).append(" -T /dev/null");
        }
        for (String member : members) {
            command.append(' ').append(ContainerCommands.quotedLiteral("./" + member));
        }
        return command.toString();
    }

    /**
     * 源码部件面选件（系统＋设计的整树侧）：工作区全树减非交付目录（源码包
     * {@code packSource} 同名单）减机密 {@code .env} 减设计线两目录（design/exports
     * ——设计部件走显式成员追加，exports 兼自排除：暂存件自身不进包）。剪枝在
     * find 源头（对偶文件树 {@code ProjectFiles.listCommand} 同法）；NUL 分隔经
     * 管道喂 tar（文件名含空格/换行不碎）。
     */
    static String findSourceCommand() {
        Stream<String> pruned = Stream.concat(
                WorkspaceLayout.NON_DELIVERABLE_DIRS.stream(),
                Stream.of(WorkspaceLayout.DESIGN_DIR, WorkspaceLayout.EXPORTS_DIR));
        String prunes = pruned.map(dir -> "-path ./" + dir)
                .collect(Collectors.joining(" -o "));
        return "cd " + WorkspaceLayout.ROOT + " && find . \\( " + prunes
                + " \\) -prune -o -type f ! -path ./" + WorkspaceLayout.ENV_FILE
                + " -printf './%P\\0'";
    }

    /**
     * 落名命令（暂存 → 该单冻结件）：{@code mv -f} 同目录原子改名——打包与落库
     * 之间的窗口里暂存件不是任何单的冻结件，改名即定格。
     */
    public static String sealCommand(String stagingPath, String packagePath) {
        return "mv -f " + ContainerCommands.quoted(stagingPath)
                + " " + ContainerCommands.quoted(packagePath);
    }

    /** 文件名 → 词干（末段去末个扩展名；无扩展名即整名）——稿名派生单点。 */
    public static String stemOf(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
