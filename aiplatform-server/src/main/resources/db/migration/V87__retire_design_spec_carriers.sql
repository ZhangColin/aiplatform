-- ========================================================================
-- business.project：设计物／设计规范载体与项目设计列退场（设计线整条退役-3 #612，
-- 父票 #609 裁决：设计线整条不要了）。
--
-- 拆除面：
--   ① prj_design_items（V26、V27 加列）——设计轨道件行整表退场：设计过程与执行体
--      已在 #610 拆除，件行无载体；
--   ② prj_design_specs（V28、V77 加列）——设计规范正本整表退场：设计稿与定稿链路
--      已退场，规范提炼／物化／收口扫描随之拆除；
--   ③ prj_style_direction_switch（V77）——风格方向层 kill-switch 随设计规范物化链
--      退场（物化无载体，开关无读面）；
--   ④ prj_projects.endpoint_type 列与 CHECK——终点类型收窄为系统单值（码位 2），
--      存量设计类项目（1／3）随列拆除隐式归为系统单值，无需逐行数据迁移；
--   ⑤ prj_projects.design_scope_type／design_scope_pages 两列与 CHECK——系统＋设计
--      的设计范围锚定随设计线退场。
--
-- 增量迁移（V87），不改已应用迁移；新库（全量迁移后）与旧库（含存量数据）均可起。
-- ========================================================================

DROP TABLE IF EXISTS prj_design_items;

DROP TABLE IF EXISTS prj_design_specs;

DROP TABLE IF EXISTS prj_style_direction_switch;

ALTER TABLE prj_projects DROP CONSTRAINT IF EXISTS ck_prj_projects_endpoint_type;

ALTER TABLE prj_projects DROP CONSTRAINT IF EXISTS ck_prj_projects_design_scope_type;

ALTER TABLE prj_projects DROP COLUMN IF EXISTS endpoint_type;

ALTER TABLE prj_projects DROP COLUMN IF EXISTS design_scope_type;

ALTER TABLE prj_projects DROP COLUMN IF EXISTS design_scope_pages;
