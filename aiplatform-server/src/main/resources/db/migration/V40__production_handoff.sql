-- ========================================================================
-- business.project：生产承接（#338「开工判定与生产承接」，spec #320 实施序④
-- 合同·付款·生产承接收官）。开工判定＝双门禁（合同签署完成 && 全部开工门
-- 节点已到账）满足时刻，订单转「已支付」（新语义＝开工条件满足）＋创建生产
-- 承接记录（同一事务）。本迁移立三面：
--   prj_production_handoffs            承接记录（挂项目、只建一次＝project_id
--                                      唯一索引兜底＋服务端事务内判定；两态：
--                                      1=待安排生产 2=生产中，后台人工推进）
--   prj_production_handoff_transitions 推进留痕（append-only：操作人/时间/依据
--                                      必填，照 ord_contract_transitions 形制）
--   prj_projects.needs_user_supplement 前台六态「需用户补充」的项目级标志
--                                      （后台置位/清零，六态推导读面）
-- 前台六态（待签约/待付款/款项核验中/待安排生产/生产中/需用户补充）＝订单＋
-- 合同＋付款＋承接四源纯推导视图、不存储（读口在订单侧投影端点）。承接字段：
-- 交付联系人/下一步说明/进展更新（人工措辞、不上工程术语）/预计开始时间
-- （仅录入真实值才显示——可空）。
-- 增量迁移（V40），不改已应用迁移。
-- ========================================================================

CREATE TABLE prj_production_handoffs (
    id                 BIGINT PRIMARY KEY,     -- TSID
    project_id         BIGINT NOT NULL,        -- 所属项目（只建一次——库侧唯一约束兜底）
    order_id           BIGINT NOT NULL,        -- 触发开工判定的订单（软引用，正身溯源）
    status             INT NOT NULL,           -- ProductionHandoffStatus：1=待安排生产 2=生产中
    contact            VARCHAR(200),           -- 交付联系人（可空＝未录入）
    next_step          VARCHAR(1000),          -- 下一步说明（人工措辞）
    progress_note      VARCHAR(2000),          -- 进展更新（人工措辞、不上工程术语）
    estimated_start_at TIMESTAMP,              -- 预计开始时间（仅真实值；可空＝待安排不显示）
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         BIGINT,                 -- 审计基列（Auditable；创建＝开工判定联动恒空）
    updated_by         BIGINT,
    -- 项目删除级联清承接（承接随项目存亡；生产无终态概念——两态即全部）
    CONSTRAINT fk_prj_production_handoffs_project FOREIGN KEY (project_id)
        REFERENCES prj_projects (id) ON DELETE CASCADE
);

-- 「挂项目、只建一次」最终防线（全生命周期一行、无终态概念→普通唯一索引，
-- 非 ord_contracts 的部分唯一索引——终态放行重建在那里有语义、在这里没有）
CREATE UNIQUE INDEX uk_prj_production_handoffs_project ON prj_production_handoffs (project_id);

-- 订单维度溯源查询面（order_id 软引用不设 FK——订单删除晚于承接由项目侧级联收口）
CREATE INDEX idx_prj_production_handoffs_order ON prj_production_handoffs (order_id);

-- 推进留痕（append-only：创建/推进/信息更新各一行，操作人/时间/依据随行落；
-- 只插不改写——全列库侧无可更新路径即口径）
CREATE TABLE prj_production_handoff_transitions (
    id            BIGINT PRIMARY KEY,     -- TSID
    handoff_id    BIGINT NOT NULL,        -- 所属承接记录
    action        INT NOT NULL,           -- ProductionHandoffAction：1=创建（开工判定触发） 2=推进 3=信息更新
    from_status   INT NOT NULL,           -- 动作前状态
    to_status     INT NOT NULL,           -- 动作后状态（非迁移类＝from 同值）
    basis         VARCHAR(1000) NOT NULL, -- 依据（必填自由文本——人工推进留痕的凭据）
    operator_id   VARCHAR(64),            -- 操作者标识（创建行＝开工判定系统操作者）
    operator_name VARCHAR(200),           -- 操作者昵称（直读展示）
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_prj_production_handoff_transitions_handoff FOREIGN KEY (handoff_id)
        REFERENCES prj_production_handoffs (id) ON DELETE CASCADE
);

CREATE INDEX idx_prj_production_handoff_transitions_handoff ON prj_production_handoff_transitions (handoff_id);

-- 前台六态「需用户补充」的项目级标志（#338）：生产承接期间需用户补资料/确认
-- 事项时后台置位、处理完清零；六态推导以本标志盖帽（非终态订单最高优先级）
ALTER TABLE prj_projects ADD COLUMN needs_user_supplement BOOLEAN NOT NULL DEFAULT FALSE;
