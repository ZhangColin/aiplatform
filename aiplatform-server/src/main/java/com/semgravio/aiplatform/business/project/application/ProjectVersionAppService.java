package com.semgravio.aiplatform.business.project.application;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionRollbackStartResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersion;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersions;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 版本用例（#91 版本层地基 + #93 回滚 + #626 自动成版／单轴时间线 + #627 异步回滚）：
 * 版本正本 = git log，无便利表；版本列表/详情读面 + 回到某版（异步整回＋build＋
 * 重启＋探活＋记新版，只回代码不回数据）+ 用户面版本自动成版写口。
 *
 * <p><b>自动成版（#626，ADR-0056）</b>：每轮成功生成（首建/迭代）探活通过后，界面
 * 侧经 {@link #commitUserVersion} 把当时工作区提交为用户面版本（{@code Platform-Version}
 * ＋{@code Run-Id} trailer，主题＝用户那句话）；版本序列＝按标记过滤的 git log，
 * 无新表。成版失败降级（日志留痕、不反噬生成）——下一轮成功交付覆盖累计现状。</p>
 *
 * <p><b>回到某版（#627，ADR-0056 回滚面）</b>：{@link #rollback} 是<b>平台侧异步
 * 动作</b>（不是内核 run）：校验版本存在／项目未归档／无 run 在途后立即受理，
 * 后台 restore tracked 文件 → 停 8081 旧进程 → 全新 build（删旧 .next）→ 按平台
 * 起服路径启动 → 8081 探活 → 通过才记为新版本 vN+1（主题「回到 vN」）并发
 * preview-ready。进度复用现有工作消息起止呈现（标题「回到版本 vN」，不新增 SSE
 * 事件类型、不出收尾卡）；失败不记版本、经 run-failed 明确展示原因与两条路。
 * 活库（schema＋数据）不倒带——git 快照不含 data/，只 restore tracked 文件。
 * 只拦 run 在途：脏工作树（含失败 run 残留）允许被回滚覆盖（本阶段有意语义）。</p>
 *
 * <p><b>读面</b>：git log 实时读取（exec 不经业务事务，同 {@code ProjectQueryAppService}
 * 形制）；列表首条＝当前版，confirmed 按项目确认指针映射。</p>
 *
 * <p><b>确认用于签约（#629，ADR-0056 商业面改接）</b>：{@link #confirm} 把项目
 * 确认指针指向<b>当前用户面版本</b>（git 版本号，非旧 Demo/PRD 版本号）——版本号＋
 * 确认时间落项目单指针；换选＝指针随移，重复确认同版幂等（指针不变、时间刷新）。
 * 下单前置「存在已确认用户面版本」与订单冻结的版本引用消费本位。</p>
 */
@Service
@Slf4j
public class ProjectVersionAppService {

    /** 回滚停旧进程（#627）：pkill 模式用 {@code [n]ext} 正则——命令原文含
     * 「next」字面也不匹配自身（否则 sh -c 会杀掉执行命令的自己）；无进程命中
     * 忽略、尾部 true 恒退 0（进程是否真停由后续探活循环判定）。 */
    static final String STOP_APP_COMMAND =
            "pkill -f '[n]ext-server' >/dev/null 2>&1;"
                    + " pkill -f '[n]ext dev' >/dev/null 2>&1;"
                    + " pkill -f '[n]pm start' >/dev/null 2>&1;"
                    + " pkill -f '[n]ode server.js' >/dev/null 2>&1; true";

    /** 停旧进程的强制收尾（#627）：常规停失败后 kill -9 同批模式。 */
    static final String FORCE_STOP_APP_COMMAND =
            "pkill -9 -f '[n]ext-server' >/dev/null 2>&1;"
                    + " pkill -9 -f '[n]ext dev' >/dev/null 2>&1;"
                    + " pkill -9 -f '[n]pm start' >/dev/null 2>&1;"
                    + " pkill -9 -f '[n]ode server.js' >/dev/null 2>&1; true";

    /**
     * 回滚重新构建（#627）：先删旧 {@code .next} 再 {@code pnpm build}——不拿旧构建
     * 产物冒充回滚结果（ADR-0056）；exit 非 0 即回滚失败。
     */
    static final String ROLLBACK_BUILD_COMMAND =
            "cd " + WorkspaceLayout.ROOT + " && rm -rf .next && pnpm build";

    /** 停旧进程的等待窗与轮询节奏（#627）：常规/强制各一窗，超时即回滚失败。 */
    static final long APP_STOP_TIMEOUT_MS = 15_000;
    static final long APP_STOP_POLL_MS = 300;

    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final CodingRunTrack codingRunTrack;
    private final AgentSessionExecutor sessionExecutor;
    private final AgentEventBridge eventBridge;
    private final EventsAppService eventsAppService;
    private final KernelServingGuard kernelServingGuard;
    private final ClosingProbes closingProbes;

    public ProjectVersionAppService(ProjectRepository projectRepository,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            CodingRunTrack codingRunTrack,
            AgentSessionExecutor sessionExecutor,
            AgentEventBridge eventBridge,
            EventsAppService eventsAppService,
            KernelServingGuard kernelServingGuard,
            ClosingProbes closingProbes) {
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.codingRunTrack = codingRunTrack;
        this.sessionExecutor = sessionExecutor;
        this.eventBridge = eventBridge;
        this.eventsAppService = eventsAppService;
        this.kernelServingGuard = kernelServingGuard;
        this.closingProbes = closingProbes;
    }

    /**
     * 版本序列（git log 原生序：新→旧）。零版本 = 空列表非错误。时间线标位：
     * 首条＝当前版（最新交付）；confirmed 按项目确认指针映射版本号（#629 起指针
     * 即用户面版本语义，{@link #confirm} 写入）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；WSP_002 环境故障
     */
    public List<VersionResponse> list(Long projectId) {
        Project project = loadProject(projectId);
        List<WorkspaceVersion> versions = readVersions(project);
        List<VersionResponse> responses = new ArrayList<>(versions.size());
        for (int index = 0; index < versions.size(); index++) {
            WorkspaceVersion version = versions.get(index);
            responses.add(VersionResponse.of(version, index == 0, confirmedOf(project, version)));
        }
        return responses;
    }

    /**
     * 用户面版本自动成版（#626，ADR-0056）：跑前快照（#619）不是用户版本——每轮
     * 生成探活通过后，平台把当时工作区提交为用户面版本（版本号＝已有版本最大值+1，
     * 主题＝用户那句话，正文携版本标记与 run 标识）。<b>成版失败降级</b>：任何
     * exec 失败/回执非 hash 只记日志返回 null，绝不反噬生成——下一轮成功交付的
     * 提交覆盖累计后的工作区现状，时间线可能少一格但不丢代码。
     *
     * @param project 成版项目（工作区锚）
     * @param runId   成版 run 标识（用户面 runId）
     * @param label   版本主题＝用户那句话（调用方已清洗为单行）
     * @return 新版本号（成功）；失败/降级为 null
     */
    public Integer commitUserVersion(Project project, String runId, String label) {
        try {
            List<WorkspaceVersion> existing = readVersions(project);
            int nextVersionNo = existing.stream()
                    .map(WorkspaceVersion::versionNo)
                    .filter(versionNo -> versionNo != null)
                    .max(Integer::compareTo)
                    .orElse(0) + 1;
            ExecResultResponse result = exec(project,
                    WorkspaceVersions.commitVersionCommand(nextVersionNo, runId, label));
            if (result == null || result.exitCode() != 0) {
                log.warn("[version] 项目 {} run {} 用户面版本提交失败（生成照常，下一次成功交付"
                                + "覆盖累计现状）：{}", project.getId(), runId,
                        result == null ? "exec 无结果" : result.stderr());
                return null;
            }
            log.info("[version] 项目 {} run {} 记录用户面版本 v{}（{}）", project.getId(), runId,
                    nextVersionNo, label);
            return nextVersionNo;
        }
        catch (RuntimeException e) {
            log.warn("[version] 项目 {} 用户面版本提交执行异常（生成照常，下一次成功交付覆盖累计"
                    + "现状）：{}", project.getId(), e.toString());
            return null;
        }
    }

    /** git log 读取（零版本 = 空列表；其它非零 = WSP_002 环境故障）。 */
    private List<WorkspaceVersion> readVersions(Project project) {
        ExecResultResponse result = exec(project, WorkspaceVersions.listCommand());
        if (result == null) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "版本列表读取失败: exec 无结果");
        }
        if (result.exitCode() == WorkspaceVersions.EXIT_NO_VERSION) {
            return List.of();
        }
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "版本列表读取失败: " + result.stderr());
        }
        return WorkspaceVersions.parseLog(result.stdout());
    }

    /**
     * 确认用于签约（#629，ADR-0056 商业面改接）：「确认此版本，准备签约」标记
     * <b>当前用户面版本</b>——版本号＋确认时间落项目单指针（不产生新版本、版本
     * 序列零改动）。换选＝指针随移（旧标记自然失效），重复确认同版幂等（指针不变、
     * 确认时间刷新）。只认带版本号的用户面版本（回滚版 versionNo 为 null 不可确认）。
     *
     * @return 所确认版本的读面（含 confirmed=true）
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档（历史版本
     *                              照读、不再确认）；PRJ_028 项目内无该用户面版本号；
     *                              WSP_002 环境故障
     */
    public VersionResponse confirm(Long projectId, int versionNo) {
        Project project = loadProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        List<WorkspaceVersion> versions = readVersions(project);
        int index = indexOfVersion(versions, versionNo);
        if (index < 0) {
            throw new ApplicationException(ProjectMessage.VERSION_NOT_FOUND);
        }
        project.confirmVersion(versionNo);
        projectRepository.save(project);
        return VersionResponse.of(versions.get(index), index == 0, true);
    }

    /** 在版本序列里按用户面版本号定位（回滚版 versionNo 为 null 不入列）；未命中 -1。 */
    private static int indexOfVersion(List<WorkspaceVersion> versions, int versionNo) {
        for (int index = 0; index < versions.size(); index++) {
            Integer candidate = versions.get(index).versionNo();
            if (candidate != null && candidate.intValue() == versionNo) {
                return index;
            }
        }
        return -1;
    }

    /** 确认标记映射（#626/#629）：项目确认指针的版本号＝本用户面版本号即已确认。 */
    private static boolean confirmedOf(Project project, WorkspaceVersion version) {
        return version.versionNo() != null
                && version.versionNo().equals(project.getConfirmedVersionNo());
    }

    /**
     * 版本主题清洗（#626）：用户那句话可能含换行/多空白——折成单行再作提交主题；
     * 空即回落（首建「首次生成」/ 迭代「更新系统」）。
     */
    public static String cleanUserSentence(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String cleaned = raw.replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /**
     * 回到某版（#627，ADR-0056 回滚面）：平台侧异步动作——校验版本存在 → 项目未
     * 归档 → 无 run 在途后立即受理，后台整回代码（只 restore tracked 文件）、停
     * 8081 旧进程、重新 build、按平台起服路径重启、探活；通过才记为新版本 vN+1
     * （主题「回到 vN」）并发 preview-ready。活库不倒带。进度复用工作消息起止呈现，
     * 失败经 run-failed 明确展示（不记版本）。
     *
     * <p>只拦 run 在途：失败 run 留下的未提交 tracked 改动允许被回滚覆盖——回到
     * 某版就是要那份代码（与旧「脏工作树拒绝」不同，本阶段有意语义）。</p>
     *
     * @return 本场回滚的运行标识（SSE 锚）与版本标签
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              PRJ_028 版本不存在（含非 hex 形态 ref，不触
     *                              工作区）；PRJ_031 在途 run；WSP_002 任务提交失败
     */
    public VersionRollbackStartResponse rollback(Long projectId, String ref) {
        Project project = loadProject(projectId);
        WorkspaceVersion target = resolveVersion(project, ref);
        requireRollbackAllowed(project);
        String runId = EventsAppService.newRunId();
        String label = rollbackLabel(target);
        String title = rollbackWorkTitle(target);
        if (!codingRunTrack.begin(projectId)) {
            throw new ApplicationException(ProjectMessage.VERSION_ROLLBACK_RUN_IN_FLIGHT);
        }
        try {
            Future<?> task = sessionExecutor.submitGeneration(
                    rollbackSession(projectId, runId),
                    () -> runRollback(project, target, runId, label, title));
            codingRunTrack.registerTask(projectId, task);
        }
        catch (RuntimeException e) {
            codingRunTrack.end(projectId);
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "回滚任务提交失败: " + e.getMessage());
        }
        log.info("[version] 项目 {} 受理回到 {}（runId={}，异步：restore→停→build→起→探活→成版）",
                projectId, label, runId);
        return new VersionRollbackStartResponse(runId, label);
    }

    /** 回滚会话寻址（coder- 前缀同执行侧口径——工作消息断线补建的判据）。 */
    static String rollbackSession(Long projectId, String runId) {
        return CodingRunDispatcher.SESSION_PREFIX + projectId + "-rollback-" + runId;
    }

    /** 版本标签（成功成版主题）：有版本号＝「回到 vN」，旧回滚版＝「回到「主题」」。 */
    static String rollbackLabel(WorkspaceVersion target) {
        return target.versionNo() != null
                ? "回到 v" + target.versionNo()
                : "回到「" + cleanUserSentence(target.subject(), "该版本") + "」";
    }

    /** 工作消息标题（#627）：有版本号＝「回到版本 vN」，旧回滚版回落主题。 */
    static String rollbackWorkTitle(WorkspaceVersion target) {
        return target.versionNo() != null
                ? "回到版本 v" + target.versionNo()
                : "回到版本「" + cleanUserSentence(target.subject(), "该版本") + "」";
    }

    /**
     * 回滚后台任务（#627）：restore → 停旧进程 → 全新 build → 起服 → 探活 → 成版
     * （成功）／run-failed（失败，不记版本）。工作消息起点/收口/失败复用 SSE 既有
     * 事件；成版失败（提交未落）也判失败——成功判据是「时间线多出 vN+1」。
     */
    private void runRollback(Project project, WorkspaceVersion target, String runId,
            String label, String title) {
        String sessionId = rollbackSession(project.getId(), runId);
        try {
            eventBridge.emitPlatformActionStarted(project, runId, title);
            restoreTrackedFiles(project, target);
            stopOldApp(project);
            buildFresh(project);
            startAndProbe(project);
            Integer versionNo = commitUserVersion(project, runId, label);
            if (versionNo == null) {
                throw new IllegalStateException("新版本没有记录下来（版本提交失败）");
            }
            emitPreviewReadyQuietly(project);
            eventBridge.emitPlatformActionFinished(project, runId, sessionId);
            log.info("[version] 项目 {} run {} 回到 {} 成功，记为新版本 v{}", project.getId(),
                    runId, label, versionNo);
        }
        catch (RuntimeException e) {
            String reason = rollbackReason(e);
            log.warn("[version] 项目 {} run {} 回到 {} 失败（不记版本）：{}", project.getId(),
                    runId, label, reason, e);
            eventBridge.emitPlatformActionFailed(project, runId,
                    rollbackFailureMessage(target, reason));
        }
        finally {
            codingRunTrack.end(project.getId());
        }
    }

    /** 代码整回复位（#627 步一）：只 restore tracked 文件——数据/依赖/.env 原样保留。 */
    private void restoreTrackedFiles(Project project, WorkspaceVersion target) {
        ExecResultResponse result = exec(project,
                WorkspaceVersions.restoreCommand(target.commitHash()));
        if (result == null || result.exitCode() != 0) {
            throw new IllegalStateException("代码没能回到该版本" + execDetail(result));
        }
    }

    /** 停 8081 旧进程（#627 步二）：常规停 → 轮询至端口不可达 → 超时强制停再轮询。 */
    private void stopOldApp(Project project) {
        exec(project, STOP_APP_COMMAND);
        if (awaitAppDown(project)) {
            return;
        }
        exec(project, FORCE_STOP_APP_COMMAND);
        if (awaitAppDown(project)) {
            return;
        }
        throw new IllegalStateException("旧的应用进程没能停下来");
    }

    /** 轮询 8081 至不可达（真 = 已停）；超时返回 false。 */
    private boolean awaitAppDown(Project project) {
        long deadline = System.currentTimeMillis() + APP_STOP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            ExecResultResponse probe = exec(project, GenerationAppService.CLOSING_PROBE);
            if (probe != null && probe.exitCode() != 0) {
                return true;
            }
            if (!sleep(APP_STOP_POLL_MS)) {
                throw new IllegalStateException("回滚被中断");
            }
        }
        return false;
    }

    /** 全新构建（#627 步三）：删旧 .next 再 pnpm build——不拿旧产物冒充。 */
    private void buildFresh(Project project) {
        ExecResultResponse result = exec(project, ROLLBACK_BUILD_COMMAND);
        if (result == null || result.exitCode() != 0) {
            throw new IllegalStateException("重新构建失败" + execDetail(result));
        }
    }

    /** 起服＋探活（#627 步四/五）：平台起服路径拉起 → 8081 有响应才算起来。 */
    private void startAndProbe(Project project) {
        kernelServingGuard.ensureServing(project.getWorkspaceId());
        try {
            closingProbes.requireReachable(project.getWorkspaceId());
        }
        catch (IllegalStateException unreachable) {
            throw new IllegalStateException("应用没有起来（8081 无响应）");
        }
    }

    /**
     * preview-ready 推送（#627 成功面，尽力而为）：取平台预览 URL 并推既有通知——
     * 失败只记日志，不反噬回滚成功（版本已落、时间线经 run-finish 失效刷新）。
     */
    private void emitPreviewReadyQuietly(Project project) {
        try {
            URI url = workspaceLifecycleAppService.exposePreview(
                    Long.toString(project.getWorkspaceId()));
            eventsAppService.publishNotification(ProjectEventTypes.PREVIEW_READY, Map.of(
                    ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                    ProjectEventTypes.URL_FIELD, url.toString(),
                    EventsAppService.OWNER_FIELD,
                    EventsAppService.ownerPayload(project.getOwnerAccountId()),
                    EventsAppService.OWNER_GUEST_FIELD,
                    EventsAppService.ownerPayload(project.getOwnerGuestId())));
        }
        catch (RuntimeException e) {
            log.warn("[version] 项目 {} 回滚成功但 preview-ready 推送失败（UI 面，不影响版本）：{}",
                    project.getId(), e.getMessage());
        }
    }

    /** 回滚守卫（#627）：项目未归档＋无 run 在途——不拦脏树（有意允许覆盖）。 */
    private void requireRollbackAllowed(Project project) {
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        if (codingRunTrack.isInFlight(project.getId())) {
            throw new ApplicationException(ProjectMessage.VERSION_ROLLBACK_RUN_IN_FLIGHT);
        }
    }

    /** exec 回执的细节片段（失败原因人话）：stderr/stdout 首个非空行，截断。 */
    private static String execDetail(ExecResultResponse result) {
        if (result == null) {
            return "（执行通道无回执）";
        }
        String detail = firstNonBlankLine(result.stderr());
        if (detail == null) {
            detail = firstNonBlankLine(result.stdout());
        }
        return detail == null ? "（退出码 " + result.exitCode() + "）" : "（" + truncate(detail) + "）";
    }

    private static String firstNonBlankLine(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return null;
    }

    /** 回滚失败原因的呈现值（异常消息 / 类名，定宽截断）。 */
    private static String rollbackReason(RuntimeException e) {
        String message = e.getMessage();
        String reason = message != null && !message.isBlank()
                ? message.strip() : e.getClass().getSimpleName();
        return truncate(reason);
    }

    /** 失败终态人话（#627）：原因＋两条恢复路——再点最新版回去，或提一句修改意见。 */
    static String rollbackFailureMessage(WorkspaceVersion target, String reason) {
        String versionText = target.versionNo() != null
                ? "v" + target.versionNo()
                : "「" + cleanUserSentence(target.subject(), "该版本") + "」";
        return "没能回到 " + versionText + "：" + reason
                + "。你可以再点一次最新版的「回到此版」回到最新版，"
                + "或在对话里说一句修改意见，让系统按现有数据重新修好。";
    }

    private static String truncate(String text) {
        return text.length() <= 160 ? text : text.substring(0, 159) + "…";
    }

    /** 可中断休眠（true = 睡完；false = 被中断）。 */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 版本详情：版本元数据（closing 恒空——Version 与收尾卡的 Run-Id 联接随
     * 收口成版提交退役，#605；字段留至阶段四版本重建）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_028 版本不存在（含非
     *                              hash 形态 ref——用户可控入参不进 shell）；
     *                              WSP_002 环境故障
     */
    public VersionDetailResponse detail(Long projectId, String ref) {
        Project project = loadProject(projectId);
        return VersionDetailResponse.of(resolveVersion(project, ref), null);
    }

    /** ref → 版本 commit 元数据（showCommand + 解析）；非版本 / 野 commit / 非 hex 一律 PRJ_028。 */
    private WorkspaceVersion resolveVersion(Project project, String ref) {
        ExecResultResponse result;
        try {
            result = exec(project, WorkspaceVersions.showCommand(ref));
        } catch (IllegalArgumentException malformedRef) {
            throw new ApplicationException(ProjectMessage.VERSION_NOT_FOUND);
        }
        if (result.exitCode() == WorkspaceVersions.EXIT_NO_VERSION) {
            throw new ApplicationException(ProjectMessage.VERSION_NOT_FOUND);
        }
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "版本详情读取失败: " + result.stderr());
        }
        List<WorkspaceVersion> parsed = WorkspaceVersions.parseLog(result.stdout());
        if (parsed.isEmpty()) {
            // rev-parse 过了但条目被过滤 = 野 commit（无版本 trailer）——不是版本
            throw new ApplicationException(ProjectMessage.VERSION_NOT_FOUND);
        }
        return parsed.get(0);
    }

    /** exec 通道出口（命令构造归 {@link WorkspaceVersions} 纯函数）。 */
    private ExecResultResponse exec(Project project, String command) {
        return workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(command));
    }

    private Project loadProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
