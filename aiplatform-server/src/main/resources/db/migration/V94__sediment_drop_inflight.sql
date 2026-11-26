-- ========================================================================
-- base.knowledge：拆除沉淀草稿表在途机制（#642，阶段六-4；父票 #637，ADR-0058）。
--
-- 事后提炼每次最多落 3 条候选经验（#641），旧「每项目每类最多 3 条」上限不再需要：
-- 同一项目跨版本累计的待审候选可以并存，不受名额限制。V93 已拆在途部分唯一索引，
-- 本迁移彻底拆除结构性剩余件：
--   ① 在途序号检查约束（inflight_slot BETWEEN 1 AND 3）；
--   ② 在途序号列 inflight_slot。
-- 索引在 V59 建立、V93 已 DROP IF EXISTS；此处再幂等兜一次，干净库与存量库均可回放。
-- 不改已应用迁移。
-- ========================================================================

DROP INDEX IF EXISTS uq_knw_sediment_drafts_pending_slot;

ALTER TABLE knw_sediment_drafts
    DROP CONSTRAINT IF EXISTS ck_knw_sediment_drafts_inflight_slot;

ALTER TABLE knw_sediment_drafts
    DROP COLUMN IF EXISTS inflight_slot;
