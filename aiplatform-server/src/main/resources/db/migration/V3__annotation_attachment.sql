-- ========================================================================
-- business.project：对话史标注附件（#97 圈注 B 档）
-- prj_conversation_entries 增列 attachments（JSONB，可空）：用户发言随带的
-- 圈注锚（点选/圈选/评论的结构化定位 + 可选评语）落库——刷新/回访后消息回显
-- 可重建圈注 chip（对话史落库口径：用户可回看的全部对话内容）。
-- 载荷形状与 SSE 事件清单「消息附件部件锚载荷 schema」同源
-- （[ { attachmentType, annotation: { kind, anchor, note } } ]）。
-- 仅 kind=user 携带；其余 kind 恒 NULL（同 question/closing 的分列惯例）。
-- 增量迁移（V3），不改 V1/V2 基线。
-- ========================================================================

ALTER TABLE prj_conversation_entries
    ADD COLUMN attachments JSONB;
