-- ========================================================================
-- base.metering：单价表时段带列（#498，ADR-0044 峰谷 2/4）——
-- met_price_entries 加可空 band 列＋唯一约束重建为含带五元组。
--
-- 时段带＝峰/谷（Band：1=峰 2=谷，BaseEnum code 落库），NULL＝不区分时段——
-- 无峰谷通道与按张行存量语义零变动（ADR-0044 向后兼容口径）；匹配键、重叠校验
-- （METER_008）同步扩带（#498），读时归段选价为引擎票（#499 PRICE_MATCH 扩
-- 时段维）改动面。
--
-- 唯一约束重建为 (provider, model, token_kind, band, effective_from)：同键
-- 同带同起点数据库层拒；PG 唯一约束 NULL 值互不相等——NULL 带同起点不被约束
-- 拦，由写口服务端重叠校验兜（对称 #497 时段规则表「同起点拒由写口服务端拦」
-- 口径，METER_008 校验覆盖同带全行含 NULL 带）。
-- ========================================================================

ALTER TABLE met_price_entries
    ADD COLUMN band INT;  -- Band：1=峰 2=谷（BaseEnum code）；NULL = 不区分时段

ALTER TABLE met_price_entries
    DROP CONSTRAINT uk_met_price_entries_key_from;

ALTER TABLE met_price_entries
    ADD CONSTRAINT uk_met_price_entries_key_band_from
        UNIQUE (provider, model, token_kind, band, effective_from);
