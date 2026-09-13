-- ========================================================================
-- business.order：运营取消留痕（#157）——ord_orders 补取消原因＋操作者两列
--
-- 运营取消（后台写口）语义与用户取消完全一致（复用 Order.cancel 守卫），差异
-- 仅在必填取消原因＋操作者落痕。落点取订单行列（取消一次性、终态，不建伴随
-- 记录）：reason＝运营内部口径，不呈现任何用户面读面；operator 两列口径同
-- V6 ord_price_entries（admin 侧管理员 TSID+昵称，非平台用户，VARCHAR 存外域
-- 标识不与 accountId 混型，服务端不校验真实性）。
--
-- 落空口径：用户取消（无痕）与签名请求缺 X-User-Id/X-User-Name 透传头时，
-- operator 两列落 NULL；存量行不回填（呈现为空）。原因列用户取消恒 NULL。
-- ========================================================================

ALTER TABLE ord_orders
    ADD COLUMN cancel_reason        VARCHAR(1000),
    ADD COLUMN cancel_operator_id   VARCHAR(64),
    ADD COLUMN cancel_operator_name VARCHAR(200);
