package com.aieducenter.aiplatform.business.project.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceObservationAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceObservation;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeWorkspaceDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeWorkspaceSummaryResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 后台沙箱观测面组合用例（#173）：沙箱事实经 base.workspace 观测用例（跨 BC 调用
 * 走对方应用层——限界上下文编写规范），本层只补业务侧事实——所属项目引用
 * （工作区先于项目存在、软引用容缺：无所属项目为 null，不 404）。只读观测，
 * 不做任何收敛/清算动作（ADR-0016：v1 攒数据，回收决策另立）。
 */
@Service
public class BackofficeWorkspaceAppService {

    private final WorkspaceObservationAppService observationAppService;
    private final ProjectRepository projectRepository;

    public BackofficeWorkspaceAppService(WorkspaceObservationAppService observationAppService,
            ProjectRepository projectRepository) {
        this.observationAppService = observationAppService;
        this.projectRepository = projectRepository;
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

    /** 项目引用（软引用容缺）：一项目一 dev 环境，寻不到＝无所属项目 → null。 */
    private BackofficeWorkspaceSummaryResponse.ProjectRef projectRefOf(long workspaceId) {
        return projectRepository.findByWorkspaceId(workspaceId)
                .map(project -> new BackofficeWorkspaceSummaryResponse.ProjectRef(
                        project.getId().toString(), project.getName(),
                        project.getArchivedAt() != null))
                .orElse(null);
    }
}
