package com.semgravio.aiplatform.base.metering.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.semgravio.aiplatform.base.metering.domain.enums.Band;
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;

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
     * 既有行（同起点被唯一约束兜底，服务端先拦出干净错误）。#498 时段带：校验
     * 按同键同带各查各的——NULL 带行与带行互不构成重叠（派生查询 null 参数
     * 绑定即 {@code = NULL} 恒不命中，故带查询单列方法、空带走本方法，应用服务
     * 分流，不靠框架 null 语义）。
     */
    List<PriceEntry> findByProviderAndModelAndTokenKindOrderByEffectiveFromAsc(
            String provider, String model, TokenKind tokenKind);

    /**
     * 同键同带全部行（含历史行，起点升序）——#498 带行重叠校验素材，语义同上
     * （同键同带同起点拒、不同带不误拒）。
     */
    List<PriceEntry> findByProviderAndModelAndTokenKindAndBandOrderByEffectiveFromAsc(
            String provider, String model, TokenKind tokenKind, Band band);
}
