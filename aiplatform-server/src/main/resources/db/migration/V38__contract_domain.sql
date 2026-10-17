-- ========================================================================
-- business.order：合同域六态（#336「合同域六态」，spec #320 实施序④
-- 合同·付款·生产承接，纯绿地）。合同对象全立：
--   ord_contracts          合同聚合（六态＋双方签署位＋范围快照冻结集＋预览字段集）
--   ord_contract_transitions 迁移留痕（append-only：操作人/时间/依据必填）
--   ord_orders 加范围文本三段（交付清单/验收方式/工期口径——后台结构化录入的
--   工作副本；「确认范围并准备合同」时定格为合同上的不可变副本＝范围快照，
--   不另立报价快照对象——报价版本引用＝冻结时点的价目行 id，改价历史本就
--   append-only）。
-- 六态照交接包原样立：1=准备中 2=待核对与签署 3=签署进行中 4=签署完成
-- 5=签署失败或过期 6=已撤回或作废（4/5/6 终态，终态合同永不物理删除——
-- 无删除口即口径）。全部后台人工推进，每次迁移 append-only 留痕。
-- 编号全局唯一（SG- 风格：SG-＋TSID base36，创建即定格）。同订单至多一份
-- 活跃（非终态）合同＝部分唯一索引（仿 uk_ord_orders_active 先例）；签署
-- 进行中起撤回/作废重建＝新号新合同、旧合同终态保留（索引天然放行）。
-- PRD 关联＝确认版本引用＋全文冗余拷贝、Demo 关联＝确认版本号引用（下单
-- 随单冻结集 #335 的合同侧冗余——合同自含，引用语义同款）。
-- 增量迁移（V38），不改已应用迁移。
-- ========================================================================

-- 订单范围文本工作副本（后台结构化录入、整组可替换；确认后变动触发合同
-- 「需更新」派生标志——冻结集在合同上，工作副本永远可改）
ALTER TABLE ord_orders ADD COLUMN scope_deliverables VARCHAR(2000);  -- 范围文本·交付清单
ALTER TABLE ord_orders ADD COLUMN scope_acceptance   VARCHAR(2000);  -- 范围文本·验收方式
ALTER TABLE ord_orders ADD COLUMN scope_timeline     VARCHAR(2000);  -- 范围文本·工期口径

CREATE TABLE ord_contracts (
    id                 BIGINT PRIMARY KEY,     -- TSID（contractNo 的正身源）
    order_id           BIGINT NOT NULL,        -- 所属订单（同 BC 软引用外键，级联同价目行）
    contract_no        VARCHAR(40) NOT NULL,   -- 合同编号（SG-<tsid base36>，全局唯一）
    status             INT NOT NULL,           -- ContractStatus：1=准备中 2=待核对与签署 3=签署进行中 4=签署完成 5=签署失败或过期 6=已撤回或作废
    version            INT NOT NULL,           -- 合同版本（草稿同号重录 +1；重建＝新号新合同从 1 起）
    -- 范围快照（冻结集：报价版本引用＋范围文本不可变副本，创建/重录时定格）
    quote_entry_id     BIGINT NOT NULL,        -- 冻结时点价目行 id（报价版本引用，append-only 行身份）
    amount             BIGINT NOT NULL,        -- 冻结金额（分，随 quote_entry 定格）
    currency           VARCHAR(10) NOT NULL,   -- 币种（v1 恒 CNY）
    scope_deliverables VARCHAR(2000) NOT NULL, -- 范围快照·交付清单
    scope_acceptance   VARCHAR(2000) NOT NULL, -- 范围快照·验收方式
    scope_timeline     VARCHAR(2000) NOT NULL, -- 范围快照·工期口径
    -- 双版本引用（#335 下单冻结集的合同侧冗余：PRD 确认版全文冗余拷贝＋版本号、Demo 确认版本号）
    prd_version_no     INT NOT NULL,           -- PRD 确认版本号（随单冻结值冗余）
    prd_snapshot       TEXT NOT NULL,          -- PRD 确认版全文冗余拷贝（合同自含）
    demo_version_no    INT NOT NULL,           -- Demo 确认版本号（引用，#15 冻结集）
    -- 预览字段集（后台结构化录入、草稿态可改；不存文档本体——电子签文件＝渠道真接后扩展位）
    party_a            VARCHAR(200),           -- 甲方主体（客户方）
    party_b            VARCHAR(200),           -- 乙方主体（服务方）
    project_scope      VARCHAR(2000),          -- 项目范围
    payment_plan       VARCHAR(2000),          -- 付款计划（文本面；节点对象归付款计划域后续票）
    -- 双方签署位两枚（未签/已签；「签署完成」＝双方均签——推进守卫）
    party_a_signed     BOOLEAN NOT NULL DEFAULT FALSE,  -- 甲方签署位
    party_b_signed     BOOLEAN NOT NULL DEFAULT FALSE,  -- 乙方签署位
    party_a_signed_at  TIMESTAMP,              -- 甲方落签时点
    party_b_signed_at  TIMESTAMP,              -- 乙方落签时点
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 创建时间（确认范围/后台重建时点）
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         BIGINT,
    updated_by         BIGINT,
    -- 订单删除级联清合同（真删，不留孤儿——同价目行；终态不删是业务口径「无删除口」，
    -- 库层不做终态删除拦截）
    CONSTRAINT fk_ord_contracts_order FOREIGN KEY (order_id)
        REFERENCES ord_orders (id) ON DELETE CASCADE
);

-- 编号全局唯一（格式实施定：SG-＋TSID base36，创建即定格、永不改）
CREATE UNIQUE INDEX uk_ord_contracts_no ON ord_contracts (contract_no);

-- 同订单至多一份活跃（非终态）合同（活跃 = 非 签署完成/签署失败或过期/已撤回或作废；
-- 终态后可重建新合同——撤回/作废重建换新号，索引天然放行）
CREATE UNIQUE INDEX uk_ord_contracts_active ON ord_contracts (order_id) WHERE status NOT IN (4, 5, 6);

-- 订单维度查询面（订单面合同卡：活跃合同/最近合同）
CREATE INDEX idx_ord_contracts_order ON ord_contracts (order_id);

-- 后台按状态拉单面（合同工作清单）
CREATE INDEX idx_ord_contracts_status ON ord_contracts (status);

-- 迁移留痕（append-only：每次状态迁移与后台写动作各一行，操作人/时间/依据必填；
-- from_status = to_status 表示非迁移类动作〔签署标记/草稿重录〕的在场快照）
CREATE TABLE ord_contract_transitions (
    id            BIGINT PRIMARY KEY,     -- TSID
    contract_id   BIGINT NOT NULL,        -- 所属合同
    action        INT NOT NULL,           -- ContractTransitionAction：1=推进 2=撤回 3=作废 4=签署标记 5=草稿重录 6=失败或过期
    from_status   INT NOT NULL,           -- 动作前状态
    to_status     INT NOT NULL,           -- 动作后状态（非迁移类＝from 同值）
    basis         VARCHAR(1000) NOT NULL, -- 依据（必填自由文本）
    operator_id   VARCHAR(64),            -- 操作者 id（admin 侧管理员 TSID；缺头落空口径）
    operator_name VARCHAR(200),           -- 操作者名（直读展示）
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 动作时间（业务时间）
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 合同删除级联清留痕（同合同口径；合同本体随订单删才可达）
    CONSTRAINT fk_ord_contract_transitions_contract FOREIGN KEY (contract_id)
        REFERENCES ord_contracts (id) ON DELETE CASCADE
);

-- 合同动作时间线（新→旧查询面）
CREATE INDEX idx_ord_contract_transitions_contract ON ord_contract_transitions (contract_id);
