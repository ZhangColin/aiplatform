package com.semgravio.aiplatform.business.project.domain.model;

import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 容器 shell 命令构造的公共骨架（#284 抽取，承 #283 备案触发器「第三处出现」）：
 * 字面量进 shell 命令的共用件——单引号转义（工作区路径经 {@link #quoted} 绝对
 * 形化，任意字面量如 URL 经 {@link #quotedLiteral}）与存在守卫。消费方
 * （{@link ProjectFiles} 文件读、{@link WorkspaceRenders} 位图出口、
 * {@link ImageTransfers} 出图转存）各自命令形制不同（大小限读、stat 回执、渲染器
 * 载荷），骨架只收<strong>字面相同</strong>的部分，不为「将来统一的命令构造器」
 * 预铸结构。纯函数无依赖。
 *
 * <p><b>命令书写纪律（#401，#399 家族）</b>：命令不得内嵌双引号——Windows JVM→
 * docker.exe 的 argv 编码链会剥内嵌双引号，{@code runCapture} 缝已 fail-fast 防呆
 * （WSP_018）。shell 变量中间层（{@code p=…; cat "$p"}）因此全数消灭：路径由
 * Java 侧内联单引号字面量（派生路径——父目录、{@code .part} 暂存——同样 Java 侧
 * 算，见 {@link #quotedParent}/{@link #quotedPart}）；唯一保留的变量是 stat 的
 * 纯数字回执（无空白/元字符，无引号安全）。</p>
 */
final class ContainerCommands {

    private ContainerCommands() {
    }

    /** 工作区锚定路径 → 单引号包裹 + 转义（' → '\''，无注入面）。 */
    static String quoted(String relativePath) {
        return quotedLiteral(WorkspaceLayout.absolute(relativePath));
    }

    /** 任意字面量（路径/URL）→ 单引号包裹 + 转义（' → '\''，无注入面）。 */
    static String quotedLiteral(String literal) {
        return "'" + literal.replace("'", "'\\''") + "'";
    }

    /**
     * 工作区锚定路径的父目录 → 单引号包裹 + 转义（写面的父目录幂等落位用）。
     * POSIX 路径按字符串切分，不经 {@code Path}——Windows JVM 上 Path 会把容器
     * 路径切成反斜杠形。
     */
    static String quotedParent(String relativePath) {
        String absolute = WorkspaceLayout.absolute(relativePath);
        return quotedLiteral(absolute.substring(0, absolute.lastIndexOf('/')));
    }

    /** {@code .part} 暂存落点（转存原子写）→ 单引号包裹 + 转义。 */
    static String quotedPart(String relativePath) {
        return quotedLiteral(WorkspaceLayout.absolute(relativePath) + ".part");
    }

    /**
     * 存在守卫前缀（退出码 1 = 源不在）：{@code if ! test -f '<路径>';
     * then exit 1; fi;}——cat/渲染器执行前的同款守卫，调用方续写各自的命令体。
     */
    static String existenceGuard(String relativePath) {
        return "if ! test -f " + quoted(relativePath) + "; then exit 1; fi;";
    }

    /**
     * stdin 灌入写文件（#296 收单点，承 #295 备案触发器「第三处出现」）：父目录
     * 幂等落位、{@code cat >} 接 stdin、stdout = stat 字节回执——平台落盘件共用形
     * （{@link ProjectMaterials} 物料上传 / {@link DesignSpecs} 规范侧车 / 遵守面
     * 规则文件与 lint 配置）。路径经单引号包裹＋转义，无注入面。
     */
    static String stdinWriteCommand(String relativePath) {
        return "mkdir -p " + quotedParent(relativePath)
                + " && cat > " + quoted(relativePath)
                + " && stat -c %s " + quoted(relativePath);
    }
}
