package com.aieducenter.aiplatform.business.project.domain.model;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 容器 shell 命令构造的公共骨架（#284 抽取，承 #283 备案触发器「第三处出现」）：
 * 字面量进 shell 命令的共用件——单引号转义（工作区路径经 {@link #quoted} 绝对
 * 形化，任意字面量如 URL 经 {@link #quotedLiteral}）与存在守卫。消费方
 * （{@link ProjectFiles} 文件读、{@link WorkspaceRenders} 位图出口、
 * {@link ImageTransfers} 出图转存）各自命令形制不同（大小限读、stat 回执、渲染器
 * 载荷），骨架只收<strong>字面相同</strong>的部分，不为「将来统一的命令构造器」
 * 预铸结构。纯函数无依赖。
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
     * 存在守卫前缀（退出码 1 = 源不在）：{@code p='<路径>'; if ! test -f "$p";
     * then exit 1; fi;}——cat/渲染器执行前的同款守卫，调用方续写各自的命令体。
     */
    static String existenceGuard(String relativePath) {
        return "p=" + quoted(relativePath) + "; if ! test -f \"$p\"; then exit 1; fi;";
    }

    /**
     * stdin 灌入写文件（#296 收单点，承 #295 备案触发器「第三处出现」）：父目录
     * 幂等落位、{@code cat >} 接 stdin、stdout = stat 字节回执——平台落盘件共用形
     * （{@link ProjectMaterials} 物料上传 / {@link DesignSpecs} 规范侧车 / 遵守面
     * 规则文件与 lint 配置）。路径经单引号包裹＋转义，无注入面。
     */
    static String stdinWriteCommand(String relativePath) {
        return "p=" + quoted(relativePath)
                + "; d=${p%/*}; mkdir -p \"$d\" && cat > \"$p\" && stat -c %s \"$p\"";
    }
}
