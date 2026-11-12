-- #462 去确认门：prj_prd_versions 去门化——Demo 类 run（首构建/修正重建）起跑自动记行
-- （快照当时工作区 PRD 全文），版本号语义改为构建基准序（ADR-0039）。来源 run_id 为
-- 可空新增列：确认制时代存量行保持 NULL（Flyway 只增不改——既有列不动）。
ALTER TABLE prj_prd_versions ADD COLUMN run_id VARCHAR(64);
