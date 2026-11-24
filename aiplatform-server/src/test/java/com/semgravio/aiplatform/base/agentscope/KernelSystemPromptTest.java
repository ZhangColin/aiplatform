package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 内核系统提示词权威版本（#595，ADR-0054）：结构完整性与逐字出处核对——
 * 角色＋环境描述＋任务句＋长文件拆分提示＋完成判据＋全程中文，外加完成纪律三句
 * （bolt.diy 原文）与工具使用说明三件（DeepSeek Harness 原文只换工具名）。
 *
 * <p>期望字面量来自独立正本（非实现推导）：{@code prototype/v0-firstrun2} 分支
 * {@code v0-harness4.py} VARIANT=3 系统提示原文；纪律三句与工具说明三件经
 * #595 施工对 bolt.diy（prompt-workstyle.ts / stream-text.ts）与 DSH
 * （packages/fs/tool-fs read.ts:74 / write.ts:68 / edit.ts:82）一手源码逐字核对；
 * 环境句的事实清单对照当前 dev 镜像实况（baseline 模板 / init-workspace.sh /
 * DockerEnvironmentBackend 起服与 .env 写入——日志位按平台实况校准为
 * /workspace/.app.log，实验脚本的 /tmp/app.log 是实验环境自写句、平台实况不同）。
 */
class KernelSystemPromptTest {

    /** 全文即权威版本本体（幂等、住代码、无后台可配面）。 */
    @Test
    void given_authoritative_prompt_when_text_then_full_body_matches_decided_form() {
        String text = KernelSystemPrompt.text();

        // 六基件（#593 结构决议）：角色／环境描述／任务句／长文件拆分提示／完成判据／
        // 全程中文；外加平台追加段（#593 起服句、#607 工作区约定句）
        assertThat(text).isEqualTo(KernelSystemPrompt.ROLE + KernelSystemPrompt.ENVIRONMENT
                + KernelSystemPrompt.TASK + KernelSystemPrompt.LONG_FILE_HINT
                + KernelSystemPrompt.SERVER_NOTE + KernelSystemPrompt.WORKSPACE_CONSTRAINTS
                + KernelSystemPrompt.COMPLETION_CRITERIA
                + KernelSystemPrompt.TOOL_USAGE_READ + KernelSystemPrompt.TOOL_USAGE_WRITE
                + KernelSystemPrompt.TOOL_USAGE_EDIT + KernelSystemPrompt.DISCIPLINE_FIRST
                + KernelSystemPrompt.DISCIPLINE_SECOND + KernelSystemPrompt.DISCIPLINE_THIRD
                + KernelSystemPrompt.LANGUAGE);
    }

    /** 结构顺序（#593 结构决议的先后即权威版本段落序——实验修正轮原文顺序）。 */
    @Test
    void given_authoritative_prompt_when_text_then_sections_in_decided_order() {
        String text = KernelSystemPrompt.text();

        assertThat(text).containsSubsequence(
                KernelSystemPrompt.ROLE,
                KernelSystemPrompt.ENVIRONMENT,
                KernelSystemPrompt.TASK,
                KernelSystemPrompt.LONG_FILE_HINT,
                KernelSystemPrompt.SERVER_NOTE,
                KernelSystemPrompt.WORKSPACE_CONSTRAINTS,
                KernelSystemPrompt.COMPLETION_CRITERIA,
                KernelSystemPrompt.TOOL_USAGE_READ,
                KernelSystemPrompt.TOOL_USAGE_WRITE,
                KernelSystemPrompt.TOOL_USAGE_EDIT,
                KernelSystemPrompt.DISCIPLINE_FIRST,
                KernelSystemPrompt.DISCIPLINE_SECOND,
                KernelSystemPrompt.DISCIPLINE_THIRD,
                KernelSystemPrompt.LANGUAGE);
    }

    /** 角色句（#576 原句沿用）。 */
    @Test
    void given_authoritative_prompt_when_text_then_role_line_verbatim() {
        assertThat(KernelSystemPrompt.text()).startsWith("你是全栈工程师。");
    }

    /**
     * 环境句逐字对照镜像实况（#595 验收第二条）：每个事实单独断言、注释挂正本出处——
     * 模板工程（baseline/package.json：Next 16.3.1 App Router＋React 19.2.8＋
     * Tailwind 4＋shadcn）、依赖已装（dev 镜像构建期 pnpm install 层）、PG/Redis
     * 容器内已起（init-workspace.sh）、连接串在 /workspace/.env
     * （DockerEnvironmentBackend.writeEnvFile）。
     */
    @Test
    void given_authoritative_prompt_when_text_then_environment_line_matches_image_reality() {
        String environment = KernelSystemPrompt.ENVIRONMENT;

        // 正本：aiplatform-server/src/main/resources/docker/workspace/baseline/package.json
        // （next 16.3.1 ＋ react 19.2.8 ＋ tailwindcss ^4 ＋ @shadcn/react，src/app 即 App Router）
        assertThat(environment).isEqualTo("环境：Linux 容器，/workspace 是配置好的 Next.js(App Router)"
                + "+React 19+Tailwind 4+shadcn 工程，pnpm 可用、node_modules 已装；"
                + "容器内 PostgreSQL 与 Redis 已在运行，连接串在 /workspace/.env。");
        assertThat(environment).contains("Next.js(App Router)+React 19+Tailwind 4+shadcn 工程");
        // 正本：dev 镜像 Dockerfile 层（RUN npm install -g pnpm@10；基座构建期 pnpm install
        // 装进 node_modules，init-workspace.sh 首次 cp -a /opt/baseline/. 落工作区）
        assertThat(environment).contains("pnpm 可用、node_modules 已装");
        // 正本：init-workspace.sh 第 2)/4) 节（postgresql-15 与 redis-server 容器内回环常驻）
        assertThat(environment).contains("容器内 PostgreSQL 与 Redis 已在运行");
        // 正本：DockerEnvironmentBackend.writeEnvFile（DATABASE_URL/REDIS_URL 写 /workspace/.env）
        assertThat(environment).contains("连接串在 /workspace/.env");
    }

    /** 任务句与长文件拆分提示（#576 原句沿用，#593 结构决议的两件）。 */
    @Test
    void given_authoritative_prompt_when_text_then_task_and_long_file_hint_verbatim() {
        String text = KernelSystemPrompt.text();

        assertThat(text).contains("任务：把 /workspace/docs/PRD.md 描述的完整系统实现出来"
                + "（建表、后端 API、全部页面、演示数据）。");
        assertThat(KernelSystemPrompt.LONG_FILE_HINT)
                .isEqualTo("注意：单个文件太长时拆分或分块写（write_file 覆盖写全量、bash 可追加）。");
    }

    /**
     * 开发服务器句按平台实况校准（#595：实验脚本的「日志在 /tmp/app.log」是实验环境
     * 自写句，校准以镜像实况为准）：平台由 DockerEnvironmentBackend.startApp 起服常驻
     * （npm start → next dev -H 0.0.0.0 -p 8081），日志重定向落 /workspace/.app.log
     * （appStartCommand 的 nohup $APP > .app.log 2>&1）。
     */
    @Test
    void given_authoritative_prompt_when_text_then_server_note_calibrated_to_platform_reality() {
        String note = KernelSystemPrompt.SERVER_NOTE;

        assertThat(note).isEqualTo("开发服务器已由平台启动并常驻在 0.0.0.0:8081，"
                + "改动会自动生效，不要停掉或重启它（日志在 /workspace/.app.log）。");
        // 校准判据本身钉死：平台实况日志位＝卷内 .app.log（非实验脚本的 /tmp/app.log）
        assertThat(note).contains("0.0.0.0:8081");
        assertThat(note).contains("/workspace/.app.log");
        assertThat(note).doesNotContain("/tmp/app.log");
    }

    /**
     * 工作区约定句（#607）：原工作区 AGENTS.md 平台约定退场后，仍成立的四条生产
     * 约束并入内核提示词单一权威——data/ 持久化、external/ 外部仓库、.platform/
     * 不写、.env 只读。已被环境句／起服句覆盖的约定不重复。
     */
    @Test
    void given_authoritative_prompt_when_text_then_workspace_constraints_merged_from_agents_md() {
        String constraints = KernelSystemPrompt.WORKSPACE_CONSTRAINTS;

        assertThat(constraints).contains("/workspace/data/");
        assertThat(constraints).contains("external/<仓库名>");
        assertThat(constraints).contains("/workspace/external/");
        assertThat(constraints).contains("不合并进系统");
        assertThat(constraints).contains("/workspace/.platform/").contains("不要写入");
        assertThat(constraints).contains("/workspace/.env").contains("不要修改");
        assertThat(constraints).contains("DATABASE_URL").contains("REDIS_URL");
        assertThat(KernelSystemPrompt.text()).contains(constraints);
    }

    /** 完成判据句（#593：build 过＋8081 探活有响应才算完成）。 */
    @Test
    void given_authoritative_prompt_when_text_then_completion_criteria_verbatim() {
        assertThat(KernelSystemPrompt.COMPLETION_CRITERIA)
                .isEqualTo("完成后用 pnpm build 验证可构建，用 curl http://localhost:8081 "
                        + "确认有响应才算完成。");
    }

    /**
     * 完成纪律三句（bolt.diy 原文口径）：正本 app/lib/.server/llm/prompt-workstyle.ts
     * （前两句）与 stream-text.ts agent_mode_workflow 段（第三句），#595 施工经一手
     * 源码逐字核对。
     */
    @Test
    void given_authoritative_prompt_when_text_then_discipline_three_sentences_verbatim() {
        assertThat(KernelSystemPrompt.DISCIPLINE_FIRST)
                .isEqualTo("Treat tool output as the source of truth.");
        assertThat(KernelSystemPrompt.DISCIPLINE_SECOND).isEqualTo("Report failed commands as failed; "
                + "do not claim a file or command changed until its tool result confirms it.");
        assertThat(KernelSystemPrompt.DISCIPLINE_THIRD)
                .isEqualTo("Do not claim a task or check is complete without tool evidence.");
    }

    /**
     * 工具使用说明三件（DSH 原文只换工具名）：read 引导两件出自
     * packages/fs/tool-fs/src/read.ts:74 同一段原文（原句 "Use the read tool"，
     * 只换工具名为 read_file），write/edit 引导各出自 write.ts:68 / edit.ts:82。
     */
    @Test
    void given_authoritative_prompt_when_text_then_tool_usage_guidance_verbatim() {
        assertThat(KernelSystemPrompt.TOOL_USAGE_READ)
                .isEqualTo("Use the read_file tool — not shell commands like cat, sed, head, or tail — "
                        + "to inspect text files. Use offset and limit to read a line range or "
                        + "continue a large file.");
        assertThat(KernelSystemPrompt.TOOL_USAGE_WRITE)
                .isEqualTo("Read an existing file before overwriting it with write_file and prefer "
                        + "edit_file for targeted changes.");
        assertThat(KernelSystemPrompt.TOOL_USAGE_EDIT)
                .isEqualTo("Read a file before editing it unless you just created or edited it in "
                        + "this session.");
    }

    /** 语言钉：全程中文（#593 结构决议第六件）。 */
    @Test
    void given_authoritative_prompt_when_text_then_chinese_throughout_clause() {
        assertThat(KernelSystemPrompt.text()).endsWith("全程中文。");
    }
}
