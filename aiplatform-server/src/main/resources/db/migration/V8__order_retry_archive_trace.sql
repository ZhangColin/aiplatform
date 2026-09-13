-- ========================================================================
-- business.order：重试归档操作者留痕（#158）——ord_orders 补归档操作者两列
--
-- 重试归档（后台写口）语义与支付链自动归档完全一致（复用 Order.archive 守卫，
-- 仅已支付态可达），差异仅在操作者落痕。落点取订单行列（归档一次性、终态，不建
-- 伴随记录，同 V7 取消留痕先例）：operator 两列＝admin 侧管理员 TSID+昵称，非
-- 平台用户，VARCHAR 存外域标识不与 accountId 混型，服务端不校验真实性。
--
-- 落空口径：支付链自动归档（无人工触发）与签名请求缺 X-User-Id/X-User-Name
-- 透传头时，两列落 NULL；存量行不回填（呈现为空）。
-- ========================================================================

ALTER TABLE ord_orders
    ADD COLUMN archive_operator_id   VARCHAR(64),
    ADD COLUMN archive_operator_name VARCHAR(200);
