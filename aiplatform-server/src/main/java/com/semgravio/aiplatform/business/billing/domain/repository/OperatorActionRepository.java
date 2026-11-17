package com.semgravio.aiplatform.business.billing.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.billing.domain.aggregate.OperatorAction;

/**
 * 后台账户运营动作留痕仓储：append-only 只插不改（聚合列级 updatable=false
 * 兜底），读面为 admin 对账/审计（#535 计费⑤——按项目/账户拉动作序的索引在
 * 迁移 V73）。
 */
public interface OperatorActionRepository extends BaseRepository<OperatorAction, Long> {
}
