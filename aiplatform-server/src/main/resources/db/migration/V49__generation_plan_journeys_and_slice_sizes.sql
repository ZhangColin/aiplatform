-- ========================================================================
-- business.project：切片计划结构化输出扩展落库（#423，spec #416 决定集首批：
-- #406 整轨终验载荷契约＋#414 路由信号）
-- 两件随切片计划同批落库、同生命周期（PRD 演进即重产＝整组替换、进程重启不丢，
-- ADR-0020 轨道表口径的延伸）：
-- ① prj_generation_segments.size_label：每片规模标签（小/中/大封闭集，模型路由
--    信号〔#414〕）——SliceSizeLabel code（1=小 2=中 3=大，库交互以 Integer code
--    为准、工具契约 token 小/中/大在应用边界转换）；NULL = 未设（本迁移前旧片行
--    与阶段 0 片行——消费面按「无信号」处理，存量不回补）。片行除状态与 run 锚
--    外不可变（updatable=false，同 description/depends_on），标签是计划整组替换
--    的单位。
-- ② prj_generation_plan_journeys：核心旅程清单（#406 整轨终验的载荷轴）——
--    主智能体随切片计划同批结构化自报的「角色＋入口＋关键动作串」逐条行；
--    prd_produced_at = 计划落库时的 PRD 版本锚（与片行同锚同事务），锚不一致
--    （PRD 已演进）＝过期不沿用，重产 = 整组替换（旧行不残留）。一项目至多一份
--    现行旅程集（uk 兜底）。删除真删级联随项目（软引用显式清，同轨道片行惯例）。
-- 增量迁移（V49），不改已应用迁移。
-- ========================================================================

ALTER TABLE prj_generation_segments
    ADD COLUMN size_label INT;

CREATE TABLE prj_generation_plan_journeys (
    id               BIGINT PRIMARY KEY,     -- TSID（旅程行标识）
    project_id       BIGINT NOT NULL,        -- 所属项目（prj_projects 软引用，无 FK）
    ord              INT NOT NULL,           -- 旅程序（1..M，主智能体产出序）
    role             TEXT NOT NULL,          -- 角色（谁在用）
    entry            TEXT NOT NULL,          -- 入口（从哪个页面进来）
    key_actions      TEXT NOT NULL,          -- 关键动作串（一路按顺序做什么）
    prd_produced_at  TIMESTAMP NOT NULL,     -- 计划落库时的 PRD 版本锚（锚不一致 = 过期）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       BIGINT,
    updated_by       BIGINT,
    CONSTRAINT uk_prj_generation_plan_journeys_project_ord UNIQUE (project_id, ord)
);
