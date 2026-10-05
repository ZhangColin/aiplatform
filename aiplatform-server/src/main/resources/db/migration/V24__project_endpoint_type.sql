-- ========================================================================
-- business.project：项目终点类型属性（#285 设计线·步2，ADR-0024「项目不分型、
-- 终点是属性」）：单一项目实体带终点类型（1=设计 2=系统 3=系统＋设计，对齐订单
-- 交付物类型枚举），入口两档显式选择定初值（入口面属门面票——落地前一律系统）、
-- 下单前可变（设置 tab 控件＝项目内唯一变更位）、下单即冻结（守卫归编排）。
-- 存量与新建行 DEFAULT 2（系统）回填/缺省一致。
--
-- design_scope_type/design_scope_pages：设计范围（ADR-0025 切换受理时选——
-- 全部页面 1 / 勾选页面 2，系统＋设计项目专用持久位）：设计主线的计划对应物是
-- PRD 设计物清单章（不另存范围，NULL）；系统＋设计 PRD 保持系统形、设计范围＝
-- 功能清单页面集，勾选子集无处安身故落本列（pages 为功能清单条目标签 jsonb，
-- 建议性锚不是稳定标识）。设计主线出身（转系统开发）不由页面锚定，两列同为
-- NULL。增量迁移（V24），不改已应用迁移。
-- ========================================================================

ALTER TABLE prj_projects ADD COLUMN endpoint_type INT NOT NULL DEFAULT 2;

ALTER TABLE prj_projects ADD COLUMN design_scope_type INT;

ALTER TABLE prj_projects ADD COLUMN design_scope_pages JSONB;

ALTER TABLE prj_projects ADD CONSTRAINT ck_prj_projects_endpoint_type
    CHECK (endpoint_type IN (1, 2, 3));

ALTER TABLE prj_projects ADD CONSTRAINT ck_prj_projects_design_scope_type
    CHECK (design_scope_type IN (1, 2));
