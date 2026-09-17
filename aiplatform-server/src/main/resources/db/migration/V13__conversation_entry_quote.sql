-- ========================================================================
-- business.project：报价卡入对话流（#203 报价感知 1/5，ADR-0017 视镜语义）
-- prj_conversation_entries 增列 quote（JSONB，可空）：平台对用户的发言——
-- 载荷仅事件（quoted=报价已出；改价「报价已更新」归 #204）+ 订单引用
-- （orderId，TSID 十进制字符串），**不含金额**——卡为视镜非快照，金额/备注/
-- 状态渲染时取订单当前态；append-only 纪律不变（改价追加新卡不改旧卡）。
-- 仅 kind=7（报价卡）携带；其余 kind 恒 NULL（同 question/closing/attachments
-- 的分列惯例）。增量迁移（V13），不改已应用迁移。
-- ========================================================================

ALTER TABLE prj_conversation_entries
    ADD COLUMN quote JSONB;
