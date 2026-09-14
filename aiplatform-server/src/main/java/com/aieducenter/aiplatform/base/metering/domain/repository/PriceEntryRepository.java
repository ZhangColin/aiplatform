package com.aieducenter.aiplatform.base.metering.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;

/**
 * 单价行仓储（{@code met_price_entries}）：base.metering 私有表的写面——维护
 * 入口唯一＝后台管理写口（#160 清单/原子改价/停用＋#165 开行；启动种子已随
 * #165 退役，初始化经幂等签名脚本走开行端点）。换算查询不走本仓储（读侧原生
 * SQL 见 {@link UsageEventAggregations}）。
 */
public interface PriceEntryRepository extends BaseRepository<PriceEntry, Long> {

    /**
     * 该匹配键全部行（含历史行，起点升序）——管理写口的同键生效区间重叠校验
     * 素材（#160）：新开区间须与除被关行外的既有区间皆不重叠、且起点不撞任何
     * 既有行（同起点被唯一约束兜底，服务端先拦出干净错误）。
     */
    List<PriceEntry> findByProviderAndModelAndTokenKindOrderByEffectiveFromAsc(
            String provider, String model, TokenKind tokenKind);
}
