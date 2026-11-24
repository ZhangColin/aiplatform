package com.semgravio.aiplatform.business.project.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

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
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.semgravio.aiplatform.business.billing.application.SegmentSettlementAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;

import lombok.extern.slf4j.Slf4j;

/**
 * 编码/设计 run 派发器（生成与更新共用，#22 落位 / #26 迭代环复用——所有编码
 * run 同机制；<b>#604 尝试环对生成/更新 run 整体退役</b>，ADR-0055 #580-13 失败
 * 语义落地）：生成与更新 run（内核座）一场 run <b>一次尝试</b>——内核循环（ReAct
 * 轮次＋护栏三数值＋循环五条语义中间件）在底座内跑到自然终止，平台侧不再叠
 * 尝试环（旧「失败有余量静默续试」语义连机制移除；重试面只剩两层且都在供给链/
 * 底座：模型内建重试已关（{@code maxRetries=1}，#555）、流层断流重试与候选
 * failover 归供给链（#321/#557）——<b>不造第三层重试</b>）。失败＝唯一失败终态
 * {@code run-failed}（明确展示：失败面落库＋SSE 扩载，#418），恢复＝用户在点火
 * 入口重跑（<b>重跑即续</b>：内核自见工作区现状、不重做已对的工作，#577——不
 * 携带错误现场、无断点续跑机器，ADR-0055 续跑机器出局，#604）。
 *
 * <p><b>设计座例外（设计线原样保留）</b>：失败语义决议（#580-13）的对象是生成链；
 * 设计线按总 spec「原样保留、一行不动」——设计座沿用原有的<b>有界重试</b>：失败／
 * 零稿收口后带续作提示（错误现场＋平台可观测的进展现场，#323）重试，至多
 * {@code max-attempts} 次；通道级秒败另有免预算重试账（#477）。相关配置键自 #604
 * 起只服务设计轨。</p>
 *
 * <p>命令全要素同构：执行体配置（{@link AgentProfile#EXECUTOR}——systemPrompt/
 * 模型配置经运营配置读面 {@link AgentConfigAppService} 取生效值，#251 库值优先、
 * 缺省回落枚举默认；模型配置另经模型路由判定 {@link ModelRoutingAppService} 选档
 * ——路由判定优先、人工覆盖兜底（钉死位＝路由关），#354 只对 run 执行体座生效）、
 * 会话寻址由轨道层拼装传入（#114 每 run 换会话——已落盘成果保留，同工作区不丢
 * 数据）、owner 寻址、活超时、计量 dims（agentKind=executor）、项目工作区、流
 * 关联。知识命中前置注入只进首跑 prompt（一次下发一次注入）。</p>
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
     * 尝试环的执行体座席（#289 designer 座并入，两座共用同一派发器）：模型配置寻址
     * （{@link AgentProfile#EXECUTOR} / {@link AgentProfile#DESIGNER}——systemPrompt
     * 与模型配置经运营配置读面取生效值、计量 dims agentKind 同键）、工作区面
     * （designer 无 shell——ProjectDesign 形态，写文件件在、命令执行结构性关闭）、
     * 收尾卡稿清单开关（designer 会话扩载「本轮稿清单与去向」——条目随判定事实由
     * 轨道层拼装，#292；null = 非设计会话不扩载）。<b>收口自动成版（Run-Id trailer）
     * 随审查与收口组退役（#605）</b>：run 收口不再提交 git 版本，版本语义归阶段四
     * 重建；设计定稿的成版提交同步出局（设计线整条退役另开新票）。
     *
     * <p>{@code kernelForm}（#601 接线，ADR-0054/0055）：<b>生成内核形态座席</b>——
     * 命令按内核装配形态构建（系统提示＝{@link KernelSystemPrompt} 权威版本、
     * 装配键＝{@link AgentscopeHarnessAgentFactory#KERNEL_AGENT_NAME}——不带
     * executor 键，决策提示／verify 自决旧中间件结构性挂不上；工具面＝恰四件经
     * 装配兑现，本层不发放工具规格）。模型配置寻址／模型路由／计量 dims 仍随
     * {@link AgentProfile#EXECUTOR} 座（供给链照现路径——模型配置→候选队列→
     * 通道行，#601 供给链不绕开）。</p>
     */
    record RunSeat(AgentProfile profile, boolean workspaceNoShell,
            String draftsItem, boolean kernelForm) {

        /**
         * 生成内核座席（#601 接线）：首建与迭代重建派发走新内核装配——profile 仍
         * EXECUTOR（模型配置／路由／计量 dims 连续性），装配面四件内核形态由
         * kernelForm 分叉（提示词权威版本／内核装配键／无工具面规格）。
         */
        static RunSeat kernel() {
            return new RunSeat(AgentProfile.EXECUTOR, false, null, true);
        }

        /** 设计执行体座（#289 设计过程轨道）：无 shell、收尾卡带稿清单。 */
        static RunSeat designer(String draftsItem) {
            return new RunSeat(AgentProfile.DESIGNER, true, draftsItem, false);
        }
    }

    /**
     * 收口判定的权威事实（#88 判定行）：PRD 改没改（生成轮恒未动；更新轮 = 交接物
     * 的修订说明）+ 系统改没改（生成轮 = 探活收口产出；更新轮 = 平台可观测文件改动
     * 事实）+ 各自说明。事实源在收口判据回调（onSuccess）——判定跟职责走，本层
     * 只拼装不判定。设计轨道另携 {@code drafts} 稿清单（#292：轨道层在收口点拼装
     * ——平面稿渲成 PNG 后以 PNG 为正身的事实面，非设计会话恒 null）；编码轨道
     * 另携 {@code designLint} 设计规范扫描事实（#296：token 合规是机器可判的
     * <b>验证事实</b>不是判定分类——判定出口零改，仅随收尾卡如实呈现；带规范
     * 项目的编码 run 在场，非规范项目/设计会话恒 null）。验证统计／基座 lint 两档／
     * 已知缺陷清单随审查与收口组退役（#605）。
     */
    record ClosingJudgment(boolean prdChanged, String prdNote, boolean systemChanged,
            String systemNote, List<Map<String, Object>> drafts,
            Map<String, Object> designLint) {

        /**
         * 生成轨道的分段判定（#104）：PRD 未动、系统产出；note = 本场叙事，进收尾卡
         * summary（版本成版主题同源）。
         */
        static ClosingJudgment generation(String note) {
            return new ClosingJudgment(false, null, true, note, null, null);
        }

        /**
         * 设计轨道的分段判定（#289）：PRD 未动、系统未动（设计稿不是系统——两套
         * 产出逻辑，ADR-0024）；note = 本场设计物叙事（完成设计物：X），进收尾卡
         * summary；drafts = 本轮稿清单与去向（#292 轨道层拼装——渲成的平面稿以
         * PNG 为正身；relaxed 改稿收口 0 稿即空清单，如实）。
         */
        static ClosingJudgment design(String note, List<Map<String, Object>> drafts) {
            return new ClosingJudgment(false, null, false, note,
                    drafts == null ? List.of() : drafts, null);
        }

        /**
         * 修正轨道的分段判定（#26）：判定事实随交接物与平台可观测文件改动事实直拼
         * （稿清单恒无——设计会话才有）。
         */
        static ClosingJudgment update(boolean prdChanged, String prdNote,
                boolean systemChanged, String systemNote) {
            return new ClosingJudgment(prdChanged, prdNote, systemChanged, systemNote,
                    null, null);
        }

        /** 附设计规范扫描事实（#296 收口回调拼装：非规范项目传 null＝不携带）。 */
        ClosingJudgment withDesignLint(Map<String, Object> lint) {
            return new ClosingJudgment(prdChanged, prdNote, systemChanged, systemNote, drafts,
                    lint);
        }
    }

    /**
     * 一场编码 run 的收场事实：成败（终态收口事件 run-failed 的用户面锚 = 调用方
     * 持有的 runId，#84——run 身份全程不变）+ 收口时的执行体终文（{@code closingText}，
     * 成功收口才非 null——生成轨把它当交接摘要用；修正轨不用）+ 失败面
     * （{@code failure}，终态失败/停止才非 null——#418 失败 run 落库面：本场墙钟/
     * 失败原因/观察面，轨道层经事件桥与 run-failed SSE 扩载同载荷落库）。
     */
    record RunResult(boolean succeeded, String closingText, Map<String, Object> failure) {

        static RunResult ok(String closingText) {
            return new RunResult(true, closingText, null);
        }

        static RunResult failed(Map<String, Object> failure) {
            return new RunResult(false, null, failure);
        }
    }

    /**
     * run 的耗时账（#111 阶段耗时分布）：run 墙钟 + 跨流段（converse）合并的阶段
     * 耗时事实 + 生效模型配置（#354 路由留档——哪档可归因，收尾卡
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
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final AgentConfigAppService agentConfigs;
    private final ModelConfigAppService modelConfigs;
    private final GenerationProperties properties;
    private final ConversationHistoryAppService conversationHistory;
    private final ModelRoutingAppService modelRouting;
    private final CodingRunTrack codingRunTrack;
    private final SegmentSettlementAppService segmentSettlement;

    CodingRunDispatcher(AgentscopeAgentClient agentClient,
            AgentEventBridge eventBridge, ProjectKnowledgeAppService knowledgeAppService,
            AgentConfigAppService agentConfigs, ModelConfigAppService modelConfigs,
            GenerationProperties properties,
            ConversationHistoryAppService conversationHistory,
            ModelRoutingAppService modelRouting, CodingRunTrack codingRunTrack,
            SegmentSettlementAppService segmentSettlement) {
        this.agentClient = agentClient;
        this.eventBridge = eventBridge;
        this.knowledgeAppService = knowledgeAppService;
        this.agentConfigs = agentConfigs;
        this.modelConfigs = modelConfigs;
        this.properties = properties;
        this.conversationHistory = conversationHistory;
        this.modelRouting = modelRouting;
        this.codingRunTrack = codingRunTrack;
        this.segmentSettlement = segmentSettlement;
    }

    /**
     * 一场 run 的提示词（{@link #run} 入参）：{@code first}＝首试提示；{@code retry}
     * ＝续作提示函数（入参＝重试现场——错误事实＋平台可观测的进展事实，#323）。
     *
     * <p>#604 起续作提示<b>只服务设计座</b>：设计线按总 spec「原样保留」沿用有界
     * 重试（失败／零稿收口后带续作提示重试）；生成/更新 run（内核座）单发无重试，
     * 只携首试提示（{@link #single}）。</p>
     */
    record Prompts(String first, UnaryOperator<String> retry) {

        /** 单发提示（内核座——无续作提示）。 */
        static Prompts single(String prompt) {
            return new Prompts(prompt, null);
        }
    }

    /**
     * 一场 run 的跨尝试共享账（{@link #run} 开场建、各尝试累加）：本场起跑时刻、
     * 累计文件变更（成果面——收口清单的事实源，#323 失败尝试的部分观察不清零）、
     * 逐尝试耗时账（#111）、计量锚（#537）。
     */
    private record RunState(Project project, String firstRunId, String sessionId, Prompts prompts,
            BiFunction<String, List<FileChange>, ClosingJudgment> onSuccess, String what,
            RunSeat seat, RunHeading heading, List<String> meterRunIds, String knowledgePrefix,
            Instant runStartedAt, List<FileChange> runChanges,
            List<AttemptDuration> attemptDurations) {
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
     * 对偶 8081 探活看的是工作区累计状态；返回收口判定的权威事实——#88 判定行；
     * 回调抛异常即该次尝试失败——收口判据不满足的既有口径，如生成 8081 核验 /
     * 设计会话落稿核验）。项目事实（工作区 / owner）从聚合派生。
     *
     * <p><b>尝试口径（#604）</b>：生成与更新 run（内核座）<b>单发</b>——一次尝试
     * 即终态，失败语义＝明确展示＋重跑即恢复、不自动重跑（ADR-0055 #580-13：不造
     * 第三层重试）。<b>设计座保留有界重试环</b>（设计线按总 spec 原样保留，失败
     * 语义决议的对象是生成链）：失败／零稿收口后带续作提示重试，至多
     * {@code max-attempts} 次；通道级秒败（未发生真实模型调用）另有免预算重试账
     * （#477——不烧尝试预算，额度尽回落正常预算，终态兜底不变）。</p>
     *
     * <p><b>收口扩载（#88）</b>：真收口释放被押后的 run-finish 时拼装 {@code closing}
     * 载荷——摘要（判定事实的合并叙事）/ 判定行（onSuccess 返回的权威事实）/ 变更
     * 清单（{@link AgentReply#changes()} 的文件级观察）/ 轮末统计（时长 = 起跑到
     * 收口）/ 阶段耗时分布（#111 durationBreakdown——平台分析口径、前端不渲染）。
     * 咨询/纯追问轮不经本层，run-finish 无扩载（无收尾卡）。</p>
     *
     * @param what           日志标签（generate / fix / design）
     * @param firstRunId     run 的 runId（调用方预生成随响应回 = 用户面 run 身份，全程
     *                       不变；设计轨重试的中间尝试用内部 runId，事件投影归锚到本值）
     * @param sessionId      本场 run 的会话寻址（#114 每 run 换会话，会话标识由轨道层
     *                       按寻址规则拼装传入，不再固定 {@code coder-{projectId}}；
     *                       设计轨重试续同一场会话）
     * @param injectKnowledge 是否做知识命中前置注入（#24/#114「一次切入一次注入」：
     *                       只随首试提示注入——一次下发一次注入）
     * @param heading        工作消息头部标题（#118：标题 + 生成轨道进度；可空——
     *                       主智能体对话轮不经本层）——随 run-start 的 slice 字段
     *                       透出
     * @return               收场事实（成败 + 收口终文）；失败转终态后的兜底归轨道层
     *                       ——终态收口事件 run-failed 锚 runId，用户侧重跑即恢复
     *                       （#604 失败新语义：生成/更新走点火入口／「继续生成」，
     *                       设计轨重提意见，ADR-0055 #580-13）
     */
    RunResult run(Project project, String firstRunId, String sessionId, Prompts prompts,
            BiFunction<String, List<FileChange>, ClosingJudgment> onSuccess, String what,
            boolean injectKnowledge, RunSeat seat, RunHeading heading,
            SegmentType segmentType) {
        Long projectId = project.getId();
        List<String> meterRunIds = new ArrayList<>();
        try {
            boolean boundedRetries = seat.profile() == AgentProfile.DESIGNER;
            int maxAttempts = boundedRetries ? properties.getMaxAttempts() : 1;
            RunState state = new RunState(project, firstRunId, sessionId, prompts, onSuccess,
                    what, seat, heading, meterRunIds,
                    injectKnowledge ? knowledgeAppService.dispatchInjection(prompts.first()) : "",
                    Instant.now(), new ArrayList<>(), new ArrayList<>());
            String previousError = null;
            int channelInstantRetries = 0;
            int iteration = 0;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                iteration++;
                AttemptOutcome outcome =
                        attempt(state, attempt, maxAttempts, iteration, previousError);
                if (outcome.terminal() != null) {
                    return outcome.terminal();
                }
                RuntimeException cause = outcome.cause();
                if (boundedRetries && isChannelInstant(cause)
                        && channelInstantRetries < properties.getChannelInstantRetryMax()) {
                    // 通道级秒败免预算（#477）：llmMs=0＝未发生真实模型调用（连接拒绝/
                    // 凭证失效类通道错误）——不消耗正常尝试预算，免预算账内同尝试序
                    // 干净重发（不升档、无错误现场可携——previousError 不刷新）；额度尽
                    // 回落正常预算（防通道长期故障无限循环）
                    channelInstantRetries++;
                    log.warn("[{}] 项目 {} 第 {}/{} 次尝试通道级秒败（未发生真实模型调用）：{}"
                                    + "——不烧尝试预算，免预算重试 {}/{}（退避 {}）",
                            what, projectId, attempt, maxAttempts, cause.toString(),
                            channelInstantRetries, properties.getChannelInstantRetryMax(),
                            channelBackoffOf(channelInstantRetries));
                    sleepBackoff(channelBackoffOf(channelInstantRetries));
                    attempt--; // 同尝试序重试（for 自增抵消）——起跑闸（停/关停）重查
                    continue;
                }
                previousError = errorScene(cause);
                log.warn("[{}] 项目 {} 第 {}/{} 次尝试失败：{}",
                        what, projectId, attempt, maxAttempts, previousError);
            }
            // 终态留档（#323 失败留账）：尝试耗尽转终态失败时，耗时账与观察面仍随
            // 终态日志留档（平台观测面）——累计墙钟＋逐尝试账＋已落盘文件清单，
            // 失败看得见成本。失败面（#418 落库面）随 RunResult 回传轨道层——经
            // 事件桥与 run-failed SSE 扩载同载荷落库（失败不再只看日志）
            log.error("[{}] 项目 {} 尝试耗尽（{} 次）转终态失败——本场留账：累计 {}、已落盘"
                            + "文件变更 {}。用户侧兜底（#604：生成/更新走点火入口重跑即恢复、"
                            + "设计轨重提意见续跑）",
                    what, projectId, maxAttempts,
                    humanDuration(Duration.between(state.runStartedAt(), Instant.now()).toMillis()),
                    filePayloads(state.runChanges()));
            return RunResult.failed(failureFace(false, previousError, state.attemptDurations(),
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
     * 单次尝试本体（{@link #run} 的内层——生成/更新 run 只跑一次；设计座失败后由
     * {@link #run} 带续作提示再调）：成功收口返回终态；失败返回可重试原因（尝试账
     * 已记、部分观察已并入本场累计）。停止／关停旁路直接抛出。
     *
     * @param attempt      尝试序（1 起）
     * @param maxAttempts  本场尝试上界（内核座恒 1；设计座＝max-attempts）
     * @param iteration    迭代序（免预算重试的 firstRunId 判据：只有首迭代沿用首试
     *                     runId，其后一律新内部 runId——计量幂等键不撞、重试投影滤
     *                     run-start 不重复）
     * @param previousError 此前尝试的错误现场（首试为 null；续作提示的携带物）
     */
    private AttemptOutcome attempt(RunState state, int attempt, int maxAttempts, int iteration,
            String previousError) {
        Project project = state.project();
        Long projectId = project.getId();
        Long workspaceId = project.getWorkspaceId();
        RunSeat seat = state.seat();
        String what = state.what();
        String firstRunId = state.firstRunId();
        List<FileChange> runChanges = state.runChanges();
        List<AttemptDuration> attemptDurations = state.attemptDurations();
        Instant runStartedAt = state.runStartedAt();
        // 起跑闸（每次尝试起跑前重查）。停止旁路（#359 生成取消）：停止不走重试
        // （一次尝试即收场），直抛轨道层停止收口；停止失败面（#418）随异常携出
        // （此前尝试的账在场——跨尝试累计）
        if (codingRunTrack.stopRequested(projectId)) {
            throw new RunStoppedException(firstRunId,
                    failureFace(true, null, attemptDurations, runStartedAt));
        }
        // 关停旁路（#477）：宽限窗内不起尝试（不烧预算、不升档）——直抛轨道层关停
        // 收口（重启后由重跑入口恢复）
        if (AgentShutdownGate.isShuttingDown()) {
            throw new RunShutdownException(firstRunId,
                    failureFace(false, null, attemptDurations, runStartedAt));
        }
        Instant attemptStartedAt = Instant.now();
        // 首次尝试沿用首试 runId（用户面 run 身份），其后一律新内部 runId（计量
        // 幂等键不撞、重试投影滤 run-start 不重复）
        String attemptRunId = attempt == 1 && iteration == 1
                ? firstRunId : EventsAppService.newRunId();
        // 段计量锚收集（#537 段收口接线）：每次尝试的全场合锚随收——成败/停止/
        // 异常全扣（「失败也扣、停止也扣」，结算挂 run finally）
        state.meterRunIds().add(attemptRunId);
        AgentConfigAppService.EffectiveConfig agent = agentConfigs.effectiveOf(seat.profile());
        // 模型路由判定（#354）：只对 run 执行体座生效（Demo 阶段模型档＝run
        // 执行体的模型档）；路由判定优先、人工覆盖兜底——run 起跑时读档定档
        // （进行中 run 不定格）。单发后恒首轮首试（attempt=1/round=1）
        ModelRoutingAppService.ConfigSelection config = seat.profile() == AgentProfile.EXECUTOR
                ? modelRouting.select(agent, Long.toString(workspaceId), 1, 1,
                        ModelRoutingAppService.SegmentSignals.none())
                : null;
        if (config != null) {
            log.info("[{}] 项目 {} 本场 run 模型路由：{}（来源 {}，理由 {}）",
                    what, projectId, config.modelConfigKey(), config.source(), config.reasons());
        }
        // 配置键 → 模型串＋通道注入（ADR-0031 解析单点）：路由选定档或座
        // 生效档统一在此解析——供应商/凭证/配置变更下一场 run 起跑即生效
        String attemptModelConfigKey = config != null ? config.modelConfigKey() : agent.modelConfigKey();
        // #557 整候选队列（多候选＝failover 装配面，五个 resolve 面之一）
        ModelCandidateQueue model = modelConfigs.resolveQueue(attemptModelConfigKey);
        // 提示：首试＝首试提示（知识命中前置注入只进首试）；重试＝续作提示（设计座，
        // 入参＝重试现场：错误事实＋平台可观测的进展事实）
        String prompt = attempt == 1
                ? state.knowledgePrefix() + state.prompts().first()
                : state.prompts().retry().apply(retryScene(previousError, attempt - 1, maxAttempts,
                        Duration.between(runStartedAt, Instant.now()).toMillis(), runChanges));
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
                seat.workspaceNoShell(),
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
            // 本尝试账先记——核验/收口段抛错不重记。配置随账留档（#354 路由
            // 可观测——收尾卡 attempts[].modelId）；尝试墙钟止于核验前
            attemptDurations.add(new AttemptDuration(attempt,
                    Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                    reply.durations(), attemptModelConfigKey, /* channelInstant= */ false));
            attemptAccounted = true;
            // 自检播报（#85）：收口判据核验（onSuccess——生成 8081 探活 / 落稿
            // 核验，复用既有收口链路、不新增探针）的呈现——核验前「检查中」、
            // 落定出结果，位于被押后的 run-finish 之前（收口前播报）
            emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_CHECKING);
            Instant closingStartedAt = Instant.now(); // 收口尾序起表（探活 → 成版）
            ClosingJudgment judgment;
            try {
                judgment = state.onSuccess().apply(attemptRunId, runChanges);
            }
            catch (RuntimeException e) {
                // 核验未过＝该次尝试失败。单发（内核座）：❌ 直接落定（不悬挂
                // 「检查中」）；设计座重试环：尝试间不出 ❌（静默重试信号不外泄，
                // 部件停「检查中」），末次尝试落定
                if (attempt == maxAttempts) {
                    emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_FAILED);
                }
                throw e;
            }
            emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_PASSED);
            if (pendingFinish.get() != null) {
                Map<String, Object> closing = closingPayload(judgment, runChanges,
                        runStartedAt, what, seat.draftsItem(), config);
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
            return AttemptOutcome.terminal(RunResult.ok(reply.text()));
        }
        catch (RuntimeException e) {
            // 关停旁路（#477，先于停止处置——进程正死时事件面无意义）：宽限窗内
            // 的一切失败按关停中断收场——不重试、不发终态事件
            if (AgentShutdownGate.isShuttingDown() || AgentShutdownGate.causedByShutdown(e)) {
                recordInterrupted(state, e, attempt, attemptStartedAt, attemptModelConfigKey,
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
                recordInterrupted(state, e, attempt, attemptStartedAt, attemptModelConfigKey,
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
                attemptDurations.add(new AttemptDuration(attempt,
                        Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                        failure.durations(), attemptModelConfigKey, isChannelInstant(e)));
                runChanges.addAll(failure.changes());
            }
            else if (!attemptAccounted) {
                // 非客户端失败（前段失败）：无可携事实仍零桶——观察面口径见
                // StageDurationFacts
                attemptDurations.add(new AttemptDuration(attempt,
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
    private static void recordInterrupted(RunState state, RuntimeException e, int attempt,
            Instant attemptStartedAt, String attemptModelConfigKey, boolean attemptAccounted) {
        if (e instanceof AgentTurnFailure failure) {
            state.runChanges().addAll(failure.changes());
            if (!attemptAccounted) {
                state.attemptDurations().add(new AttemptDuration(attempt,
                        Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                        failure.durations(), attemptModelConfigKey, /* channelInstant= */ false));
            }
            return;
        }
        if (!attemptAccounted) {
            state.attemptDurations().add(new AttemptDuration(attempt,
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
     * 不是通道级）。#604 起两处消费：设计座的免预算重试判据；失败面逐尝试标注
     * （内核座单发无免预算重试，仅标注）。
     */
    private static boolean isChannelInstant(Throwable failure) {
        return failure instanceof AgentTurnFailure instant && instant.durations().llmMs() == 0;
    }

    /** 通道级秒败免预算重试的退避上限（#477）：指数翻倍封顶，通道故障给上游喘息。 */
    private static final Duration CHANNEL_BACKOFF_CAP = Duration.ofSeconds(30);

    /** 免预算重试第 {@code retry} 次的退避时长（设计座）：起始值指数翻倍、封顶 30s。 */
    private Duration channelBackoffOf(int retry) {
        Duration backoff = properties.getChannelInstantRetryBackoff();
        for (int i = 1; i < retry && backoff.compareTo(CHANNEL_BACKOFF_CAP) < 0; i++) {
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(CHANNEL_BACKOFF_CAP) > 0 ? CHANNEL_BACKOFF_CAP : backoff;
    }

    /**
     * 退避等待（#477）：可中断——中断（停止面 cancel/关停）不吞，恢复中断标志后
     * 返回，由下一轮起跑闸（停止/关停旁路）收口；非正时长直过。
     */
    private static void sleepBackoff(Duration backoff) {
        if (backoff == null || backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 进展现场列出的文件上限（防长清单刷屏——完整清单以工作区现状为准）。 */
    private static final int SCENE_FILES_LISTED = 15;

    /**
     * 重试现场（#323 重试携进展现场，{@link Prompts#retry} 的入参拼装单点——只服务
     * 设计座的续作提示）：错误事实＋平台可观测的进展事实——已干多久（本场累计
     * 墙钟＋已失败尝试计数）/ 干到哪（跨尝试〔含失败尝试〕已落盘的文件变更）。
     * 事实源全在本层（墙钟 / 累计变更账），不由模型自报。
     */
    private static String retryScene(String errorScene, int failedAttempts, int maxAttempts,
            long elapsedMs, List<FileChange> changes) {
        return "错误现场：" + errorScene
                + "\n进展现场（平台可观测事实）：本场已累计用时 " + humanDuration(elapsedMs)
                + "（已失败 " + failedAttempts + '/' + maxAttempts + " 次尝试）"
                + "；已落盘文件变更 " + sceneFiles(changes);
    }

    /** 进展现场的文件清单：同路径合并行数（活动量口径同收口清单），截 {@link SCENE_FILES_LISTED} 件。 */
    private static String sceneFiles(List<FileChange> changes) {
        Map<String, int[]> merged = new TreeMap<>();
        for (FileChange change : changes) {
            int[] lines = merged.computeIfAbsent(change.path(), key -> new int[2]);
            lines[0] += change.added();
            lines[1] += change.removed();
        }
        if (merged.isEmpty()) {
            return "尚无";
        }
        StringBuilder files = new StringBuilder().append(merged.size()).append(" 件：");
        int listed = 0;
        for (Map.Entry<String, int[]> entry : merged.entrySet()) {
            if (listed == SCENE_FILES_LISTED) {
                files.append("…共 ").append(merged.size()).append(" 件（其余见工作区现状）");
                break;
            }
            if (listed > 0) {
                files.append("、");
            }
            files.append(entry.getKey()).append("（+").append(entry.getValue()[0])
                    .append("/-").append(entry.getValue()[1]).append("）");
            listed++;
        }
        return files.toString();
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

    /** 生成轨日志标签（run 的 what 参数值）：收口摘要口径分岔用——调用点同包引用。 */
    static final String GENERATE_LABEL = "generate";

    /** 设计轨日志标签（#289 设计过程轨道）：收口摘要口径分岔用。 */
    static final String DESIGN_LABEL = "design";

    /** 收口扩载载荷的摘要键（对话史收尾卡叙事）。 */
    static final String CLOSING_SUMMARY_FIELD = "summary";

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
     * 收口扩载拼装（#88）：被押后的 run-finish 载荷加 {@code closing} 对象——
     * schema 见 SSE事件清单·收口扩载（对话史落库 #89 复用同一载荷，
     * 拼装单点：SSE 扩载与对话史落库取同一 map）。
     * 判定行 = 收口判据回调返回的权威事实；变更清单 = 工具调用观察（同路径行数
     * 合并——用户面一场 run 的活动量口径）；时长 = 起跑到本收口。设计会话
     * （#289）另扩 {@code drafts} 稿清单（#292 起随判定事实由轨道层拼装——平面稿
     * 渲成 PNG 后以 PNG 为正身；draftsItem 非空＝设计会话开关）。验证统计／
     * 基座 lint／已知缺陷清单随审查与收口组退役（#605）。模型路由（#354）另扩
     * {@code modelTier}——收口 run 的路由判定（哪场用了哪档、为什么：配置 + 来源
     * + 升档理由 + 判定依据快照）；{@code config} 非空才携带（run 执行体座的编码
     * run；设计/其余会话缺省不携带，平台分析口径、前端不渲染——逐尝试配置另见
     * durationBreakdown.attempts[].modelId）。
     */
    private static Map<String, Object> closingPayload(ClosingJudgment judgment,
            List<FileChange> changes, Instant runStartedAt, String what,
            String draftsItem, ModelRoutingAppService.ConfigSelection config) {
        Map<String, Object> closing = new LinkedHashMap<>();
        closing.put(CLOSING_SUMMARY_FIELD, closingSummary(judgment, what));
        closing.put("prdChanged", judgment.prdChanged());
        if (judgment.prdNote() != null) {
            closing.put("prdNote", judgment.prdNote());
        }
        closing.put("systemChanged", judgment.systemChanged());
        if (judgment.systemNote() != null) {
            closing.put("systemNote", judgment.systemNote());
        }
        closing.put("files", filePayloads(changes));
        closing.put("durationMs", Duration.between(runStartedAt, Instant.now()).toMillis());
        if (draftsItem != null) {
            closing.put(CLOSING_DRAFTS_FIELD, judgment.drafts());
        }
        if (judgment.designLint() != null) {
            closing.put(CLOSING_LINT_FIELD, judgment.designLint());
        }
        if (config != null) {
            closing.put(CLOSING_MODEL_CONFIG_FIELD, config.payload());
        }
        return closing;
    }

    /** 收口扩载的稿清单键（#289 设计会话扩载——本轮稿清单与去向）。 */
    static final String CLOSING_DRAFTS_FIELD = "drafts";

    /**
     * 收口扩载的设计规范扫描键（#296：{@code { status, retried?, total?, violations? }}
     * ——带规范项目编码 run 的「按稿对齐」叙事与 lint 结果；无规范项目不携带）。
     */
    static final String CLOSING_LINT_FIELD = "designLint";

    /**
     * 收口扩载的模型路由键（#354：收口 run 的路由判定留档——{@code { modelId,
     * source, reasons?, signals }}；run 执行体座的编码 run 在场、其余缺省不携带。
     * 平台分析口径、前端不渲染，随收尾卡落对话史——「哪场用了哪档、为什么」的
     * 可查留档；逐尝试配置见 {@code durationBreakdown.attempts[].modelId}）。
     */
    static final String CLOSING_MODEL_CONFIG_FIELD = "modelTier";

    /** 设计稿产物目录（工作区锚定前缀——#288 ImageTransfers 落点同一目录，正本
     *  = 工作区布局常量表）。 */
    private static final String DESIGN_DIR_PREFIX = "/" + WorkspaceLayout.DESIGN_DIR + "/";
    /** 同上相对形前缀（write_file 件上报工作区相对形——#306 两形兼容）。 */
    private static final String DESIGN_DIR_RELATIVE_PREFIX =
            WorkspaceLayout.DESIGN_DIR + "/";

    /**
     * design/ 锚定判定（#289 单点——稿清单派生与设计会话收口判据共用，两处口径
     * 不许漂移）：path 两形兼容——锚定形（{@code /design/home-1.html}）与工作区
     * 相对形（{@code design/home-1.html}——write_file 件落稿变更的逐字上报形；
     * 平面类 GenerateImageTool 落盘才显式补锚定形），大小写不敏感（文件系统侧无
     * 大小写约定）。谓词只判锚定、不归一——清单与锚的归一在
     * {@code DesignProcessAppService.designAnchoredPaths}（ProjectFiles.
     * anchoredFormOf），消费面单一锚定形不变。相对形同律判定在
     * {@code ProjectFiles.isDraftHtml}（#293 稿伺服面——domain 层不可上引本包，
     * 同律互指为约）。
     */
    static boolean designAnchored(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith(DESIGN_DIR_PREFIX)
                || lower.startsWith(DESIGN_DIR_RELATIVE_PREFIX);
    }

    /** 界面类稿扩展名（media 形态判据——平面类位图同目录落盘）。 */
    private static final String HTML_MEDIA = "html";

    /** 平面类稿扩展名集合（#292 双路位图：图片模型直出与代码出图渲成的 PNG 同集）。 */
    private static final Set<String> IMAGE_MEDIA_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "webp", "gif", "svg");

    /** 稿形态（按扩展名派生：html＝界面类可交互轻量实现；图片＝平面类位图——
     * 代码出图路渲成的 PNG 同此派生）——首产/改稿收尾卡与定稿收尾卡（#291）
     * 同源派生。 */
    static String mediaOf(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (IMAGE_MEDIA_EXTENSIONS.contains(extension)) {
            return "image";
        }
        return HTML_MEDIA;
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
     * 桶（#453，同级 overhead 口径）+ 生效模型配置（#354 路由留档——哪档可归因）。 */
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

    /** 摘要（判定事实的合并叙事——文档与系统不分侧）：更新轮四类收口各一句、生成/
     * 设计轨道按段叙事。 */
    private static String closingSummary(ClosingJudgment judgment, String what) {
        if (GENERATE_LABEL.equals(what) || DESIGN_LABEL.equals(what)) {
            // 生成轨道（#104）：summary = 本场叙事；设计轨道（#289）：summary = 本场
            // 设计物叙事（完成设计物：X）——note 恒非空（轨道层 ClosingJudgment.
            // generation 同形拼装）
            return judgment.systemNote();
        }
        if (judgment.prdChanged() && judgment.systemChanged()) {
            return "修订了需求文档，并更新了系统";
        }
        if (judgment.prdChanged()) {
            return "修订了需求文档，系统无需改动";
        }
        if (judgment.systemChanged()) {
            return "更新了系统";
        }
        return "本轮系统无需改动";
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
     * 自检播报事件（#85）：平台侧收口判据核验的部件事实（不经引擎部件映射表——
     * 判据是平台事实），payload = runId + sessionId + state。经用户面投影发射。
     */
    private static void emitSelfCheck(Consumer<AgentEvent> projection, AgentCommand command,
            String state) {
        projection.accept(new AgentEvent(AgentEventTypes.PART_CHECK, Map.of(
                AgentEventTypes.RUN_FIELD, command.runId(),
                AgentEventTypes.SESSION_FIELD, command.sessionId(),
                AgentEventTypes.PART_CHECK_STATE_FIELD, state)));
    }

    /**
     * 用户面投影（#84：底座逐次失败补的 {@code error} 事件是失败族的过程事实——
     * 滤掉，用户面只见工作消息正常生长或（失败后）唯一的失败终态
     * {@code run-failed}）。设计轨的中间重试尝试（{@code attemptRunId} 非首试
     * runId）：run-start 不出用户面（重试信号不外泄——用户面 run 身份恒为首试
     * runId）、其余事件归锚到首试 runId。内核座单发恒首试，无归锚需求。
     */
    private static Consumer<AgentEvent> userFacingProjection(String firstRunId,
            String attemptRunId, Consumer<AgentEvent> sink) {
        boolean retryAttempt = !attemptRunId.equals(firstRunId);
        return event -> {
            if (AgentEventTypes.ERROR.equals(event.type())) {
                return;
            }
            if (retryAttempt && AgentEventTypes.RUN_START.equals(event.type())) {
                return;
            }
            sink.accept(retryAttempt ? reanchor(event, firstRunId) : event);
        };
    }

    /** 事件归锚：payload 的 runId 换成用户面标识（首试 runId）；其余字段原样。 */
    private static AgentEvent reanchor(AgentEvent event, String firstRunId) {
        if (firstRunId.equals(event.payload().get(AgentEventTypes.RUN_FIELD))) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.put(AgentEventTypes.RUN_FIELD, firstRunId);
        return new AgentEvent(event.type(), payload);
    }
}
