package com.semgravio.aiplatform.base.agentscope;

import java.nio.file.Path;

import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 智能体的工作区锚定：HarnessAgent 的文件面落点，三形态——
 *
 * <ul>
 *   <li>{@link Local}：平台本地目录（配置兜底，如取名轻调用）——HarnessAgent 默认
 *       本地文件系统直用</li>
 *   <li>{@link ProjectDev}：项目 dev 工作区——dev 容器内 {@code /workspace}（Docker
 *       常开口径），智能体经 docker exec 读写，写入即落项目工作区（源码包可见）</li>
 *   <li>{@link ProjectReadOnly}：项目工作区只读面（#86 主智能体对话姿态）——同一
 *       dev 容器同一根，但不挂内核文件/命令工具（写面结构性关闭；业务侧以只读
 *       工具集自查自答，见 {@code ProfileToolkitSupplier}）</li>
 *   <li>{@link ProjectDesign}：项目工作区设计面（#289 设计执行体姿态，ADR-0025
 *       「无 shell」）——容器与内核文件工具同 {@link ProjectDev}（写文件件在：
 *       界面稿 HTML 落工作区），另关内核 shell 与委派（命令执行结构性不存在）</li>
 *   <li>{@link ProjectKernel}：项目工作区生成内核形态（#595 内核装配，ADR-0054）
 *       ——容器与根同 {@link ProjectDev}，差异在装配面：系统提示＝
 *       {@link KernelSystemPrompt} 权威版本、工具面＝内核四件（{@link KernelTools}）、
 *       不挂技能、不挂子智能体委派</li>
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
     * 项目工作区只读面：容器与根同 {@link ProjectDev}（工作区上下文照常
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

    /**
     * 项目工作区设计面（#289 设计执行体姿态，ADR-0025 无 shell）：容器与内核文件
     * 工具同 {@link ProjectDev}（写文件件在——设计稿落工作区），差异在工具面——
     * 工厂对本形态关闭内核 shell 与委派（命令执行结构性不存在，写稿无需命令）。
     */
    record ProjectDesign(String workspaceId, String containerName) implements AgentWorkspace {

        public ProjectDesign {
            if (workspaceId == null || workspaceId.isBlank()
                    || containerName == null || containerName.isBlank()) {
                throw new IllegalArgumentException("ProjectDesign 工作区需要 workspaceId 与 containerName");
            }
        }

        @Override
        public String identity() {
            return "project-design:" + containerName;
        }
    }

    /**
     * 项目工作区生成内核形态（#595 内核装配，ADR-0054）：容器与根同
     * {@link ProjectDev}（docker exec 文件面、记忆关、会话状态存储同库），差异在
     * 装配面——工厂对本形态：系统提示只收 {@link KernelSystemPrompt} 权威版本
     * （改提示词＝改内核＝开 issue）、工具面＝恰四件（{@link KernelTools}，不发放
     * 旧工具面）、技能与子智能体委派结构性不挂。形态入实例缓存键
     * （{@code identity()} 与 {@link ProjectDev} 不同键——同容器两形态不静默复用）。
     */
    record ProjectKernel(String workspaceId, String containerName) implements AgentWorkspace {

        public ProjectKernel {
            if (workspaceId == null || workspaceId.isBlank()
                    || containerName == null || containerName.isBlank()) {
                throw new IllegalArgumentException("ProjectKernel 工作区需要 workspaceId 与 containerName");
            }
        }

        @Override
        public String identity() {
            return "project-kernel:" + containerName;
        }
    }
}
