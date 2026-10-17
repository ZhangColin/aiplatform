-- ========================================================================
-- business.project：Demo 构建版本表＋确认标记（#333「Demo 版本化与确认标记」，
-- spec #320 实施序③ PRD 确认门·版本快照·生成加门）
-- prj_demo_versions：每轮 Demo 构建收口自动成版的项目内递增整数版本行
-- （v1/v2/v3…）——首版随 generated_at 同刻落（生成收口），此后每轮迭代 run
-- 收口（系统实动 finish_edit changed=true）递增成版；每版锚定起跑时已确认
-- PRD 版本（prd_version_no，「Demo vN（基于 PRD vM 快照）」式关联——在途
-- Demo 照跑完收口绑起跑时版本，不随后续确认漂移；无确认版项目/设计类起跑
-- 为 NULL）。run_id = 收口 run 的用户面标识（版本记录抽屉 #344「查看当时」
-- 的 git 成版 Run-Id 同锚）。
-- 版本表模式先例＝prj_prd_versions（V32）：不可变＝只插不改（聚合零变更
-- 方法），删除真删级联随项目（软引用显式清，同轨道表惯例）。
-- 确认标记不落版本行（行不可变）：prj_projects 两列单指针承载——
-- 「确认此版本，准备签约」＝既有版本上打标记（选定动作、不产生新版本号；
-- 换确认版本＝指针移动，旧标记随移）；下单/合同冻结引用挂 #335。
-- 增量迁移（V35），不改已应用迁移。
-- ========================================================================

CREATE TABLE prj_demo_versions (
    id              BIGINT PRIMARY KEY,     -- TSID（版本行标识）
    project_id      BIGINT NOT NULL,        -- 所属项目（prj_projects 软引用，无 FK）
    version_no      INT NOT NULL,           -- 项目内递增整数版本号（1 起，v1/v2/v3…）
    prd_version_no  INT,                    -- 起跑时已确认 PRD 版本锚（无确认版 = NULL）
    run_id          VARCHAR(100) NOT NULL,  -- 收口 run 的用户面标识（git 成版 Run-Id 同锚）
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT,
    updated_by      BIGINT,
    CONSTRAINT uk_prj_demo_versions_project_version UNIQUE (project_id, version_no)
);

ALTER TABLE prj_projects ADD COLUMN confirmed_demo_version_no INT;
ALTER TABLE prj_projects ADD COLUMN confirmed_demo_at TIMESTAMP;
