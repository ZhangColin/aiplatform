-- ========================================================================
-- business.order：付款计划与凭证核验（#337「付款计划与凭证核验」，spec #320
-- 实施序④合同·付款·生产承接）。付款域四表全立（全走 ord_ 前缀）：
--   ord_payment_plans      付款计划（挂订单、随合同冻结同刻创建；撤回重建＋
--                          范围重冻→新计划行、旧计划随旧合同留痕——行
--                          append-only 不改写，活跃＝订单名下最新行）
--   ord_payment_nodes      付款节点（序号/名称/金额〔绝对额分〕/五态/附言码
--                          〔SG-<合同号后缀>-K<序> 式〕/开工门标志/渠道接口位
--                          〔渠道类型枚举＋渠道流水号可空〕/累计已核到账金额）
--   ord_payment_verifications 核验留痕（append-only：到账核验/打回各一行，
--                          实到金额/实到时间/关联凭证/依据随行落）
--   ord_payment_vouchers   转账凭证（用户上传、追加保留不覆盖——上传仅代表
--                          已提交；元数据行，字节在伴行 files 表）
--   ord_payment_voucher_files 凭证字节（bytea 伴行表——凭证与节点状态同事务
--                          落库，DB 内联使核验证据与订单生命周期同存亡〔级联
--                          随单清〕，不引平台文件库）
-- 节点五态（#337 照交接包原样立）：1=待支付 2=支付处理中 3=支付失败或关闭
-- 4=线下转账待核验 5=已到账。v1 只走凭证核验路径：待支付→（用户上传凭证）
-- 线下转账待核验→（后台核验）已到账/打回（回待支付＋依据留痕）；「处理中/
-- 失败」枚举立着、路径 v1 不通（渠道真接后启用）。节点转已到账要求全额
-- ——不足留痕＋回待支付，前台以 amount-settled_amount 推导「还需支付金额与
-- 对应节点」。渠道接口位最小形态：channel_type 枚举（1=线下转账 2=扫码〔占位〕）
-- ＋channel_ref_no 可空；既有支付端口（PaymentPort）维持现状不加宽。mock 支付
-- 闭环（同步支付端点＋确认弹窗）随本表退役，支付语义并入凭证路径。
-- 增量迁移（V39），不改已应用迁移。
-- ========================================================================

CREATE TABLE ord_payment_plans (
    id            BIGINT PRIMARY KEY,     -- TSID（订单名下最新行＝活跃计划）
    order_id      BIGINT NOT NULL,        -- 所属订单（随合同创建口落值）
    contract_id   BIGINT NOT NULL,        -- 创建时点的合同（撤回重建/重冻→新计划行挂新冻结集）
    contract_no   VARCHAR(40) NOT NULL,   -- 合同号定格（附言码正身源：SG-<base36>-K<序>）
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 创建时间（＝合同冻结同刻）
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    BIGINT,                 -- 审计基列（Auditable；v1 恒空——节点录入无操作者留痕面）
    updated_by    BIGINT,
    -- 订单删除级联清计划（同价目行/合同口径——订单是交易生命周期的根）
    CONSTRAINT fk_ord_payment_plans_order FOREIGN KEY (order_id)
        REFERENCES ord_orders (id) ON DELETE CASCADE,
    -- 合同侧级联（合同本体随订单删才可达，双 FK 同向不互斥）
    CONSTRAINT fk_ord_payment_plans_contract FOREIGN KEY (contract_id)
        REFERENCES ord_contracts (id) ON DELETE CASCADE
);

-- 订单维度查询面（活跃计划寻址：最新行）
CREATE INDEX idx_ord_payment_plans_order ON ord_payment_plans (order_id);

CREATE TABLE ord_payment_nodes (
    id              BIGINT PRIMARY KEY,     -- TSID
    plan_id         BIGINT NOT NULL,        -- 所属付款计划
    seq             INT NOT NULL,           -- 序号（1 起，录入序即展示序）
    name            VARCHAR(200) NOT NULL,  -- 名称（如「首期开工款」「中期验收款」「尾款结算」）
    amount          BIGINT NOT NULL,        -- 金额（分，绝对额录入——占比展示换算）
    status          INT NOT NULL,           -- PaymentNodeStatus：1=待支付 2=支付处理中 3=支付失败或关闭 4=线下转账待核验 5=已到账
    remark_code     VARCHAR(60) NOT NULL,   -- 附言码（<合同号>-K<序>，录入时自合同号定格派生）
    kickoff_gate    BOOLEAN NOT NULL DEFAULT FALSE,  -- 开工门标志（后台录入时定；开工款≠默认全款）
    -- 渠道接口位最小形态（渠道真接后启用，v1 恒线下转账）
    channel_type    INT NOT NULL DEFAULT 1, -- PaymentChannelType：1=线下转账 2=扫码（占位）
    channel_ref_no  VARCHAR(100),           -- 渠道流水号（可空；核验录入时落值）
    settled_amount  BIGINT NOT NULL DEFAULT 0,  -- 累计已核到账金额（分；不足留痕后仍累积，前台推导还需支付）
    received_at     TIMESTAMP,              -- 转已到账时点（实到时间，核验录入）
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 计划删除级联清节点（节点无独立存亡；节点重录＝计划内整组替换〔仅支付未开始窗口〕）
    CONSTRAINT fk_ord_payment_nodes_plan FOREIGN KEY (plan_id)
        REFERENCES ord_payment_plans (id) ON DELETE CASCADE
);

-- 计划维度查询面（节点清单按 seq 展示）
CREATE INDEX idx_ord_payment_nodes_plan ON ord_payment_nodes (plan_id);

-- 核验留痕（append-only：每次到账核验/打回各一行；实到金额/实到时间/关联凭证/
-- 依据、操作人随行落——留痕行是「不足/打回」的审计正身，只插不改写）
CREATE TABLE ord_payment_verifications (
    id            BIGINT PRIMARY KEY,     -- TSID
    node_id       BIGINT NOT NULL,        -- 所属付款节点
    action        INT NOT NULL,           -- PaymentVerificationAction：1=到账核验 2=打回
    from_status   INT NOT NULL,           -- 动作前节点状态
    to_status     INT NOT NULL,           -- 动作后节点状态
    actual_amount BIGINT,                 -- 实到金额（分；到账核验必录，打回为空）
    arrived_at    TIMESTAMP,              -- 实到时间（到账核验必录，打回为空）
    voucher_id    BIGINT,                 -- 关联凭证（ord_payment_vouchers 软引用，可空）
    basis         VARCHAR(1000),          -- 依据/备注（打回必填留痕；到账核验可空备注）
    operator_id   VARCHAR(64),            -- 操作者 id（admin 侧管理员 TSID；核验动作必携）
    operator_name VARCHAR(200),           -- 操作者名（直读展示）
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 动作时间（业务时间）
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 节点删除级联清留痕（同节点口径）
    CONSTRAINT fk_ord_payment_verifications_node FOREIGN KEY (node_id)
        REFERENCES ord_payment_nodes (id) ON DELETE CASCADE
);

-- 核验动作时间线（新→旧查询面）
CREATE INDEX idx_ord_payment_verifications_node ON ord_payment_verifications (node_id);

-- 转账凭证（用户上传、追加保留不覆盖——上传仅代表已提交，核验结论在留痕行；
-- 元数据行轻载可列，字节伴行 files 表隔离聚合读面）
CREATE TABLE ord_payment_vouchers (
    id           BIGINT PRIMARY KEY,     -- TSID
    node_id      BIGINT NOT NULL,        -- 所属付款节点
    file_name    VARCHAR(500) NOT NULL,  -- 净化后原始名（回执单/水单人读）
    content_type VARCHAR(100) NOT NULL,  -- MIME（application/pdf | image/png | image/jpeg）
    byte_size    BIGINT NOT NULL,        -- 字节数（≤10MB 业务上限）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 上传时间
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,                 -- 审计基列（Auditable；上传是用户动作，恒空）
    updated_by   BIGINT,
    -- 节点删除级联清凭证（同留痕口径）
    CONSTRAINT fk_ord_payment_vouchers_node FOREIGN KEY (node_id)
        REFERENCES ord_payment_nodes (id) ON DELETE CASCADE
);

-- 节点维度查询面（凭证清单/核验关联校验）
CREATE INDEX idx_ord_payment_vouchers_node ON ord_payment_vouchers (node_id);

-- 凭证字节伴行表（bytea 内联：凭证与节点状态同事务落库、随单级联清——平台零
-- 文件库口径下不引新存储面；仅按 id 点取，不进任何清单查询）
CREATE TABLE ord_payment_voucher_files (
    voucher_id BIGINT PRIMARY KEY,     -- 所属凭证（同 id 一对一）
    content    BYTEA NOT NULL,         -- 文件字节
    -- 凭证删除级联清字节（字节无独立存亡）
    CONSTRAINT fk_ord_payment_voucher_files_voucher FOREIGN KEY (voucher_id)
        REFERENCES ord_payment_vouchers (id) ON DELETE CASCADE
);
