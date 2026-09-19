package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.business.project.application.GenerationAppService.GenerationRun;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 外部仓库惯例真模型冒烟（#214；DEEPSEEK_API_KEY 未设或 docker daemon 不在整类
 * 跳过）：PRD 引用外部 GitHub 仓库（cartisan-boot）时，真 run 执行体按执行体协议
 * 第 9 条「起手先 clone 再读」把仓库浅克隆进工作区 external/ 资料目录——只读参考、
 * 不合并进交付系统。仅 SSE 发射边 mock 收口观测。
 *
 * <p>机械判据一条：run 收口后 external/ 下存在 clone 痕迹（{@code .git} 目录）。
 * external/ 不进交付源码包/文件树/版本正本的「单一事实」是代码层保证
 * （{@code WorkspaceLayout.NON_DELIVERABLE_DIRS}，WorkspaceLayoutTest 已钉），本
 * 冒烟只验活体 clone 行为（协议软约束是否被模型遵守）——不遵守即红，暴露「写协议
 * 不够、需代码强约束」。</p>
 *
 * <p>诊断面（区分「模型没 clone」与「环境不支持」）：起跑前探测容器 git 版本与
 * ls-remote 出网能力；断言失败时附会话状态里模型执行过的 git/clone 痕迹 + 事件序
 * + external/ 目录实况。</p>
 */
@IntegrationTest
class ExternalRepoConventionSmokeTest {

    /** 完整生成 run 的期限（对齐 GenerationEarlyServiceSmokeTest 口径）。 */
    private static final Duration GEN_DEADLINE = Duration.ofSeconds(1800);

    /** clone 探活节流。 */
    private static final long PROBE_INTERVAL_MS = 2000;

    /** 外部仓库（cartisan-boot 场景，与 #212/#214 票面同源）。 */
    private static final String EXTERNAL_REPO_URL = "https://github.com/ZhangColin/cartisan-boot";

    /**
     * 冒烟用 PRD：明确引用外部仓库 GitHub 地址，并要求官网文案基于仓库真实
     * 内容撰写（给执行体 clone 理由，不凭空编造框架能力）。
     */
    private static final String PRD_CONTENT = """
            # 框架官网 PRD

            ## 需求背景
            为一个 Java 后端框架做官网展示页。

            ## 参考来源
            该框架的源码仓库在 GitHub：https://github.com/ZhangColin/cartisan-boot
            官网的定位、能力描述、特性列表都应基于该仓库 README 与文档的真实内容撰写，
            不要凭空编造框架能力。

            ## 目标用户
            开发者。

            ## 核心场景
            访客打开首页看到框架名称、一句话定位、核心特性列表、快速开始片段。

            ## 范围边界
            仅一个首页，中文界面，静态内容为主。
            """;

    @Autowired
    private GenerationAppService appService;

    @Autowired
    private ProjectKnowledgeAppService knowledgeAppService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    /** 事件发射边收口（单端点单流）：真实链路无订阅者，发射本身是观测缝。 */
    @MockitoBean
    private EventsAppService eventsAppService;

    private final ConcurrentLinkedQueue<Frame> frames = new ConcurrentLinkedQueue<>();

    private String workspaceId;
    private Long projectId;
    private String coderSessionId;

    private record Frame(String type, Map<String, Object> payload) {
    }

    @BeforeAll
    static void requireEnvironment() {
        Assumptions.assumeTrue(
                System.getenv("DEEPSEEK_API_KEY") != null
                        && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过外部仓库惯例真模型冒烟");
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        if (coderSessionId != null) {
            jdbcTemplate.update("DELETE FROM met_usage_events WHERE session_id LIKE ?", coderSessionId + "%");
            jdbcTemplate.update("DELETE FROM cat_agent_state WHERE session_id LIKE ?", coderSessionId + "%");
        }
        if (projectId != null) {
            knowledgeAppService.purgeByProject(projectId);
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
        if (workspaceId != null) {
            try {
                workspaceLifecycleAppService.destroy(workspaceId);
            } catch (RuntimeException e) {
                // 物理清理尽力而为（记录已随 destroy 删；Docker 残留可重试销毁）
            }
        }
    }

    @Test
    @Timeout(2400)
    void given_prd_referencing_external_repo_when_generate_then_repo_cloned_into_external() {
        // 0) 真实 dev 容器 + 工作区记录 + 项目落库；事件捕获就位；PRD 预置（写文件 +
        //    置已产出——等价 savePrd，不走访谈链路）
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        doAnswer(invocation -> {
            frames.add(new Frame(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(eventsAppService).publishAgentEvent(any(), any());
        Project project = projectRepository.save(Project.create("外部仓库冒烟", null,
                Long.parseLong(workspaceId), null));
        projectId = project.getId();
        coderSessionId = CoderRunAttempts.SESSION_PREFIX + projectId;
        placePrd();
        projectRepository.findById(projectId).ifPresent(p -> {
            p.markPrdProduced();
            projectRepository.save(p);
        });

        // 1) 网络预检：容器能否 clone github（本地直连 github 常卡在 TCP 连接——
        //    中国大陆网络特性）。不能则整方法 Assumptions 跳过，避免把「本地网络
        //    限制」误判为「协议软约束无效」；好网络环境才真跑 run 验活体。
        //    判据用 exitCode（.git 目录在 git init 阶段即创建，clone 卡住时也会
        //    残留，不可作成功判据；exitCode==0 才是 fetch 完成）。
        String gitVersion = exec("git --version 2>&1").stdout().trim();
        exec("rm -rf /tmp/clone-probe");
        ExecResultResponse probe = exec("timeout 30 git clone --depth 1 "
                + EXTERNAL_REPO_URL + " /tmp/clone-probe 2>&1");
        boolean cloneOk = probe.exitCode() == 0;
        String probeTail = probe.stdout().trim();
        if (probeTail.length() > 300) {
            probeTail = probeTail.substring(0, 300);
        }
        Assumptions.assumeTrue(cloneOk,
                "容器无法 clone github（本地网络限制，非功能缺陷），跳过活体。git："
                        + gitVersion + "；clone 探测：" + probeTail);
        System.out.println("[external-repo] cloneOk=" + cloneOk + "，clone 探测：" + probeTail);

        // 2) 起跑生成（异步轨道），等 run 收口（RUN_FINISH）
        GenerationRun run = appService.startGeneration(projectId);
        assertThat(run.runId()).isNotEmpty();
        awaitUntil(GEN_DEADLINE, () -> frames.stream()
                .anyMatch(f -> AgentEventTypes.RUN_FINISH.equals(f.type())));

        // 3) 活体判据：external/ 下存在 clone 痕迹（.git 目录）——协议第 9 条被遵守。
        //    失败时附诊断：会话 git/clone 痕迹（区分「模型没 clone」与「clone 失败」）
        //    + external 实况 + 事件序
        ExecResultResponse gitDirs = exec("find /workspace/external -maxdepth 3 -name '.git' -type d 2>/dev/null");
        ExecResultResponse externalLs = exec("ls -la /workspace/external/ 2>/dev/null");
        System.out.println("[external-repo] external/ 实况：\n" + externalLs.stdout());
        assertThat(gitDirs.stdout().trim())
                .as("[外部仓库 clone] external/ 应有 .git 痕迹。\n"
                        + "会话 git/clone 痕迹：%s\n"
                        + "external/ 实况：%s\n"
                        + "事件序：%s",
                        sessionGitTraces(), externalLs.stdout(), frameDigest())
                .isNotBlank();
    }

    // ---------- 编排件 ----------

    /** PRD 预置（heredoc 单引号定界不展开，正文无定界符冲突）。 */
    private void placePrd() {
        exec("mkdir -p /workspace/docs && cat > /workspace/docs/PRD.md <<'SMOKE_PRD_EOF'\n"
                + PRD_CONTENT + "SMOKE_PRD_EOF");
    }

    private ExecResultResponse exec(String command) {
        return workspaceLifecycleAppService.exec(workspaceId, new WorkspaceExecCommand(command));
    }

    /** 会话状态里模型执行过的 git/clone/cartisan 痕迹（区分「模型没 clone」与「clone 失败」）。 */
    private String sessionGitTraces() {
        List<String> states = jdbcTemplate.queryForList(
                "SELECT state_data FROM cat_agent_state WHERE session_id LIKE ?",
                String.class, coderSessionId + "%");
        StringBuilder sb = new StringBuilder();
        for (String state : states) {
            if (state == null) {
                continue;
            }
            for (String line : state.split("\n")) {
                String lower = line.toLowerCase();
                if (lower.contains("git") || lower.contains("clone") || lower.contains("cartisan")) {
                    String trimmed = line.trim();
                    if (trimmed.length() > 160) {
                        trimmed = trimmed.substring(0, 160) + "…";
                    }
                    sb.append(trimmed).append(" | ");
                }
            }
        }
        return sb.isEmpty() ? "(无)" : sb.toString();
    }

    /** 有界轮询直到条件成立（null / false / 空串 = 未达成继续等；超时红）。 */
    private <T> T awaitUntil(Duration deadline, Supplier<T> condition) {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            T value = condition.get();
            if (satisfied(value)) {
                return value;
            }
            sleepQuietly();
        }
        throw new AssertionError("等待超时（" + deadline + "），事件序：" + frameDigest());
    }

    private static boolean satisfied(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        return true;
    }

    /** 事件序诊断（超时/失败时附事件类型序列，长度封顶防刷屏）。 */
    private String frameDigest() {
        return frames.stream().map(Frame::type).limit(200).toList().toString();
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(PROBE_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断", e);
        }
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
