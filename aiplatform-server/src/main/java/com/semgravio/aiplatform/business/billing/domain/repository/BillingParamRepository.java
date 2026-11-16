package com.semgravio.aiplatform.business.billing.domain.repository;

import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingParam;

/**
 * 计费运营参数仓储（param_key 自然键）。
 */
public interface BillingParamRepository extends BaseRepository<BillingParam, String> {

    /** 按参数键取现值行。 */
    Optional<BillingParam> findByParamKey(String paramKey);
}
