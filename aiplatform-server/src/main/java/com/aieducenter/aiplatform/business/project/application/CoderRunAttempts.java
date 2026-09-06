package com.aieducenter.aiplatform.business.project.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSuspension;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.FileChange;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.infrastructure.agentscope.ConfirmingShellTool;
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
 * 续派的中途超限不是终态，本层不判），用户侧兜底——生成重新发起 / 修正恢复出口
 * 重派或再提意见（#48）。
 *
 * <p>命令全要素同构：执行体配置（{@link AgentProfile#EXECUTOR}）、
 * {@code coder-{projectId}} 会话（重试续同会话——已落盘成果保留，同工作区不丢
 * 数据）、owner 寻址、长 run 超时、计量 dims（agentKind=executor）、项目工作区、
 * 流关联。知识命中前置注入只进首试 prompt（一次下发一次注入，重试不重检索不重
 * 块）。流桥挂步骤边界探活装饰（#49 逐修改刷新：part-step 边界 → 探活 →
 * preview-updated 通知）。</p>
 *
 * <p><b>权限确认挂起（#83）</b>：run 内需批准的工具操作（危险命令）以
 * {@code permission-required} 事件呈现确认卡后流软终点——本环在挂起点驻留
 * （{@link RunPermissionAppService#await}，持有会话执行器 stripe；作答由权限
 * 作答通道在请求线程直接唤醒，无自锁），批准/拒绝即以 ConfirmResult 续跑同
 * run（拒绝语义 = 引擎写 DENIED 工具结果回模型，改道或自行收口）。挂起期间
 * 轨道不收口、不重试（不是尝试失败）、不排空队列——run 仍在途，意见照常排队
 * 合并。挂起会合与作答校验的用户面锚 = 首试 runId（与投影后事件同锚）。</p>
 */
@Component
@Slf4j
class CoderRunAttempts {

    /** 编码会话标识派生前缀（projectId → coder-{projectId}，稳定绑定勿动）。 */
    public static final String SESSION_PREFIX = "coder-";

    /** 一场编码 run 的 prompt 对（首试 + 重试续作轨）。 */
    record Prompts(String first, String retry) {
    }

    /**
     * 收口判定的权威事实（#88 判定行）：PRD 改没改（生成轮恒未动；更新轮 = 交接物
     * 的修订说明）+ 系统改没改（生成轮 = 探活收口产出；更新轮 = finish_edit 工具
     * 事实）+ 各自说明。事实源在收口判据回调（onSuccess）——判定跟职责走，本环
     * 只拼装不判定。
     */
    record ClosingJudgment(boolean prdChanged, String prdNote, boolean systemChanged,
            String systemNote) {

        /**
         * 生成轨道的分段判定（#104）：PRD 未动、系统产出；note = 本段叙事（阶段 0
         * 起服骨架 / 完成切片），进收尾卡 summary（版本成版主题同源）。
         */
        static ClosingJudgment generation(String note) {
            return new ClosingJudgment(false, null, true, note);
        }
    }

    /**
     * 一场编码 run 的收场事实：成败（终态收口事件 run-failed 的用户面锚 = 调用方
     * 持有的首试 runId，#84——重试不换新锚，本层不再回传末次尝试的内部标识）。
     */
    record RunResult(boolean succeeded) {
    }

    private final AgentscopeAgentClient agentClient;
    private final AgentEventBridge eventBridge;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final GenerationProperties properties;
    private final StepBoundaryPreviewRefresh previewRefresh;
    private final RunPermissionAppService permissions;
    private final ConversationHistoryAppService conversationHistory;
    private final ProjectVersionAppService versions;

    CoderRunAttempts(AgentscopeAgentClient agentClient,
            AgentEventBridge eventBridge, ProjectKnowledgeAppService knowledgeAppService,
            GenerationProperties properties, StepBoundaryPreviewRefresh previewRefresh,
            RunPermissionAppService permissions, ConversationHistoryAppService conversationHistory,
            ProjectVersionAppService versions) {
        this.agentClient = agentClient;
        this.eventBridge = eventBridge;
        this.knowledgeAppService = knowledgeAppService;
        this.properties = properties;
        this.previewRefresh = previewRefresh;
        this.permissions = permissions;
        this.conversationHistory = conversationHistory;
        this.versions = versions;
    }

    /**
     * 跑一场编码 run（有限次尝试）：成功收口即 {@code onSuccess}（收口回调携该次
     * 尝试的 runId，返回收口判定的权威事实——#88 判定行；回调抛异常即该次尝试
     * 失败，走重试/终态——收口判据不满足的既有口径，如生成 8081 核验 / 修正
     * finish_edit 事实）。项目事实（工作区 / owner）从聚合派生。
     *
     * <p><b>收口扩载（#88）</b>：真收口释放被押后的 run-finish 时拼装 {@code closing}
     * 载荷——摘要（判定事实的合并叙事）/ 判定行（onSuccess 返回的权威事实）/ 变更
     * 清单（{@link AgentReply#changes()} 的文件级观察，跨尝试同路径合并）/ 轮末
     * 统计（时长 = 首试起跑到收口）。咨询/纯追问轮不经本环，run-finish 无扩载
     * （无收尾卡）。</p>
     *
     * @param what       日志标签（generate / fix）
     * @param firstRunId 首试 runId（调用方预生成随响应回 = 用户面 run 身份，全程
     *                   不变；重试尝试的内部 runId 不出用户面——经投影归一）
     * @return           收场事实（成败）；超限转终态后的兜底归轨道层——终态收口
     *                   事件 run-failed 锚首试 runId，与生成重新发起 / 修正恢复
     *                   出口（#48/#56）衔接
     */
    RunResult run(Project project, String firstRunId, Prompts prompts,
            Function<String, ClosingJudgment> onSuccess, String what) {
        Long projectId = project.getId();
        String knowledgePrefix = knowledgeAppService.dispatchInjection(prompts.first());
        int maxAttempts = properties.getMaxAttempts();
        Instant runStartedAt = Instant.now();
        List<FileChange> runChanges = new ArrayList<>();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptRunId = attempt == 1 ? firstRunId : EventsAppService.newRunId();
            AgentCommand command = new AgentCommand(
                    attemptRunId,
                    attempt == 1 ? knowledgePrefix + prompts.first() : prompts.retry(),
                    AgentProfile.EXECUTOR.systemPrompt(),
                    AgentProfile.EXECUTOR.chatModelString(),
                    SESSION_PREFIX + projectId,
                    project.ownerUserId(),
                    new UsageContext(Long.toString(projectId),
                            UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.EXECUTOR),
                                    SESSION_PREFIX + projectId)),
                    Long.toString(project.getWorkspaceId()),
                    Map.of(EventsAppService.PROJECT_FIELD, projectId.toString()),
                    properties.getTimeout(),
                    AgentProfile.EXECUTOR.key(),
                    /* workspaceReadOnly= */ false);
            try {
                // 逐修改刷新（#49）：事件桥 sink 外包步骤边界探活装饰——part-step 边界
                // （完整修改落定）→ 平台侧探活 → 通过才发 preview-updated 通知
                Consumer<AgentEvent> projection = userFacingProjection(firstRunId, attemptRunId,
                        previewRefresh.decorate(
                                projectId, project.getWorkspaceId(), eventBridge.sink(projectId)));
                // run-finish 押后到收口判据落定（#84 假完成不闪收口）：converse 正常
                // 返回 ≠ 收口（判据在 onSuccess——8081 核验 / finish_edit 事实），判据
                // 不过即该次尝试失败走重试——中场 run-finish 若出用户面，工作消息定格
                // 了又生长（重试信号外泄）、run-failed 前出现假收口
                AtomicReference<AgentEvent> pendingFinish = new AtomicReference<>();
                // 自测观察（#96）：自测子智能体（source=self-test）的 command 动作按
                // toolCallId 去重计数，收口计入 closing.selfTest.total——判定以平台可
                // 观测的命令动作事实为准（自测跑了几项命令），不解析子智能体自由文本。
                // 每次尝试各起一账（重试尝试的自测随尝试失败作废，只成功尝试的自测
                // 进收尾统计）
                Set<String> selfTestCommands = new LinkedHashSet<>();
                Consumer<AgentEvent> sink = event -> {
                    if (AgentEventTypes.RUN_FINISH.equals(event.type())) {
                        pendingFinish.set(event);
                        return;
                    }
                    observeSelfTest(event, selfTestCommands);
                    projection.accept(event);
                };
                List<FileChange> attemptChanges = new ArrayList<>();
                AgentReply reply = agentClient.converse(command, sink);
                attemptChanges.addAll(reply.changes());
                settlePermissions(command, reply, sink, firstRunId, attemptChanges);
                runChanges.addAll(attemptChanges);
                // 自检播报（#85）：收口判据核验（onSuccess——生成 8081 探活 / 修正
                // finish_edit 事实，复用既有收口链路、不新增探针）的呈现——核验前
                // 「检查中」、落定出结果，位于被押后的 run-finish 之前（收口前播报）。
                // 静默重试同构口径：尝试间核验未过不出 ❌（部件停在「检查中」——重试
                // 信号不外泄，重试核验再发「检查中」前端幂等）；❌ 仅在末次尝试未过
                //（超限转终态）时出，与轨道层 run-failed 同窗口。状态终值 = 探活结果，
                // 随智能体事件族进重放缓冲，可被收尾统计消费（#88 轮末统计行）
                emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_CHECKING);
                ClosingJudgment judgment;
                try {
                    judgment = onSuccess.apply(attemptRunId);
                }
                catch (RuntimeException e) {
                    if (attempt == maxAttempts) {
                        emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_FAILED);
                    }
                    throw e;
                }
                emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_PASSED);
                if (pendingFinish.get() != null) {
                    Map<String, Object> closing = closingPayload(judgment, runChanges,
                            runStartedAt, what, selfTestCommands);
                    // 版本锚定（#91）：收口自动成版——git commit 的 Run-Id trailer
                    // 锚定收尾卡，commit hash 回填 closing 的 version 键（SSE 扩载与
                    // 对话史落库同载荷，版本详情复用）。成版失败 quietly 只记日志
                    // （缺 version 键 = 本轮未成版，run 收口不受影响）
                    String versionHash = versions.commitAtClosing(project, firstRunId,
                            (String) closing.get(CLOSING_SUMMARY_FIELD));
                    if (versionHash != null) {
                        closing.put(CLOSING_VERSION_FIELD, versionHash);
                    }
                    // 对话史落库（#89 收尾卡腿）：先落库后发 run-finish——事件即触发
                    // 前端对话史域失效重拉（水合按 run 整体接管 live 片段），次序反转
                    // 会让重拉撞上未落库的空窗（code-review #89）
                    conversationHistory.recordClosing(projectId, firstRunId, closing);
                    projection.accept(withClosing(pendingFinish.get(), closing));
                }
                return new RunResult(true);
            }
            catch (RuntimeException e) {
                log.warn("[{}] 项目 {} 第 {}/{} 次尝试失败（attemptRunId={}）：{}",
                        what, projectId, attempt, maxAttempts, attemptRunId, e.toString());
            }
        }
        log.error("[{}] 项目 {} 重试超限（{} 次），转终态失败——用户侧兜底（生成重新发起/修正恢复出口）",
                what, projectId, maxAttempts);
        return new RunResult(false);
    }

    /** 生成轨日志标签（run 的 what 参数值）：收口摘要口径分岔用——调用点同包引用。 */
    static final String GENERATE_LABEL = "generate";

    /** 收口扩载载荷的摘要键（成版提交主题 + 版本详情叙事同源）。 */
    static final String CLOSING_SUMMARY_FIELD = "summary";

    /** 收口扩载载荷的版本键（#91 收口自动成版回填的 commit hash；成版失败缺省）。 */
    static final String CLOSING_VERSION_FIELD = "version";

    /**
     * 收口扩载拼装（#88）：被押后的 run-finish 载荷加 {@code closing} 对象——
     * schema 见 SSE事件清单·收口扩载（对话史落库 #89 与版本锚定 #91 复用同一载荷，
     * 拼装单点：SSE 扩载与对话史落库取同一 map）。
     * 判定行 = 收口判据回调返回的权威事实；变更清单 = 工具调用观察（同路径跨尝试
     * 行数合并——用户面一场 run 的活动量口径）；时长 = 首试起跑到本收口。
     */
    private static Map<String, Object> closingPayload(ClosingJudgment judgment,
            List<FileChange> changes, Instant runStartedAt, String what,
            Set<String> selfTestCommands) {
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
        Map<String, Object> selfTest = selfTestStatistic(selfTestCommands);
        if (selfTest != null) {
            closing.put(AgentEventTypes.SELF_TEST_FIELD, selfTest);
        }
        return closing;
    }

    /**
     * 自测观察（#96）：自测子智能体（source=self-test）的 command 动作部件按
     * toolCallId 去重入账（started/running/completed/failed 任一态即记一条——同
     * toolCallId 跨态只记一次，Set 天然去重）。只在自测子智能体的命令动作上落账：
     * 执行体自身（source 缺省）的 command 不计、读类工具不进部件（无账可记）。
     * 只记「自测跑了几项命令」——命令工具的成败态不反映测试成败（非零退出码仍是
     * completed），平台不据此伪报通过/未过（不粉饰、不瞎判）。
     */
    private static void observeSelfTest(AgentEvent event, Set<String> selfTestCommands) {
        if (!AgentEventTypes.PART_ACTION.equals(event.type())) {
            return;
        }
        if (!ProfileSubagentSupplier.SELF_TEST_NAME
                .equals(event.payload().get(AgentEventTypes.SOURCE_FIELD))) {
            return;
        }
        if (!ConfirmingShellTool.NAME
                .equals(event.payload().get(AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD))) {
            return;
        }
        selfTestCommands.add(String.valueOf(
                event.payload().get(AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD)));
    }

    /**
     * 自测统计（#96 收尾卡轮末统计）：{@code { total }}——自测子智能体跑了几项
     * 测试命令。无自测命令动作（自测子智能体未跑）返回 {@code null}（closing 不
     * 携带 selfTest 键，前端缺省不显示自测统计行）。逐项 ✅/❌ 明细在过程播报，
     * 收尾卡只带聚合计数。
     */
    private static Map<String, Object> selfTestStatistic(Set<String> selfTestCommands) {
        if (selfTestCommands.isEmpty()) {
            return null;
        }
        return Map.of(AgentEventTypes.SELF_TEST_TOTAL_FIELD, selfTestCommands.size());
    }

    /** 被押后的 run-finish 加挂 closing 载荷（载荷拼装归 {@link #closingPayload}）。 */
    private static AgentEvent withClosing(AgentEvent finish, Map<String, Object> closing) {
        Map<String, Object> payload = new LinkedHashMap<>(finish.payload());
        payload.put(AgentEventTypes.CLOSING_FIELD, closing);
        return new AgentEvent(finish.type(), payload);
    }

    /** 摘要（判定事实的合并叙事——文档与系统不分侧）：更新轮四类收口各一句、生成轨道按段叙事。 */
    private static String closingSummary(ClosingJudgment judgment, String what) {
        if (GENERATE_LABEL.equals(what)) {
            // 生成轨道（#104）：summary = 本段叙事（阶段 0 起服骨架 / 完成切片，来自
            // {@link ClosingJudgment#generation(String)} 的 note——恒非空）
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
     * 权限确认驻留与续跑（#83）：挂起（软终点）即等作答——批准/拒绝以 ConfirmResult
     * 续跑同 run（命令全要素同构，恢复私货从本环命令原样携带），续跑可再挂起
     * （一 run 多确认点）。问答挂起在编码 run 不可达（执行体无 ask_user 工具），
     * 防御即失败（走尝试环重试，最终 run-failed——不静默错频道）。
     *
     * @param userRunId 用户面 run 身份（首试 runId，#84）——挂起会合与作答校验的
     *                  锚，与投影后事件同锚（前端按所见 runId 作答）
     * @param changes  本尝试的文件变更观察累积口（#88 收口扩载——续跑段的变更
     *                  与首段同场，随 attempt 一并计入）
     */
    private AgentReply settlePermissions(AgentCommand command, AgentReply reply,
            Consumer<AgentEvent> sink, String userRunId, List<FileChange> changes) {
        while (reply.suspension() != null && reply.suspension().permission()) {
            AgentSuspension suspension = reply.suspension();
            boolean approved = permissions.await(suspension.engineRef(), userRunId);
            reply = agentClient.resume(permissionResume(command, suspension, approved), sink);
            changes.addAll(reply.changes());
        }
        if (reply.suspension() != null) {
            throw new IllegalStateException(
                    "编码 run 出现提问挂起（执行体无 ask_user 工具，不可达）：engineRef="
                            + reply.suspension().engineRef());
        }
        return reply;
    }

    /**
     * 权限作答的续跑请求：命令全要素同构（会话/配置/计量/工作区原样——run 上下文
     * 不因确认点漂移），ConfirmResult 按批准位重建（拒绝 = confirmed=false，引擎写
     * DENIED 工具结果回模型）。续跑文本给模型明确的决策反馈与拒绝后的出路
     * （改道或如实收口），不替模型做决定。
     */
    private static AgentResume permissionResume(AgentCommand command, AgentSuspension suspension,
            boolean approved) {
        return new AgentResume(
                command.runId(),
                command.sessionId(),
                command.userId(),
                command.workspaceId(),
                command.modelString(),
                command.systemPrompt(),
                suspension.engineRef(),
                suspension.toolCalls().stream()
                        .map(toolCall -> AgentscopeAgentClient.confirmedToolCall(toolCall, approved))
                        .toList(),
                approved
                        ? "用户已批准该操作，请继续执行并完成本轮任务。"
                        : "用户已拒绝该操作。",
                command.usageContext(),
                command.agentKey(),
                command.workspaceReadOnly());
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
