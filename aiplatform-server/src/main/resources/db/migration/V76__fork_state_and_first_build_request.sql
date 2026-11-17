-- ========================================================================
-- business.project：岔路口状态位＋首建派发点火时点（#542 访谈② / ADR-0048＋
-- ADR-0049——自动派发两入口整体退役、岔路口三选卡与付费轨 PRD 确认门）。
--
-- fork_state：试用态访谈自然收口（判定明确含提前明确——非快道）置 PENDING（code 1），
-- 岔路口三选卡的在场事实（持久可回：未选择前关页回来卡仍在）；NULL＝不在场
-- （未收口／快道直生成不出卡）。点火后不回清——卡呈现由 generation_requested_at
-- 派生隐藏（失败重试走「继续生成」，卡不复现）。单值枚举（ForkState.PENDING），
-- INT 无 CHECK（值域归 BaseEnumConverter autoApply 单源——落库 Integer code，
-- 同聚合 ProjectType/ProjectEndpointType 先例，防 ORDINAL 错位）。
--
-- generation_requested_at：首个构建派发点火时点（岔路口「立即生成」／付费轨
-- PRD 确认／快道直生成共同落位，单向置位不回清）——岔路口卡与确认按钮
-- 「首建未派发」呈现条件的事实源（确认按钮呈现条件＝付费态∧PRD 在∧本位空）。
-- ========================================================================

ALTER TABLE prj_projects
    ADD COLUMN fork_state INT,
    ADD COLUMN generation_requested_at TIMESTAMP;
