package com.aieducenter.aiplatform.business.project.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 权限作答通道（#83 与问答作答分家）：run 执行环的权限确认挂起等待用户批准/
 * 拒绝的进程内会合点。轨道侧（{@link CoderRunAttempts}）在 run 挂起时以
 * {@link #await} 驻留（持有会话执行器 stripe——作答由本通道在请求线程直接
 * complete，不再经会话执行器排队，无自锁）；作答侧（REST 面）以 {@link #answer}
 * 落定决策并发射 {@code permission-resolved} 事件（确认卡转已批/已拒终态）。
 *
 * <p><b>会合键 = engineRef</b>（挂起事件的续跑批复锚，一次挂起唯一）。作答校验
 * runId 同挂起登记（防串卡）；未知/过期 engineRef 拒 PRJ_027——进程内态，平台
 * 重启即清（与轨道在途标记同口径：重启后确认卡作答指路刷新/重提，不代答不续跑）。
 * 问答挂起不经本通道（ask_user 挂起走问答作答通道，意见环），互不串扰。</p>
 */
@Service
@Slf4j
public class RunPermissionAppService {

    /** 一次权限确认挂起的等待句柄（runId 校验腿 + 决策 future）。 */
    private record Wait(String runId, CompletableFuture<Boolean> decision) {
    }

    /**
     * 权限确认超时（#112）：10 分钟未作答即默认拒绝（破坏性命令永不默认放行），
     * 等待纳入 run 墙钟口径。恒量（非配置——实施期定的落点，与生成超时同处
     * {@code app.generation} 前缀下的「超时」不同属：本项是权限作答的等待上限）。
     */
    static final Duration PERMISSION_TIMEOUT = Duration.ofMinutes(10);

    /** 超时轮询片（#112 时钟注入可测：以短片轮询重读注入时钟，避免真等 10 分钟）。 */
    private static final Duration PERMISSION_WAIT_POLL = Duration.ofMillis(250);

    /** 一次权限确认挂起的落定（#112 三态：批准 / 拒绝 / 超时——超时即默认拒绝）。 */
    enum Decision {
        /** 批准（续跑放行执行）。 */
        APPROVED,
        /** 拒绝（引擎写 DENIED 结果回模型，改道或自行收口）。 */
        DENIED,
        /** 超时（默认拒绝——轨道直接 run-failed 收口，不复用静默重试）。 */
        TIMED_OUT,
    }

    private final Map<String, Wait> waiting = new ConcurrentHashMap<>();

    private final AgentEventBridge eventBridge;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    public RunPermissionAppService(AgentEventBridge eventBridge,
            ProjectRepository projectRepository, Clock clock) {
        this.eventBridge = eventBridge;
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    /**
     * 作答（权限确认卡的批准/拒绝）：恰一次——先取走会合点（拿不到即过期）再发
     * {@code permission-resolved}（确认卡终态呈现源）并唤醒驻留轨道续跑；并发双答
     * （双击/双端竞态）只有先取走者落定，另一发 PRJ_027（重复作答守卫）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_027 确认不存在或已落定
     *                              （过期卡/平台重启丢账——刷新查看最新状态）
     */
    public void answer(Long projectId, String runId, String engineRef, boolean approved) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        Wait wait = waiting.get(engineRef);
        if (wait == null || !wait.runId().equals(runId)) {
            throw new ApplicationException(ProjectMessage.PERMISSION_ANSWER_STALE);
        }
        // 恰一次（并发双答守卫）：remove 是先取走者独占，后到者再查即不在
        if (!waiting.remove(engineRef, wait)) {
            throw new ApplicationException(ProjectMessage.PERMISSION_ANSWER_STALE);
        }
        // 先发落定事件再唤醒：确认卡先转终态，续跑过程事件（可能随即到达）在其后
        eventBridge.emitPermissionResolved(projectId, runId, engineRef, approved);
        wait.decision().complete(approved);
        log.info("[permission] 项目 {} run {} 权限确认作答：{}（engineRef={}）",
                projectId, runId, approved ? "批准" : "拒绝", engineRef);
    }

    /** 会合点已登记探针（同包测试缝，先例 AgentSessionExecutor.stripeIndex）：作答前等登记到位。 */
    boolean isAwaiting(String engineRef) {
        return waiting.containsKey(engineRef);
    }

    /**
     * 轨道侧驻留（权限确认挂起处调用）：阻塞至作答落定或超时，返回三态落定。
     * 超时（#112）＝时钟越过 {@link #PERMISSION_TIMEOUT} 上限——默认拒绝
     * （破坏性命令永不默认放行）、会合点随 finally 清（作答侧再查即过期 PRJ_027，
     * 确认卡定格不可再点）。中断视同拒绝（进程关闭即此路径——轨道随执行器终止，
     * 无需精细处理）；future 无其他异常面（complete 只在作答路径，无异常完成）。
     */
    Decision await(String engineRef, String runId) {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        // 截止先于会合点登记：isAwaiting 探针变真即保证 deadline 已定格（测试快进时钟
        // 不会再推迟截止——时钟注入可测的确定性前提）
        Instant deadline = clock.instant().plus(PERMISSION_TIMEOUT);
        waiting.put(engineRef, new Wait(runId, decision));
        try {
            while (clock.instant().isBefore(deadline)) {
                try {
                    Boolean approved = decision.get(PERMISSION_WAIT_POLL.toMillis(),
                            TimeUnit.MILLISECONDS);
                    return approved ? Decision.APPROVED : Decision.DENIED;
                }
                catch (TimeoutException poll) {
                    // 轮询片到、未落定：重读注入时钟——测试快进时钟越过 deadline 即判超时
                }
            }
            log.info("[permission] 权限确认等待超时（engineRef={}，run={} 未作答），默认拒绝",
                    engineRef, runId);
            return Decision.TIMED_OUT;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.DENIED;
        }
        catch (ExecutionException e) {
            // 不可达防御：future 只在作答路径正常 complete，无异常完成面
            log.warn("[permission] 权限等待异常完成（engineRef={}）：{}", engineRef, e.toString());
            return Decision.DENIED;
        }
        finally {
            waiting.remove(engineRef);
        }
    }
}
