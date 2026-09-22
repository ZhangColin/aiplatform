package com.aieducenter.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.specification.ConditionSpecifications;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.aieducenter.aiplatform.business.identity.application.AccountAppService;
import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountBriefResponse;
import com.aieducenter.aiplatform.business.order.application.OrderQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.query.BackofficeProjectQuery;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeProjectSummaryResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 后台项目读面（#159 项目域，/api/backoffice/projects 机机签名的两读端点）：
 * 清单（三档单选＋创建时间区间＋账号＋项目 id 精确）/ 详情（带订单引用照用户面
 * activeOrder/latestOrder 先例；#164 补成本汇总指针）。检索走数据库查询路径
 * （Specification）——用户面 findAll 内存过滤不适用于后台新增维度。归档项目
 * 全状态照读（清单缺省含）；已删项目真删无墓碑，任何读面自然不可见。
 *
 * <p>跨 BC 事实（订单引用/externalId 换算/账号取名）经 order/identity 应用层软
 * 引用——与 {@link ProjectQueryAppService} → OrderQueryAppService 同向，不与
 * order 写面（order → project）成环；成本指针经 metering 读端口（business →
 * base 允许方向）。</p>
 */
@Service
public class BackofficeProjectAppService {

    private final ProjectRepository projectRepository;
    private final OrderQueryAppService orderQueryAppService;
    private final AccountAppService accountAppService;
    private final UsageQueryPort usageQueryPort;

    public BackofficeProjectAppService(ProjectRepository projectRepository,
                                       OrderQueryAppService orderQueryAppService,
                                       AccountAppService accountAppService,
                                       UsageQueryPort usageQueryPort) {
        this.projectRepository = projectRepository;
        this.orderQueryAppService = orderQueryAppService;
        this.accountAppService = accountAppService;
        this.usageQueryPort = usageQueryPort;
    }

    /**
     * 后台项目清单（新项目在前）：状态三档单选（ACTIVE/ARCHIVED，缺省＝全部、
     * 归档项目缺省含——照用户面先例，与订单多选有意不同）、创建时间区间（含
     * 两端）、归属账号（externalId 入参，服务端换算 accountId）、项目 id 精确。
     * 过滤在数据库侧完成（Specification），不入内存全量。分页钳制/换算全部来自
     * 框架 {@link Pagination}（1 基、缺省 1/20、上界 100 静默贴边）；排序定死
     * id 倒序（TSID 时间有序＝创建新在前），客户端 sort 被 withSort 覆盖静默
     * 忽略，不开放客户端排序。
     *
     * <p>externalId 换算不到（用户在我方无建档）与非数值项目 id 都如实返回空清单
     * （200、total 0，页码原样回显）——两者都是检索维度上的「无命中」，不是错误
     * （同订单面口径；详情寻址语义的 404 归 {@link #detail}）。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeProjectSummaryResponse> projects(ProjectStatusFilter status,
                                                                   LocalDateTime createdFrom,
                                                                   LocalDateTime createdTo,
                                                                   String externalId,
                                                                   String projectId,
                                                                   Pagination pagination) {
        Long ownerAccountId = null;
        if (externalId != null && !externalId.isBlank()) {
            ownerAccountId = accountAppService.accountIdOf(externalId).orElse(null);
            if (ownerAccountId == null) {
                return PageResponse.empty(pagination);
            }
        }
        Long parsedProjectId = Tsid.parseOrNull(projectId);
        if (projectId != null && !projectId.isBlank() && parsedProjectId == null) {
            return PageResponse.empty(pagination);
        }

        BackofficeProjectQuery condition = new BackofficeProjectQuery(
                createdFrom, createdTo, ownerAccountId, parsedProjectId);
        Specification<Project> annotated = ConditionSpecifications.fromAnnotation(condition);
        Specification<Project> specification = Specification.where(annotated)
                .and(archivedPredicate(status));
        Pageable pageable = pagination.toPageRequest()
                .withSort(Sort.by(Sort.Direction.DESC, "id"));
        Page<Project> result = projectRepository.findAll(specification, pageable);
        Map<Long, AccountBriefResponse> owners = accountAppService.briefsOf(
                result.getContent().stream().map(Project::getOwnerAccountId).toList());
        return PageResponse.of(result.map(project -> BackofficeProjectSummaryResponse.of(project,
                project.getOwnerAccountId() == null ? null
                        : owners.get(project.getOwnerAccountId()))));
    }

    /**
     * 状态三档单选 → archivedAt 派生谓词：ACTIVE（1）＝未归档（IS NULL）、
     * ARCHIVED（3）＝已归档（IS NOT NULL）、缺省＝全量（无附加条件）。注解机制
     * 无 IS NULL 条件类型（BackofficeProjectQuery 只承载列值维度），此处手工
     * 拼合。
     */
    private static Specification<Project> archivedPredicate(ProjectStatusFilter status) {
        if (status == ProjectStatusFilter.ACTIVE) {
            return (root, criteriaQuery, cb) -> cb.isNull(root.get("archivedAt"));
        }
        if (status == ProjectStatusFilter.ARCHIVED) {
            return (root, criteriaQuery, cb) -> cb.isNotNull(root.get("archivedAt"));
        }
        return null;
    }

    /**
     * 后台项目详情：清单字段全量＋归属账号摘要（externalId＋显示名）＋订单引用
     * （activeOrder＝未终结订单摘要，有值即冻结迭代；latestOrder＝最近一张任意
     * 状态订单，支付归档后承接「完整记录」取单面）＋成本汇总指针（项目全量
     * 口径，明细下钻走成本域端点）。归属账号摘要软引用容缺（null 呈现）——
     * 项目是交付载体，不因账号档缺失而 404（同清单口径）。
     *
     * @throws ApplicationException PRJ_001 项目不存在（含已删项目——真删无墓碑）
     */
    @Transactional(readOnly = true)
    public BackofficeProjectDetailResponse detail(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        UsageSummary usage = usageQueryPort.bySubject(Long.toString(projectId), null, null);
        return BackofficeProjectDetailResponse.of(project,
                accountAppService.briefOf(project.getOwnerAccountId()),
                orderQueryAppService.activeOrderOf(projectId).orElse(null),
                orderQueryAppService.latestOrderOf(projectId).orElse(null),
                new BackofficeProjectDetailResponse.CostSummary(
                        usage.costByCurrencyCode(), !usage.unpriced().isEmpty()));
    }
}
