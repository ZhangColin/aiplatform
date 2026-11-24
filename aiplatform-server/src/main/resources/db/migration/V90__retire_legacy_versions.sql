-- ========================================================================
-- business.project：拆旧 Demo/PRD 版本数据与端点（阶段四-9 #630，ADR-0056）。
--
-- 阶段四版本重建为单轴用户面版本（git log，#626/#627），「确认此版本，准备签约」
-- 改标记当前用户面版本、下单/合同冻结收敛为「已确认用户面版本号」（#629，V89）。
-- 旧双轴版本面（PRD 版本组 × Demo 版本组）整条退场：
--
--   删表 prj_prd_versions —— 旧 PRD 构建基准版本行（V32/V60）不再写入、无人读；
--   删表 prj_demo_versions —— 旧 Demo 构建版本行（V35）不再写入、无人读；
--   改义项目确认列 —— confirmed_demo_version_no/confirmed_demo_at 更名
--                     confirmed_version_no/confirmed_version_at：语义由「Demo
--                     版本号」改为「已确认用户面版本号（git 版本号）」＋确认时点。
--                     列承载的仍是项目单指针（#629 confirm 写入），仅改义更名。
--
-- 订单/合同的历史版本引用（ord_orders/ord_contracts.prd_version_no/
-- demo_version_no）保留原值：历史订单自含快照（prd_snapshot），旧版本表删除后
-- 不回填、照读；新单单版本号走 V89 新增的 confirmed_version_no。
--
-- 存量确认指针值不改写：确认列改名后原值照留——#629 起指针写入的即用户面版本号，
-- 存量值语义与改名后一致（无版本表耦合）。
--
-- 增量迁移（V90），不改已应用迁移；干净库与存量库均可回放。
-- ========================================================================

DROP TABLE IF EXISTS prj_prd_versions;

DROP TABLE IF EXISTS prj_demo_versions;

ALTER TABLE prj_projects RENAME COLUMN confirmed_demo_version_no TO confirmed_version_no;

ALTER TABLE prj_projects RENAME COLUMN confirmed_demo_at TO confirmed_version_at;
