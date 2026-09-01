package com.aieducenter.aiplatform.business.project.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 生成编排（#22 片2-1）：「开始做系统」→ 编码智能体 run。编码智能体与 BA 同构
 * （AgentScope HarnessAgent 经 {@link AgentscopeAgentClient} 直调——编排缝极薄），
 * 仅资产与工具不同：会话 {@code coder-{projectId}}、角色卡 = 平台技术约定 +
 * 实现协议（{@link RolePreset#CODER}）、无业务工具（编码工具由 harness 内核自带）。
 *
 * <p><b>纯动作无门</b>：待定项未清也可发起（守卫只有项目存在 / 未归档 /
 * 未生成过）；重复触发（已生成或生成在途）拒绝 PRJ_017。</p>
 *
 * <p><b>编码 run 开直播</b>（#23 生成环②）：命令带 {@code live}——过程帧外并产
 * 直播帧（智能体自述逐段 + 工具动作人话行 + 步骤），前端直播侧栏消费；BA 对话
 * 不开（对话不流式不留痕）。</p>
 *
 * <p><b>知识命中前置注入</b>（#24 生成环③）：下发前以首试任务 prompt 检索知识库
 * （query 截 2000 字、topK=5），命中块拼在任务 prompt <b>前</b>（知识是背景非
 * 指令）；检索失败降级空注入、run 照旧下发。一次下发一次注入——重试续同 coder
 * 会话（注入块已在会话历史），不重检索不重注入。</p>
 *
 * <p><b>工作区布局资产就位</b>：下发前把平台约定写入工作区 AGENTS.md
 * （幂等覆写，内容平台所有）——编码智能体经 harness 工作区上下文自读；
 * PRD（docs/PRD.md）由 BA 先前写出，同样是智能体自读，平台不搬运。</p>
 *
 * <p><b>失败自动重试有限次</b>（同工作区不丢数据——重试续在同一 coder 会话，
 * 已落盘成果保留）：尝试失败先发 {@code run-retrying} 帧（话术「遇到问题，
 * 正在重试」）再下发下一尝试（新 runId）；超限转终态失败，由用户重新发起兜底
 * （generated_at 不落位 = 按钮口径仍在）。run 成功收口才落
 * {@code generated_at}（首次生成时点，单向置位——「确认下单」可见性口径）。</p>
 */
@Service
@Slf4j
public class GenerationAppService {

    /** 生成任务 prompt（首试下发）：读 PRD 自主实现 + 收口判据（8081 可访问）。 */
    static final String GENERATE_RUN_PROMPT =
            "开始做系统：请完整阅读工作区 docs/PRD.md（需求正本，「功能清单」是实现的"
                    + "直接依据），在工作区内把这套系统真正实现出来——带数据库、预置可演示的"
                    + "初始数据，并按平台约定把应用服务跑在 8081 端口（后台常驻），"
                    + "用 curl 确认可访问后收尾。";

    /** 重试续作 prompt：同工作区不丢数据——已落盘成果保留，从中断处继续。 */
    static final String RETRY_RUN_PROMPT =
            "上一次尝试中断了，工作区内已完成的成果仍然有效。请先检查现状"
                    + "（代码、依赖、数据、8081 端口服务是否在跑），从中断处继续把系统做完，"
                    + "直至 docs/PRD.md 功能清单实现、服务在 8081 端口可访问。";

    /**
     * AGENTS.md 平台约定正文（工作区布局资产，#22 就位）：工作区物理约定的正本
     * ——harness 工作区上下文自动注入编码智能体，也是后续迭代 run / 引擎资产的
     * 演进载体。内容平台所有（无用户可控片段），幂等覆写。
     */
    static final String AGENTS_MD_CONTENT = """
            # 工作区平台约定

            本工作区是平台的单容器沙箱：Node.js 运行时与 PostgreSQL、Redis 同容器，/workspace 是唯一持久卷——容器可随时销毁重建，卷内数据不丢、卷外皆可弃。

            - 应用代码放工作区根目录；docs/ 放文档（docs/PRD.md 是需求正本，只读）。
            - 数据库连接串读 .env 的 DATABASE_URL（平台生成、唯一注入通道，勿手改）；需要缓存用 .env 的 REDIS_URL。
            - 应用自用的文件数据必须落 data/ 目录（卷内才持久）。
            - 应用服务必须监听 0.0.0.0:8081——平台预览从该端口取流量。
            - 依赖安装在本工作区（node_modules 属可重建物，打包交付时排除）。
            - .platform/ 是平台产物目录，不要写入；本文件（AGENTS.md）由平台维护，不要修改。
            - 每轮生成或修正收尾前，确认 8081 端口的服务在后台常驻运行——预览呈现的是活的服务。
            """;

    /**
     * 收口判据核验探针（#35）：converse 无异常不构成成功——编码智能体可能道歉式
     * 放弃或被 maxIters 掐断而照常返回。8081 可达才算收口（与 CODER systemPrompt
     * 的收口判据对齐）。{@code -s} 静默、{@code -o /dev/null} 弃正文，exitCode 0 =
     * 端口有 HTTP 应答（连接拒绝即非 0）。
     */
    static final String CLOSING_PROBE = "curl -s -o /dev/null http://localhost:8081";

    private final ProjectRepository projectRepository;
    private final AgentSessionExecutor sessionExecutor;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final CoderRunAttempts coderRunAttempts;

    /** 生成在途项目集（含已提交未起跑——排队中）：重复触发守卫的进程内事实。 */
    private final Set<Long> generationsInFlight = ConcurrentHashMap.newKeySet();

    public GenerationAppService(ProjectRepository projectRepository,
            AgentSessionExecutor sessionExecutor,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            CoderRunAttempts coderRunAttempts) {
        this.projectRepository = projectRepository;
        this.sessionExecutor = sessionExecutor;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.coderRunAttempts = coderRunAttempts;
    }

    /**
     * 开始做系统（触发首次生成）：守卫 → AGENTS.md 资产就位 → 异步提交编码 run
     * （首试 runId 随响应回，过程帧经 SSE；失败重试与超限兜底在异步轨道内）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              PRJ_017 已生成或生成在途（重复发起）；
     *                              PRJ_018 PRD 从未产出（编码 run 的任务就是读
     *                              PRD 实现，无 PRD 起跑只会空烧重试）
     */
    public GenerationRun startGeneration(Long projectId) {
        Project project = requireGeneratableProject(projectId);
        if (!generationsInFlight.add(projectId)) {
            throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
        }
        try {
            placeConventionsAsset(project);
        } catch (RuntimeException e) {
            generationsInFlight.remove(projectId);
            throw e;
        }

        String firstRunId = AgentStreamAppService.newRunId();
        sessionExecutor.submit(CoderRunAttempts.SESSION_PREFIX + projectId, () -> {
            try {
                runAttemptsWithRetry(project, firstRunId);
            }
            finally {
                generationsInFlight.remove(projectId);
            }
        });
        return new GenerationRun(firstRunId);
    }

    /** 一场生成（首试）的运行标识（前端挂智能体流 ?runId= 的锚；重试换新 runId 经帧到达）。 */
    public record GenerationRun(String runId) {
    }

    // ---------- 内部 ----------

    /**
     * 尝试环（异步轨道内，共用件 {@link CoderRunAttempts}）：生成首试 prompt =
     * GENERATE_RUN_PROMPT、重试换轨 RETRY_RUN_PROMPT；成功收口（converse 无异常
     * + 8081 可达，#35 核验在 {@link #markGeneratedIfReachable}）即 markGenerated
     * 收场（首次生成时点单向落位）。
     */
    private void runAttemptsWithRetry(Project project, String firstRunId) {
        coderRunAttempts.run(project, firstRunId,
                new CoderRunAttempts.Prompts(GENERATE_RUN_PROMPT, RETRY_RUN_PROMPT),
                () -> markGeneratedIfReachable(project), "generate");
    }

    /**
     * 生成成功收场（#35）：先核验收口判据（8081 可达）再落 generated_at——converse
     * 无异常不构成成功（智能体可能道歉式放弃 / 被 maxIters 掐断）。核验不过抛异常，
     * 被共用件尝试环当作该次尝试失败（走重试/终态路径，generated_at 不落位 = 项目
     * 不被空壳锁死、重新发起出口仍在）。
     */
    private void markGeneratedIfReachable(Project project) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(CLOSING_PROBE));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("8081 不可达（curl 退出码 " + result.exitCode() + "）");
        }
        markGenerated(project.getId());
    }

    /** 首次生成时点落位：重载置位（单向），失败记日志不炸异步轨道（run 已成功）。 */
    private void markGenerated(Long projectId) {
        try {
            projectRepository.findById(projectId).ifPresentOrElse(project -> {
                project.markGenerated();
                projectRepository.save(project);
            }, () -> log.warn("[generate] 项目 {} 生成成功但记录已不存在（generated_at 无处落）",
                    projectId));
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} generated_at 落位失败：{}", projectId, e.toString());
        }
    }

    /**
     * 工作区布局资产就位：AGENTS.md 平台约定写入（幂等覆写）。heredoc 单引号定界
     * 不做展开，正文为平台常量（无用户可控片段、无单引号）；退出码非 0 即写入
     * 失败（环境故障口径如实上抛，生成不起跑）。
     */
    private void placeConventionsAsset(Project project) {
        String command = "cat > '" + WorkspaceLayout.absolute(WorkspaceLayout.AGENTS_MD)
                + "' <<'PLATFORM_EOF'\n" + AGENTS_MD_CONTENT + "\nPLATFORM_EOF";
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(command));
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "AGENTS.md 平台约定写入失败: " + result.stderr());
        }
    }

    /**
     * 可生成守卫：存在 / 未归档 / 未生成过（已生成项目的调整走指令区意见，迭代环）/
     * PRD 已产出（「无门」指待定项不设门；编码 run 的任务就是读 PRD，无 PRD 起跑
     * 只会空烧重试——守的是动作成立的前置事实，不是流程门）。
     */
    private Project requireGeneratableProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        if (project.getGeneratedAt() != null) {
            throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
        }
        if (project.getPrdProducedAt() == null) {
            throw new ApplicationException(ProjectMessage.GENERATION_PRD_NOT_PRODUCED);
        }
        return project;
    }
}
