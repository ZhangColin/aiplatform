package com.semgravio.aiplatform.business.project.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentShutdownGate;
import com.semgravio.aiplatform.base.agentscope.AgentTurnFailure;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.AgentscopeHarnessAgentFactory;
import com.semgravio.aiplatform.base.agentscope.FileChange;
import com.semgravio.aiplatform.base.agentscope.KernelSystemPrompt;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.agentscope.RunHeading;
import com.semgravio.aiplatform.base.agentscope.StageDurations;
import com.semgravio.aiplatform.base.agentscope.UsageContext;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.business.billing.application.SegmentSettlementAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceChanges;

import lombok.extern.slf4j.Slf4j;

/**
 * 编码 run 派发器（生成与更新共用，#22 落位 / #26 迭代环复用——所有编码
 * run 同机制；<b>#604 尝试环对生成/更新 run 整体退役</b>，ADR-0055 #580-13 失败
 * 语义落地）：生成与更新 run（内核座）一场 run <b>一次尝试</b>——内核循环（ReAct
 * 轮次＋护栏三数值＋循环五条语义中间件）在底座内跑到自然终止，平台侧不再叠
 * 尝试环（旧「失败有余量静默续试」语义连机制移除；重试面只剩两层且都在供给链/
 * 底座：模型内建重试已关（{@code maxRetries=1}，#555）、流层断流重试与候选
 * failover 归供给链（#321/#557）——<b>不造第三层重试</b>）。失败＝唯一失败终态
 * {@code run-failed}（明确展示：失败面落库＋SSE 扩载，#418），恢复＝用户在点火
 * 入口重跑（<b>重跑即续</b>：内核自见工作区现状、不重做已对的工作，#577——不
 * 携带错误现场、无断点续跑机器，ADR-0055 续跑机器出局，#604）。#610 设计线整条
 * 退役：设计执行体座与有界重试分支一并拆除。</p>
 *
 * <p>命令全要素同构：执行体配置（{@link AgentProfile#EXECUTOR}——systemPrompt/
 * 模型配置经运营配置读面 {@link AgentConfigAppService} 取生效值，#251 库值优先、
 * 缺省回落枚举默认；模型路由已随 #606 退役——run 起跑直接取执行体生效档）、
 * 会话寻址由轨道层拼装传入（#114 每 run 换会话——已落盘成果保留，同工作区不丢
 * 数据）、owner 寻址、活超时、计量 dims（agentKind=executor）、项目工作区、流
 * 关联。生成执行体只读当前项目需求文档，不接收知识命中注入（#639——知识命中
 * 的唯一作用点＝主智能体开场找相似需求）。</p>
 *
 * <p><b>用户停止旁路（#359 生成取消）</b>：停止请求在场（CodingRunTrack 停止面）
 * 时，run 起跑前查停、失败处置时查停——停止<b>不走重试</b>（中断口径非判死），以
 * {@link RunStoppedException} 直抛轨道层停止收口（run-failed 载荷 stopped=true
 * 失败账口径；已收口成果保留，用户重跑即续）。</p>
 *
 * <p><b>失败留账（#323）</b>：失败 run 的耗时账与观察面不清零弃置——客户端流失败经
 * {@link AgentTurnFailure} 携部分观察（已提交文件变更＋已闭合区间阶段耗时）上浮，
 * 本层计入本场累计（收口清单与耗时分布含失败 run 的部分观察）；超限转终态时耗时账
 * 随终态日志留档（平台观测面——用户面呈现归等待态线，#314）。</p>
 *
 * <p><b>破坏性命令直通（#219 透明面化）</b>：执行体命令走内核 shell 工具
 * （无确认自检——沙箱可随时销毁重建，确认机制连代码带概念已删），过程照常经
 * 动作部件播报（命令原值滚动行，#228）对用户透明可见。</p>
 */
@Component
@Slf4j
class CodingRunDispatcher {

    /** 编码会话标识派生前缀（projectId → coder-{projectId}；#114 每 run 换会话，会话寻址规则归各轨道拼装）。 */
    public static final String SESSION_PREFIX = "coder-";

    /**
     * 尝试环的执行体座席：模型配置寻址（{@link AgentProfile#EXECUTOR}——systemPrompt
     * 与模型配置经运营配置读面取生效值、计量 dims agentKind 同键）。<b>收口自动
     * 成版（Run-Id trailer）随审查与收口组退役（#605）</b>：run 收口不再提交 git
     * 版本，版本语义归阶段四重建。#610 设计执行体座（designer）随设计线整条退役
     * 拆除。
     *
     * <p>{@code kernelForm}（#601 接线，ADR-0054/0055）：<b>生成内核形态座席</b>——
     * 命令按内核装配形态构建（系统提示＝{@link KernelSystemPrompt} 权威版本、
     * 装配键＝{@link AgentscopeHarnessAgentFactory#KERNEL_AGENT_NAME}——不带
     * executor 键，决策提示／verify 自决旧中间件结构性挂不上；工具面＝恰四件经
     * 装配兑现，本层不发放工具规格）。模型配置寻址／计量 dims 仍随
     * {@link AgentProfile#EXECUTOR} 座（供给链照现路径——模型配置→候选队列→
     * 通道行，#601 供给链不绕开）。</p>
     */
    record RunSeat(AgentProfile profile, boolean kernelForm) {

        /**
         * 生成内核座席（#601 接线）：首建与迭代重建派发走新内核装配——profile 仍
         * EXECUTOR（模型配置／计量 dims 连续性），装配面四件内核形态由
         * kernelForm 分叉（提示词权威版本／内核装配键／无工具面规格）。
         */
        static RunSeat kernel() {
            return new RunSeat(AgentProfile.EXECUTOR, true);
        }
    }

    /**
     * 收口判据的逐件结果（#617 五字段收尾卡·判据结果）：build 过没过＋预览探活
     * 有响应——由收口判据回调（onSuccess）给出权威事实，本层只拼装不判定。
     * 生成与更新同形（旧「PRD／系统改没改」判定行随平台句式摘要一起退役）。
     */
    record ClosingJudgment(boolean buildPassed, boolean previewPassed) {
    }

    /**
     * 一场编码 run 的收场事实：成败（终态收口事件 run-failed 的用户面锚 = 调用方
     * 持有的 runId，#84——run 身份全程不变）+ 失败面（{@code failure}，终态失败/
     * 停止才非 null——#418 失败 run 落库面：本场墙钟/失败原因/观察面，轨道层经
     * 事件桥与 run-failed SSE 扩载同载荷落库）。收口终文（内核自述）不在此携带
     * ——收尾卡拼装在派发器内直接取当次末次文本（#617）。
     */
    record RunResult(boolean succeeded, Map<String, Object> failure) {

        static RunResult ok() {
            return new RunResult(true, null);
        }

        static RunResult failed(Map<String, Object> failure) {
            return new RunResult(false, failure);
        }
    }

    /**
     * run 的耗时账（#111 阶段耗时分布）：run 墙钟 + 跨流段（converse）合并的阶段
     * 耗时事实 + 生效模型配置（供给链留档——用了哪档可归因，收尾卡
     * durationBreakdown.attempts[].modelId）。墙钟口径：成功/判据未过的 run =
     * converse 起跑 → 收口落定（核验起计收口尾序桶，判据未过的核验时间未归因，
     * 探活类量级小）；中段崩的 run = 起跑到异常点（含全部在途），桶 = 该流段已闭合
     * 区间的部分观察（#323 失败留账——AgentTurnFailure 携带；非客户端失败〔前段
     * 失败/判据回调抛错〕无可携事实仍零桶，观察面口径见 StageDurationFacts）。
     */
    private record AttemptDuration(int attempt, long wallMs,
            StageDurations durations, String modelConfigKey, boolean channelInstant) {
    }

    private final AgentscopeAgentClient agentClient;
    private final AgentEventBridge eventBridge;
    private final AgentConfigAppService agentConfigs;
    private final ModelConfigAppService modelConfigs;
    private final GenerationProperties properties;
    private final ConversationHistoryAppService conversationHistory;
    private final CodingRunTrack codingRunTrack;
    private final SegmentSettlementAppService segmentSettlement;
    private final WorkspaceChangeAppService workspaceChanges;

    CodingRunDispatcher(AgentscopeAgentClient agentClient,
            AgentEventBridge eventBridge,
            AgentConfigAppService agentConfigs, ModelConfigAppService modelConfigs,
            GenerationProperties properties,
            ConversationHistoryAppService conversationHistory,
            CodingRunTrack codingRunTrack,
            SegmentSettlementAppService segmentSettlement,
            WorkspaceChangeAppService workspaceChanges) {
        this.agentClient = agentClient;
        this.eventBridge = eventBridge;
        this.agentConfigs = agentConfigs;
        this.modelConfigs = modelConfigs;
        this.properties = properties;
        this.conversationHistory = conversationHistory;
        this.codingRunTrack = codingRunTrack;
        this.segmentSettlement = segmentSettlement;
        this.workspaceChanges = workspaceChanges;
    }

    /**
     * 一场 run 的跨尝试共享账（{@link #run} 开场建）：本场起跑时刻、累计文件变更
     * （成果面——收口清单的事实源）、耗时账（#111）、计量锚（#537）。
     */
    private record RunState(Project project, String firstRunId, String sessionId, String prompt,
            BiFunction<String, List<FileChange>, ClosingJudgment> onSuccess, String what,
            RunSeat seat, RunHeading heading, List<String> meterRunIds,
            Instant runStartedAt, List<FileChange> runChanges,
            List<AttemptDuration> attemptDurations,
            /** 跑前快照 commit hash（#619 收口 diff 基线）；快照失败 = null（清单降级）。 */
            String changeBaseline,
            /** 变更清单是否携单文件只读 diff（迭代 true / 首建 false）。 */
            boolean includePatch) {
    }

    /**
     * 单次尝试的收场：终态（成功收口）或可重试的失败原因。用户停止／平台关停
     * 旁路不经本记录——直接抛 {@link RunStoppedException}／{@link RunShutdownException}
     * 交轨道层收口。
     */
    private record AttemptOutcome(RunResult terminal, RuntimeException cause) {

        static AttemptOutcome terminal(RunResult result) {
            return new AttemptOutcome(result, null);
        }

        static AttemptOutcome retryable(RuntimeException cause) {
            return new AttemptOutcome(null, cause);
        }
    }

    /**
     * 跑一场编码/设计 run（座席 {@link RunSeat} 定配置与工具面）：成功收口即
     * {@code onSuccess}（收口回调携该次 run 的 runId 与本场累计文件变更事实——
     * 对偶 8081 探活看的是工作区累计状态；返回收口判据的逐件权威事实——#617
     * 判据结果；回调抛异常即该次尝试失败——收口判据不满足的既有口径，如生成
     * 8081 核验）。
     * 项目事实（工作区 / owner）从聚合派生。
     *
     * <p><b>尝试口径（#604）</b>：生成与更新 run（内核座）<b>单发</b>——一次尝试
     * 即终态，失败语义＝明确展示＋重跑即恢复、不自动重跑（ADR-0055 #580-13：不造
     * 第三层重试）。#610 设计座与有界重试环随设计线整条退役拆除。</p>
     *
     * <p><b>收口扩载（#617/#619）</b>：真收口释放被押后的 run-finish 时拼装 {@code closing}
     * 载荷——判据逐件（onSuccess 返回的权威事实）/ 内核自述（内核末次文本）/
     * 变更清单（#619 起为工作区真实 diff：跑前快照基线 + 收口 numstat；迭代另携
     * 单文件只读 patch，首建只出行数——目录汇总归 #620）/ 用时（起跑到收口）/
     * 阶段耗时分布（#111 durationBreakdown——平台分析口径、前端不渲染）。
     * 咨询/纯追问轮不经本层，run-finish 无扩载（无收尾卡）。</p>
     *
     * @param what           日志标签（generate / fix）
     * @param firstRunId     run 的 runId（调用方预生成随响应回 = 用户面 run 身份，全程
     *                       不变）
     * @param sessionId      本场 run 的会话寻址（#114 每 run 换会话，会话标识由轨道层
     *                       按寻址规则拼装传入，不再固定 {@code coder-{projectId}}）
     * @param prompt         本场提示词（单发；生成执行体只读当前项目需求文档，
     *                       不接收知识命中注入——#639）
     * @param heading        工作消息头部标题（#118：用户语言标题；可空——
     *                       主智能体对话轮不经本层）——随 run-start 的 slice 字段
     *                       透出
     * @param includePatch   变更清单是否携单文件只读 diff（迭代 = true；首建 = false，
     *                       目录汇总与不携 patch 归 #620）
     * @return               收场事实（成败 + 收口终文）；失败转终态后的兜底归轨道层
     *                       ——终态收口事件 run-failed 锚 runId，用户侧重跑即恢复
     *                       （#604 失败新语义：生成/更新走点火入口「继续生成」，
     *                       ADR-0055 #580-13）
     */
    RunResult run(Project project, String firstRunId, String sessionId, String prompt,
            BiFunction<String, List<FileChange>, ClosingJudgment> onSuccess, String what,
            RunSeat seat, RunHeading heading,
            SegmentType segmentType, boolean includePatch) {
        Long projectId = project.getId();
        List<String> meterRunIds = new ArrayList<>();
        // 跑前快照（#619）：内核起跑前对工作区做一次幂等全量提交，hash 作为收口
        // diff 基线。失败不阻断（null → 变更清单降级），快照不进用户面版本序列。
        String changeBaseline = workspaceChanges.snapshot(project.getWorkspaceId(), firstRunId);
        try {
            RunState state = new RunState(project, firstRunId, sessionId, prompt, onSuccess,
                    what, seat, heading, meterRunIds,
                    Instant.now(), new ArrayList<>(), new ArrayList<>(), changeBaseline,
                    includePatch);
            AttemptOutcome outcome = attempt(state);
            if (outcome.terminal() != null) {
                return outcome.terminal();
            }
            RuntimeException cause = outcome.cause();
            String reason = errorScene(cause);
            // 终态留档（#323 失败留账）：转终态失败时，耗时账与观察面仍随终态日志
            // 留档（平台观测面）——累计墙钟＋尝试账＋已落盘文件清单，失败看得见
            // 成本。失败面（#418 落库面）随 RunResult 回传轨道层——经事件桥与
            // run-failed SSE 扩载同载荷落库（失败不再只看日志）
            log.error("[{}] 项目 {} 尝试失败转终态——本场留账：累计 {}、已落盘文件变更 {}。"
                            + "用户侧兜底（#604：生成/更新走点火入口重跑即恢复）",
                    what, projectId,
                    humanDuration(Duration.between(state.runStartedAt(), Instant.now()).toMillis()),
                    filePayloads(state.runChanges()));
            return RunResult.failed(failureFace(false, reason, state.attemptDurations(),
                    state.runStartedAt()));
        }
        finally {
            settleRun(projectId, segmentType, firstRunId, meterRunIds);
        }
    }

    /** run 段收口结算（#537）：计量锚随收——成败/停止/异常全扣（「失败也扣、停止也扣」），{@code finally} 挂载保证异常路径不漏；零尝试（秒止/关停旁路在首尝试生成前抛出）无计量锚空过。结算尽力而为（异常只记日志不反噬收口轨道）。 */
    private void settleRun(Long projectId, SegmentType segmentType,
            String firstRunId, List<String> meterRunIds) {
        if (meterRunIds.isEmpty()) {
            return;
        }
        try {
            segmentSettlement.settleSegment(projectId, segmentType, firstRunId, meterRunIds);
        }
        catch (RuntimeException e) {
            log.warn("[run-settle] 项目 {} run 段结算失败（不反噬收口轨道）：{}", projectId, e.toString());
        }
    }

    /**
     * 单次尝试本体（{@link #run} 的内层——生成/更新 run 只跑一次）：成功收口返回
     * 终态；失败返回失败原因（尝试账已记、部分观察已并入本场累计）。停止／关停
     * 旁路直接抛出。
     */
    private AttemptOutcome attempt(RunState state) {
        Project project = state.project();
        Long projectId = project.getId();
        Long workspaceId = project.getWorkspaceId();
        RunSeat seat = state.seat();
        String what = state.what();
        String firstRunId = state.firstRunId();
        List<FileChange> runChanges = state.runChanges();
        List<AttemptDuration> attemptDurations = state.attemptDurations();
        Instant runStartedAt = state.runStartedAt();
        // 起跑闸（起跑前重查）。停止旁路（#359 生成取消）：停止不走重试（一次尝试
        // 即收场），直抛轨道层停止收口；停止失败面（#418）随异常携出
        if (codingRunTrack.stopRequested(projectId)) {
            throw new RunStoppedException(firstRunId,
                    failureFace(true, null, attemptDurations, runStartedAt));
        }
        // 关停旁路（#477）：宽限窗内不起尝试（不烧预算）——直抛轨道层关停
        // 收口（重启后由重跑入口恢复）
        if (AgentShutdownGate.isShuttingDown()) {
            throw new RunShutdownException(firstRunId,
                    failureFace(false, null, attemptDurations, runStartedAt));
        }
        Instant attemptStartedAt = Instant.now();
        // 单发：本场唯一尝试即用户面 run 身份
        String attemptRunId = firstRunId;
        // 段计量锚收集（#537 段收口接线）：本场 run 的全场合锚——成败/停止/异常
        // 全扣（「失败也扣、停止也扣」，结算挂 run finally）
        state.meterRunIds().add(attemptRunId);
        AgentConfigAppService.EffectiveConfig agent = agentConfigs.effectiveOf(seat.profile());
        // 配置键 → 模型串＋通道注入（ADR-0031 解析单点）：run 直接取执行体生效档
        // （库值优先、缺省回落枚举——起跑时读档，进行中 run 不定格；模型路由已随
        // #606 退役）
        String attemptModelConfigKey = agent.modelConfigKey();
        // #557 整候选队列（多候选＝failover 装配面，五个 resolve 面之一）
        ModelCandidateQueue model = modelConfigs.resolveQueue(attemptModelConfigKey);
        // 提示：单发提示原样下发（#639：生成执行体只读当前项目需求文档，不接收知识注入）
        String prompt = state.prompt();
        // #601 内核形态分叉：内核座席的命令按生成内核装配形态构建——系统提示
        // ＝KernelSystemPrompt 权威版本（住代码、调用方不可覆盖）、装配键＝内核
        // 键（不带 executor——平台中间件按配置键挂、不看工作区形态）、工具面
        // 规格串空（内核工具面＝恰四件经装配结构性兑现，不消费业务工具规格）。
        // 供给链三产物（模型串／通道行／候选队列）与计量 dims 照现路径不变。
        AgentCommand command = new AgentCommand(
                attemptRunId,
                prompt,
                seat.kernelForm() ? KernelSystemPrompt.text() : agent.systemPrompt(),
                model.modelString(),
                model.channel(),
                state.sessionId(),
                project.ownerUserId(),
                new UsageContext(Long.toString(projectId),
                        UsageDims.of(projectId, UsageDims.kindOf(seat.profile()),
                                state.sessionId())),
                Long.toString(workspaceId),
                Map.of(EventsAppService.PROJECT_FIELD, projectId.toString()),
                /* timeout= */ null, // #322 活超时替换一次性总墙钟
                properties.getSilenceTimeout(),
                properties.getToolSilenceTimeout(), // #355 工具执行窗延长预算
                seat.kernelForm()
                        ? AgentscopeHarnessAgentFactory.KERNEL_AGENT_NAME
                        : seat.profile().key(),
                /* workspaceReadOnly= */ false,
                state.heading(),
                seat.kernelForm() ? null : agent.toolSpec(),
                // #557 候选队列（生成尝试同获链式切换——多候选＝failover）
                model,
                seat.kernelForm());
        // 本尝试账是否已记（成功路 converse 返回即记；其后核验/收口段抛错不重记）
        boolean attemptAccounted = false;
        try {
            Consumer<AgentEvent> projection = userFacingProjection(firstRunId, attemptRunId,
                    eventBridge.sink(project));
            // run-finish 押后到收口判据落定（#84 假完成不闪收口）：converse 正常
            // 返回 ≠ 收口（判据在 onSuccess——8081 核验 / 落稿核验），判据不过即
            // 该次尝试失败——中场 run-finish 若出用户面，工作消息定格了又生长、
            // run-failed 前出现假收口
            AtomicReference<AgentEvent> pendingFinish = new AtomicReference<>();
            Consumer<AgentEvent> sink = event -> {
                if (AgentEventTypes.RUN_FINISH.equals(event.type())) {
                    pendingFinish.set(event);
                    return;
                }
                projection.accept(event);
            };
            List<FileChange> attemptChanges = new ArrayList<>();
            AgentReply reply = agentClient.converse(command, sink);
            // 挂起在编码 run 不可达（执行体工具面无 ask_user、命令直通无确认
            // 自检）——防御即失败：未来给执行体挂上提问工具时立即炸出，不静默
            // 把挂起轮的部分文本当收口终文
            if (reply.suspension() != null) {
                throw new IllegalStateException(
                        "编码 run 出现提问挂起（执行体无 ask_user 工具，不可达）：engineRef="
                                + reply.suspension().engineRef());
            }
            attemptChanges.addAll(reply.changes());
            runChanges.addAll(attemptChanges);
            // 本尝试账先记——核验/收口段抛错不重记。配置随账留档（供给链归因
            // ——收尾卡 attempts[].modelId）；尝试墙钟止于核验前
            attemptDurations.add(new AttemptDuration(1,
                    Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                    reply.durations(), attemptModelConfigKey, /* channelInstant= */ false));
            attemptAccounted = true;
            Instant closingStartedAt = Instant.now(); // 收口尾序起表（探活 → 成版）
            // 核验未过＝本次尝试失败（onSuccess 抛错直接上抛）
            ClosingJudgment judgment = state.onSuccess().apply(attemptRunId, runChanges);
            if (pendingFinish.get() != null) {
                // 内核自述 = 内核自然终止时的末次文本（#617；模型文本不直播，
                // 只在此落收尾卡）。变更清单 = 工作区真实 diff（#619）：快照基线在
                // 场则对基线取 numstat（迭代另取单文件 patch），否则降级不可用。
                WorkspaceChanges.ChangeSet changeSet = state.changeBaseline() == null
                        ? WorkspaceChanges.ChangeSet.degraded()
                        : workspaceChanges.diff(workspaceId, state.changeBaseline(),
                                state.includePatch());
                Map<String, Object> closing = closingPayload(judgment, changeSet,
                        runStartedAt, reply.text());
                // 阶段耗时分布（#111）：收口尾序止表于载荷定型（落库自指不可测——
                // 载荷先于落库定型，量级极小忽略）；三桶齐备 + 逐尝试分布
                closing.put(CLOSING_BREAKDOWN_FIELD, durationBreakdown(attemptDurations,
                        Duration.between(closingStartedAt, Instant.now()).toMillis()));
                // 对话史落库（#89 收尾卡腿）：先落库后发 run-finish——事件即触发
                // 前端对话史域失效重拉（水合按 run 整体接管 live 片段），次序反转
                // 会让重拉撞上未落库的空窗（code-review #89）
                conversationHistory.recordClosing(projectId, firstRunId, closing);
                projection.accept(withClosing(pendingFinish.get(), closing));
            }
            return AttemptOutcome.terminal(RunResult.ok());
        }
        catch (RuntimeException e) {
            // 关停旁路（#477，先于停止处置——进程正死时事件面无意义）：宽限窗内
            // 的一切失败按关停中断收场——不重试、不发终态事件
            if (AgentShutdownGate.isShuttingDown() || AgentShutdownGate.causedByShutdown(e)) {
                recordInterrupted(state, e, attemptStartedAt, attemptModelConfigKey,
                        attemptAccounted);
                throw new RunShutdownException(firstRunId,
                        failureFace(false, null, attemptDurations, runStartedAt));
            }
            // 停止旁路（#359）：停止请求在场时的一切失败按停止收场——中断打断的
            // 模型流（Future.cancel 线程中断经 runTurn 包装成 AgentTurnFailure）
            // 不走重试，直抛轨道层停止收口。收场前照 #323 口径留账（失败账口径
            // ——中断尝试的部分观察面与终态日志不弃置；已耗计量另由 MeteredModel
            // 流收口直报，不重复）
            if (codingRunTrack.stopRequested(projectId)) {
                recordInterrupted(state, e, attemptStartedAt, attemptModelConfigKey,
                        attemptAccounted);
                log.info("[{}] 项目 {} 用户停止收场（runId={}）——本场留账：累计 {}、"
                                + "文件变更 {}",
                        what, projectId, firstRunId,
                        humanDuration(Duration.between(runStartedAt, Instant.now()).toMillis()),
                        filePayloads(runChanges));
                // 停止的失败面（#418 落库面）：stopped 口径＋本场账——停止是用户
                // 动作，无失败原因可报（不携 error）；中断尝试的部分观察已计入账
                throw new RunStoppedException(firstRunId,
                        failureFace(true, null, attemptDurations, runStartedAt));
            }
            // 失败留账（#323）：失败流段的部分观察面（已提交文件变更＋已闭合区间
            // 阶段耗时）计入本场累计——收口清单与耗时分布含失败 run 的部分观察，
            // 不清零弃置（已落盘的写是真实工作区事实）
            if (e instanceof AgentTurnFailure failure) {
                attemptDurations.add(new AttemptDuration(1,
                        Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                        failure.durations(), attemptModelConfigKey, isChannelInstant(e)));
                runChanges.addAll(failure.changes());
            }
            else if (!attemptAccounted) {
                // 非客户端失败（前段失败）：无可携事实仍零桶——观察面口径见
                // StageDurationFacts
                attemptDurations.add(new AttemptDuration(1,
                        Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                        StageDurations.zero(), attemptModelConfigKey, /* channelInstant= */ false));
            }
            return AttemptOutcome.retryable(e);
        }
    }

    /**
     * 中断收场留账（停止／关停旁路的共用面，#323 口径）：被中断尝试的部分观察
     * （客户端流失败携带的已提交文件变更＋已闭合区间阶段耗时）并入本场累计；本
     * 尝试尚无账则记一条（无可携事实仍零桶——失败面 attempts 缺省出一条，schema
     * 与终态失败面同形）。
     */
    private static void recordInterrupted(RunState state, RuntimeException e,
            Instant attemptStartedAt, String attemptModelConfigKey, boolean attemptAccounted) {
        if (e instanceof AgentTurnFailure failure) {
            state.runChanges().addAll(failure.changes());
            if (!attemptAccounted) {
                state.attemptDurations().add(new AttemptDuration(1,
                        Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                        failure.durations(), attemptModelConfigKey, /* channelInstant= */ false));
            }
            return;
        }
        if (!attemptAccounted) {
            state.attemptDurations().add(new AttemptDuration(1,
                    Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                    StageDurations.zero(), attemptModelConfigKey, /* channelInstant= */ false));
        }
    }

    /**
     * 错误现场（失败面的失败原因字段）：异常消息优先、无消息退类名——异常事实
     * 原样携带，平台不加工不隐瞒。
     */
    private static String errorScene(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * 通道级秒败判式（#477 单点）：{@link AgentTurnFailure} 且 {@code llmMs=0}——
     * 未发生真实模型调用（连接拒绝/凭证失效类通道错误；裸异常＝前段/环境失败，
     * 不是通道级）。#604 起只作失败面逐尝试标注（内核座单发无免预算重试）。
     */
    private static boolean isChannelInstant(Throwable failure) {
        return failure instanceof AgentTurnFailure instant && instant.durations().llmMs() == 0;
    }

    /** 人读时长（终态留档）：秒 → 分秒 → 时分，向下取整。 */
    private static String humanDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) {
            return seconds + " 秒";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分 " + seconds % 60 + " 秒";
        }
        return minutes / 60 + " 小时 " + minutes % 60 + " 分";
    }

    /** 生成轨日志标签（run 的 what 参数值）——调用点同包引用。 */
    static final String GENERATE_LABEL = "generate";

    /** 收口扩载载荷的内核自述键（内核末次文本；空文本缺省不写）。 */
    static final String CLOSING_NARRATIVE_FIELD = "narrative";

    /** 收口扩载载荷的判据结果键（build 与预览逐件布尔）。 */
    static final String CLOSING_CRITERIA_FIELD = "criteria";

    /**
     * 收口扩载的阶段耗时分布键（#111；#453 增 overhead 桶）：{@code { llmMs,
     * toolsMs, closingMs, overhead, attempts[] }}——overhead＝每迭代平台
     * 开销归因桶（configMs/evalMs/eventPipelineMs/gateSkipped/gateEvaluated/
     * iterations，attempts[].overheadMs 同口径）——平台分析口径（时长优化归因用），
     * 前端不渲染（收尾卡呈现不变，只有「用时」durationMs）。验证桶 verifyMs 随
     * 验证机器退役（#605）。
     */
    static final String CLOSING_BREAKDOWN_FIELD = "durationBreakdown";

    /**
     * 收口扩载拼装（#617 五字段骨架；#619 变更清单改真实 diff）：被押后的
     * run-finish 载荷加 {@code closing} 对象——schema 见 SSE事件清单·收口扩载
     * （对话史落库 #89 复用同一载荷，拼装单点：SSE 扩载与对话史落库取同一 map）。
     * 五字段 = 用时（durationMs）/ 判据逐件（criteria）/ 变更清单（files，工作区
     * 真实 diff；迭代携 patch、首建只出行数并另携 {@code directories} 目录汇总
     * ——#620）/ 内核自述（narrative，空文本不写）/ 预览入口（前端取既有预览查询，
     * 不入载荷）。快照或 diff 失败时 files 为空并携 {@code filesUnavailable=true}
     * （卡上标注「变更清单不可用」，其余字段照常）。
     * 旧平台句式摘要与「需求文档／系统」判定键退役；档案内旧载荷由前端读侧回落
     * 呈现（旧对话史不出坏卡）。
     */
    private static Map<String, Object> closingPayload(ClosingJudgment judgment,
            WorkspaceChanges.ChangeSet changes, Instant runStartedAt, String narrative) {
        Map<String, Object> closing = new LinkedHashMap<>();
        if (narrative != null && !narrative.isBlank()) {
            closing.put(CLOSING_NARRATIVE_FIELD, narrative);
        }
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("buildPassed", judgment.buildPassed());
        criteria.put("previewPassed", judgment.previewPassed());
        closing.put(CLOSING_CRITERIA_FIELD, criteria);
        closing.put("files", changePayloads(changes));
        if (!changes.directories().isEmpty()) {
            closing.put(CLOSING_DIRECTORIES_FIELD, directoryPayloads(changes));
        }
        if (changes.unavailable()) {
            closing.put(CLOSING_FILES_UNAVAILABLE_FIELD, true);
        }
        closing.put("durationMs", Duration.between(runStartedAt, Instant.now()).toMillis());
        return closing;
    }

    /** 收口扩载变更清单不可用标记键（#619：快照或 diff 执行失败时的降级事实）。 */
    static final String CLOSING_FILES_UNAVAILABLE_FIELD = "filesUnavailable";

    /** 收口扩载首建目录汇总键（#620：目录、文件数、± 行数；迭代不产出该键）。 */
    static final String CLOSING_DIRECTORIES_FIELD = "directories";

    /**
     * 真实 diff 变更清单载荷：文件级 ± 行数恒在；patch 可缺省（首建不携 / 超限
     * 省略），{@code patchOmitted=true} 标注超限（前端呈现「diff 过大」）。顺序
     * 已由 {@link WorkspaceChanges#assemble} 按路径排定。
     */
    private static List<Map<String, Object>> changePayloads(WorkspaceChanges.ChangeSet changes) {
        return changes.files().stream().map(file -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("path", file.path());
            payload.put("added", file.added());
            payload.put("removed", file.removed());
            if (file.patch() != null) {
                payload.put("patch", file.patch());
            }
            if (file.patchOmitted()) {
                payload.put("patchOmitted", true);
            }
            return payload;
        }).toList();
    }

    /**
     * 首建目录汇总载荷（#620）：目录、文件数、± 行数（顺序已由
     * {@link WorkspaceChanges#aggregateByDirectory} 按路径排定）。前端以目录行
     * 呈现，点开展开到所处目录的文件。
     */
    private static List<Map<String, Object>> directoryPayloads(WorkspaceChanges.ChangeSet changes) {
        return changes.directories().stream().map(directory -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("path", directory.path());
            payload.put("fileCount", directory.fileCount());
            payload.put("added", directory.added());
            payload.put("removed", directory.removed());
            return payload;
        }).toList();
    }

    /**
     * 阶段耗时分布装配（#111；#417 口径修真；#453 增每迭代平台开销归因桶）：
     * 三桶齐备（LLM 等待 = 模型调用累计 / 工具执行 = 按工具名分桶·command
     * 按命令归组嵌套 / 收口尾序 = 探活）＋ {@code overhead} 每迭代平台开销归因桶
     * （#453：configMs 中间件动态查库 / evalMs 微指令判定 / eventPipelineMs 引擎
     * 事件处理链（含 SSE 派发）/ gateSkipped·gateEvaluated 判定门观测 / iterations
     * 闭合模型调用计数——残差饼图：attempts[].durationMs − llm − tools −
     * overhead ≈ 未归因差值）+ 逐 run 分布（平台分析口径）。
     * 与 durationMs 的一致性口径：桶计 + 未归因差值（平台管道、判据未过的核验、
     * 委派等待屏障等）− 真并行重叠 = durationMs——量级不符即埋点有洞（时长桶一致性校验）。
     * 验证桶 verifyMs 随验证机器退役（#605）。
     */
    private static Map<String, Object> durationBreakdown(List<AttemptDuration> attempts,
            long closingMs) {
        StageDurations total = attempts.stream()
                .map(AttemptDuration::durations)
                .reduce(StageDurations.zero(), StageDurations::plus);
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("llmMs", total.llmMs());
        breakdown.put("toolsMs", toolsPayload(total));
        breakdown.put("closingMs", closingMs);
        breakdown.put("overhead", new LinkedHashMap<>(total.overheadMs()));
        breakdown.put("attempts", attempts.stream()
                .map(CodingRunDispatcher::attemptPayload)
                .toList());
        return breakdown;
    }

    /** 逐 run 分布条目：run 墙钟 + 三桶（无收口尾序——归级）+ 每迭代平台开销归因
     * 桶（#453，同级 overhead 口径）+ 生效模型配置（供给链归因——用了哪档）。 */
    private static Map<String, Object> attemptPayload(AttemptDuration attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempt", attempt.attempt());
        payload.put("durationMs", attempt.wallMs());
        payload.put("modelId", attempt.modelConfigKey()); // 键＝持久化留档契约，#554 改名不改数据面
        payload.put("llmMs", attempt.durations().llmMs());
        payload.put("toolsMs", toolsPayload(attempt.durations()));
        payload.put("overheadMs", new LinkedHashMap<>(attempt.durations().overheadMs()));
        if (attempt.channelInstant()) {
            payload.put("channelInstant", true); // 通道级秒败 run（#477 标注——平台分析口径）
        }
        return payload;
    }

    /**
     * 失败面装配（#418 失败 run 落库面）：{@code { stopped?, error?, durationMs,
     * attempts[] }}——本场墙钟/配置＋失败原因（错误现场原文，停止口径不携——停止
     * 是用户动作无失败原因可报）＋本场累计墙钟（起跑到终态收口）。经 {@link
     * RunResult}/{@link RunStoppedException} 回传轨道层，事件桥以同一 map 落库与
     * 扩载 run-failed（同收尾卡「SSE 扩载与落库同载荷」先例）——平台分析口径、前端
     * 不渲染（同 durationBreakdown）。schema 正本＝SSE事件清单·失败面节。
     */
    private static Map<String, Object> failureFace(boolean stopped, String error,
            List<AttemptDuration> attempts, Instant runStartedAt) {
        Map<String, Object> failure = new LinkedHashMap<>();
        if (stopped) {
            failure.put(AgentEventTypes.RUN_STOPPED_FIELD, true);
        }
        if (error != null) {
            failure.put(FAILURE_ERROR_FIELD, error);
        }
        failure.put("durationMs", Duration.between(runStartedAt, Instant.now()).toMillis());
        failure.put("attempts", attempts.stream()
                .map(CodingRunDispatcher::attemptPayload)
                .toList());
        return failure;
    }

    /** 失败面的失败原因键（错误现场原文——平台分析口径，不经用户面映射）。 */
    static final String FAILURE_ERROR_FIELD = "error";

    /**
     * 失败面的失败原因读口（#418）：事件桥取末次错误现场做用户面 message 映射
     * （#355）用——map 形状知识留在装配侧，消费端不逐键解构。
     */
    static String errorOf(Map<String, Object> failure) {
        return failure != null && failure.get(FAILURE_ERROR_FIELD) instanceof String error
                ? error : null;
    }

    /**
     * 通道全死的秒败终态判定（#477 重派上限——单发后读法顺延）：整场 run 的尝试
     * {@code llmMs=0}（未发生任何真实模型调用——连接/凭证级通道故障）即真——轨道
     * 层据此停派后续排队轮（不形成「转终态→重派→秒败」循环）；有真实模型工作的
     * 失败（模型确实跑过但没干成——内容性失败）不受影响，走既有排队合并续派口径。
     */
    static boolean instantTerminalFailure(Map<String, Object> failure) {
        if (failure == null || !(failure.get("attempts") instanceof List<?> attempts)
                || attempts.isEmpty()) {
            return false;
        }
        // 逐尝试 channelInstant 标记判定（非裸 llmMs==0——前段/环境失败的零桶尝试
        // 不是通道级秒败，#429 失败续派口径不受影响）
        return attempts.stream().allMatch(attempt -> attempt instanceof Map<?, ?> attemptMap
                && Boolean.TRUE.equals(attemptMap.get("channelInstant")));
    }

    /**
     * 工具桶载荷：按工具名分桶；command 工具以命令归组对象嵌套
     * （install / build / dev / test / db / http / script / other——#417 七族，
     * 只携带出现过的组）。
     */
    private static Map<String, Object> toolsPayload(StageDurations durations) {
        Map<String, Object> tools = new LinkedHashMap<>();
        durations.toolsMs().forEach(tools::put);
        if (!durations.commandMs().isEmpty()) {
            tools.put("command", new LinkedHashMap<>(durations.commandMs()));
        }
        return tools;
    }

    /** 被押后的 run-finish 加挂 closing 载荷（载荷拼装归 {@link #closingPayload}）。 */
    private static AgentEvent withClosing(AgentEvent finish, Map<String, Object> closing) {
        Map<String, Object> payload = new LinkedHashMap<>(finish.payload());
        payload.put(AgentEventTypes.CLOSING_FIELD, closing);
        return new AgentEvent(finish.type(), payload);
    }

    /** 变更清单载荷：同路径合并行数、按路径排序（呈现稳定，非时间序）。 */
    private static List<Map<String, Object>> filePayloads(List<FileChange> changes) {
        Map<String, int[]> merged = new TreeMap<>();
        for (FileChange change : changes) {
            int[] lines = merged.computeIfAbsent(change.path(), key -> new int[2]);
            lines[0] += change.added();
            lines[1] += change.removed();
        }
        return merged.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "path", entry.getKey(),
                        "added", entry.getValue()[0],
                        "removed", entry.getValue()[1]))
                .toList();
    }

    /**
     * 用户面投影（#84：底座逐次失败补的 {@code error} 事件是失败族的过程事实——
     * 滤掉，用户面只见工作消息正常生长或（失败后）唯一的失败终态
     * {@code run-failed}）。单发后本场唯一尝试即用户面 run 身份，无归锚需求。
     */
    private static Consumer<AgentEvent> userFacingProjection(String firstRunId,
            String attemptRunId, Consumer<AgentEvent> sink) {
        return event -> {
            if (AgentEventTypes.ERROR.equals(event.type())) {
                return;
            }
            sink.accept(event);
        };
    }
}
