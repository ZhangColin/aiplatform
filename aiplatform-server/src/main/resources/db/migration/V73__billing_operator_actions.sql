-- ========================================================================
-- business.billing：后台账户运营留痕（#535 计费⑤ / ADR-0047）——赠送/手动充值/
-- 余额调整/扣减源调整四类后台动作各落一行 append-only 留痕：谁（操作者两列，
-- 照 #151 口径——admin 侧标识缺头落空）、对哪个池/项目（account/project/pool
-- 三锚列软引用）、改了多少钱（amount——赠送/充值恒正、调整带符号、源调整 NULL）、
-- 变更前后快照（balance_before/balance_after＋detail JSONB——凭证核验字段/
-- 扣减源旧值新值）。只插不改；与流水行（bil_entries）/计费态行同事务成对落。
-- ========================================================================

CREATE TABLE bil_operator_actions (
    id              BIGINT PRIMARY KEY,   -- TSID（时间有序，追加序=时序）
    action_type     INT NOT NULL,         -- OperatorActionType：1=赠送 2=手动充值
                                          -- 3=余额调整 4=扣减源调整
    account_id      BIGINT,               -- 账户锚（idn_accounts 软引用；账户池动作非空）
    project_id      BIGINT,               -- 项目锚（prj_projects 软引用；项目池/源调整动作非空）
    pool_id         BIGINT,               -- 池锚（bil_pools 软引用；源调整为 NULL——不动池）
    amount          BIGINT,               -- 金额（分；赠送/充值恒正、调整带符号、源调整 NULL）
    balance_before  BIGINT,               -- 变更前池余额快照（分；源调整为 NULL）
    balance_after   BIGINT,               -- 变更后池余额快照（分；源调整为 NULL）
    detail          JSONB,                -- 动作明细（手动充值=凭证核验三字段；源调整=旧/新扣减源快照）
    operator_id     VARCHAR(64),          -- 后台操作者（admin 侧标识；缺头落空 NULL）
    operator_name   VARCHAR(200),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT,
    updated_by      BIGINT
);

-- 读面（admin 对账/审计按项目/账户拉动作序）
CREATE INDEX idx_bil_operator_actions_project ON bil_operator_actions (project_id);
CREATE INDEX idx_bil_operator_actions_account ON bil_operator_actions (account_id);
