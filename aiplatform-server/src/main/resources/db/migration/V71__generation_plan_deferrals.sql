-- ========================================================================
-- business.project：切片计划结构化推迟段落库（ADR-0050，#532——验证让路速度
-- 决议的实施切片①）：主智能体随切片计划同批结构化自报的推迟清单（本期不做的
-- 功能面，与 PRD「推迟清单」章同内容）逐条一行。
--
-- 生命周期同切片计划（ADR-0020 轨道表口径）：落库时记 prd_produced_at 版本锚
-- （与片行/旅程行同锚同事务）——PRD 演进即锚不一致、旧推迟项过期不沿用，重产 =
-- 整组替换（旧行不残留）。一项目至多一份现行推迟集（uk 兜底）。删除真删级联
-- 随项目（软引用显式清，同轨道片行惯例）。
--
-- 协议约定非机器闸：平台不校验拦截推迟清单的执行（执行体「清单内不做、不自行
-- 加回」是提示层约定）；本表是事实观测面（落库有据、随 PRD 版本演进重产可查），
-- 不参与任何收口判定。
--
-- 增量迁移（V71），不改已应用迁移。
-- ========================================================================

CREATE TABLE prj_generation_plan_deferrals (
    id               BIGINT PRIMARY KEY,     -- TSID（推迟行标识）
    project_id       BIGINT NOT NULL,        -- 所属项目（prj_projects 软引用，无 FK）
    ord              INT NOT NULL,           -- 推迟项序（1..M，主智能体产出序）
    item             TEXT NOT NULL,          -- 推迟项（本期不做的功能面＋示意级边界，用户语言）
    prd_produced_at  TIMESTAMP NOT NULL,     -- 计划落库时的 PRD 版本锚（锚不一致 = 过期）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       BIGINT,
    updated_by       BIGINT,
    CONSTRAINT uk_prj_generation_plan_deferrals_project_ord UNIQUE (project_id, ord)
);
