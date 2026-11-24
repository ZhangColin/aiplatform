package com.semgravio.aiplatform.base.agentscope;

/**
 * 生成内核系统提示词权威版本（#595，ADR-0054／#593 用户故事 9/11）：角色＋环境描述
 * ＋任务句＋长文件拆分提示＋开发服务器常驻句＋完成判据＋全程中文，外加完成纪律三句
 * （bolt.diy 原文口径）与工具使用说明三件（DeepSeek Harness 原文只换工具名）。
 *
 * <p><b>权威版本住代码、不进后台可配</b>：改提示词＝改内核＝开 issue。装配唯一合法
 * 入口 {@link AgentscopeHarnessAgentFactory#obtainKernel}——直用内核工作区携外来
 * 提示词装配被结构拒绝（buildAgent 守卫）。逐句出处：</p>
 *
 * <ul>
 *   <li>正文六件（角色／环境／任务句／长文件拆分提示／完成判据／全程中文）沿用
 *       {@code prototype/v0-firstrun2} 分支 {@code v0-harness4.py} VARIANT=3
 *       （#590 修正轮）原文——该脚本自承这部分无成熟方案出处（#576 原句沿用）</li>
 *   <li>开发服务器常驻句同为实验自写句，<b>日志位按平台实况校准</b>：实验脚本写
 *       「日志在 /tmp/app.log」（实验环境自起服自写句），平台实况是
 *       {@code DockerEnvironmentBackend.appStartCommand} 的
 *       {@code nohup $APP > .app.log 2>&1}——日志在 /workspace/.app.log，
 *       本权威版本按实况落字（#595：校准以镜像实况为准）</li>
 *   <li>完成纪律三句照 bolt.diy 原文：{@code app/lib/.server/llm/prompt-workstyle.ts}
 *       前两句 ＋ {@code stream-text.ts} agent_mode_workflow 段第三句
 *       （#595 施工经一手源码逐字核对）</li>
 *   <li>工具使用说明三件照 DeepSeek Harness 原文只换工具名：
 *       {@code packages/fs/tool-fs/src/read.ts:74}（read 引导一段，含两句）、
 *       {@code write.ts:68}、{@code edit.ts:82}（#595 施工经一手源码逐字核对；
 *       工具件的说明文字同口径见 {@link KernelTools}）</li>
 *   <li>工作区约定句（#607）：原工作区 AGENTS.md 平台约定退场后，仍成立的四条生产
 *       约束（data/ 持久化、external/ 外部仓库、.platform/ 不写、.env 只读）并入
 *       本提示词单一权威；已由环境句／起服句覆盖或不再需要的约定不重复</li>
 * </ul>
 *
 * <p>环境句事实清单（2026-11-23 逐字对照当前镜像实况，验收第二条的正本）：</p>
 * <ul>
 *   <li>模板工程：{@code baseline/package.json}——Next 16.3.1（src/app 即 App Router）
 *       ＋React 19.2.8＋Tailwind 4（tailwindcss ^4）＋shadcn（@shadcn/react）</li>
 *   <li>依赖已装：dev 镜像构建期 {@code pnpm install} 层（store-dir 钉卷上），
 *       init-workspace.sh 首次 {@code cp -a /opt/baseline/.} 落工作区；
 *       pnpm@10 全局可用（镜像层 {@code npm install -g pnpm@10}）</li>
 *   <li>PG/Redis 已起：init-workspace.sh 第 2)/4) 节——postgresql-15 与 redis-server
 *       容器内回环常驻（容器重建幂等自愈）</li>
 *   <li>连接串在 .env：{@code DockerEnvironmentBackend.writeEnvFile} 写
 *       /workspace/.env（DATABASE_URL＝postgresql://&lt;库名&gt;@localhost:5432/…、
 *       REDIS_URL＝redis://localhost:6379）</li>
 *   <li>开发服务器平台常驻：{@code DockerEnvironmentBackend.startApp} 拉起
 *       （npm start → next dev -H 0.0.0.0 -p 8081），日志 /workspace/.app.log；
 *       「不要停掉或重启它」对应 #593 用户故事 14（模型不杀不起）</li>
 * </ul>
 */
public final class KernelSystemPrompt {

    /** 角色。 */
    static final String ROLE = "你是全栈工程师。";

    /**
     * 环境描述（逐字对照镜像实况——事实清单见类 javadoc；#590 修正轮原文沿用，
     * 事实面与现行 dev 镜像/baseline 逐一相符）。
     */
    static final String ENVIRONMENT = "环境：Linux 容器，/workspace 是配置好的 Next.js(App Router)"
            + "+React 19+Tailwind 4+shadcn 工程，pnpm 可用、node_modules 已装；"
            + "容器内 PostgreSQL 与 Redis 已在运行，连接串在 /workspace/.env。";

    /** 任务句（#576 原句沿用）。 */
    static final String TASK = "任务：把 /workspace/docs/PRD.md 描述的完整系统实现出来"
            + "（建表、后端 API、全部页面、演示数据）。用工具读写文件、运行命令，"
            + "可随时跑命令验证与调试。";

    /** 长文件拆分提示（#576 原句沿用）。 */
    static final String LONG_FILE_HINT = "注意：单个文件太长时拆分或分块写"
            + "（write_file 覆盖写全量、bash 可追加）。";

    /**
     * 开发服务器常驻句（实验自写句，平台实况校准）：日志位按
     * DockerEnvironmentBackend.appStartCommand 实况落 /workspace/.app.log
     * （实验脚本的 /tmp/app.log 不采用——#595 校准以镜像实况为准）。
     */
    static final String SERVER_NOTE = "开发服务器已由平台启动并常驻在 0.0.0.0:8081，"
            + "改动会自动生效，不要停掉或重启它（日志在 /workspace/.app.log）。";

    /**
     * 工作区约定句（#607 并入：原工作区 AGENTS.md 平台约定退场后，仍成立的
     * 四条生产约束归提示词单一权威）。原 AGENTS.md 逐条去向：
     * <ul>
     *   <li>并入——data/ 持久化、external/ 外部仓库、.platform/ 不写、.env 只读
     *       （含变量名 DATABASE_URL／REDIS_URL）</li>
     *   <li>已被覆盖不重复——基座技术栈／8081 常驻／起服节奏（环境句、起服句）、
     *       docs/PRD.md 是需求正本与实现依据（任务句；首建只按 PRD 实现，迭代时
     *       内核按迭代任务提示把需求并入 PRD 对应章节——#624，平台不写 PRD）</li>
     *   <li>平台侧保证、不需模型动作——external/ 不进交付源码包/文件树/版本正本、
     *       node_modules 打包排除</li>
     *   <li>删除——AGENTS.md 自述（平台不再写该文件）、依赖安装提示（环境句已述
     *       依赖已装）</li>
     * </ul>
     */
    static final String WORKSPACE_CONSTRAINTS = "工作区约定：应用自用的文件数据落 "
            + "/workspace/data/ 目录（随卷持久、不进交付源码包）；PRD 引用外部仓库时"
            + "用 git clone --depth 1 <仓库地址> external/<仓库名> 浅克隆进 "
            + "/workspace/external/，只读参考其 README/文档/源码结构、不合并进系统；"
            + "/workspace/.platform/ 是平台产物目录，不要写入；/workspace/.env 由平台"
            + "生成维护，不要修改（数据库连接串读 DATABASE_URL，缓存用 REDIS_URL）。";

    /** 完成判据句（#593：build 过＋8081 探活有响应才算完成）。 */
    static final String COMPLETION_CRITERIA = "完成后用 pnpm build 验证可构建，"
            + "用 curl http://localhost:8081 确认有响应才算完成。";

    /** 工具使用说明·read 引导（DSH read.ts:74 原文一段〔两句〕，read→read_file）。 */
    static final String TOOL_USAGE_READ = "Use the read_file tool — not shell commands like cat, "
            + "sed, head, or tail — to inspect text files. Use offset and limit to read a line "
            + "range or continue a large file.";

    /** 工具使用说明·write 引导（DSH write.ts:68 原文，write→write_file）。 */
    static final String TOOL_USAGE_WRITE = "Read an existing file before overwriting it with "
            + "write_file and prefer edit_file for targeted changes.";

    /** 工具使用说明·edit 引导（DSH edit.ts:82 原文）。 */
    static final String TOOL_USAGE_EDIT = "Read a file before editing it unless you just "
            + "created or edited it in this session.";

    /** 完成纪律第一句（bolt.diy prompt-workstyle.ts 原文）。 */
    static final String DISCIPLINE_FIRST = "Treat tool output as the source of truth.";

    /** 完成纪律第二句（bolt.diy prompt-workstyle.ts 原文）。 */
    static final String DISCIPLINE_SECOND = "Report failed commands as failed; do not claim "
            + "a file or command changed until its tool result confirms it.";

    /** 完成纪律第三句（bolt.diy stream-text.ts agent_mode_workflow 段原文）。 */
    static final String DISCIPLINE_THIRD = "Do not claim a task or check is complete without "
            + "tool evidence.";

    /** 语言钉（#593 结构决议第六件）。 */
    static final String LANGUAGE = "全程中文。";

    /**
     * 权威版本全文（段落序＝#593 结构决议序＝#590 修正轮原文序）：段间无分隔符——
     * 原文即连排单行形态。
     */
    private static final String TEXT = ROLE + ENVIRONMENT + TASK + LONG_FILE_HINT + SERVER_NOTE
            + WORKSPACE_CONSTRAINTS + COMPLETION_CRITERIA + TOOL_USAGE_READ + TOOL_USAGE_WRITE
            + TOOL_USAGE_EDIT + DISCIPLINE_FIRST + DISCIPLINE_SECOND + DISCIPLINE_THIRD + LANGUAGE;

    private KernelSystemPrompt() {
    }

    /** 权威版本全文。 */
    public static String text() {
        return TEXT;
    }
}
