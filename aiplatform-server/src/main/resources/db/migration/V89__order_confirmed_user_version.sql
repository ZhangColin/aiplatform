-- ========================================================================
-- business.order：下单/合同版本引用收敛为已确认用户面版本号（阶段四-8 #629，
-- ADR-0056 商业面改接）。
--
-- 背景：旧版版本面是 PRD 版本组 × Demo 版本组双轴，下单/合同冻结「PRD 版本号＋
-- Demo 版本号」两枚引用（V37）。阶段四版本重建为单轴用户面版本（git 提交，
-- #626/#627），「确认此版本，准备签约」改标记当前用户面版本；订单/合同冻结
-- 收敛为「已确认用户面版本号」一个引用。
--
-- 本迁移只增列、放宽旧列可空，不删表：
--   ord_orders.confirmed_version_no    下单冻结的已确认用户面版本号（新单写入；
--                                      遗留单 NULL——旧 prd_version_no/
--                                      demo_version_no 照读，历史不改写）；
--   ord_contracts.confirmed_version_no 合同冻结集同源冗余（新合同写入；遗留合同 NULL）；
--   ord_contracts.prd_version_no / demo_version_no 放宽为可空——新合同只写
--                                      confirmed_version_no（旧合同值原样保留）。
--
-- 旧版本表（prj_prd_versions/prj_demo_versions）与项目确认列的改义/删除归
-- 阶段四-9 #630，本迁移不触碰。
--
-- 增量迁移（V89），不改已应用迁移；新库与存量库均可回放。
-- ========================================================================

ALTER TABLE ord_orders ADD COLUMN confirmed_version_no INT;

ALTER TABLE ord_contracts ADD COLUMN confirmed_version_no INT;

ALTER TABLE ord_contracts ALTER COLUMN prd_version_no DROP NOT NULL;

ALTER TABLE ord_contracts ALTER COLUMN demo_version_no DROP NOT NULL;
