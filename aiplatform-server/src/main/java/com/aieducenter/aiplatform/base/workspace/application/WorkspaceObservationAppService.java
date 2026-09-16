package com.aieducenter.aiplatform.base.workspace.application;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceObservation;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 工作区观测用例（#173 后台观测面，只读）：沙箱事实的出口——记录字段（期望态/
 * last-touch/封存元数据，DB）＋docker 实态探查（{@link ContainerState}/卷大小，
 * 逐行现场探）。v1 只观测不清算（ADR-0016：为深度存储回收攒真实数据）。
 * 所属项目引用不在本层（base 不反向依赖 business）——组合方 business.project
 * 后台面消费本用例自行拼装。
 *
 * <p>实态过滤的机制形状（意图/实态分离，ADR-0016）：实态不落库，过滤只能在探查
 * 后做——带实态过滤时全量探查期望态命中集、按实态筛、内存分页（total 如实＝筛后
 * 计数）；不带时 SQL 侧分页、只探查当页行（实态列人人要显示，探查省不掉；省的
 * 是页外行的探查）。排序 id 倒序定死（新沙箱在前），分页钳制/换算全部来自框架
 * {@link Pagination}（SQL 侧 withSort 覆盖、内存侧直出 offset()/limit()，客户端
 * sort 两分支都静默忽略）。</p>
 */
@Service
public class WorkspaceObservationAppService {

    private final WorkspaceRepository workspaceRepository;
    private final EnvironmentBackend environmentBackend;

    public WorkspaceObservationAppService(WorkspaceRepository workspaceRepository,
            EnvironmentBackend environmentBackend) {
        this.workspaceRepository = workspaceRepository;
        this.environmentBackend = environmentBackend;
    }

    /**
     * 沙箱观测清单：期望态过滤（SQL）＋实态过滤（探查后内存）；每行＝记录字段＋
     * 实态一瞥＋卷用量（封存容缺）。空页如实（200 空清单非错误）。
     */
    @Transactional(readOnly = true)
    public PageResponse<WorkspaceObservation> observations(DesiredState desired,
            ContainerState actual, Pagination pagination) {
        Specification<Workspace> desiredSpec = desired == null ? null
                : (root, query, cb) -> cb.equal(root.get("desiredState"), desired);
        if (actual == null) {
            Page<Workspace> result = workspaceRepository.findAll(desiredSpec,
                    pagination.toPageRequest()
                            .withSort(Sort.by(Sort.Direction.DESC, "id")));
            return PageResponse.of(result.map(this::observe));
        }
        List<ProbedState> matched = workspaceRepository.findAll(desiredSpec,
                        Sort.by(Sort.Direction.DESC, "id")).stream()
                .map(workspace -> new ProbedState(workspace,
                        environmentBackend.containerState(workspace.toHandle())))
                .filter(probed -> probed.state() == actual)
                .toList();
        // offset 出 long，先与命中数取 min 再收窄——结果被行数封顶无损，超尾页落空页
        int from = (int) Math.min(pagination.offset(), matched.size());
        int to = Math.min(from + pagination.limit(), matched.size());
        return new PageResponse<>(
                matched.subList(from, to).stream()
                        .map(probed -> observe(probed.workspace(), probed.state())).toList(),
                matched.size(), pagination.page(), pagination.size());
    }

    /**
     * 单沙箱全量观测（详情）：记录全字段＋实态＋卷用量＋封存信息。
     *
     * @throws ApplicationException WSP_001 工作区不存在（含非数值/非正数标识）
     */
    @Transactional(readOnly = true)
    public WorkspaceObservation observation(String workspaceId) {
        return observe(requireWorkspace(workspaceId));
    }

    /** 探查并拼装单行（实态现场探）：卷用量封存容缺（卷已删），其余逐行探。 */
    private WorkspaceObservation observe(Workspace workspace) {
        return observe(workspace, environmentBackend.containerState(workspace.toHandle()));
    }

    /** 拼装单行（实态已知——实态过滤路径复用过滤时的探查，不二探）。 */
    private WorkspaceObservation observe(Workspace workspace, ContainerState containerState) {
        Long volumeSizeBytes = workspace.getDesiredState() == DesiredState.SEALED ? null
                : environmentBackend.volumeSizeBytes(workspace.toHandle());
        return WorkspaceObservation.of(workspace, containerState, volumeSizeBytes);
    }

    /** 实态过滤路径的中间对（过滤即探查，一探两用）。 */
    private record ProbedState(Workspace workspace, ContainerState state) {
    }

    /** 寻址解析（{@link Tsid} 严格式）：非数值/非正数即不存在的标识，语义上同 404。 */
    private Workspace requireWorkspace(String workspaceId) {
        long id = Tsid.resolve(workspaceId, WorkspaceMessage.WORKSPACE_NOT_FOUND);
        return workspaceRepository.findById(id)
                .orElseThrow(() -> new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));
    }
}
