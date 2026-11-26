-- ========================================================================
-- business.order：订单交付物类型收窄为系统单值（设计线整条退役-2 #611，
-- 父票 #609 裁决：商业面设计单／设计交付物包一并退场）。
--
-- V29 引入三值：1=设计 2=系统 3=系统＋设计。设计线整条退役后：
--   - 存量 1（设计）／3（系统＋设计）行按裁决迁移为 2（系统单）——订单交易
--     事实（金额/状态/快照）不动，只收窄交付物类型维度；
--   - CHECK 约束收窄为仅允许 2；码值 2 与列缺省值保持（新单一律系统单）。
--   - DesignPackageAppService 调用点在 #611 摘除、类本体归 #612 删；
--     deliverable_type 列本身保留（交易记录的自含维度，非设计线载体）。
--
-- 增量迁移（V86），不改已应用迁移。
-- ========================================================================

UPDATE ord_orders
   SET deliverable_type = 2
 WHERE deliverable_type IN (1, 3);

ALTER TABLE ord_orders
    DROP CONSTRAINT IF EXISTS ck_ord_orders_deliverable_type;

ALTER TABLE ord_orders
    ADD CONSTRAINT ck_ord_orders_deliverable_type CHECK (deliverable_type = 2);
