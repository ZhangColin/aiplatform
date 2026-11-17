package com.semgravio.aiplatform.business.billing.domain.repository;

import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.billing.domain.aggregate.InterceptHold;
import com.semgravio.aiplatform.business.billing.domain.enums.InterceptStatus;

/**
 * 试用拦截留存仓储（#533 计费②门②）：一项目至多一条待决（部分唯一索引兜底），
 * 读面按项目＋状态取最近一条。
 */
public interface InterceptHoldRepository extends BaseRepository<InterceptHold, Long> {

    /** 项目最近一条指定状态的拦截留存（待决读面/取代面单点）。 */
    Optional<InterceptHold> findFirstByProjectIdAndStatusOrderByIdDesc(
            Long projectId, InterceptStatus status);
}
