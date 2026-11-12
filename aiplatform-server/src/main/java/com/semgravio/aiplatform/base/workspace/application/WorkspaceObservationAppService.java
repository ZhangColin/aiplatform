package com.semgravio.aiplatform.base.workspace.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.base.workspace.application.dto.response.WorkspaceObservation;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceLostMark;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.DesiredState;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceLostMarkRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.support.Tsid;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * 工作区观测用例（#173 后台观测面，只读；#487 起纯读库零 docker 调用）：
 * 记录字段（期望态/last-touch/封存元数据）来自注册表，实态两列
 * （{@link ContainerState}/卷用量）来自采样表 {@link WorkspaceObservationSample}
 * （扫描轮旁路采样落库，带 {@code sampledAt} 新鲜度标注）——旧「清单请求时逐行
 * 现场 docker 探查＋旁路容器 du 全卷」（经常超时）口径退役，响应秒级。所属项目
 * 引用不在本层（base 不反向依赖 business）——组合方 business.project 后台面消费
 * 本用例自行拼装。
 *
 * <p>过滤机制形状（意图 SQL 侧、实态采样侧，#487 后两列全 SQL 化）：期望态等值
 * ＋实态 exists 子查询（采样表命中即匹配）合成一条 Specification，分页/计数一次
 * 库侧完成（旧「带实态过滤全量探查后内存分页」随实态落库退役）；未采样行不命中
 * 任何实态过滤（没有帧无从筛）。排序 id 倒序定死（新沙箱在前），分页钳制/换算
 * 全部来自框架 {@link Pagination}（客户端 sort 静默忽略）。</p>
 */
@Service
public class WorkspaceObservationAppService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceObservationSampleRepository sampleRepository;
    private final WorkspaceLostMarkRepository lostMarkRepository;

    public WorkspaceObservationAppService(WorkspaceRepository workspaceRepository,
            WorkspaceObservationSampleRepository sampleRepository,
            WorkspaceLostMarkRepository lostMarkRepository) {
        this.workspaceRepository = workspaceRepository;
        this.sampleRepository = sampleRepository;
        this.lostMarkRepository = lostMarkRepository;
    }

    /**
     * 沙箱观测清单：期望态/实态过滤全 SQL 侧，每行＝记录字段＋采样帧（实态＋卷
     * 用量＋采样时刻）＋失联标记（#488 在册失联判定时刻）。空页如实（200 空清单
     * 非错误）。
     */
    @Transactional(readOnly = true)
    public PageResponse<WorkspaceObservation> observations(DesiredState desired,
            ContainerState actual, Pagination pagination) {
        Page<Workspace> result = workspaceRepository.findAll(specificationOf(desired, actual),
                pagination.toPageRequest()
                        .withSort(Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, WorkspaceObservationSample> samples = byWorkspaceId(result.getContent(),
                sampleRepository::findAllById, WorkspaceObservationSample::getWorkspaceId);
        Map<Long, WorkspaceLostMark> lostMarks = byWorkspaceId(result.getContent(),
                lostMarkRepository::findAllById, WorkspaceLostMark::getWorkspaceId);
        return PageResponse.of(result.map(workspace -> observe(workspace,
                samples.get(workspace.getId()), lostMarks.get(workspace.getId()))));
    }

    /**
     * 单沙箱全量观测（详情）：记录全字段＋采样帧＋封存信息＋失联标记。
     *
     * @throws ApplicationException WSP_001 工作区不存在（含非数值/非正数标识）
     */
    @Transactional(readOnly = true)
    public WorkspaceObservation observation(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        return observe(workspace,
                sampleRepository.findById(workspace.getId()).orElse(null),
                lostMarkRepository.findById(workspace.getId()).orElse(null));
    }

    /** 拼装单行（采样帧/失联标记可缺——未采样行实态两列缺省、未失联行 lostAt null）。 */
    private WorkspaceObservation observe(Workspace workspace, WorkspaceObservationSample sample,
            WorkspaceLostMark lostMark) {
        return WorkspaceObservation.of(workspace,
                sample != null ? sample.getContainerState() : null,
                volumeSizeOf(workspace, sample),
                sample != null ? sample.getSampledAt() : null,
                lostMark != null ? lostMark.getMarkedAt() : null);
    }

    /**
     * 卷大小呈现口径：封存按注册表意图容缺（卷已删是意图事实——采样帧里的旧卷
     * 字节数不顶包）；未采样/采样容缺 null。
     */
    private Long volumeSizeOf(Workspace workspace, WorkspaceObservationSample sample) {
        if (sample == null || workspace.getDesiredState() == DesiredState.SEALED) {
            return null;
        }
        return sample.getVolumeSizeBytes();
    }

    /** 期望态（等值）＋实态（采样表 exists）合成过滤；两者皆缺省时 spec 为 null。 */
    private Specification<Workspace> specificationOf(DesiredState desired, ContainerState actual) {
        Specification<Workspace> desiredSpec = desired == null ? null
                : (root, query, cb) -> cb.equal(root.get("desiredState"), desired);
        if (actual == null) {
            return desiredSpec;
        }
        Specification<Workspace> actualSpec = (root, query, cb) -> {
            // 实态命中 = 采样表有该态的帧（未采样行无从筛，如实不命中）
            Subquery<Long> sampled = query.subquery(Long.class);
            Root<WorkspaceObservationSample> sample = sampled.from(WorkspaceObservationSample.class);
            sampled.select(cb.literal(1L))
                    .where(cb.equal(sample.get("workspaceId"), root.get("id")),
                            cb.equal(sample.get("containerState"), actual));
            return cb.exists(sampled);
        };
        return desiredSpec == null ? actualSpec : desiredSpec.and(actualSpec);
    }

    /** 当页行按工作区编号批量取随行子件（采样帧/失联标记同形；一次 findAllById，空页零查询）。 */
    private <T> Map<Long, T> byWorkspaceId(List<Workspace> page,
            Function<List<Long>, List<T>> loader, Function<T, Long> keyOf) {
        if (page.isEmpty()) {
            return Map.of();
        }
        return loader.apply(page.stream().map(Workspace::getId).toList())
                .stream().collect(Collectors.toMap(keyOf, Function.identity()));
    }

    /** 寻址解析（{@link Tsid} 严格式）：非数值/非正数即不存在的标识，语义上同 404。 */
    private Workspace requireWorkspace(String workspaceId) {
        long id = Tsid.resolve(workspaceId, WorkspaceMessage.WORKSPACE_NOT_FOUND);
        return workspaceRepository.findById(id)
                .orElseThrow(() -> new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));
    }
}
