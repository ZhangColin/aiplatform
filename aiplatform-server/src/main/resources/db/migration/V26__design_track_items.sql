-- ========================================================================
-- business.project：设计轨道表（#289 设计执行体槽位＋首产编排，ADR-0024/0025）
--
-- prj_design_items：项目当前设计清单的件行——PRD 清单章条目（设计主线＝
-- 「设计物清单」、系统＋设计＝「功能清单」按设计范围圈定）的执行事实载体，
-- 对偶生成轨道表 prj_generation_segments（V14）。每设计物一个设计会话、
-- 按清单序逐件串行（序源自清单、改序＝改 PRD）；清单生命周期跟 PRD 版本走
-- （prd_produced_at 版本锚，PRD 演进即整组重产——已收口件按标题精确对照
-- 保留：推进序随新清单、已收口不重做）。一项目至多一份现行件集（uk 兜底）。
-- run_id = 收口/失败的用户面 run 锚（待跑恒 NULL）。删除真删级联随项目
-- （软引用显式清，同轨道表惯例）。增量迁移（V26），不改已应用迁移。
-- ========================================================================

CREATE TABLE prj_design_items (
    id               BIGINT PRIMARY KEY,     -- TSID（件行标识）
    project_id       BIGINT NOT NULL,        -- 所属项目（prj_projects 软引用，无 FK）
    ord              INT NOT NULL,           -- 件序（1..N＝清单条目序，首产推进序）
    title            VARCHAR(500) NOT NULL,  -- 清单章条目首行原文（任务锚＋重产对照键）
    status           INT NOT NULL,           -- DesignItemStatus：1=待跑 2=已收口 3=失败
    run_id           VARCHAR(100),           -- 收口/失败的用户面 run 锚（待跑 = NULL）
    prd_produced_at  TIMESTAMP NOT NULL,     -- 清单落库时的 PRD 版本锚（锚不一致 = 清单过期）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       BIGINT,
    updated_by       BIGINT,
    CONSTRAINT uk_prj_design_items_project_ord UNIQUE (project_id, ord)
);
