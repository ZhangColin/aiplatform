package com.aieducenter.aiplatform.base.workspace.domain.model;

import java.util.List;

/**
 * 工作区布局常量表（ADR 0001 / #12 调研收口，#15 定盘）：单容器 all-in-one 之下
 * 引擎与平台对工作区的全部物理约定收拢于此，五条约定——
 *
 * <ol>
 *   <li><b>根路径</b>：容器内唯一持久根 {@link #ROOT}（{@code -v 卷:ROOT -w ROOT}），
 *       供给、编码智能体文件面、平台读侧同锚，不散落第二根</li>
 *   <li><b>布局</b>：根下五类落位——AGENTS.md（平台约定，内容归生成环资产）、
 *       {@code docs/}（PRD 等文档）、应用代码占根、{@code data/pg/}（pg 数据，
 *       PGDATA 进卷）、{@code .platform/{skills,rules,sessions,logs}}（平台产物）</li>
 *   <li><b>.env 唯一注入通道</b>：平台生成的连接串只经 {@link #ENV_FILE} 进工作区，
 *       引擎与应用从环境读，不经其他注入面</li>
 *   <li><b>会话寻址分层</b>：平台侧会话落库（{@code cat_agent_state}）；引擎侧会话
 *       数据经 {@link #XDG_DATA_HOME} 重定向落 {@code .platform}（容器重建不丢）</li>
 *   <li><b>可重建性断言</b>：全部持久物（代码、文档、数据、平台产物）都在卷内——
 *       容器无状态，可随时销毁重建，{@code init-workspace.sh} 对既有卷幂等自愈</li>
 * </ol>
 *
 * <p>约定取「根与约定」不建文件面网关（#12）：本表只是常量的正本，通路仍是各自
 * 引擎原生物理面。条目一律工作区锚定形（相对根），容器绝对形态经 {@link #absolute}
 * 派生；物理落位断言见 DockerEnvironmentBackendTest。跨上下文消费（agentscope /
 * business.project）直连本 domain 常量是显式例外——纯常量契约不值得上应用层网关。</p>
 */
public final class WorkspaceLayout {

    /** 约定一：容器内工作区根（唯一持久锚点，docker -v/-w 与三引擎文件面的同源事实）。 */
    public static final String ROOT = "/workspace";

    /** 约定二：平台约定文件（生成环注入内容，v1 由 system prompt 承载、文件面随资产就位）。 */
    public static final String AGENTS_MD = "AGENTS.md";

    /** 文档目录（PRD 等面向用户与智能体的文档产物）。 */
    public static final String DOCS_DIR = "docs";

    /** PRD 正本（单最新版 markdown，事实源在工作区文件——读写两端共用此路径）。 */
    public static final String PRD = DOCS_DIR + "/PRD.md";

    /** 数据目录（中间件数据落点，随卷持久）。 */
    public static final String DATA_DIR = "data";

    /** pg 数据目录（PGDATA 归位修复：从独立卷改为工作区卷内，#3 决议）。 */
    public static final String PG_DATA_DIR = DATA_DIR + "/pg";

    /** 平台产物目录（引擎会话数据重定向目标与 skills/rules/sessions/logs 的父目录）。 */
    public static final String PLATFORM_DIR = ".platform";

    /** 平台产物：技能资产。 */
    public static final String SKILLS_DIR = PLATFORM_DIR + "/skills";

    /** 平台产物：规则资产。 */
    public static final String RULES_DIR = PLATFORM_DIR + "/rules";

    /** 平台产物：引擎侧会话数据（布局保留位；XDG 重定向目标为 .platform 根，引擎自定子目录落其下）。 */
    public static final String SESSIONS_DIR = PLATFORM_DIR + "/sessions";

    /** 平台产物：日志。 */
    public static final String LOGS_DIR = PLATFORM_DIR + "/logs";

    /** 约定三：.env——平台向工作区注入连接串的唯一通道。 */
    public static final String ENV_FILE = ".env";

    /** 约定四：引擎会话数据重定向目标（容器层 ~/.local/share 不落持久物）。 */
    public static final String XDG_DATA_HOME = PLATFORM_DIR;

    /**
     * 约定二的目录面（init 骨架幂等落位的清单）：布局中的全部目录——应用代码占根
     * 无目录约定，AGENTS.md 是文件资产非目录，都不在骨架内。
     */
    public static final List<String> SKELETON_DIRS = List.of(
            DOCS_DIR, PG_DATA_DIR, SKILLS_DIR, RULES_DIR, SESSIONS_DIR, LOGS_DIR);

    private WorkspaceLayout() {
    }

    /**
     * 工作区锚定形 → 容器绝对路径（{@code docs/PRD.md} → {@code /workspace/docs/PRD.md}；
     * {@code "."} 即根本身）。绝对路径或含 {@code ..} 的逃逸输入拒绝——布局条目只有
     * 相对形态一种。
     */
    public static String absolute(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/")
                || relativePath.contains("..")) {
            throw new IllegalArgumentException("布局路径必须是工作区锚定形（相对根）: " + relativePath);
        }
        return ".".equals(relativePath) ? ROOT : ROOT + "/" + relativePath;
    }
}
