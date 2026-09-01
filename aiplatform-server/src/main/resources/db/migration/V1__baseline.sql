-- ========================================================================
-- 片0-3 · 平台库基线一次成型（#18，squash 自 V1–V17——dev 数据可弃、无生产库）
-- 全新库单迁移直建终态 10 张表：底座 6（wsp_ 2 / knw_ 1 / met_ 2 / cat_ 1）
-- + 业务 4（prj_ 1 / ord_ 2 / idn_ 1）。旧主链 8 表（prj_iterations /
-- prj_confirmations / prj_demand_pool_entries / tsk_tasks / tsk_bugs /
-- agt_engine_config / agt_pending_waits / agt_agent_sessions）随概念出局不
-- 重建；多引擎遗留列（prj_projects.engine / met_usage_events.engine）不复活。
-- ========================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ========================================================================
-- base.workspace：工作区与中间件资源（隔离环境句柄的持久化记录）
-- 工作区 = 服务重启后按记录接回容器的锚点（置备中 → 就绪/失败，异步收敛回填）；
-- 中间件资源 = 随工作区供给的 pg/redis（一工作区各一，聚合级联增删）。
-- 生命周期与记录同生共死，无软删除——继承 Auditable 只取审计字段。
-- ========================================================================

CREATE TABLE wsp_workspaces (
    id                  BIGINT PRIMARY KEY,     -- TSID（workspaceId 即其字符串形）
    kind                INT NOT NULL,           -- EnvKind：1=DEV 2=TEST 3=PROD
    container_name      VARCHAR(100) NOT NULL,  -- 容器名（exec/预览/清理的锚点）
    network_name        VARCHAR(100) NOT NULL,  -- 项目专属 docker network
    preview_port        INT NOT NULL,           -- 预览宿主端口（runtime 0）
    provisioning_status INT NOT NULL DEFAULT 2, -- ProvisioningStatus：1=置备中 2=就绪 3=失败
    provision_error     VARCHAR(500),           -- 归一化失败原因（错误码 + 文案），非失败态 NULL
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT,
    updated_by          BIGINT,
    CONSTRAINT uq_wsp_workspaces_container UNIQUE (container_name)
);

CREATE TABLE wsp_resources (
    id              BIGINT PRIMARY KEY,          -- TSID
    workspace_id    BIGINT NOT NULL,
    kind            INT NOT NULL,                -- MiddlewareKind：1=POSTGRESQL 2=REDIS
    container_name  VARCHAR(100) NOT NULL,       -- 资源容器名（销毁级联与接回的锚点）
    host_port       INT NOT NULL,                -- 宿主机映射端口（本地工具直连）
    internal_url    VARCHAR(500) NOT NULL,       -- 容器网络内连接串（/workspace/.env 注入原文，含凭据）
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT,
    updated_by      BIGINT,
    CONSTRAINT uq_wsp_resources_workspace_kind UNIQUE (workspace_id, kind),
    -- 聚合删除 = 级联删资源行（@OnDelete 同语义，避免单向关联的置空更新）
    CONSTRAINT fk_wsp_resources_workspace FOREIGN KEY (workspace_id)
        REFERENCES wsp_workspaces (id) ON DELETE CASCADE
);

-- ========================================================================
-- base.agentscope（薄 infra 包）：智能体会话状态 cat_agent_state
-- AgentScope AgentState 的 PostgreSQL 存储：对话历史/压缩摘要/权限规则/
-- Plan Mode/tool state 按 (user_id, session_id) 槽位寻址——平台重启后同一会话
-- 标识可恢复续跑（ba-/coder- 角色会话的运行时载体，run 无表的口径即靠它）。
-- 单值 = item_index 0 一行；列表 = 逐项一行增量 append（{key}:_hash 辅助行做
-- 变更检测：hash 变/缩短 → 全量重写，纯增长 → 只插新项）。state_data 为 TEXT
-- （JSON 字符串与 hash 字符串混存，不做 jsonb 解释）。不软删除（运行时数据，
-- 无审计面）。
-- ========================================================================

CREATE TABLE cat_agent_state (
    user_id     VARCHAR(255) NOT NULL,     -- 规范化 userId（匿名桶 __anon__）
    session_id  VARCHAR(255) NOT NULL,     -- AgentScope 会话标识原样
    state_key   VARCHAR(255) NOT NULL,     -- 状态键（agent_state / memory_messages / *_hash）
    item_index  INT NOT NULL DEFAULT 0,    -- 单值恒 0；列表为序号
    state_data  TEXT NOT NULL,             -- JSON 序列化状态值（hash 行存 hash 字符串）
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, session_id, state_key, item_index)
);

-- 会话槽位整体删除面（delete session）
CREATE INDEX idx_cat_agent_state_session ON cat_agent_state (user_id, session_id);

-- ========================================================================
-- base.knowledge：知识块 knw_chunks（全局共享的跨项目知识）
-- 内容口径 = 成交项目的 PRD（订单归档时沉淀，唯一沉淀触发点）——kind 对底座
-- 仍不透明（业务词汇），业务侧只用 PRD 类；旧五类素材中的 QA/FEEDBACK/
-- TEST_REPORT/BUG 随旧主链出局。幂等键 = (kind, source_ref) 删后插；meta
-- jsonb 留扩展位。检索 = 全局纯相似，embedding 列 HNSW 余弦索引承载。
-- ========================================================================

CREATE TABLE knw_chunks (
    id           BIGINT PRIMARY KEY,     -- TSID（入库时应用侧生成）
    kind         VARCHAR(30) NOT NULL,   -- 素材类别（业务定义，底座不解释；v1 仅 PRD）
    source_ref   VARCHAR(200) NOT NULL,  -- 素材来源标识（幂等键之一）
    project_id   VARCHAR(100) NOT NULL,  -- 归属项目（purgeByProject 级联清理入口）
    project_name VARCHAR(200) NOT NULL,  -- 来源项目名（命中条目展示）
    title        VARCHAR(300) NOT NULL,  -- 素材标题（命中条目展示）
    seq          INT NOT NULL,           -- 块序（同素材内分块顺序）
    chunk        TEXT NOT NULL,          -- 块文本
    embedding    vector(512) NOT NULL,   -- 块向量（本机 fastembed，BAAI/bge-small-zh-v1.5 512 维）
    meta         JSONB,                  -- 扩展位（null/空归一为 NULL，底座不解释）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,
    updated_by   BIGINT
);

-- 幂等删插键：沉淀时 DELETE ... WHERE kind = ? AND source_ref = ?
CREATE INDEX idx_knw_chunks_kind_source_ref ON knw_chunks (kind, source_ref);

-- 项目删除级联清理入口
CREATE INDEX idx_knw_chunks_project_id ON knw_chunks (project_id);

-- HNSW 余弦近邻索引（检索 ORDER BY embedding <=> ?::vector LIMIT k）
CREATE INDEX idx_knw_chunks_embedding ON knw_chunks USING hnsw (embedding vector_cosine_ops);

-- ========================================================================
-- base.metering：用量事件 met_usage_events
-- run 级 UsageEvent 一条：event_id 为调用方生成的幂等键（重复上报
-- first-write-wins，主键即幂等约束）；subject 不透明（业务层传 projectId，
-- 底座不解释）；dims 业务维度透传——终态口径 = projectId + agentKind(ba/coder)
-- + sessionId（编码接线，底座仍不解释）。单价匹配键 = (provider, model,
-- token_kind)，无引擎维度（单栈后引擎列不复活）。五档 token 互斥分解，
-- 加和 = 提供方 total 口径，支撑「平台成本 = Σ(token_kind × 单价)」不重复计。
-- ========================================================================

CREATE TABLE met_usage_events (
    event_id    VARCHAR(64) PRIMARY KEY,  -- 幂等键（调用方生成：run 级事件建议 UUID）
    ts          TIMESTAMPTZ NOT NULL,     -- 事件发生时间（bySubject 聚合的时间轴）
    subject     VARCHAR(100) NOT NULL,    -- 不透明归属 id（业务层定；零业务概念）
    run_id      VARCHAR(100),             -- 运行标识（可空）
    session_id  VARCHAR(100),             -- agent 会话标识（可空）
    provider    VARCHAR(50) NOT NULL,     -- 模型提供方（单价表匹配键之一）
    model       VARCHAR(100) NOT NULL,    -- 模型（单价表匹配键）
    dims        JSONB,                    -- 业务维度透传（终态口径 projectId/agentKind/sessionId；无维度为 NULL）
    input       BIGINT NOT NULL,          -- 普通输入 token（不含缓存命中/缓存写）
    output      BIGINT NOT NULL,          -- 普通输出 token（不含推理）
    cache_read  BIGINT NOT NULL,          -- 缓存命中读
    cache_write BIGINT NOT NULL,          -- 缓存写
    reasoning   BIGINT NOT NULL,          -- 推理输出（OpenAI o 系列等）
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT,
    updated_by  BIGINT
);

-- bySubject 聚合（subject + 时间窗）查询面
CREATE INDEX idx_met_usage_events_subject_ts ON met_usage_events (subject, ts);

-- 单价表 met_price_entries：平台成本换算的匹配数据（base.metering 私有表，
-- 不经端口暴露，业务层零感知）。匹配键 = (provider, model, token_kind)；
-- 改价 = 关旧行（effective_to = now）+ 开新行（append 式不 UPDATE 单价），
-- 事件按 ts 落生效区间匹配，历史成本不漂移。生效区间不得重叠（唯一约束只防
-- 同起点，重叠 = 换算重复计）。unit_price 精度 numeric(20,10) 随官方价位走
-- （最低价 1.4e-8/token，8 位小数放不下）。
CREATE TABLE met_price_entries (
    id             BIGINT       PRIMARY KEY,  -- TSID（应用侧 @PrePersist 生成）
    provider       VARCHAR(50)  NOT NULL,   -- 模型提供方（与 UsageEvent.provider 同键）
    model          VARCHAR(100) NOT NULL,   -- 模型（与 UsageEvent.model 同键）
    token_kind     INT          NOT NULL,   -- TokenKind：1=input 2=output 3=cache_read 4=cache_write 5=reasoning
    unit_price     NUMERIC(20,10) NOT NULL, -- 每 token 单价（如 $1.5/M = 0.0000015）
    currency       VARCHAR(10)  NOT NULL,   -- ISO 4217 币种（按 provider 官方计价币种）
    effective_from TIMESTAMPTZ  NOT NULL,   -- 生效起点（含）
    effective_to   TIMESTAMPTZ,             -- 生效终点（不含）；NULL = 当前行
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     BIGINT,
    updated_by     BIGINT,
    CONSTRAINT uk_met_price_entries_key_from UNIQUE (provider, model, token_kind, effective_from)
);

-- 换算匹配面（provider, model, kind 等值 + effective_from 区间）由唯一约束的
-- 自建索引覆盖，不另建冗余索引

-- ========================================================================
-- business.identity：账号档案 idn_accounts
-- 首次登录按外部 ID（OIDC sub）自动建档，无角色概念（角色票再加）；无 issuer
-- 列（v1 单 IdP，多 IdP 进雾）。唯一索引 = 「同 sub 不重复建档」最终防线，
-- 常态路径靠 callback upsert 编排。不软删除（审计字段照常保留）。
-- ========================================================================

CREATE TABLE idn_accounts (
    id           BIGINT PRIMARY KEY,     -- TSID（建档时应用侧生成）
    external_id  VARCHAR(100) NOT NULL,  -- identity 侧 sub（稳定不变）
    display_name VARCHAR(200) NOT NULL,  -- 显示名（nickname→name→preferred_username→sub 推导）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,
    updated_by   BIGINT
);

CREATE UNIQUE INDEX uk_idn_accounts_external_id ON idn_accounts (external_id);

-- ========================================================================
-- business.project：项目聚合 prj_projects
-- 项目 = 用户一次定制需求的全程载体：业务字段 + 工作区引用 + 归属账号 +
-- 归档终点。prd_produced_at = 「BA 已写出 PRD」门禁事实（成果区长出判据，
-- 时间戳随每次写出刷新）；generated_at = 首次生成时点（单向置位，迭代不刷新
-- ——「确认下单」可见性与列表「进行中」推导口径的锚点）。PRD 事实源是工作区
-- docs/PRD.md（文件非库表），两列只记事实不存内容。删除真删级联，无软删除。
-- workspace_id 跨上下文软引用（wsp_workspaces，无 FK）。
-- ========================================================================

CREATE TABLE prj_projects (
    id               BIGINT PRIMARY KEY,     -- TSID（projectId 即其字符串形）
    name             VARCHAR(100) NOT NULL,  -- 项目名（用户起/LLM 取名）
    type             INT NOT NULL,           -- ProjectType：1=WEBSITE 2=ECOMMERCE
    workspace_id     BIGINT NOT NULL,        -- 工作区（wsp_workspaces 软引用，无 FK）
    owner_account_id BIGINT,                 -- 归属账号（idn_accounts 软引用；v1 不过滤）
    archived_at      TIMESTAMP,              -- 归档时间（单向终点；NULL = 未归档；支付成功联动）
    prd_produced_at  TIMESTAMP,              -- PRD 产出事实（NULL = 未产出；写出即刷新）
    generated_at     TIMESTAMP,              -- 首次生成时点（NULL = 未生成过；单向置位）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       BIGINT,
    updated_by       BIGINT
);

-- 工作区寻址查询面（工作区接回的反查）
CREATE INDEX idx_prj_projects_workspace ON prj_projects (workspace_id);

-- ========================================================================
-- business.order：订单 ord_orders + 价目留痕 ord_price_entries
-- 订单 = 确认下单后的交易载体：下单即冻结 PRD 全文快照入单（自含，不依赖
-- 工作区存亡——源码不快照，交付经 source-package 实时取）。五态单向推进：
-- 待报价 → 已报价（=待支付）→ 已支付 → 已归档；已取消自任何未支付态可达，
-- 不设「支付中」。金额单位分（Long）、v1 恒 CNY；改价仅限已报价未支付，
-- append-only 价目行留痕、订单当前金额取最新行。支付流水号 mock 为平台内
-- 生成，真实接入以 PaymentPort 为切换边界（不建支付尝试表）。
-- project_id 跨上下文软引用（prj_projects，无 FK）；owner_account_id 冗余
-- 下单账号（按用户查）。同项目至多一个未终结订单 = 部分唯一索引兜底
-- （并发下单库层拒绝，仿 OPEN iteration 先例）。
-- ========================================================================

CREATE TABLE ord_orders (
    id               BIGINT PRIMARY KEY,     -- TSID（orderId 即其字符串形）
    project_id       BIGINT NOT NULL,        -- 所属项目（prj_projects 软引用，无 FK）
    owner_account_id BIGINT,                 -- 下单账号（idn_accounts 软引用；无会话上下文可空）
    status           INT NOT NULL,           -- OrderStatus：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消
    prd_snapshot     TEXT NOT NULL,          -- 下单时 PRD 全文快照（交易标的）
    amount           BIGINT,                 -- 当前总价（分；待报价 NULL，报价落值、改价取最新价目行）
    currency         VARCHAR(10),            -- ISO 4217（v1 恒 CNY；随报价落）
    quoted_at        TIMESTAMP,              -- 首次报价时点（待报价态 NULL；改价不刷新——留痕在价目行）
    paid_at          TIMESTAMP,              -- 支付成功时点
    archived_at      TIMESTAMP,              -- 归档时点（支付成功一事务内联动项目归档）
    cancelled_at     TIMESTAMP,              -- 取消时点（未支付态取消即回迭代）
    payment_no       VARCHAR(100),           -- 支付流水号（mock 平台内生成；真实接入为渠道单号）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 下单时间（待报价态时戳）
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       BIGINT,
    updated_by       BIGINT
);

-- 同项目至多一个未终结订单（未终结 = 非 已归档/已取消；终态后可再下新单）
CREATE UNIQUE INDEX uk_ord_orders_active ON ord_orders (project_id) WHERE status NOT IN (4, 5);

-- 项目维度查询面（用户面订单视图/详情）
CREATE INDEX idx_ord_orders_project ON ord_orders (project_id);

-- 后台按状态拉单面（报价工作清单）
CREATE INDEX idx_ord_orders_status ON ord_orders (status);

-- 价目行（append-only 改价留痕）：首次报价与每次改价各一行，只插不改
CREATE TABLE ord_price_entries (
    id         BIGINT PRIMARY KEY,     -- TSID
    order_id   BIGINT NOT NULL,        -- 所属订单
    amount     BIGINT NOT NULL,        -- 本次报价金额（分）
    currency   VARCHAR(10) NOT NULL,   -- 币种（v1 恒 CNY）
    note       VARCHAR(1000),          -- 报价备注（后台文本，用户面展示）
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 报价/改价时间（业务时间）
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT,
    -- 订单删除级联清价目行（真删，不留孤儿）
    CONSTRAINT fk_ord_price_entries_order FOREIGN KEY (order_id)
        REFERENCES ord_orders (id) ON DELETE CASCADE
);

-- 订单改价历史时间线（新→旧查询面）
CREATE INDEX idx_ord_price_entries_order ON ord_price_entries (order_id);
