package com.aieducenter.aiplatform.business.project.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceActionAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceObservationAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceObservation;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.model.Operator;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeWorkspaceDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeWorkspaceSummaryResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 后台沙箱观测面组合用例（#173）：沙箱事实经 base.workspace 观测用例（跨 BC 调用
 * 走对方应用层——限界上下文编写规范），本层只补业务侧事实——所属项目引用
 * （工作区先于项目存在、软引用容缺：无所属项目为 null，不 404）。#174 起在本层
 * 组合四干预动作：消费方事实（run 在途＝{@link CodingRunTrack}、已生成＝项目
 * generatedAt）在本层换算递入 base 动作用例（照 {@code WorkspaceScanFact} 先例，
 * base 不反向依赖 business），动作后回观测详情（动作结果＝新事实）。
 */
@Service
public class BackofficeWorkspaceAppService {

    private final WorkspaceObservationAppService observationAppService;
    private final WorkspaceActionAppService actionAppService;
    private final ProjectRepository projectRepository;
    private final CodingRunTrack codingRunTrack;

    public BackofficeWorkspaceAppService(WorkspaceObservationAppService observationAppService,
            WorkspaceActionAppService actionAppService,
            ProjectRepository projectRepository,
            CodingRunTrack codingRunTrack) {
        this.observationAppService = observationAppService;
        this.actionAppService = actionAppService;
        this.projectRepository = projectRepository;
        this.codingRunTrack = codingRunTrack;
    }

    /**
     * 沙箱清单：期望态/实态过滤＋分页照观测用例口径（实态过滤在探查后内存完成）；
     * 每行拼装所属项目引用。
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeWorkspaceSummaryResponse> workspaces(DesiredState desired,
            ContainerState actual, int page, int size) {
        PageResponse<WorkspaceObservation> observations =
                observationAppService.observations(desired, actual, page, size);
        return new PageResponse<>(
                observations.items().stream()
                        .map(observation -> BackofficeWorkspaceSummaryResponse.of(
                                observation, projectRefOf(observation.workspaceId())))
                        .toList(),
                observations.total(), observations.page(), observations.size());
    }

    /**
     * 单沙箱详情：全量字段＋所属项目引用。
     *
     * @throws ApplicationException WSP_001 工作区不存在（含畸形 id）
     */
    @Transactional(readOnly = true)
    public BackofficeWorkspaceDetailResponse detail(String workspaceId) {
        WorkspaceObservation observation = observationAppService.observation(workspaceId);
        return BackofficeWorkspaceDetailResponse.of(observation,
                projectRefOf(observation.workspaceId()));
    }

    /**
     * 唤醒（#174，等就绪；run 在途不受限）：已生成项目唤醒后拉起 8081 应用。
     *
     * @throws ApplicationException WSP_001/007/016（守卫链归 base 动作用例）
     */
    public BackofficeWorkspaceDetailResponse wake(String workspaceId, Operator operator) {
        actionAppService.wake(workspaceId, projectOf(workspaceId)
                .map(project -> project.getGeneratedAt() != null).orElse(false), operator);
        return detail(workspaceId);
    }

    /**
     * 强制休眠（#174）：立即删容器保卷。run 在途拒（WSP_015）。
     */
    public BackofficeWorkspaceDetailResponse hibernate(String workspaceId, Operator operator) {
        actionAppService.forceHibernate(workspaceId, runInFlightOf(workspaceId), operator);
        return detail(workspaceId);
    }

    /**
     * 强制重建（#174）：rm＋幂等重建（#168 型事故的标准化处置）。run 在途拒。
     */
    public BackofficeWorkspaceDetailResponse rebuild(String workspaceId, Operator operator) {
        actionAppService.forceRebuild(workspaceId,
                projectOf(workspaceId).map(project -> project.getGeneratedAt() != null)
                        .orElse(false),
                runInFlightOf(workspaceId), operator);
        return detail(workspaceId);
    }

    /**
     * 封存（#174，产物同自动封存）。run 在途拒——卷正被 run 读写时打包＝半程数据。
     */
    public BackofficeWorkspaceDetailResponse seal(String workspaceId, Operator operator) {
        actionAppService.seal(workspaceId, runInFlightOf(workspaceId), operator);
        return detail(workspaceId);
    }

    /** 项目引用（软引用容缺）：一项目一 dev 环境，寻不到＝无所属项目 → null。 */
    private BackofficeWorkspaceSummaryResponse.ProjectRef projectRefOf(long workspaceId) {
        return projectRepository.findByWorkspaceId(workspaceId)
                .map(project -> new BackofficeWorkspaceSummaryResponse.ProjectRef(
                        project.getId().toString(), project.getName(),
                        project.getArchivedAt() != null))
                .orElse(null);
    }

    /** 按工作区找所属项目（畸形 id 容缺 empty——base 寻址守卫随后统一 404）。 */
    private Optional<Project> projectOf(String workspaceId) {
        long id;
        try {
            id = Long.parseLong(workspaceId);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return projectRepository.findByWorkspaceId(id);
    }

    /** run 在途事实：所属项目有生成/修正 run 在途即 true（无所属项目恒 false）。 */
    private boolean runInFlightOf(String workspaceId) {
        return projectOf(workspaceId)
                .map(project -> codingRunTrack.isInFlight(project.getId()))
                .orElse(false);
    }
}
