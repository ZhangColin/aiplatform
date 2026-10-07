-- ========================================================================
-- business.project：项目设计规范正本（#295 设计规范提炼与项目正本，ADR-0028）
--
-- prj_design_specs：项目级的设计一致性正本——单一一份（一项目至多一行，
-- uk 兜底）、各设计物共用（品牌一致性即「规范」语义）、随定稿刷新（新定稿
-- 覆盖旧规范＝整行覆写）。提炼＝伴随产出＋确定性提取、不经模型判读：
-- 界面类＝定稿时平台从稿内 :root 结构化直提（tokens 面：有序名→值，
-- shadcn 语义变量集为底＋品牌附加色扩展＋字体对＋radius）；平面类＝出图
-- 设计参数（色板/风格）随稿物化为规范草稿、定稿转正（palette/style 面——
-- 参数即设计意图正身，logo 品牌色由此进系统主题）。两面互斥（一次定稿
-- 一面在场，应用层保证）。提炼源锚＝定稿稿（与 prj_design_items.
-- finalized_path 同形）＋定稿 runId（与定稿收尾卡/成版 Run-Id 同锚）。
-- 消费面＝规范遵守三件套（#296 基座物化）与设计资产包（#297 规范文件）。
-- 删除真删级联随项目（软引用显式清，同设计轨道表惯例）。
-- 增量迁移（V28），不改已应用迁移。
-- ========================================================================

CREATE TABLE prj_design_specs (
    id                BIGINT PRIMARY KEY,     -- TSID（正本行标识）
    project_id        BIGINT NOT NULL,        -- 所属项目（prj_projects 软引用，无 FK）
    source_draft_path VARCHAR(500) NOT NULL,  -- 提炼源：定稿选定的稿（工作区锚定形）
    source_run_id     VARCHAR(100) NOT NULL,  -- 提炼锚：定稿 runId（成版 Run-Id 同锚）
    tokens            JSONB,                  -- 界面类直提面：:root token（名→值；参数形为 NULL）
    palette           JSONB,                  -- 平面类转正面：色板（token 形为 NULL）
    style             VARCHAR(500),           -- 平面类转正面：风格短语
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        BIGINT,
    updated_by        BIGINT,
    CONSTRAINT uk_prj_design_specs_project UNIQUE (project_id)
);
