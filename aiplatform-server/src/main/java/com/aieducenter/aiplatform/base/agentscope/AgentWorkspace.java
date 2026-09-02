package com.aieducenter.aiplatform.base.agentscope;

import java.nio.file.Path;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 智能体的工作区锚定：HarnessAgent 的文件面落点，三形态——
 *
 * <ul>
 *   <li>{@link Local}：平台本地目录（配置兜底，如取名轻调用）——HarnessAgent 默认
 *       本地文件系统直用</li>
 *   <li>{@link ProjectDev}：项目 dev 工作区——dev 容器内 {@code /workspace}（Docker
 *       常开口径），智能体经 docker exec 读写，写入即落项目工作区（源码包可见）</li>
 *   <li>{@link ProjectReadOnly}：项目工作区只读面（#47 助理咨询姿态）——同一 dev
 *       容器同一根，但不挂内核文件/命令工具（写面结构性关闭；业务侧以只读工具集
 *       自查自答，见 {@code RoleToolkitSupplier}）</li>
 * </ul>
 */
public sealed interface AgentWorkspace {

    /** 工作区身份键（工厂复用缓存键的组成部分）。 */
    String identity();

    /** 平台本地工作区：{@code root} 为空表示用 AgentScope 默认。 */
    record Local(Path root) implements AgentWorkspace {

        @Override
        public String identity() {
            return "local:" + (root != null ? root.toString() : "<default>");
        }
    }

    /**
     * 项目 dev 工作区：{@code containerName} 为 dev 容器名（工作区句柄解析所得），
     * 容器内工作区根恒 {@code /workspace}（与供给/打包口径同源）。
     */
    record ProjectDev(String workspaceId, String containerName) implements AgentWorkspace {

        public ProjectDev {
            if (workspaceId == null || workspaceId.isBlank()
                    || containerName == null || containerName.isBlank()) {
                throw new IllegalArgumentException("ProjectDev 工作区需要 workspaceId 与 containerName");
            }
        }

        /** 容器内工作区根（正本 = 工作区布局常量表，dev 供给口径：-v vol:根 -w 根）。 */
        public static final String CONTAINER_ROOT = WorkspaceLayout.ROOT;

        @Override
        public String identity() {
            return "project-dev:" + containerName;
        }
    }

    /**
     * 项目工作区只读面（#47）：容器与根同 {@link ProjectDev}（工作区上下文照常
     * 可读），差异在工具面——工厂对本形态关闭内核文件与 shell 工具（无写面），
     * 项目事实的读取经业务侧只读工具集（文件树/文件内容/项目事实）。
     */
    record ProjectReadOnly(String workspaceId, String containerName) implements AgentWorkspace {

        public ProjectReadOnly {
            if (workspaceId == null || workspaceId.isBlank()
                    || containerName == null || containerName.isBlank()) {
                throw new IllegalArgumentException("ProjectReadOnly 工作区需要 workspaceId 与 containerName");
            }
        }

        @Override
        public String identity() {
            return "project-ro:" + containerName;
        }
    }
}
