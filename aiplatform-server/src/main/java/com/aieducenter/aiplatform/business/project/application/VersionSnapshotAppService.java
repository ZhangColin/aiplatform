package com.aieducenter.aiplatform.business.project.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.domain.model.SnapshotHandle;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionViewStartResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 「查看当时」用例（#92，ADR 0007 解路二）：任一历史版本起运行态快照容器跑当时
 * 代码 + 现在数据，用户可操作浏览，逛完关闭即回现在、不产生任何变更（卷只读 +
 * 写入只落副本，随容器销毁消失）。
 *
 * <p><b>编排</b>：ref 寻址守卫（{@link ProjectVersionAppService#requireVersion}——
 * 非成版 / 非 hex 一律 404，用户可控入参不进 shell）→ 并发上限守卫（ADR 0007
 * 建议 ≤2，v1 单用户无并发多开场景，check-then-act 可接受）→ 环境后端起快照
 * （数据副本 + 检出当时代码 + 起应用）→ 返回 viewId + 预览 URL。</p>
 *
 * <p><b>在途注册表</b>（进程内）：projectId → (viewId → 会话)，供并发上限、关闭
 * 动作寻址与闲置计时（#171 清扫）。物理销毁级联（主容器销毁时在途快照随卷删前
 * 清空）归环境后端（{@code DockerEnvironmentBackend} 按命名前缀扫清），本层注册表
 * 仅服务业务上限与关闭幂等——工作区销毁后残留的注册表条目随下次关闭动作 no-op
 * 清掉，无害。</p>
 */
@Service
@Slf4j
public class VersionSnapshotAppService {

    /** 同项目并发快照上限（ADR 0007 建议 ≤2；每会话一份数据副本占磁盘）。 */
    static final int MAX_CONCURRENT_VIEWS = 2;

    private final ProjectRepository projectRepository;
    private final ProjectVersionAppService versionAppService;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    private final Map<Long, Map<String, ActiveView>> views = new ConcurrentHashMap<>();

    public VersionSnapshotAppService(ProjectRepository projectRepository,
            ProjectVersionAppService versionAppService,
            WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.projectRepository = projectRepository;
        this.versionAppService = versionAppService;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * 起「查看当时」会话：寻址守卫 → 并发守卫 → 快照容器起服 → 返回 viewId + 预览。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_028 版本不存在（含非
     *                              hash 形态 ref）；PRJ_029 并发查看达上限；WSP_002
     *                              快照起服环境故障
     */
    public VersionViewStartResponse startView(Long projectId, String ref) {
        Project project = loadProject(projectId);
        versionAppService.requireVersion(project, ref);
        Map<String, ActiveView> active =
                views.computeIfAbsent(projectId, key -> new ConcurrentHashMap<>());
        if (active.size() >= MAX_CONCURRENT_VIEWS) {
            throw new ApplicationException(ProjectMessage.VERSION_VIEW_LIMIT);
        }
        String viewId = EventsAppService.newRunId();
        SnapshotHandle snapshot = workspaceLifecycleAppService.startSnapshot(
                Long.toString(project.getWorkspaceId()), viewId, ref);
        active.put(viewId, new ActiveView(snapshot, LocalDateTime.now()));
        // 预览 URL 由环境后端拼（#141 网关子域，与主预览 exposePort 同构），本层透传
        return new VersionViewStartResponse(viewId, snapshot.previewUrl().toString());
    }

    /**
     * 关闭「查看当时」会话：快照容器销毁（副本随容器可写层消失），注册表移除。
     *
     * @throws ApplicationException PRJ_030 会话不存在（已关闭 / 被清扫 / 平台重启丢账）
     */
    public void stopView(Long projectId, String viewId) {
        Map<String, ActiveView> active = views.get(projectId);
        ActiveView view = active == null ? null : active.remove(viewId);
        if (view == null) {
            throw new ApplicationException(ProjectMessage.VERSION_VIEW_NOT_FOUND);
        }
        workspaceLifecycleAppService.stopSnapshot(view.handle());
    }

    /**
     * 清扫闲置查看会话（#171，休眠扫描顺带——快照也是容器、同样是被扫的浪费源）：
     * ① 起服逾闲置阈值仍无人关闭的会话销毁（用户逛完没关的平台侧收口；快照浏览
     * 是「查看当时」的即兴动作，超阈值视为离场——后端看不见网关流量，「在用」以
     * 起服时刻为代理）；② 保留集（剩余在用会话）之外的快照容器扫清——运行期
     * 孤儿兜底（关窗销毁删失败、注册表丢账的漏网），孤儿清扫不再只在启动期跑。
     * 在用（阈值内）会话不受影响。返回清扫数（会话 + 孤儿容器）。
     */
    public int sweepIdleViews(LocalDateTime now, Duration idleThreshold) {
        int swept = 0;
        for (Map.Entry<Long, Map<String, ActiveView>> byProject : views.entrySet()) {
            List<String> stale = new ArrayList<>();
            for (Map.Entry<String, ActiveView> view : byProject.getValue().entrySet()) {
                if (now.isAfter(view.getValue().startedAt().plus(idleThreshold))) {
                    stale.add(view.getKey());
                }
            }
            for (String viewId : stale) {
                ActiveView view = byProject.getValue().remove(viewId);
                if (view != null) {
                    workspaceLifecycleAppService.stopSnapshot(view.handle());
                    swept++;
                    log.info("[snapshot] 项目 {} 查看会话 {} 闲置逾阈值，清扫",
                            byProject.getKey(), viewId);
                }
            }
        }
        Set<String> inUse = views.values().stream()
                .flatMap(active -> active.values().stream())
                .map(view -> view.handle().containerName())
                .collect(Collectors.toSet());
        return swept + workspaceLifecycleAppService.sweepOrphanSnapshots(inUse);
    }

    private Project loadProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

    /** 在途查看会话：快照句柄 + 起服时刻（闲置计时的锚，#171 清扫输入）。 */
    private record ActiveView(SnapshotHandle handle, LocalDateTime startedAt) {
    }
}
