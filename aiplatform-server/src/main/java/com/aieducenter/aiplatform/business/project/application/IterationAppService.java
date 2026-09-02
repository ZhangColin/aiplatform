package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 迭代编排（#26 迭代环① → #43 链必达收口）：意见判定内化 BA，派发权归平台——
 * BA 回合收口（无追问挂起）由 {@link BaInterviewAppService} 观测并自动调用本服务
 * 派修正 run，不依赖模型自觉调用派发工具（startFixRun 已撤）。修正 run 与生成同
 * 机制（复用 {@code coder-{projectId}} 会话与同工作区——编码智能体带着建系统的
 * 全部上下文继续干活；知识命中前置注入 / 失败自动重试 / 直播 / 计量全走共用尝试环
 * {@link CoderRunAttempts}）。
 *
 * <p><b>排队合并</b>：修正 run 进行中再派的任务排队（BA 回复用户「已排入下一轮」）；
 * 当前 run 收口后（无论成败）排空队列、合并为一场修正 run 续派——用户在 run 中
 * 连提多条意见不会被丢弃，也不会每条各烧一场 run。轨道状态（在途标记 + 队列）是
 * 进程内事实，重启即清（run 无表口径）：重启后进行中 run 标失败，用户重新提意见
 * 即重新起轨。</p>
 *
 * <p><b>无次数上限</b>：迭代轮数不设界，意见发散时的收敛催促归 BA 协议（角色卡），
 * 平台不设门。</p>
 */
@Service
@Slf4j
public class IterationAppService {

    /** 重试续作 prompt：修正轨的重试口径（同工作区不丢数据，续同 coder 会话）。 */
    static final String FIX_RETRY_RUN_PROMPT =
            "上一次修正尝试中断了，工作区内已完成的成果仍然有效。请先检查现状"
                    + "（代码、依赖、数据、8081 端口服务是否在跑），从中断处继续完成本轮修正，"
                    + "直至修正落实、服务在 8081 端口可访问。";

    private final ProjectRepository projectRepository;
    private final AgentSessionExecutor sessionExecutor;
    private final CoderRunAttempts coderRunAttempts;

    /** 修正在途项目集（含已提交未起跑——排队中）：起跑/排队的分岔事实。 */
    private final Set<Long> fixesInFlight = ConcurrentHashMap.newKeySet();
    /** run 进行中排队的修正任务（projectId → 待合并任务清单）。 */
    private final Map<Long, List<String>> queuedFixRuns = new ConcurrentHashMap<>();

    public IterationAppService(ProjectRepository projectRepository,
            AgentSessionExecutor sessionExecutor, CoderRunAttempts coderRunAttempts) {
        this.projectRepository = projectRepository;
        this.sessionExecutor = sessionExecutor;
        this.coderRunAttempts = coderRunAttempts;
    }

    /**
     * 派修正任务（BA 回合收口的平台自动派发入口，#43 链必达）：修正 run 空闲即
     * 起跑（runId 随派发生成，过程帧经 SSE）；在途则排入队列、当前 run 收口后
     * 合并续派。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              PRJ_019 系统从未生成（迭代在首次生成完成后
     *                              才开始——BA 收口侧对未生成项目静默止于 BA，
     *                              此处守卫兜其余调用面）
     */
    public FixDispatch startFixRun(Long projectId, String task) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        return dispatch(project, task);
    }

    /** 修正派发结果（起跑 runId / 是否排入下一轮）。 */
    public record FixDispatch(String runId, boolean queued) {
    }

    // ---------- 内部 ----------

    /** 迭代态守卫 + 派发：未归档 / 已生成（修正 run 的对象是已生成的系统）。 */
    private FixDispatch dispatch(Project project, String task) {
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        if (project.getGeneratedAt() == null) {
            throw new ApplicationException(ProjectMessage.FIX_RUN_NOT_GENERATED);
        }
        Long projectId = project.getId();
        String trimmed = task.strip();

        String firstRunId;
        synchronized (this) {
            if (!fixesInFlight.add(projectId)) {
                queuedFixRuns.computeIfAbsent(projectId, key -> new ArrayList<>()).add(trimmed);
                return new FixDispatch(null, true);
            }
            firstRunId = AgentStreamAppService.newRunId();
        }
        submitFixTrack(project, firstRunId, trimmed);
        return new FixDispatch(firstRunId, false);
    }

    /**
     * 修正轨道（异步轨道内）：当前任务跑完（含重试超限）后排空队列——有排队任务
     * 即合并为一场修正 run 续跑，队列空即收工。
     *
     * <p><b>在途标记的释放在两处，各自唯一负责一种收场</b>：正常收工在排空块
     * 内——「查队列空 + 清标记」同一临界区，起跑侧的 add 也在同锁内分岔，任务
     * 要么赶在本轨道收工前并入合并、要么在收工后自起新轨，不滞留；异常收场
     * （轨道任务本身炸，会话执行器吞掉记日志）在 finally——此时标记自 add 起一
     * 直属于本轨道，直接清不会误伤（{@code released} 防的正是「正常收工后继任
     * 轨道已 add、旧 finally 再盲清一次」的双释放）。</p>
     */
    private void runFixTrack(Project project, String firstRunId, String firstTask) {
        Long projectId = project.getId();
        boolean released = false;
        try {
            List<String> tasks = List.of(firstTask);
            String runId = firstRunId;
            while (true) {
                coderRunAttempts.run(project, runId,
                        new CoderRunAttempts.Prompts(fixRunPrompt(tasks), FIX_RETRY_RUN_PROMPT),
                        () -> { }, "fix");
                List<String> queued;
                synchronized (this) {
                    List<String> pending = queuedFixRuns.remove(projectId);
                    queued = pending != null ? pending : List.of();
                    if (queued.isEmpty()) {
                        fixesInFlight.remove(projectId);
                        released = true;
                    }
                }
                if (queued.isEmpty()) {
                    return;
                }
                log.info("[fix] 项目 {} 修正轨道续派（合并 {} 条排队任务）",
                        projectId, queued.size());
                tasks = queued;
                runId = AgentStreamAppService.newRunId();
            }
        }
        finally {
            if (!released) {
                synchronized (this) {
                    fixesInFlight.remove(projectId);
                }
            }
        }
    }

    /** 修正任务 prompt：意见转化来的任务清单 + 收口判据复述（8081 常驻）。 */
    static String fixRunPrompt(List<String> tasks) {
        StringBuilder prompt = new StringBuilder(
                "系统修正：系统已生成并可操作，用户提出了如下修正意见，请在现有工作区内"
                        + "完成修正（系统的其余部分保持可用）：");
        for (int index = 0; index < tasks.size(); index++) {
            prompt.append("\n").append(index + 1).append(". ").append(tasks.get(index));
        }
        prompt.append("\n完成后确认 8081 端口服务在跑、curl 可访问后再收尾。");
        return prompt.toString();
    }

    private void submitFixTrack(Project project, String firstRunId, String firstTask) {
        sessionExecutor.submit(CoderRunAttempts.SESSION_PREFIX + project.getId(),
                () -> runFixTrack(project, firstRunId, firstTask));
    }
}
