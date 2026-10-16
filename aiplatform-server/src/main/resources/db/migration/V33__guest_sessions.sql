-- ========================================================================
-- business.identity：访客域 idn_guests（#326 上线改版·06 访客会话域，spec #320
-- 段②——免注册先用）。
--
-- 访客独立表（不混账户表 idn_accounts）：惰性创建（首条需求消息提交成功才建行＋
-- 发凭证）、凭证值即 cookie 值（无状态校验＝按 credential 唯一索引每请求直查行）、
-- 滑动续期（last_active_at 为锚，写节流）、作废位（revoked_at，#328 绑定平移落点
-- ——本票只立字段与语义）、数据不删（过保留期/作废只是不认，行保留）。
-- 期限缺省走配置（app.guest.retention-period / renew-threshold），不落库。
--
-- 同票配套：
--   ord_orders.owner_guest_id——订单归属访客列（访客下单的 SSE 归属路由与账面
--   归属；与 owner_account_id 逻辑互斥，CHECK 兜底同 prj_projects V30 形）；
--   idx_prj_projects_owner_guest——用户面项目列表按访客过滤的查询面
--   （对齐 V30 的 idx_prj_projects_owner_account 惯例）。
-- ========================================================================

CREATE TABLE idn_guests (
    id             BIGINT PRIMARY KEY,     -- TSID（建档时应用侧生成）
    credential     VARCHAR(64) NOT NULL,   -- 凭证（值即凭证；256bit Base64url 无填充 43 字符，列宽留余量）
    last_active_at TIMESTAMP NOT NULL,     -- 最近活跃（滑动续期锚；保留期自本值起算）
    revoked_at     TIMESTAMP,              -- 作废时点（NULL = 未作废；#328 绑定平移落点）
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     BIGINT,
    updated_by     BIGINT
);

-- 无状态校验的唯一查询面：cookie 值 → 行
CREATE UNIQUE INDEX uk_idn_guests_credential ON idn_guests (credential);

ALTER TABLE ord_orders ADD COLUMN owner_guest_id BIGINT;

ALTER TABLE ord_orders ADD CONSTRAINT ck_ord_orders_owner_exclusive
    CHECK (NOT (owner_account_id IS NOT NULL AND owner_guest_id IS NOT NULL));

CREATE INDEX idx_prj_projects_owner_guest ON prj_projects (owner_guest_id);
