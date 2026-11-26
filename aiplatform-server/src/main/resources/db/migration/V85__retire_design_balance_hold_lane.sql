-- ========================================================================
-- business.billing：余额提醒留存的设计改稿车道退场（设计线整条退役 #610）。
--
-- bil_balance_holds（#537 计费③ / ADR-0047）原有三车道：1=意见 2=咨询
-- 3=作用域改稿（设计会话）。设计线整条退役后作用域改稿派发与改稿车道
-- 一并拆除（#610）：
--   - lane=3 的待决/已放行/被取代行是**瞬时输入留存**（被拦意见原文＋附件，
--     非资金流水、不参与计费对账），随车道退役整行清理；
--   - design_item / divergence 两列（仅 3 车道承载）同步拆除。
--
-- bil_entries 的 segment_type=3（设计会话轮）**保留**：那是真实扣减流水，
-- 拆除会让历史账目无法读取（删除或改写都会破坏审计），故 SegmentType 枚举
-- 保留该历史值、只退役生产者（设计会话段结算）。
--
-- 增量迁移（V85），不改已应用迁移。
-- ========================================================================

DELETE FROM bil_balance_holds WHERE lane = 3;

ALTER TABLE bil_balance_holds
    DROP COLUMN IF EXISTS design_item,
    DROP COLUMN IF EXISTS divergence;
