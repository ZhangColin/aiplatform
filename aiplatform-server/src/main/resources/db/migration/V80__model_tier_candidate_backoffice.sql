-- ========================================================================
-- 后台写面 schema（#559，spec #552-T5，ADR-0053 后台维护面）：候选队列
-- CRUD 子资源＋通道 vendor 受控值写面，两件：
--   ① prj_model_tier_candidate_traces：候选行变更留痕（append-only，照 V45
--      两留痕表机制——TSID 主键、无 FK 跨行存活、old_/new_ 前后快照＋操作者）。
--      候选行无稳定行键（PK＝tier_key+priority，排序即键变），快照以候选身份
--      （通道×实际模型名）为轴：移动/换位＝old/new priority 各自可见。
--   ② prj_model_provider_traces 加 old_vendor/new_vendor：vendor 纳入通道
--      变更快照（#559 受控值写面起 vendor 可改——快照缺腿则换归一不可回滚）。
-- ========================================================================

-- ① 候选行变更留痕（append-only；id＝TSID 写痕时生成——照 V45 机制）
CREATE TABLE prj_model_tier_candidate_traces (
    id                BIGINT PRIMARY KEY,
    tier_key          VARCHAR(50)  NOT NULL,  -- 档位语义键（无 FK——留痕跨一切存活）
    old_priority      INT,                    -- 变更前队列序（新增痕＝null）
    old_provider_key  VARCHAR(50),            -- 变更前通道键（候选身份腿一）
    old_model_name    VARCHAR(200),           -- 变更前实际模型名（候选身份腿二）
    old_enabled       BOOLEAN,                -- 变更前行级启停
    new_priority      INT,                    -- 变更后队列序（删除痕＝null）
    new_provider_key  VARCHAR(50),
    new_model_name    VARCHAR(200),
    new_enabled       BOOLEAN,
    operator_id       VARCHAR(64),            -- 变更操作者（写操作必留痕）
    operator_name     VARCHAR(200),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ② 通道留痕表 vendor 快照腿（既有行 NULL——历史痕无 vendor 面，如实）
ALTER TABLE prj_model_provider_traces
    ADD COLUMN old_vendor VARCHAR(50),
    ADD COLUMN new_vendor VARCHAR(50);
