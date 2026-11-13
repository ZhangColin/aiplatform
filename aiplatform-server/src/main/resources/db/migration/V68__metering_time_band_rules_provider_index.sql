-- ========================================================================
-- base.metering：时段规则表 provider 索引（#499，ADR-0044 峰谷 3/4）——
-- 读时归段选价引擎（PRICE_MATCH 扩时段维）对 met_time_band_rules 做每事件行
-- 相关子查询（通道 ts 时点生效判定 EXISTS / 窗口命中 MIN(band) 三次求值），
-- provider 等值过滤走索引免每行 seq scan——规则行量级小（每通道数行～数十行）
-- 但事件行量大，窗口聚合成本主要在此。只增：不改 V66 表定义。
-- ========================================================================

CREATE INDEX idx_met_time_band_rules_provider ON met_time_band_rules (provider);
