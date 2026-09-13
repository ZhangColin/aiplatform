package com.aieducenter.aiplatform.base.metering.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;

/**
 * 单价行仓储（{@code met_price_entries}）：base.metering 私有表的写面——维护
 * 入口 = 后台管理写口（#160 清单/原子改价/停用）＋启动种子（退役挂 #165）。
 * 换算查询不走本仓储（读侧原生 SQL 见 {@link UsageEventAggregations}）。
 */
public interface PriceEntryRepository extends BaseRepository<PriceEntry, Long> {

    /**
     * 该匹配键是否已有任意单价行（含已关行）——种子幂等判据：有行即跳过
     * （手工维护接管优先，种子不插手不改价）。
     */
    boolean existsByProviderAndModelAndTokenKind(String provider, String model, TokenKind tokenKind);

    /**
     * 该匹配键全部行（含历史行，起点升序）——管理写口的同键生效区间重叠校验
     * 素材（#160）：新开区间须与除被关行外的既有区间皆不重叠、且起点不撞任何
     * 既有行（同起点被唯一约束兜底，服务端先拦出干净错误）。
     */
    List<PriceEntry> findByProviderAndModelAndTokenKindOrderByEffectiveFromAsc(
            String provider, String model, TokenKind tokenKind);
}
