-- ========================================================================
-- business.billing：充值余额计费域落库（#531 计费① / ADR-0047——双池单源扣减、
-- 段间硬门、负余额软模型）。bil_ 表前缀；金额 Long 分、v1 恒 CNY；流水
-- append-only；account/project 弱引用不外键（账本可分离——未来独立账户服务的缝）。
--
-- bil_pools 双池：账户余额（账户名下、跨项目）与项目余额（项目专属）各至多一池
-- （部分唯一索引），余额可负（负余额软模型——负余额落源池，上界≈一段实耗，由
-- 段间硬门保证）。池惰性建（首笔入账才出现）；试用项目零 billing 行。
--
-- bil_project_billing 项目计费态＝两轨判定单读点：扣减源（一次性选定；NULL=未设）
-- ＋付费状态（flipped_at；NULL=试用；不可逆）。「扣减源未设＋未翻转＝试用；
-- 源选定＋翻转＝付费」四消费面（拦截闸/派发门/协议分轨/体验提示）同读本表。
-- source_account_id＝选「我的账户」时的账户定格（选定即冻结，不随项目归属漂移）。
--
-- bil_entries 流水（append-only 只插不改）：每笔池变动一行，带行后余额快照
-- （balance_after）＋操作者留痕（后台动作）；扣减行（kind=2）带结算段归属
-- （segment_type/segment_ref）＋用量/单价/折算率快照（snapshot JSONB——历史
-- 不随调参漂移）。kind：1=入账（充值/赠送）2=扣减（段收口/固定单价项直记）
-- 3=调整（后台余额调整——返还与纠错同载体，ADR-0047 退款两线）。
--
-- bil_params 计费运营参数（param_key 自然主键）：全局折算率 conversion_rate
-- 初始 1（token 项用户扣减＝成本单价×折算率；固定单价项不乘）。运营面可调
-- （后续票挂后台端点），调整走行 UPDATE（参数非流水，快照落在扣减行上）。
-- ========================================================================

CREATE TABLE bil_pools (
    id          BIGINT PRIMARY KEY,     -- TSID（应用侧 @PrePersist 生成）
    kind        INT NOT NULL,           -- PoolKind：1=账户池 2=项目池
    account_id  BIGINT,                 -- 账户（idn_accounts 软引用；kind=1 非空）
    project_id  BIGINT,                 -- 项目（prj_projects 软引用；kind=2 非空）
    balance     BIGINT NOT NULL,        -- 余额（分；可负——负余额软模型）
    currency    VARCHAR(10) NOT NULL,   -- ISO 4217（v1 恒 CNY）
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT,
    updated_by  BIGINT,
    CONSTRAINT ck_bil_pools_owner CHECK (
        (kind = 1 AND account_id IS NOT NULL AND project_id IS NULL)
        OR (kind = 2 AND project_id IS NOT NULL AND account_id IS NULL))
);

-- 双池各至多一池（部分唯一索引：kind 归属列唯一）
CREATE UNIQUE INDEX uk_bil_pools_account ON bil_pools (account_id) WHERE kind = 1;
CREATE UNIQUE INDEX uk_bil_pools_project ON bil_pools (project_id) WHERE kind = 2;

CREATE TABLE bil_project_billing (
    id                   BIGINT PRIMARY KEY,     -- TSID
    project_id           BIGINT NOT NULL,        -- 项目（prj_projects 软引用）
    deduction_source     INT,                    -- DeductionSource：1=账户余额 2=项目余额；NULL=未选
    source_account_id    BIGINT,                 -- 选账户源时的账户定格（idn_accounts 软引用）
    source_selected_at   TIMESTAMPTZ,            -- 源选定/调整时点
    source_operator_id   VARCHAR(64),            -- 后台调整操作者（用户自选/无头落 NULL）
    source_operator_name VARCHAR(200),
    flipped_at           TIMESTAMPTZ,            -- 付费状态翻转时点（计费边界锚）；NULL=试用
    flip_gate            INT,                    -- FlipGate：1=岔路口（门①）2=拦截卡（门②）
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           BIGINT,
    updated_by           BIGINT
);

-- 两轨判定单读点：一项目一行（惰性建——首次源选定/翻转/入账即选择才出现）
CREATE UNIQUE INDEX uk_bil_project_billing_project ON bil_project_billing (project_id);

CREATE TABLE bil_entries (
    id             BIGINT PRIMARY KEY,     -- TSID（时间有序，追加序=时序）
    pool_id        BIGINT NOT NULL,        -- 所属池（bil_pools 同 BC 软引用——池永不删除，不设 FK）
    project_id     BIGINT,                 -- 扣减行落段归属项目；账户池入账/调整行可空
    kind           INT NOT NULL,           -- EntryKind：1=入账 2=扣减 3=调整
    amount         BIGINT NOT NULL,        -- 金额（分；入账恒正、扣减恒负、调整可正可负）
    balance_after  BIGINT NOT NULL,        -- 行落定后池余额（可负）
    currency       VARCHAR(10) NOT NULL,   -- v1 恒 CNY
    segment_type   INT,                    -- 扣减行：SegmentType 1=主智能体轮 2=run 场 3=设计会话轮 4=固定单价项
    segment_ref    VARCHAR(100),           -- 扣减行：段寻址（runId 等调用方标识）
    snapshot       JSONB,                  -- 扣减行：用量+单价+折算率快照（历史不随调参漂移）
    operator_id    VARCHAR(64),            -- 后台动作操作者（平台动作/无头落 NULL）
    operator_name  VARCHAR(200),
    note           VARCHAR(500),           -- 备注（充值/调整留痕面）
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     BIGINT,
    updated_by     BIGINT
);

-- 流水读面（我的账户/项目流水，后续票消费）
CREATE INDEX idx_bil_entries_pool ON bil_entries (pool_id);
CREATE INDEX idx_bil_entries_project ON bil_entries (project_id);

-- 段幂等最终防线（ADR-0047「段收口即结算一次」）：同 (项目, 段型, 段址) 至多一条
-- 扣减行——段收口重试/重放不双扣（服务层 first-write-wins 先拦，库侧兜并发）；
-- 固定单价项的段址＝发生锚点（如用量事件 eventId），同锚点直记同受约束。
CREATE UNIQUE INDEX uk_bil_entries_segment ON bil_entries (project_id, segment_type, segment_ref)
    WHERE kind = 2;

CREATE TABLE bil_params (
    param_key     VARCHAR(100) PRIMARY KEY,   -- 参数自然键（如 conversion_rate）
    param_value   NUMERIC(20,10) NOT NULL,    -- 参数值（折算率无量纲）
    operator_id   VARCHAR(64),                -- 最近调整操作者（种子行落 NULL）
    operator_name VARCHAR(200),
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    BIGINT,
    updated_by    BIGINT
);

-- 全局折算率初始 1（运营面可调；扣减行带快照，历史不漂移）
INSERT INTO bil_params (param_key, param_value, created_at, updated_at)
VALUES ('conversion_rate', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
