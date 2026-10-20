package com.aieducenter.aiplatform.business.project.application;

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
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentTurnFailure;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.FileChange;
import com.aieducenter.aiplatform.base.agentscope.RunHeading;
import com.aieducenter.aiplatform.base.agentscope.StageDurations;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.infrastructure.agentscope.ProfileSubagentSupplier;


import lombok.extern.slf4j.Slf4j;

/**
 * 编码 run 尝试环（生成与更新共用，#22 落位 / #26 迭代环复用——所有编码 run 同
 * 机制）：失败有余量<b>静默续试</b>（#84：中间错误与重试信号不出用户面事件流，
 * <b>用户面 run 身份 = 首试 runId 全程不变</b>——重试尝试的内部 runId 逐次换新仅
 * 服务计量幂等键与日志，经用户面投影 {@link #userFacingProjection} 归一、重试不
 * 新发 run-start；run-finish 押后到收口判据落定——假完成不闪中场收口，判据核验
 * 出自检播报 part-check「检查中 → ✅/❌」（#85）；run 失败
 * 是唯一失败终态），超限转终态失败——终态收口事件
 * {@code run-failed} 由<b>轨道层</b>在真终态落定点发射（#56：修正轨道排队合并
 * 续派的中途超限不是终态，本层不判），用户侧兜底——生成「继续生成」断点续跑 /
 * 修正恢复出口重派或再提意见（#48/#221）。
 *
 * <p>命令全要素同构：执行体配置（{@link AgentProfile#EXECUTOR}——systemPrompt/
 * 模型档位经运营配置读面 {@link AgentConfigAppService} 取生效值，#251 库值优先、
 * 缺省回落枚举默认）、会话寻址由
 * 轨道层拼装传入（#114 每片/每 run 换会话——重试续本会话，已落盘成果保留，同
 * 工作区不丢数据）、owner 寻址、长 run 超时、计量 dims（agentKind=executor）、
 * 项目工作区、流关联。知识命中前置注入只进首试 prompt（一次下发一次注入，重试
 * 不重检索不重块）。</p>
 *
 * <p><b>错误重试＝原地修（#221）</b>：失败有余量的续试不是从头重做——新尝试的
 * prompt 由上次尝试的错误现场现拼（{@link Prompts#retry}），执行体带着错误事实
 * 在已有成果上继续修（本片已对的工作不丢弃）。超时判死类失败随确认机制删除而
 * 不复存在（#219）；重试耗尽转终态失败，末次错误现场随 {@link RunResult#lastError}
 * 回传轨道层（生成轨续跑现状盘点的「中断原因」旁证）。</p>
 *
 * <p><b>段失败回滚重跑（#353 外环——生成轨传 {@link SegmentRollback} 钩时生效）</b>：
 * 原地修重试耗尽不在坏现场叠修——回调轨道层「失败现场留账（.platform patch
 * 落盘——回滚不丢证据）＋工作区 git 干净回退本段起点」，然后以原首试 prompt
 * （干净起点重放——不携错误现场、知识注入不重复）换会话重跑一轮（预算一轮，
 * 防烧护栏；重跑轮所有尝试走重试投影——attemptRunId 全新保计量幂等键、run-start
 * 滤掉、事件归锚首试 runId，用户面零感知）。重跑轮再败则二次回滚后转终态——
 * 「终态时工作区 = 段起点」不变式，用户续跑从干净现场起。修正/设计/合并裁决轨
 * 不传钩（各轨既有失败处置口径不变）。</p>
 *
 * <p><b>失败留账与重试携进展现场（#323）</b>：失败尝试的耗时账与观察面不清零
 * 弃置——客户端流失败经 {@link AgentTurnFailure} 携部分观察（已提交文件变更＋
 * 已闭合区间阶段耗时）上浮，本环计入本场累计（收口清单与耗时分布跨尝试含失败
 * 尝试）；重试上文＝{@link #retryScene}（错误事实＋进展现场——已干多久/干到哪的
 * 平台可观测事实：累计用时、已落盘文件变更、最近计划快照），裸异常原文不再是
 * 唯一现场。超限转终态时耗时账随终态日志留档（平台观测面——用户面呈现归等待态
 * 线，#314）。</p>
 *
 * <p><b>破坏性命令直通（#219 透明面化）</b>：执行体命令走内核 shell 工具
 * （无确认自检——沙箱可随时销毁重建，确认机制连代码带概念已删），过程照常经
 * 动作部件播报（命令原值滚动行，#228）对用户透明可见。</p>
 */
@Component
@Slf4j
class CoderRunAttempts {

    /** 编码会话标识派生前缀（projectId → coder-{projectId}；#114 每片/每 run 换会话，会话寻址规则归各轨道拼装）。 */
    public static final String SESSION_PREFIX = "coder-";

    /**
     * 一场编码 run 的 prompt 对（首试 + 重试续作轨）。重试 prompt 的入参是
     * <b>重试现场</b>（{@link #retryScene}——#221 错误现场 + #323 进展现场：已干
     * 多久/干到哪的平台可观测事实），轨道层只给拼装函数（现场拼装归本环单点）。
     */
    record Prompts(String first, UnaryOperator<String> retry) {
    }

    /**
     * 尝试环的执行体座席（#289 designer 座并入，两座共用同一环）：配置档位寻址
     * （{@link AgentProfile#EXECUTOR} / {@link AgentProfile#DESIGNER}——systemPrompt
     * 与模型档位经运营配置读面取生效值、计量 dims agentKind 同键）、工作区面
     * （designer 无 shell——ProjectDesign 形态，写文件件在、命令执行结构性关闭）、
     * 收口成版（执行体真收口自动成版；设计<b>候选</b>不成版——版本流只认定稿，
     * ADR-0025 候选与版本两套语义不打通）、收尾卡稿清单开关（designer 会话扩载
     * 「本轮稿清单与去向」——条目随判定事实由轨道层拼装，#292；null = 非设计
     * 会话不扩载）。
     */
    record RunSeat(AgentProfile profile, boolean workspaceNoShell,
            boolean commitVersionAtClosing, String draftsItem) {

        /** run 执行体座（生成/更新轨道）。 */
        static RunSeat executor() {
            return new RunSeat(AgentProfile.EXECUTOR, false, true, null);
        }

        /** 设计执行体座（#289 设计过程轨道）：无 shell、候选不成版、收尾卡带稿清单。 */
        static RunSeat designer(String draftsItem) {
            return new RunSeat(AgentProfile.DESIGNER, true, false, draftsItem);
        }
    }

    /**
     * 收口判定的权威事实（#88 判定行）：PRD 改没改（生成轮恒未动；更新轮 = 交接物
     * 的修订说明）+ 系统改没改（生成轮 = 探活收口产出；更新轮 = finish_edit 工具
     * 事实）+ 各自说明。事实源在收口判据回调（onSuccess）——判定跟职责走，本环
     * 只拼装不判定。设计轨道另携 {@code drafts} 稿清单（#292：轨道层在收口点拼装
     * ——平面稿渲成 PNG 后以 PNG 为正身的事实面，非设计会话恒 null）；编码轨道
     * 另携 {@code designLint} 设计规范扫描事实（#296：token 合规是机器可判的
     * <b>验证事实</b>不是判定分类——判定出口零改，仅随收尾卡如实呈现；带规范
     * 项目的编码 run 在场，非规范项目/设计会话恒 null）。
     */
    record ClosingJudgment(boolean prdChanged, String prdNote, boolean systemChanged,
            String systemNote, List<Map<String, Object>> drafts,
            Map<String, Object> designLint) {

        /**
         * 生成轨道的分段判定（#104）：PRD 未动、系统产出；note = 本段叙事（阶段 0
         * 起服骨架 / 完成切片），进收尾卡 summary（版本成版主题同源）。
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
         * 修正轨道的分段判定（#26）：判定事实随交接物与 finish_edit 事实直拼
         * （稿清单恒无——设计会话才有）。
         */
        static ClosingJudgment update(boolean prdChanged, String prdNote,
                boolean systemChanged, String systemNote) {
            return new ClosingJudgment(prdChanged, prdNote, systemChanged, systemNote, null, null);
        }

        /** 附设计规范扫描事实（#296 收口回调拼装：非规范项目传 null＝不携带）。 */
        ClosingJudgment withDesignLint(Map<String, Object> lint) {
            return new ClosingJudgment(prdChanged, prdNote, systemChanged, systemNote, drafts, lint);
        }
    }

    /**
     * 段级失败回滚钩（#353 失败回滚本段起点）：尝试环（原地修重试，#221）耗尽后由
     * 环回调——轨道层实现「失败现场留账（.platform patch 落盘——回滚不丢证据）＋
     * 工作区 git 干净回退本段起点（前段收口 commit 不动）」；返回重跑轮的<b>新会话
     * 寻址</b>（旧会话带失败循环历史不续，同 #221 续跑新会话口径），{@code null} =
     * 回滚不成（段起点缺失/exec 失败）不重跑、直接终态。环至多重跑一轮（防烧预算；
     * 再败则二次回调回滚——「终态时工作区 = 段起点」不变式，用户续跑从干净现场起
     * ——然后转终态）。钩不在场（修正/设计/合并裁决轨）行为与既有口径一致。
     */
    interface SegmentRollback {

        /** 留账 + 回滚本段起点；返回重跑轮新会话寻址（null = 回滚不成不重跑）。 */
        String rollback();
    }

    /**
     * 一场编码 run 的收场事实：成败（终态收口事件 run-failed 的用户面锚 = 调用方
     * 持有的首试 runId，#84——重试不换新锚，本层不再回传末次尝试的内部标识）+ 收口
     * 时的执行体终文（{@code closingText}，成功收口才非 null——生成轨把它当交接
     * 摘要用，#114 片间交接：前片执行体自产的「做了什么/关键文件/下一片须知」即其
     * 收口终文；修正轨不用）+ 末次尝试的错误现场（{@code lastError}，终态失败才
     * 非 null——生成轨续跑现状盘点的「中断原因」旁证，#221）。
     */
    record RunResult(boolean succeeded, String closingText, String lastError) {

        static RunResult ok(String closingText) {
            return new RunResult(true, closingText, null);
        }

        static RunResult failed(String lastError) {
            return new RunResult(false, null, lastError);
        }
    }

    /**
     * 一次尝试的耗时账（#111 阶段耗时分布）：尝试序号 + 尝试墙钟 + 该尝试跨流段
     * （converse + 续跑）合并的阶段耗时事实。墙钟口径：成功/判据未过的尝试 =
     * converse 起跑 → 续跑落定（核验前——核验起计收口尾序桶，判据未过的核验时间
     * 未归因，探活类量级小）；中段崩的尝试 = 起跑到异常点（含全部在途），桶 =
     * 该流段已闭合区间的部分观察（#323 失败留账——AgentTurnFailure 携带；非客户
     * 端失败〔前段失败/判据回调抛错〕无可携事实仍零桶，观察面口径见
     * StageDurationFacts）。
     */
    private record AttemptDuration(int attempt, long wallMs, StageDurations durations) {
    }

    private final AgentscopeAgentClient agentClient;
    private final AgentEventBridge eventBridge;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final AgentConfigAppService agentConfigs;
    private final GenerationProperties properties;
    private final ConversationHistoryAppService conversationHistory;
    private final ProjectVersionAppService versions;
    private final VerificationFacts verificationFacts;

    CoderRunAttempts(AgentscopeAgentClient agentClient,
            AgentEventBridge eventBridge, ProjectKnowledgeAppService knowledgeAppService,
            AgentConfigAppService agentConfigs, GenerationProperties properties,
            ConversationHistoryAppService conversationHistory,
            ProjectVersionAppService versions, VerificationFacts verificationFacts) {
        this.agentClient = agentClient;
        this.eventBridge = eventBridge;
        this.knowledgeAppService = knowledgeAppService;
        this.agentConfigs = agentConfigs;
        this.properties = properties;
        this.conversationHistory = conversationHistory;
        this.versions = versions;
        this.verificationFacts = verificationFacts;
    }

    /**
     * 跑一场编码/设计 run（有限次尝试，座席 {@link RunSeat} 定配置与工具面）：
     * 成功收口即 {@code onSuccess}（收口回调携该次尝试的 runId 与本场累计文件
     * 变更事实——对偶 8081 探活看的是工作区累计状态，重试尝试不因「本次没新写」
     * 误判；返回收口判定的权威事实——#88 判定行；回调抛异常即该次尝试失败，走
     * 重试/终态——收口判据不满足的既有口径，如生成 8081 核验 / 修正 finish_edit
     * 事实 / 设计会话 design/ 落稿核验）。项目事实（工作区 / owner）从聚合派生。
     *
     * <p><b>收口扩载（#88）</b>：真收口释放被押后的 run-finish 时拼装 {@code closing}
     * 载荷——摘要（判定事实的合并叙事）/ 判定行（onSuccess 返回的权威事实）/ 变更
     * 清单（{@link AgentReply#changes()} 的文件级观察，跨尝试同路径合并）/ 轮末
     * 统计（时长 = 首试起跑到收口）/ 阶段耗时分布（#111 durationBreakdown——
     * {@link AgentReply#durations()} 的阶段耗时事实跨尝试汇总 + 收口尾序掐表，
     * 平台分析口径、前端不渲染）。咨询/纯追问轮不经本环，run-finish 无扩载
     * （无收尾卡）。</p>
     *
     * @param what           日志标签（generate / fix）
     * @param firstRunId     首试 runId（调用方预生成随响应回 = 用户面 run 身份，全程
     *                       不变；重试尝试的内部 runId 不出用户面——经投影归一）
     * @param sessionId      本场 run 的会话寻址（#114 每片/每 run 换会话——重试续
     *                       本会话，会话标识由轨道层按寻址规则拼装传入，不再固定
     *                       {@code coder-{projectId}}）
     * @param injectKnowledge 是否做知识命中前置注入（#24/#114「一次切入一次注入」：
     *                       生成链只在首片注入，切片与重试不注入；修正每场各注入
     *                       一次——注入只进首试 prompt，重试不重检索不重块）
     * @param heading        工作消息头部标题（#118：标题 + 生成轨道切片进度；可空
     *                       ——主智能体对话轮不经本环）——随 run-start 的 slice 字段
     *                       透出，首试与重试同值（重试 run-start 被投影滤掉，不碍）
     * @return               收场事实（成败 + 收口终文）；超限转终态后的兜底归轨道层
     *                       ——终态收口事件 run-failed 锚首试 runId，与生成「继续生成」续跑
     *                       / 修正恢复出口（#48/#56/#221）衔接
     */
    RunResult run(Project project, String firstRunId, String sessionId, Prompts prompts,
            BiFunction<String, List<FileChange>, ClosingJudgment> onSuccess, String what,
            boolean injectKnowledge, RunSeat seat, RunHeading heading) {
        return run(project, project.getWorkspaceId(), firstRunId, sessionId, prompts, onSuccess,
                what, injectKnowledge, seat, heading);
    }

    /**
     * 同 {@link #run}，但显式指定执行工作区（#351 切片并行——fork 副本 run 的执行面
     * 与收口成版落在 fork 工作区，非项目主工作区）；计量/事件归属仍用 projectId。
     */
    RunResult run(Project project, Long workspaceId, String firstRunId, String sessionId,
            Prompts prompts, BiFunction<String, List<FileChange>, ClosingJudgment> onSuccess,
            String what, boolean injectKnowledge, RunSeat seat, RunHeading heading) {
        return run(project, workspaceId, firstRunId, sessionId, prompts, onSuccess, what,
                injectKnowledge, seat, heading, null);
    }

    /**
     * 全参形（#353 段失败回滚）：{@code rollback} 在场时，首轮尝试环耗尽 → 回调轨道层
     * 留账＋回滚本段起点 → 以原首试 prompt（干净起点重放——不携错误现场、知识注入
     * 不重复）换会话重跑一轮；重跑轮所有尝试走重试投影（attemptRunId 全新——计量
     * 幂等键不撞首轮；run-start 滤掉、事件归锚首试 runId，用户面零感知）。重跑轮
     * 再耗尽则二次回滚后终态（不变式：终态时工作区 = 段起点，续跑从干净现场起）。
     * {@code null} = 无段级回滚（修正/设计/合并裁决轨——既有口径不变）。
     */
    RunResult run(Project project, Long workspaceId, String firstRunId, String sessionId,
            Prompts prompts, BiFunction<String, List<FileChange>, ClosingJudgment> onSuccess,
            String what, boolean injectKnowledge, RunSeat seat, RunHeading heading,
            SegmentRollback rollback) {
        Long projectId = project.getId();
        String knowledgePrefix = injectKnowledge
                ? knowledgeAppService.dispatchInjection(prompts.first()) : "";
        int maxAttempts = properties.getMaxAttempts();
        Instant runStartedAt = Instant.now();
        // 本场变更账（成果面）：收口清单的事实源。重跑轮起跑清空——回滚后首轮改动
        // 已不在工作区，收口清单只认重跑轮真实增量（首轮现场证据在轨道层留账 patch）
        List<FileChange> runChanges = new ArrayList<>();
        // 验证事实清残留（#352）：本轨验证统计从零起算——上一轨/生成 run 的遗留
        // 不进本轨收尾卡（生命周期对偶 FinishEditFacts 的轨道起跑清残留）
        verificationFacts.clear(Long.toString(workspaceId));
        // 上次尝试的错误现场（#221 原地修）：重试 prompt 的携带物——首试为 null，
        // 每次失败刷新为该次的错误事实
        String previousError = null;
        // 阶段耗时分布（#111）：每次尝试各起一账——尝试墙钟 + 跨流段合并的阶段耗时
        // 事实（收口判据核验起的时间计收口尾序桶，成功尝试才进）。跨轮累计（成本面
        // 不随回滚抹去——失败留账 #323 口径）
        List<AttemptDuration> attemptDurations = new ArrayList<>();
        // 最近计划快照（#323 重试携进展现场）：执行体自产计划（part-plan 全量快照）
        // 的最新一帧——重试续本会话、计划跨尝试连续，失败后重试现场据此携带
        //「干到哪」（✓●○ 计划状态是平台可观测事实，不由模型自报）
        AtomicReference<List<Map<String, Object>>> planSteps = new AtomicReference<>();
        // lint 违规修轮已用标记（#296 有限自动重试一轮）：一轮内至多一次——首轮
        // 收口扫描违规即喂回执行体原地修，之后（修净或仍违规）如实收口。重跑轮重置
        boolean lintFixRoundUsed = false;
        // 段级外环（#353）：钩在场时至多重跑一轮（预算 1——回滚重跑还不行说明不是
        // 现场脏的问题，转终态让人介入）；无钩恒一轮（既有口径）
        int maxRounds = rollback != null ? 2 : 1;
        String roundSessionId = sessionId;
        boolean reran = false;
        // 待决核验失败播报（#85 口径随 #353 外环延展）：末次尝试核验未过只记待决——
        // 首轮末次不过还有重跑轮（信号不外泄，部件停「检查中」）；确定终态（降级
        // break 或全轮耗尽）才补发 ❌（降级路径不悬挂）
        boolean failedCheckPending = false;
        Consumer<AgentEvent> failedCheckProjection = null;
        AgentCommand failedCheckCommand = null;
        for (int round = 1; round <= maxRounds; round++) {
            if (round > 1) {
                String rerunSession = rollback.rollback();
                if (rerunSession == null) {
                    break; // 回滚不成（段起点缺失/exec 失败）——不重跑，照首轮现场终态
                }
                reran = true;
                roundSessionId = rerunSession;
                // 重跑轮起跑面重置：验证统计（本论验证从零起算）/ 错误现场（首试纯净
                // 重放）/ 计划快照（新会话新计划）/ lint 修轮（新一轮预算）/ 成果变更账
                verificationFacts.clear(Long.toString(workspaceId));
                runChanges.clear();
                previousError = null;
                planSteps.set(null);
                lintFixRoundUsed = false;
                // 首轮的待决 ❌ 作废（新一轮重跑——重跑核验再发「检查中」前端幂等）
                failedCheckPending = false;
                failedCheckProjection = null;
                failedCheckCommand = null;
                log.info("[{}] 项目 {} 首轮重试耗尽——工作区已回滚本段起点，新会话重跑一轮",
                        what, projectId);
            }
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                Instant attemptStartedAt = Instant.now();
                AtomicReference<StageDurations> durations =
                        new AtomicReference<>(StageDurations.zero());
                boolean attemptAccounted = false;
                // 重跑轮全部尝试（含首试）用新内部 runId：计量幂等键（runId 前缀）不撞
                // 首轮，且投影层按「重试尝试」滤 run-start、归锚（用户面零感知）
                String attemptRunId = round == 1 && attempt == 1
                        ? firstRunId : EventsAppService.newRunId();
                AgentConfigAppService.EffectiveConfig agent =
                        agentConfigs.effectiveOf(seat.profile());
                AgentCommand command = new AgentCommand(
                        attemptRunId,
                        attempt == 1 ? (round == 1 ? knowledgePrefix : "") + prompts.first()
                                : prompts.retry().apply(retryScene(previousError, attempt - 1,
                                        maxAttempts,
                                        Duration.between(runStartedAt, Instant.now()).toMillis(),
                                        runChanges, planSteps.get())),
                        agent.systemPrompt(),
                        agent.chatModelString(),
                        roundSessionId,
                        project.ownerUserId(),
                        new UsageContext(Long.toString(projectId),
                                UsageDims.of(projectId, UsageDims.kindOf(seat.profile()),
                                        roundSessionId)),
                        Long.toString(workspaceId),
                        Map.of(EventsAppService.PROJECT_FIELD, projectId.toString()),
                        /* timeout= */ null, // #322 活超时替换一次性总墙钟
                        properties.getSilenceTimeout(),
                        seat.profile().key(),
                        /* workspaceReadOnly= */ false,
                        seat.workspaceNoShell(),
                        heading,
                        agent.toolSpec());
            try {
                Consumer<AgentEvent> projection = userFacingProjection(firstRunId, attemptRunId,
                        eventBridge.sink(project));
                // run-finish 押后到收口判据落定（#84 假完成不闪收口）：converse 正常
                // 返回 ≠ 收口（判据在 onSuccess——8081 核验 / finish_edit 事实），判据
                // 不过即该次尝试失败走重试——中场 run-finish 若出用户面，工作消息定格
                // 了又生长（重试信号外泄）、run-failed 前出现假收口
                AtomicReference<AgentEvent> pendingFinish = new AtomicReference<>();
                Consumer<AgentEvent> sink = event -> {
                    if (AgentEventTypes.RUN_FINISH.equals(event.type())) {
                        pendingFinish.set(event);
                        return;
                    }
                    observePlanSteps(event, planSteps);
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
                durations.accumulateAndGet(reply.durations(), StageDurations::plus);
                attemptChanges.addAll(reply.changes());
                runChanges.addAll(attemptChanges);
                // 尝试墙钟止于核验前（收口判据核验起计收口尾序桶）；本尝试账先记，
                // 核验/收口段抛错不重记（attemptAccounted 守卫）
                attemptDurations.add(new AttemptDuration(attempt,
                        Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                        durations.get()));
                attemptAccounted = true;
                // 自检播报（#85）：收口判据核验（onSuccess——生成 8081 探活 / 修正
                // finish_edit 事实，复用既有收口链路、不新增探针）的呈现——核验前
                // 「检查中」、落定出结果，位于被押后的 run-finish 之前（收口前播报）。
                // 静默重试同构口径：尝试间核验未过不出 ❌（部件停在「检查中」——重试
                // 信号不外泄，重试核验再发「检查中」前端幂等）；末次尝试核验未过记
                // 待决（#353 重跑轮在场不算终态），终态落定时补发 ❌、与轨道层
                // run-failed 同窗口。状态终值 = 探活结果，随智能体事件族进重放缓冲，
                // 可被收尾统计消费（#88 轮末统计行）
                emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_CHECKING);
                Instant closingStartedAt = Instant.now(); // 收口尾序起表（探活 → 样式扫描 → 成版）
                ClosingJudgment judgment;
                try {
                    judgment = onSuccess.apply(attemptRunId, runChanges);
                }
                catch (RuntimeException e) {
                    // 末次尝试核验未过只记待决 ❌（重跑轮在场不算终态——确定终态时
                    // 由外环补发，降级 break 不悬挂）
                    if (attempt == maxAttempts) {
                        failedCheckPending = true;
                        failedCheckProjection = projection;
                        failedCheckCommand = command;
                    }
                    throw e;
                }
                // lint 违规有限自动重试一轮（#296 遵守三件套③，软约束不当硬门）：
                // 判据已过（8081/finish_edit）、样式扫描有违规且修轮未用、尝试还有
                // 余量 → 违规清单喂回执行体原地修一轮（对偶 #221 错误现场续试——
                // 静默重试信号不外泄，part-check 停「检查中」、PASSED 不抢先播报）；
                // 一轮用尽仍违规＝如实随收尾卡列违规项收口（不静默不假装达标，
                // 也不 run 失败）
                Map<String, Object> lint = judgment.designLint();
                if (lint != null && !lintViolations(lint).isEmpty()
                        && !lintFixRoundUsed && attempt < maxAttempts) {
                    lintFixRoundUsed = true;
                    previousError = lintRetryScene(lint);
                    log.info("[{}] 项目 {} 样式合规扫描未过，自动修一轮（attemptRunId={}，违规 {} 处）",
                            what, projectId, attemptRunId, lint.get("total"));
                    continue;
                }
                emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_PASSED);
                if (pendingFinish.get() != null) {
                    // lint 修轮事实（#296）：曾自动修一轮即标记 retried——收尾卡如实
                    // 叙事「曾发现违规、修一轮后通过/仍余 N 处」，不抹掉纠偏过程
                    if (judgment.designLint() != null && lintFixRoundUsed) {
                        Map<String, Object> retried = new LinkedHashMap<>(judgment.designLint());
                        retried.put(CLOSING_LINT_RETRIED_FIELD, true);
                        judgment = judgment.withDesignLint(retried);
                    }
                    Map<String, Object> closing = closingPayload(judgment, runChanges,
                            runStartedAt, what,
                            verificationFacts.consume(Long.toString(workspaceId)),
                            seat.draftsItem());
                    // 版本锚定（#91）：收口自动成版——git commit 的 Run-Id trailer
                    // 锚定收尾卡，commit hash 回填 closing 的 version 键（SSE 扩载与
                    // 对话史落库同载荷，版本详情复用）。成版失败 quietly 只记日志
                    // （缺 version 键 = 本轮未成版，run 收口不受影响）。设计会话
                    // 不成版（ADR-0025 候选与版本两套语义不打通——只有定稿成版）
                    if (seat.commitVersionAtClosing()) {
                        String versionHash = versions.commitAtClosing(workspaceId, projectId,
                                firstRunId, (String) closing.get(CLOSING_SUMMARY_FIELD));
                        if (versionHash != null) {
                            closing.put(CLOSING_VERSION_FIELD, versionHash);
                        }
                    }
                    // 阶段耗时分布（#111）：收口尾序止表于成版（落库自指不可测——载荷
                    // 先于落库定型，量级极小忽略）；四桶齐备 + 逐尝试分布
                    closing.put(CLOSING_BREAKDOWN_FIELD, durationBreakdown(attemptDurations,
                            Duration.between(closingStartedAt, Instant.now()).toMillis()));
                    // 对话史落库（#89 收尾卡腿）：先落库后发 run-finish——事件即触发
                    // 前端对话史域失效重拉（水合按 run 整体接管 live 片段），次序反转
                    // 会让重拉撞上未落库的空窗（code-review #89）
                    conversationHistory.recordClosing(projectId, firstRunId, closing);
                    projection.accept(withClosing(pendingFinish.get(), closing));
                }
                return RunResult.ok(reply.text());
            }
            catch (RuntimeException e) {
                previousError = errorScene(e);
                if (e instanceof AgentTurnFailure failure) {
                    // 失败留账（#323）：失败流段的部分观察面（已提交文件变更＋已闭合
                    // 区间阶段耗时）计入本场累计——收口清单与耗时分布跨尝试含失败
                    // 尝试，不清零弃置（已落盘的写是真实工作区事实）
                    durations.accumulateAndGet(failure.durations(), StageDurations::plus);
                    runChanges.addAll(failure.changes());
                }
                if (!attemptAccounted) {
                    // 中段失败（converse/续跑抛错）：尝试账带墙钟与该流段已闭合区间
                    // 的部分观察（AgentTurnFailure 携带；非客户端失败无可携事实仍
                    // 零桶——观察面口径见 StageDurationFacts）
                    attemptDurations.add(new AttemptDuration(attempt,
                            Duration.between(attemptStartedAt, Instant.now()).toMillis(),
                            durations.get()));
                }
                log.warn("[{}] 项目 {} 第 {}/{} 次尝试失败（attemptRunId={}）：{}",
                        what, projectId, attempt, maxAttempts, attemptRunId, e.toString());
            }
        }
        }
        // 终态前再回滚（#353 不变式：终态时工作区 = 段起点——用户「继续生成」续跑
        // 从干净现场起，不是坏现场叠修）；重跑轮没跑过（回滚不成 break——现场已是
        // 首轮回滚失败态）不再二次回调
        if (reran) {
            rollback.rollback();
        }
        // 待决 ❌ 补发（确定终态）：末次核验未过的部件从「检查中」落定——先于
        // 轨道层 run-failed 到达（同终态窗口）
        if (failedCheckPending) {
            emitSelfCheck(failedCheckProjection, failedCheckCommand,
                    AgentEventTypes.PART_CHECK_STATE_FAILED);
        }
        // 终态留档（#323 失败留账）：超限转终态、没有成功收尾卡时，耗时账与观察面
        // 仍随终态日志留档（平台观测面）——累计墙钟＋逐尝试墙钟与桶＋已落盘文件
        // 清单，失败看得见成本；SSE 封闭集与 run-failed 载荷不变
        log.error("[{}] 项目 {} 重试超限（{} 次尝试{}），转终态失败——本场留账：累计 {}、"
                        + "逐尝试 {}、已落盘文件变更 {}。用户侧兜底（生成「继续生成」续跑/修正恢复出口/设计重提意见续跑）",
                what, projectId, maxAttempts * (reran ? 2 : 1),
                reran ? "，含回滚后重跑一轮" : "",
                humanDuration(Duration.between(runStartedAt, Instant.now()).toMillis()),
                attemptDurations.stream().map(CoderRunAttempts::attemptPayload).toList(),
                filePayloads(runChanges));
        return RunResult.failed(previousError);
    }

    /**
     * 错误现场（#221 原地修的携带物）：异常消息优先、无消息退类名——异常事实
     * 原样携带，平台不加工不隐瞒（执行体据此定位修什么）。
     */
    private static String errorScene(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * 进展现场列出的文件上限（防长清单刷屏——完整清单以重试起手的现状盘点为准，
     * 对偶 {@code MAX_LINT_LISTED} 截断先例）。
     */
    private static final int SCENE_FILES_LISTED = 15;

    /**
     * 重试现场（#323 重试携进展现场，{@link Prompts#retry} 的入参拼装单点）：
     * 错误事实（#221 原地修携带物）＋平台可观测的进展事实——已干多久（本场累计
     * 墙钟＋已失败尝试计数）/ 干到哪（跨尝试〔含失败尝试〕已落盘的文件变更＋最近
     * 计划快照的 ✓●○ 状态）。事实源全在本环（墙钟 / 累计变更账 / part-plan 事件
     * 观察），不由模型自报；裸异常原文不再是重试上文的唯一现场。
     */
    private static String retryScene(String errorScene, int failedAttempts, int maxAttempts,
            long elapsedMs, List<FileChange> changes, List<Map<String, Object>> planSteps) {
        StringBuilder scene = new StringBuilder("错误现场：").append(errorScene);
        scene.append("\n进展现场（平台可观测事实）：本场已累计用时 ")
                .append(humanDuration(elapsedMs))
                .append("（已失败 ").append(failedAttempts).append('/').append(maxAttempts)
                .append(" 次尝试）；已落盘文件变更 ").append(sceneFiles(changes));
        if (planSteps != null && !planSteps.isEmpty()) {
            scene.append("；最近计划进度：").append(planProgress(planSteps));
        }
        return scene.toString();
    }

    /**
     * 计划快照观察（#323 进展现场「干到哪」）：part-plan 事件载全量快照
     * （快照式整表替换），取最新一帧存用——重试续本会话、计划跨尝试连续，最新
     * 快照即当前进度（与 {@link #observeSelfTest} 同族的 sink 观察，事件面事实）。
     */
    @SuppressWarnings("unchecked")
    private static void observePlanSteps(AgentEvent event,
            AtomicReference<List<Map<String, Object>>> latest) {
        if (!AgentEventTypes.PART_PLAN.equals(event.type())) {
            return;
        }
        Object steps = event.payload().get(AgentEventTypes.PART_PLAN_STEPS_FIELD);
        if (steps instanceof List<?> list) {
            latest.set(list.stream()
                    .filter(step -> step instanceof Map)
                    .map(step -> (Map<String, Object>) step)
                    .toList());
        }
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

    /** 进展现场的计划进度：逐步骤 ✓/●/○ ＋ 标题（状态词表即部件契约三态）。 */
    private static String planProgress(List<Map<String, Object>> steps) {
        return steps.stream()
                .map(step -> {
                    String state = String.valueOf(
                            step.get(AgentEventTypes.PART_PLAN_STEP_STATE_FIELD));
                    String mark = AgentEventTypes.PART_PLAN_STATE_COMPLETED.equals(state) ? "✓"
                            : AgentEventTypes.PART_PLAN_STATE_IN_PROGRESS.equals(state) ? "●" : "○";
                    return mark + " " + step.get(AgentEventTypes.PART_PLAN_STEP_TITLE_FIELD);
                })
                .collect(Collectors.joining(" / "));
    }

    /** 人读时长（进展现场与终态留档共用）：秒 → 分秒 → 时分，向下取整。 */
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

    /** 收口扩载载荷的摘要键（成版提交主题 + 版本详情叙事同源）。 */
    static final String CLOSING_SUMMARY_FIELD = "summary";

    /** 收口扩载载荷的版本键（#91 收口自动成版回填的 commit hash；成版失败缺省）。 */
    static final String CLOSING_VERSION_FIELD = "version";

    /**
     * 收口扩载的阶段耗时分布键（#111）：{@code { llmMs, toolsMs, verifyMs,
     * closingMs, attempts[] }}——平台分析口径（时长优化归因用），前端不渲染
     * （收尾卡呈现不变，只有「用时」durationMs）。
     */
    static final String CLOSING_BREAKDOWN_FIELD = "durationBreakdown";

    /**
     * 委派工具注册名（harness 子智能体发起面）：其执行跨距 ≈ 被委执子智能体的事件
     * 窗——工具桶除名防双计（验证时长由 verifyMs 桶承载）。
     */
    private static final String AGENT_SPAWN_TOOL = "agent_spawn";

    /**
     * 收口扩载拼装（#88）：被押后的 run-finish 载荷加 {@code closing} 对象——
     * schema 见 SSE事件清单·收口扩载（对话史落库 #89 与版本锚定 #91 复用同一载荷，
     * 拼装单点：SSE 扩载与对话史落库取同一 map）。
     * 判定行 = 收口判据回调返回的权威事实；变更清单 = 工具调用观察（同路径跨尝试
     * 行数合并——用户面一场 run 的活动量口径）；时长 = 首试起跑到本收口。设计会话
     * （#289）另扩 {@code drafts} 稿清单（#292 起随判定事实由轨道层拼装——平面稿
     * 渲成 PNG 后以 PNG 为正身；draftsItem 非空＝设计会话开关）。验证统计
     * （#352）另扩 {@code verification}——事实源＝verify 子智能体的
     * report_verification 登记（{@code verification} 参数；null＝未跑/未登记，
     * 如实缺省不携带）。
     */
    private static Map<String, Object> closingPayload(ClosingJudgment judgment,
            List<FileChange> changes, Instant runStartedAt, String what,
            VerificationFacts.Facts verification, String draftsItem) {
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
        Map<String, Object> verificationPayload = verificationPayload(verification);
        if (verificationPayload != null) {
            closing.put(AgentEventTypes.VERIFICATION_FIELD, verificationPayload);
        }
        if (draftsItem != null) {
            closing.put(CLOSING_DRAFTS_FIELD, judgment.drafts());
        }
        if (judgment.designLint() != null) {
            closing.put(CLOSING_LINT_FIELD, judgment.designLint());
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

    /** 扫描载荷的「曾自动修一轮」键（修轮用尽即标——纠偏过程如实）。 */
    static final String CLOSING_LINT_RETRIED_FIELD = "retried";

    /**
     * 扫描事实里的违规清单（形状由 {@code DesignSpecAppService#lintClosingPayload}
     * 平台拼装——此处只读不计；passed/unavailable 无清单＝空）。
     */
    private static List<Map<String, Object>> lintViolations(Map<String, Object> lint) {
        Object violations = lint.get("violations");
        if (violations instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> violation = (Map<String, Object>) item;
                        return violation;
                    })
                    .toList();
        }
        return List.of();
    }

    /**
     * lint 违规重试现场（#296 喂回执行体的错误现场——对偶 #221 原地修携带物）：
     * 违规清单逐条原样（file:line 规则：message——执行体据此定位修），不加工
     * 不截短（清单在扫描载荷处已截 {@code MAX_LINT_LISTED}）。
     */
    private static String lintRetryScene(Map<String, Object> lint) {
        StringBuilder scene = new StringBuilder(
                "设计规范样式合规扫描未过（样式一律引用语义 token 或品牌色 token，细则见工作区"
                        + " DESIGN.md）——修掉以下违规再收口：");
        List<Map<String, Object>> violations = lintViolations(lint);
        for (int i = 0; i < violations.size(); i++) {
            Map<String, Object> violation = violations.get(i);
            scene.append('\n').append(i + 1).append(". ").append(violation.get("file"))
                    .append(':').append(violation.get("line")).append(' ')
                    .append(violation.get("rule")).append("：").append(violation.get("message"));
        }
        return scene.toString();
    }

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
     * 阶段耗时分布装配（#111）：四桶齐备（LLM 等待 = 跨尝试模型调用累计 / 工具执行
     * = 按工具名分桶·command 按命令归组嵌套 / 验证 = verify 委派窗 / 收口尾序 =
     * 探活 + 样式合规扫描（#296）+ 成版）+ 逐尝试分布（静默重试代价可归因——每次
     * 尝试的墙钟与桶各自带）。
     * 与 durationMs 的一致性口径：桶计 + 未归因差值（平台管道、判据未过的核验等）
     * = durationMs；closingMs 含成版而 durationMs 窗口不含（小正
     * 偏差）——量级不符即埋点有洞（缝测守卫）。
     */
    private static Map<String, Object> durationBreakdown(List<AttemptDuration> attempts,
            long closingMs) {
        StageDurations total = attempts.stream()
                .map(AttemptDuration::durations)
                .reduce(StageDurations.zero(), StageDurations::plus);
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("llmMs", total.llmMs());
        breakdown.put("toolsMs", toolsPayload(total));
        breakdown.put("verifyMs", verifyMs(total));
        breakdown.put("closingMs", closingMs);
        breakdown.put("attempts", attempts.stream()
                .map(CoderRunAttempts::attemptPayload)
                .toList());
        return breakdown;
    }

    /** 逐尝试分布条目：尝试序号 + 尝试墙钟 + 该尝试的三桶（无收口尾序——归 run 级）。 */
    private static Map<String, Object> attemptPayload(AttemptDuration attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempt", attempt.attempt());
        payload.put("durationMs", attempt.wallMs());
        payload.put("llmMs", attempt.durations().llmMs());
        payload.put("toolsMs", toolsPayload(attempt.durations()));
        payload.put("verifyMs", verifyMs(attempt.durations()));
        return payload;
    }

    /**
     * 工具桶载荷：按工具名分桶（{@link #AGENT_SPAWN_TOOL} 委派调用除名——其跨距 ≈
     * 子智能体事件窗，入桶即与验证桶双计）；command 工具以命令归组对象嵌套
     * （install / dev / test / other——只携带出现过的组）。
     */
    private static Map<String, Object> toolsPayload(StageDurations durations) {
        Map<String, Object> tools = new LinkedHashMap<>();
        durations.toolsMs().forEach((toolName, ms) -> {
            if (!AGENT_SPAWN_TOOL.equals(toolName)) {
                tools.put(toolName, ms);
            }
        });
        if (!durations.commandMs().isEmpty()) {
            tools.put("command", new LinkedHashMap<>(durations.commandMs()));
        }
        return tools;
    }

    /** 验证桶 = verify 委派窗（无验证 = 0——四桶齐备，缺省出 0 不缺席）。 */
    private static long verifyMs(StageDurations durations) {
        return durations.subagentMs()
                .getOrDefault(ProfileSubagentSupplier.VERIFY_NAME, 0L);
    }

    /**
     * 验证统计（#352 收尾卡轮末统计）：{@code { total, failed, faces{browser,db,log},
     * rounds, failedItems[] }}——事实源＝verify 子智能体的 report_verification 登记：
     * 末轮快照的总项数/未过数/三面分布/未过项名（修不了如实标注的落点）＋本场验证
     * 轮数（修复—复验环如实：>1＝修后复验过）。验证子智能体未跑/未登记（null）
     * 返回 {@code null}（closing 不携带 verification 键，前端缺省不显示验证统计行）。
     * 逐项 ✅/❌ 明细与证据在过程播报，收尾卡只带聚合。
     */
    private static Map<String, Object> verificationPayload(VerificationFacts.Facts facts) {
        if (facts == null || facts.lastRound().isEmpty()) {
            return null;
        }
        List<VerificationFacts.Check> lastRound = facts.lastRound();
        Map<String, Integer> faceCounts = new LinkedHashMap<>();
        List<String> failedItems = new ArrayList<>();
        for (String face : VerificationFacts.FACES) {
            faceCounts.put(face, 0);
        }
        for (VerificationFacts.Check check : lastRound) {
            faceCounts.merge(check.face(), 1, Integer::sum);
            if (!check.ok()) {
                failedItems.add(check.name());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AgentEventTypes.VERIFICATION_TOTAL_FIELD, lastRound.size());
        payload.put(AgentEventTypes.VERIFICATION_FAILED_FIELD, failedItems.size());
        payload.put(AgentEventTypes.VERIFICATION_FACES_FIELD, faceCounts);
        payload.put(AgentEventTypes.VERIFICATION_ROUNDS_FIELD, facts.rounds());
        payload.put(AgentEventTypes.VERIFICATION_FAILED_ITEMS_FIELD, failedItems);
        return payload;
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
            // 生成轨道（#104）：summary = 本段叙事（阶段 0 起服骨架 / 完成切片）；
            // 设计轨道（#289）：summary = 本场设计物叙事（完成设计物：X）——note
            // 恒非空（轨道层 ClosingJudgment.generation 同形拼装）
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
     * 判据是平台事实），payload = runId + sessionId + state。经用户面投影发射——
     * 重试尝试归锚首试 runId（用户面 run 身份不变）。
     */
    private static void emitSelfCheck(Consumer<AgentEvent> projection, AgentCommand command,
            String state) {
        projection.accept(new AgentEvent(AgentEventTypes.PART_CHECK, Map.of(
                AgentEventTypes.RUN_FIELD, command.runId(),
                AgentEventTypes.SESSION_FIELD, command.sessionId(),
                AgentEventTypes.PART_CHECK_STATE_FIELD, state)));
    }

    /**
     * 静默重试的用户面投影（#84：中间错误与重试信号不出用户面事件流——用户面
     * run 身份 = 首试 runId 全程不变）：① 底座逐次失败补的 {@code error} 事件
     * 是重试族的过程事实——滤掉；② 重试尝试的 {@code run-start}（重开场 + 内部
     * 重试 prompt 文本）——滤掉；③ 重试尝试的事件 payload runId 归一到首试
     * runId（内部 attempt runId 仅服务计量幂等键与日志，不出用户面）。用户面
     * 只见工作消息正常生长或（超限后）唯一的失败终态 {@code run-failed}。
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
