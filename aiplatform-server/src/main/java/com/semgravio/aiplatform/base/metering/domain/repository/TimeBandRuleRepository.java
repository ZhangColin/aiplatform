package com.semgravio.aiplatform.base.metering.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.base.metering.domain.aggregate.TimeBandRule;

/**
 * 时段规则行仓储（{@code met_time_band_rules}）：base.metering 私有表的写面——
 * 维护入口唯一＝后台管理写口（#497 开/改/停三签名端点；种子经幂等签名脚本走
 * 开行端点）。归段消费查询（引擎 PRICE_MATCH 扩时段维）为后续票改动面，不走
 * 本仓储。日期覆写预留表（{@code met_time_band_date_overrides}）无实体无写口，
 * 升级位随 ADR-0044 落地。
 */
public interface TimeBandRuleRepository extends BaseRepository<TimeBandRule, Long> {

    /**
     * 该通道全部行（含历史行，起点升序）——管理写口的同通道生效区间重叠校验
     * 素材（#497，对称 #160 口径）：新开区间须与除被关版本外的既有区间皆不
     * 重叠、且起点不撞任何既有行（同版本多窗口行共享起点，同起点拒由服务端
     * 拦——本表无 (provider, effective_from) 唯一约束可兜底）。
     */
    List<TimeBandRule> findByProviderOrderByEffectiveFromAsc(String provider);

    /**
     * 该通道当前版本的全部敞口行——改窗/停用寻址命中后整版本关闭的素材。
     */
    List<TimeBandRule> findByProviderAndEffectiveToIsNull(String provider);
}
