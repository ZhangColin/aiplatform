-- ========================================================================
-- business.project：项目 owner 双列（#325 上线改版·05，spec #320 段②访客会话
-- 与账号绑定——修既有安全缺口「单账号假设全量返回」）。
--
-- owner_guest_id：归属访客列（访客独立表不混账户表，表体属 #326 访客域）——
-- 本票只建列、暂无写入方与挂载表（软引用，无 FK）。owner_account_id 自 V1
-- 已在且创建即随会话落值，存量行已带归属、无需回填。
--
-- 互斥约束：owner_account_id 与 owner_guest_id 至多一方落值（皆可空——无主行
-- 是测试/无会话上下文的合法占位形）；库层 CHECK 兜底，聚合侧等值谓词
-- Project.ownedBy 承载数据面过滤与单项目守卫。
--
-- idx_prj_projects_owner_account：用户面项目列表按 owner 过滤的查询面
-- （对齐既有 idx_prj_projects_workspace 的寻址索引惯例）。增量迁移（V30），
-- 不改已应用迁移。
-- ========================================================================

ALTER TABLE prj_projects ADD COLUMN owner_guest_id BIGINT;

ALTER TABLE prj_projects ADD CONSTRAINT ck_prj_projects_owner_exclusive
    CHECK (NOT (owner_account_id IS NOT NULL AND owner_guest_id IS NOT NULL));

CREATE INDEX idx_prj_projects_owner_account ON prj_projects (owner_account_id);
