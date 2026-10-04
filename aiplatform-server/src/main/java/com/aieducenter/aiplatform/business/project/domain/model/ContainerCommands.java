package com.aieducenter.aiplatform.business.project.domain.model;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 容器 shell 命令构造的公共骨架（#284 抽取，承 #283 备案触发器「第三处出现」）：
 * 工作区路径进 shell 命令的两个共用件——单引号转义与存在守卫。消费方
 * （{@link ProjectFiles} 文件读、{@link WorkspaceRenders} 位图出口）各自命令形制
 * 不同（大小限读、stat 回执、渲染器载荷），骨架只收<strong>字面相同</strong>的
 * 部分，不为「将来统一的命令构造器」预铸结构。纯函数无依赖。
 */
final class ContainerCommands {

    private ContainerCommands() {
    }

    /** 工作区锚定路径 → 单引号包裹 + 转义（' → '\''，无注入面）。 */
    static String quoted(String relativePath) {
        return "'" + WorkspaceLayout.absolute(relativePath).replace("'", "'\\''") + "'";
    }

    /**
     * 存在守卫前缀（退出码 1 = 源不在）：{@code p='<路径>'; if ! test -f "$p";
     * then exit 1; fi;}——cat/渲染器执行前的同款守卫，调用方续写各自的命令体。
     */
    static String existenceGuard(String relativePath) {
        return "p=" + quoted(relativePath) + "; if ! test -f \"$p\"; then exit 1; fi;";
    }
}
