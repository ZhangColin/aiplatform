package com.aieducenter.aiplatform.base.workspace.domain.model;

import java.util.List;

/**
 * 工作区布局常量表（ADR 0001 / #12 调研收口，#15 定盘）：单容器 all-in-one 之下
 * run 执行体与平台对工作区的全部物理约定收拢于此，四条约定——
 *
 * <ol>
 *   <li><b>根路径</b>：容器内唯一持久根 {@link #ROOT}（{@code -v 卷:ROOT -w ROOT}），
 *       供给、run 执行体文件面、平台读侧同锚，不散落第二根</li>
 *   <li><b>布局</b>：根下八类落位——AGENTS.md（平台约定，内容归生成环资产）、
 *       {@code docs/}（PRD 等文档）、应用代码占根、{@code data/pg/}（pg 数据，
 *       PGDATA 进卷）、{@code .platform/{rules,logs}}（平台产物——skills 预留位
 *       已随技能草稿库制删除，ADR-0022；既有工作区残留目录无害不再创建）、
 *       {@code materials/}（物料）、{@code design/}（设计产物）、
 *       {@code exports/}（导出物）——后三目录为设计线存储地基（#283/ADR-0027，
 *       图片一律以工作区为正本）</li>
 *   <li><b>.env 唯一注入通道</b>：平台生成的连接串只经 {@link #ENV_FILE} 进工作区，
 *       run 执行体与应用从环境读，不经其他注入面</li>
 *   <li><b>可重建性断言</b>：全部持久物（代码、文档、数据、平台产物）都在卷内——
 *       容器无状态，可随时销毁重建，{@code init-workspace.sh} 对既有卷幂等自愈；
 *       会话状态不落工作区（平台侧唯一落 {@code cat_agent_state} 库）</li>
 * </ol>
 *
 * <p>约定取「根与约定」不建文件面网关（#12）：本表只是常量的正本，通路仍是各
 * 消费方原生物理面（run 执行体经 docker exec 读写即容器文件面）。条目一律工作区
 * 锚定形（相对根），容器绝对形态经 {@link #absolute}
 * 派生；物理落位断言见 DockerEnvironmentBackendTest。跨上下文消费（agentscope /
 * business.project）直连本 domain 常量是显式例外——纯常量契约不值得上应用层网关。</p>
 */
public final class WorkspaceLayout {

    /** 约定一：容器内工作区根（唯一持久锚点，docker -v/-w 与 run 执行体/平台文件面的同源事实）。 */
    public static final String ROOT = "/workspace";

    /** 约定二：平台约定文件（生成环注入内容，v1 由 system prompt 承载、文件面随资产就位）。 */
    public static final String AGENTS_MD = "AGENTS.md";

    /** 文档目录（PRD 等面向用户与智能体的文档产物）。 */
    public static final String DOCS_DIR = "docs";

    /** PRD 正本（单最新版 markdown，事实源在工作区文件——读写两端共用此路径）。 */
    public static final String PRD = DOCS_DIR + "/PRD.md";

    /**
     * 物料目录（#283/ADR-0027 设计线存储地基）：用户上传进项目的文件资料落点
     * （图片物料为首形态，#286 上传端点写入）。文件区可见可点看、随封存保全
     * （输入非缓存，不进可重建名单）；版本化属输入面不入 git（版本层 .gitignore
     * 口径）。具名是实现自由度（ADR-0027），正本在此。
     */
    public static final String MATERIALS_DIR = "materials";

    /**
     * 设计产物目录（#283/ADR-0027）：设计执行体产出的设计稿落点（位图稿、平面
     * HTML 稿等，出图工具件转存与写文件件写入）。文件区可见（设计稿呈现之家是
     * 设计稿 tab 画布，文件树是另一扇窗）；不可再生的位图正身入版本流 git、可再生
     * 衍生不入（ADR-0027 版本化规则，版本层实施）。
     */
    public static final String DESIGN_DIR = "design";

    /**
     * 导出物目录（#283/ADR-0027）：平台打包的交付导出件落点（设计资产包 tar、
     * PNG 衍生下载件，#297 写入）。文件区可见；导出件可再生（源都在），但非
     * 缓存语义——随封存保全，不进可重建名单。
     */
    public static final String EXPORTS_DIR = "exports";

    /** 数据目录（中间件数据落点，随卷持久）。 */
    public static final String DATA_DIR = "data";

    /** pg 数据目录（PGDATA 归位修复：从独立卷改为工作区卷内，#3 决议）。 */
    public static final String PG_DATA_DIR = DATA_DIR + "/pg";

    /** 平台产物目录（rules/logs 的父目录）。 */
    public static final String PLATFORM_DIR = ".platform";

    /**
     * 子智能体 per-agent 工作区根（#95 委派位）：框架 ISOLATED 工作区布局的落点
     * （{@code agents/<name>/workspace/}，自动创建、namespace 隔离）——引擎自带，
     * 零新机制。进非交付目录集（子智能体报告/记忆不进源码包）。
     */
    public static final String AGENTS_DIR = "agents";

    /**
     * 外部仓库资料目录（#214 run 外部仓库惯例）：PRD 引用外部仓库时，run 起手
     * 浅克隆（{@code --depth 1}）进此目录、执行体只读参考（README/文档/源码结构），
     * 不合并进用户系统。进非交付目录集（不进交付源码包/文件树/版本正本），但<b>不</b>
     * 进可重建缓存集（封存默认保全——资料目录非缓存，与数据/平台产物同口径）。
     */
    public static final String EXTERNAL_DIR = "external";

    /** 平台产物：规则资产。 */
    public static final String RULES_DIR = PLATFORM_DIR + "/rules";

    /** 平台产物：日志。 */
    public static final String LOGS_DIR = PLATFORM_DIR + "/logs";

    /** 约定三：.env——平台向工作区注入连接串的唯一通道。 */
    public static final String ENV_FILE = ".env";

    /** 版本元数据目录（#91 容器内 git 仓库——版本正本 git log 落点，随卷持久）。 */
    public static final String GIT_DIR = ".git";

    /**
     * 约定二的目录面（init 骨架幂等落位的清单）：随骨架落位的目录——应用代码占根
     * 无目录约定，AGENTS.md 是文件资产非目录，都不在骨架内；external/（#214 run
     * 起手浅克隆创建）与 agents/（#95 框架委派位创建）是运行时按需创建，不进骨架
     * （写入方自带 mkdir -p）；设计线三目录（#283 物料/设计产物/导出物）进骨架——
     * 布局约定物理先行，写入方（上传端点/设计执行体/导出打包）仍自带 mkdir -p
     * 兜底既有卷。
     */
    public static final List<String> SKELETON_DIRS = List.of(
            DOCS_DIR, PG_DATA_DIR, RULES_DIR, LOGS_DIR,
            MATERIALS_DIR, DESIGN_DIR, EXPORTS_DIR);

    /**
     * 非交付目录名单（任意深度）：数据（{@link #DATA_DIR}）、平台产物
     * （{@link #PLATFORM_DIR}）、子智能体工作区（{@link #AGENTS_DIR}，#95 委派位
     * 隔离根——交付目录无子智能体脏写）、外部仓库资料目录
     * （{@link #EXTERNAL_DIR}，#214 run 外部仓库惯例——只读参考不合并进系统，非缓存、
     * 封存默认保全）、可重建依赖（node_modules）与 pnpm 依赖
     * 缓存（.pnpm-store，#113 基座 store-dir 落卷）、Next 构建产物（.next）与版本
     * 元数据（.git）不是交付物——源码包打包、平台文件树只读端点与版本层
     * .gitignore（#91）共用此单一事实（配合 {@link #ENV_FILE} 机密文件）。.git 由
     * 版本层 git 管道产出，不入源码包/文件树/版本跟踪（git 自排除，入名单为三者
     * 口径统一）。
     */
    public static final List<String> NON_DELIVERABLE_DIRS = List.of(
            "node_modules", ".pnpm-store", ".next", DATA_DIR, PLATFORM_DIR, AGENTS_DIR, EXTERNAL_DIR,
            GIT_DIR);

    /**
     * 可重建缓存名单（#172 封存单一事实）：封存打包（整卷 tar）唯一排除的目录——
     * 依赖（node_modules）、pnpm 依赖缓存（.pnpm-store）、Next 构建产物（.next），
     * 均可由「深度唤醒后重装依赖 / dev 模式即时构建」重建；数据库（{@link #PG_DATA_DIR}）
     * 与全部用户产物随包。是 {@link #NON_DELIVERABLE_DIRS} 的真子集（交付口径更严：
     * 数据/平台产物不交付但必须随封存包保全）。
     */
    public static final List<String> REBUILDABLE_CACHE_DIRS = List.of(
            "node_modules", ".pnpm-store", ".next");

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
