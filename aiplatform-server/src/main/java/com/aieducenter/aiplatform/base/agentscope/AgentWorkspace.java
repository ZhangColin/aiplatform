package com.aieducenter.aiplatform.base.agentscope;

import java.nio.file.Path;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 智能体的工作区锚定：HarnessAgent 的文件面落点，两形态——
 *
 * <ul>
 *   <li>{@link Local}：平台本地目录（配置兜底，如取名轻调用）——HarnessAgent 默认
 *       本地文件系统直用</li>
 *   <li>{@link ProjectDev}：项目 dev 工作区——dev 容器内 {@code /workspace}（Docker
 *       常开口径），智能体经 docker exec 读写，写入即落项目工作区（源码包可见）</li>
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
}
