-- ========================================================================
-- business.project：生成轨道表（#220 切片计划与片状态落库，ADR-0020）
-- prj_generation_segments：项目当前生成轨道的片行（片 = 阶段 0 + 各纵向切片：
-- ord 0 = 阶段 0〔先起服〕，1..N = 切片计划逐片）。「run 无表、重启即清」口径
-- 的精确例外：切片计划与每片收口/失败状态落平台库，进程重启后计划与进度仍可查
-- （断点 = 表中最深收口片，续跑事实源）。
-- 计划生命周期跟 PRD 版本走：prd_produced_at = 计划落库时的 PRD 版本锚
-- （与 prj_projects.prd_produced_at 同值比对）——锚不一致 = PRD 已演进、现行
-- 计划过期不沿用，计划重产 = 整组替换（旧片不残留）。一项目至多一份现行片集
-- （uk 兜底）。run_id = 收口/失败的用户面 run 锚（待跑恒 NULL）。删除真删级联
-- 随项目（软引用显式清，同对话史惯例）。增量迁移（V14），不改已应用迁移。
-- ========================================================================

CREATE TABLE prj_generation_segments (
    id               BIGINT PRIMARY KEY,     -- TSID（片行标识）
    project_id       BIGINT NOT NULL,        -- 所属项目（prj_projects 软引用，无 FK）
    ord              INT NOT NULL,           -- 片序（0 = 阶段 0 先起服；1..N = 切片计划）
    description      TEXT NOT NULL,          -- 片描述（阶段 0 固定题 / 切片句「用户能 X」）
    status           INT NOT NULL,           -- GenerationSegmentStatus：1=待跑 2=已收口 3=失败
    run_id           VARCHAR(100),           -- 收口/失败的用户面 run 锚（待跑 = NULL）
    prd_produced_at  TIMESTAMP NOT NULL,     -- 计划落库时的 PRD 版本锚（锚不一致 = 计划过期）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       BIGINT,
    updated_by       BIGINT,
    CONSTRAINT uk_prj_generation_segments_project_ord UNIQUE (project_id, ord)
);
