-- ========================================================================
-- business.project：失败留账入对话流（#418 失败片落库面——观测地基批）
-- prj_conversation_entries 增列 failure（JSONB，可空）：run 失败终态的失败面
-- ——逐尝试墙钟/档位/失败原因/本场累计，**与 SSE run-failed 扩载同载荷**
-- （同收尾卡「SSE 扩载与落库同载荷」先例，拼装单点在事件桥：先落库后发事件）。
-- 仅 kind=8（失败留账）携带；其余 kind 恒 NULL（同 question/closing/quote 的
-- 分列惯例）。平台观测口径（速度观测不再只看成功者——失败作废可归因），用户面
-- 零呈现（前端水合弃守未知 kind，同 durationBreakdown 不渲染）。append-only
-- 纪律不变。增量迁移（V48），不改已应用迁移。
-- ========================================================================

ALTER TABLE prj_conversation_entries
    ADD COLUMN failure JSONB;
