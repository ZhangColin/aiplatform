-- ========================================================================
-- business.project：enableThinking 开关留痕面（#370 续 V46——档位行加
-- thinking_enabled 后，变更留痕快照纳入新值面，照全量快照原则）。
--
-- prj_model_tier_traces 加 old_/new_thinking_enabled 两列（可空——创建痕
-- old 全 null、删除痕 new 全 null 的生命周期口径同既有列）：开关开/关、连同
-- 缺省保持（不落痕）的幂等语义与 enabled 同形——回滚＝旧值写回即一次新变更。
-- ========================================================================

ALTER TABLE prj_model_tier_traces
    ADD COLUMN old_thinking_enabled BOOLEAN,   -- 变更前开关（创建痕＝null）
    ADD COLUMN new_thinking_enabled BOOLEAN;   -- 变更后开关（删除痕＝null）
