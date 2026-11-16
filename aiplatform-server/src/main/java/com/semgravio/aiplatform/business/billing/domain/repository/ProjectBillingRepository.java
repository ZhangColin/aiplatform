package com.semgravio.aiplatform.business.billing.domain.repository;

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
}
