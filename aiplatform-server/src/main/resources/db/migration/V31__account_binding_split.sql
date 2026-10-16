-- ========================================================================
-- business.identity：账户绑定表拆分（#327，spec-320 ②「绑定表拆分」）
--
-- idn_account_bindings：账户凭据的唯一下落——account_id × provider（1=oidc
-- 2=email 3=phone）× identifier，unique(provider, identifier) 兜底「同标识
-- 不重复建档/挂绑」。迁 SSO、加验证码通道、换身份供数方都只动本表，账号档
-- （idn_accounts）随之瘦身为 id＋显示名＋时间戳。
--
-- 存量搬迁：idn_accounts.external_id（OIDC sub）一次性搬为 provider=1（oidc）
-- 行，ON CONFLICT DO NOTHING 兜底重放（Flyway 单迁移单事务本已原子，此兜底
-- 防人工重跑脚本/squash 重放翻倍）。搬迁行 id 直接复用账号 id：同源 TSID
-- 空间时间唯一、不与未来生成的 TSID 相撞，且一对一账号至多一行，无碰撞面。
--
-- 随后 accounts 瘦身：删 external_id 列与其唯一索引（凭据一律住绑定表）。
-- 增量迁移（V31），不改已应用迁移。
-- ========================================================================

CREATE TABLE idn_account_bindings (
    id           BIGINT PRIMARY KEY,     -- TSID（建档时应用侧生成；搬迁行复用账号 id，见头注）
    account_id   BIGINT NOT NULL,        -- 归属账号（idn_accounts 软引用，无 FK）
    provider     INT NOT NULL,           -- AccountBindingProvider：1=oidc 2=email 3=phone
    identifier   VARCHAR(200) NOT NULL,  -- provider 侧标识（oidc sub / 邮箱 / 手机号；归一形态由应用侧定）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,
    updated_by   BIGINT,
    CONSTRAINT uk_idn_account_bindings_provider_identifier UNIQUE (provider, identifier),
    CONSTRAINT ck_idn_account_bindings_provider CHECK (provider IN (1, 2, 3))
);

CREATE INDEX idx_idn_account_bindings_account ON idn_account_bindings (account_id);

-- backfill-oidc-start
INSERT INTO idn_account_bindings (id, account_id, provider, identifier, created_at, updated_at)
SELECT a.id, a.id, 1, a.external_id, a.created_at, a.updated_at
FROM idn_accounts a
ON CONFLICT (provider, identifier) DO NOTHING;
-- backfill-oidc-end

DROP INDEX uk_idn_accounts_external_id;

ALTER TABLE idn_accounts DROP COLUMN external_id;
