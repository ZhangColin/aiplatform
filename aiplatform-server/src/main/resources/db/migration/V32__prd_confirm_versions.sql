-- ========================================================================
-- business.project：PRD 确认版本表（#330「确认即成版」，spec #320 实施序③
-- PRD 确认门·版本快照·生成加门）
-- prj_prd_versions：项目内已确认 PRD 版本行——确认动作把当时工作区 PRD
-- （docs/PRD.md 活草稿）全文拷贝为不可变快照落库：全文（content）＋确认时间
-- （confirmed_at）＋项目内递增整数版本号（version_no，v1/v2/v3…——原型
-- 「v1.2 小数」形不取；草稿不编号，确认即分配版本号）。
-- 版本表模式先例＝切片计划落库（V14 prj_generation_segments）：prd_produced_at
-- ＝成版时的 PRD 产出锚（与 prj_projects.prd_produced_at 同源比对——锚定
-- PRD 产出事实、记谱系观测；「重复确认（无新草稿）不产生新版本」的成版判定
-- 正本是快照全文比对，不靠锚）。
-- 不可变＝只插不改（无 UPDATE 路径，聚合零变更方法）；删除真删级联随项目
-- （软引用显式清，同对话史/轨道表惯例）。工作区 PRD 仍是活草稿照旧覆盖写，
-- 已确认版本不受后续 savePrd 影响。下单/合同冻结改「引用已确认 PRD 版本＋
-- 全文冗余拷贝」的引用语义挂后续票（#335）。增量迁移（V32），不改已应用迁移。
-- ========================================================================

CREATE TABLE prj_prd_versions (
    id               BIGINT PRIMARY KEY,     -- TSID（版本行标识）
    project_id       BIGINT NOT NULL,        -- 所属项目（prj_projects 软引用，无 FK）
    version_no       INT NOT NULL,           -- 项目内递增整数版本号（1 起，v1/v2/v3…）
    content          TEXT NOT NULL,          -- 确认时工作区 PRD 全文快照（markdown 原样）
    confirmed_at     TIMESTAMP NOT NULL,     -- 确认时间（确认动作落库时点）
    prd_produced_at  TIMESTAMP NOT NULL,     -- 成版时的 PRD 产出锚（谱系观测；成版判定＝全文比对）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       BIGINT,
    updated_by       BIGINT,
    CONSTRAINT uk_prj_prd_versions_project_version UNIQUE (project_id, version_no)
);
