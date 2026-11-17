package com.semgravio.aiplatform.business.billing.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.billing.domain.aggregate.ProjectBilling;

/**
 * 项目计费态仓储（两轨判定单读点的存取面）：一项目一行（uk 唯一索引兜底），
 * 行缺席＝试用开局（惰性建行）。
 */
public interface ProjectBillingRepository extends BaseRepository<ProjectBilling, Long> {

    /** 项目计费态（无行 = 试用开局、源未设）。 */
    Optional<ProjectBilling> findByProjectId(Long projectId);

    /**
     * 已翻转行全集（#536 成本观测切片锚供数面）：flipped_at 非空＝已付费——经
     * 计量面 {@code SubjectSliceAnchors} 端口透出为 subject → 翻转时点映射
     * （计量只按锚分桶、不读本 BC 表，base 不依赖 business 边界不破）。
     */
    List<ProjectBilling> findByFlippedAtIsNotNull();
}
