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
 * 迭代编排（#26 迭代环① → #43 链必达收口 → #46 结束工具收口）：意见判定内化
 * BA，派发权归平台——BA 回合收口（无追问挂起）由 {@link BaInterviewAppService}
 * 观测并自动调用本服务派修正 run，不依赖模型自觉调用派发工具（startFixRun 已撤）。
 * 修正 run 与生成同机制（复用 {@code coder-{projectId}} 会话与同工作区——编码智能体
 * 带着建系统的全部上下文继续干活；知识命中前置注入 / 失败自动重试 / 直播 / 计量全走
 * 共用尝试环 {@link CoderRunAttempts}）。
 *
 * <p><b>收口以 finish_fix 工具事实为准</b>（#46）：编码智能体判定本轮要不要动系统
 * ——动则修改后报 changed=true+改了什么，不动（纯文档性修订、系统现状已满足等）也
 * 必报 changed=false+原因，判定从工具调用事实观测（{@link FinishFixFacts}），不解析
 * 自由文本。未调用即 run 未正常收口，按既有重试/终态机制处理；changed=false 经
 * {@code fix-unchanged} 帧如实呈现「未动系统+原因」——用户能区分「不需要改」与
 * 「链路断了」。</p>
 *
 * <p><b>排队合并</b>：修正 run 进行中再派的任务排队（BA 回复用户「已排入下一轮」）；
 * 当前 run 收口后（无论成败）排空队列、合并为一场修正 run 续派——用户在 run 中
 * 连提多条意见不会被丢弃，也不会每条各烧一场 run。轨道状态（在途标记 + 队列）是
 * 进程内事实，重启即清（run 无表口径）：重启后进行中 run 标失败，用户重新提意见
 * 即重新起轨。</p>
 *
 * <p><b>超限终态恢复出口</b>（#48）：修正 run 重试超限转终态时，终态那场的交接
 * 任务记入 {@link #terminallyFailedTasks}（成功收工即清），恢复出口
 * {@link #restartFixRun} 据此重派——交接物沿用、新 runId 随响应回（与新 run 的
 * 链路锚）。仅终态可达：修正在途（进行中/排队中）拒绝 PRJ_025、无终态账（未派过/
 * 已成功/重启丢账）拒绝 PRJ_026——正常流程全自动，不出现任何手动触发，故障态留
 * 最后一条生路。</p>
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
                    + "直至修正落实、服务在 8081 端口可访问，最后调用 finish_fix 工具收口"
                    + "（动了系统传 changed=true 并说明改了什么；判定无需改动也必须调用，"
                    + "传 changed=false 并说明原因）。";

    private final ProjectRepository projectRepository;
    private final AgentSessionExecutor sessionExecutor;
    private final CoderRunAttempts coderRunAttempts;
    private final FinishFixFacts finishFacts;
    private final AgentStreamBridge streamBridge;

    /** 修正在途项目集（含已提交未起跑——排队中）：起跑/排队的分岔事实。 */
    private final Set<Long> fixesInFlight = ConcurrentHashMap.newKeySet();
    /** run 进行中排队的修正任务（projectId → 待合并任务清单）。 */
    private final Map<Long, List<String>> queuedFixRuns = new ConcurrentHashMap<>();
    /**
     * 超限终态那场的交接任务（projectId → 终态任务清单）：恢复出口的重派依据，
     * 成功收工即清。进程内态与轨道状态同口径——重启丢账，恢复出口 409 指路重提
     * 意见（既有兜底不变）。
     */
    private final Map<Long, List<String>> terminallyFailedTasks = new ConcurrentHashMap<>();

    public IterationAppService(ProjectRepository projectRepository,
            AgentSessionExecutor sessionExecutor, CoderRunAttempts coderRunAttempts,
            FinishFixFacts finishFacts, AgentStreamBridge streamBridge) {
        this.projectRepository = projectRepository;
        this.sessionExecutor = sessionExecutor;
        this.coderRunAttempts = coderRunAttempts;
        this.finishFacts = finishFacts;
        this.streamBridge = streamBridge;
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
        Project project = requireFixableProject(projectId);
        return dispatch(project, List.of(task.strip()));
    }

    /**
     * 修正 run 超限终态的恢复出口（#48）：重派终态那场的交接任务（交接物沿用、
     * 同 coder 会话续上下文），新 runId 随响应回——与新 run 的链路锚（同
     * {@code /generate} 口径），重派事实落日志可追溯。仅终态可达：修正在途（进行
     * 中/排队中）PRJ_025；无终态账（未派过修正/已成功收工/重启丢账）PRJ_026；
     * 归档/未生成守卫同 {@link #startFixRun}。
     */
    public FixDispatch restartFixRun(Long projectId) {
        Project project = requireFixableProject(projectId);
        List<String> tasks;
        String firstRunId;
        synchronized (this) {
            if (fixesInFlight.contains(projectId)) {
                throw new ApplicationException(ProjectMessage.FIX_RESTART_IN_FLIGHT);
            }
            tasks = terminallyFailedTasks.remove(projectId);
            if (tasks == null) {
                throw new ApplicationException(ProjectMessage.FIX_RESTART_UNAVAILABLE);
            }
            fixesInFlight.add(projectId);
            firstRunId = AgentStreamAppService.newRunId();
        }
        log.info("[fix] 项目 {} 恢复出口重派修正 run（runId={}，交接任务 {} 条，源自超限终态）",
                projectId, firstRunId, tasks.size());
        submitFixTrack(project, firstRunId, tasks);
        return new FixDispatch(firstRunId, false);
    }

    /** 修正派发结果（起跑 runId / 是否排入下一轮）。 */
    public record FixDispatch(String runId, boolean queued) {
    }

    // ---------- 内部 ----------

    /** 迭代态守卫：未归档 / 已生成（修正 run 的对象是已生成的系统）。 */
    private Project requireFixableProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        if (project.getGeneratedAt() == null) {
            throw new ApplicationException(ProjectMessage.FIX_RUN_NOT_GENERATED);
        }
        return project;
    }

    /** 迭代态守卫过后的派发：空闲即起跑，在途即排队。 */
    private FixDispatch dispatch(Project project, List<String> tasks) {
        Long projectId = project.getId();
        String firstRunId;
        synchronized (this) {
            if (!fixesInFlight.add(projectId)) {
                queuedFixRuns.computeIfAbsent(projectId, key -> new ArrayList<>()).addAll(tasks);
                return new FixDispatch(null, true);
            }
            firstRunId = AgentStreamAppService.newRunId();
        }
        submitFixTrack(project, firstRunId, tasks);
        return new FixDispatch(firstRunId, false);
    }

    /**
     * 修正轨道（异步轨道内）：当前任务跑完（含重试超限）后排空队列——有排队任务
     * 即合并为一场修正 run 续跑，队列空即收工。收工时按末场成败结算终态账（#48）：
     * 超限终态即记下该场交接任务（恢复出口 {@link #restartFixRun} 的重派依据），
     * 成功即清账（正常态无恢复面）。
     *
     * <p><b>在途标记的释放在两处，各自唯一负责一种收场</b>：正常收工在排空块
     * 内——「查队列空 + 清标记」同一临界区，起跑侧的 add 也在同锁内分岔，任务
     * 要么赶在本轨道收工前并入合并、要么在收工后自起新轨，不滞留；异常收场
     * （轨道任务本身炸，会话执行器吞掉记日志）在 finally——此时标记自 add 起一
     * 直属于本轨道，直接清不会误伤（{@code released} 防的正是「正常收工后继任
     * 轨道已 add、旧 finally 再盲清一次」的双释放）。</p>
     */
    private void runFixTrack(Project project, String firstRunId, List<String> firstTasks) {
        Long projectId = project.getId();
        // 本轨收口判定从零起算：上一轨/生成 run 的事实残留不进本轨（#46）
        finishFacts.clear(Long.toString(project.getWorkspaceId()));
        boolean released = false;
        try {
            List<String> tasks = firstTasks;
            String runId = firstRunId;
            while (true) {
                boolean succeeded = coderRunAttempts.run(project, runId,
                        new CoderRunAttempts.Prompts(fixRunPrompt(tasks), FIX_RETRY_RUN_PROMPT),
                        attemptRunId -> closeFixRun(project, attemptRunId), "fix");
                List<String> queued;
                synchronized (this) {
                    List<String> pending = queuedFixRuns.remove(projectId);
                    queued = pending != null ? pending : List.of();
                    if (queued.isEmpty()) {
                        fixesInFlight.remove(projectId);
                        released = true;
                        // 终态账与释放同临界区结算：恢复出口（同锁内「查在途+取账+占位」）
                        // 要么见释放前已落的账、要么在收工后自起新轨——不出现「已释放
                        // 无账」的假 PRJ_026 窗口，也不被先收工的旧轨覆盖
                        if (succeeded) {
                            terminallyFailedTasks.remove(projectId);
                        }
                        else {
                            terminallyFailedTasks.put(projectId, List.copyOf(tasks));
                        }
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

    /**
     * 修正收口（#46）：以 finish_fix 工具事实为准——无事实 = run 未正常收口，抛出
     * 即该次尝试失败（走共用尝试环的重试/终态，与生成 8081 核验同口径）；changed=false
     * 发 {@code fix-unchanged} 帧（「未动系统+原因」如实呈现），changed=true 现有
     * 收口行为不动（run-finish 已发，预览刷新/直播收起/状态位为前端对 run-finish 的
     * 反应）。
     */
    private void closeFixRun(Project project, String attemptRunId) {
        FinishFixFacts.Fact fact = finishFacts.consume(Long.toString(project.getWorkspaceId()));
        if (fact == null) {
            // 未正常收口不是静默失败：run-finish 已发（引擎自认成功），此处补 error 帧
            // 如实表达（帧序 run-finish → error → run-retrying → …；超限末次 error 即
            // 终态）——否则「链路断了」在用户侧呈现为正常收口，恰是要消除的困惑
            streamBridge.emitError(project.getId(), attemptRunId,
                    "修正未正常收口：编码智能体未报告收口判定（finish_fix 未调用）");
            throw new IllegalStateException("修正 run 未以 finish_fix 结束工具收口");
        }
        if (!fact.changed()) {
            log.info("[fix] 项目 {} 修正收口：系统未动（{}）", project.getId(), fact.text());
            streamBridge.emitFixUnchanged(project.getId(), attemptRunId, fact.text());
        }
    }

    /** 修正任务 prompt：意见转化来的任务清单 + 收口判据复述（8081 常驻 + 结束工具必调）。 */
    static String fixRunPrompt(List<String> tasks) {
        StringBuilder prompt = new StringBuilder(
                "系统修正：系统已生成并可操作，用户提出了如下修正意见，请在现有工作区内"
                        + "完成修正（系统的其余部分保持可用）：");
        for (int index = 0; index < tasks.size(); index++) {
            prompt.append("\n").append(index + 1).append(". ").append(tasks.get(index));
        }
        prompt.append("\n完成后确认 8081 端口服务在跑、curl 可访问后再收尾。")
                .append("\n收尾必须调用 finish_fix 工具：动了系统传 changed=true 并说明改了什么；")
                .append("判定无需改动系统也必须调用，传 changed=false 并说明原因——")
                .append("不调用即本轮修正未收口。");
        return prompt.toString();
    }

    private void submitFixTrack(Project project, String firstRunId, List<String> firstTasks) {
        sessionExecutor.submit(CoderRunAttempts.SESSION_PREFIX + project.getId(),
                () -> runFixTrack(project, firstRunId, firstTasks));
    }
}
