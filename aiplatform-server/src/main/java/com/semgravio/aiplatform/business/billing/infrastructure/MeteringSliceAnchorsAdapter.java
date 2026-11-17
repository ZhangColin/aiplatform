package com.semgravio.aiplatform.business.billing.infrastructure;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.semgravio.aiplatform.base.metering.domain.port.SubjectSliceAnchors;
import com.semgravio.aiplatform.business.billing.domain.aggregate.ProjectBilling;
import com.semgravio.aiplatform.business.billing.domain.repository.ProjectBillingRepository;

/**
 * 成本观测切片锚端口进程内适配器（#536 计费⑥）：读 {@code bil_project_billing}
 * 已翻转行（{@code flipped_at} 非空）透出为计量 subject → 翻转时点映射——
 * 计费边界锚单源在 ProjectBilling 聚合（翻转不可逆、{@code flippedAt} 落库即
 * 定格），本适配器只做词汇映射（Long projectId → 计量 subject 十进制串），
 * 计量面只按锚机械分桶（获客成本/用户费用两桶）不读本 BC 表。锚集语义：
 * 缺席＝未翻转（用量全量获客成本桶）。计量服务迁出时本适配器换 REST 供数。
 */
@Component
@Adapter(PortType.CLIENT)
public class MeteringSliceAnchorsAdapter implements SubjectSliceAnchors {

    private final ProjectBillingRepository projectBillingRepository;

    public MeteringSliceAnchorsAdapter(ProjectBillingRepository projectBillingRepository) {
        this.projectBillingRepository = projectBillingRepository;
    }

    @Override
    public Map<String, Instant> anchorsBySubject() {
        return projectBillingRepository.findByFlippedAtIsNotNull().stream()
                .collect(Collectors.toMap(
                        billing -> String.valueOf(billing.getProjectId()),
                        ProjectBilling::getFlippedAt));
    }
}
