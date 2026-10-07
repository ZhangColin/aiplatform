-- ========================================================================
-- business.order：订单交付物类型（#297 设计线·步5，ADR-0023「一单一程」）
--
-- ord_orders 加 deliverable_type：下单即冻结自项目终点类型（此后项目终点变更
-- 不影响本单，取消再下 = 新单新类型）。1=设计（交付物＝PRD 快照＋设计资产包，
-- 下单冻结时选件式 tar 入 exports/）、2=系统（源码包实时取，存量口径零改）、
-- 3=系统＋设计（统一部件容器：源码包之上追加选定设计稿部件，类型可区分）。
-- 交付物类型不参与交易机制——报价/改价/状态机/取消对三类单同一律。
-- 存量单缺省 2（系统——设计线前唯一交付物形态，回填如实）。
-- 码位与 prj_projects.endpoint_type 对齐（跨 BC 不共享枚举类型，码表互指为约）。
-- 增量迁移（V29），不改已应用迁移。
-- ========================================================================

ALTER TABLE ord_orders
    ADD COLUMN deliverable_type INT NOT NULL DEFAULT 2;  -- OrderDeliverableType：1=设计 2=系统 3=系统＋设计

ALTER TABLE ord_orders
    ADD CONSTRAINT ck_ord_orders_deliverable_type CHECK (deliverable_type IN (1, 2, 3));
