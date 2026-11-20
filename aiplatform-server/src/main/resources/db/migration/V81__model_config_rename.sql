-- ========================================================================
-- business.project：模型配置概念全链改名（#554，spec #552 grilling 定稿——
-- 「档位」不达义，概念正名「模型配置」；ADR-0053 ③ 已新词）。纯改名：表/列/
-- 约束/索引名随族 RENAME，数据行零丢失零变换、行为零变化。
--
-- 改名面：
--   ① 表族五张：prj_model_tiers→prj_model_configs、候选表/三张留痕表同族
--      （candidate/trace/switch_trace/candidate_trace 复数族）。
--   ② 列族：tier_key→config_key（五张表族同名贯通——配置表主键＋候选表外键腿
--      ＋留痕表列，沿 tier_key 一名贯通惯例）；agent_configs 引用腿三族九列
--      （model_id→model_config_key 等——名实对齐，消除 V44 刷档残留的
--      「model_id 名不副实」：列值早已是配置键而非模型名）。
--   ③ 约束/主键/外键名随表族（Postgres 表改名不带约束名，须显式 RENAME）。
--
-- 硬边界（数据不是名字）：flash/pro 配置键值、display_name 库值（「快模型档」
-- 等）、留痕行历史值零变化；V44-V47/V79/V80 原文零改动（Flyway 只增不改——
-- 已应用迁移 checksum 不可动，V56 事故口径）。
-- ========================================================================

-- ① 表族改名（五张）
ALTER TABLE prj_model_tiers RENAME TO prj_model_configs;
ALTER TABLE prj_model_tier_candidates RENAME TO prj_model_config_candidates;
ALTER TABLE prj_model_tier_traces RENAME TO prj_model_config_traces;
ALTER TABLE prj_model_tier_switch_traces RENAME TO prj_model_config_switch_traces;
ALTER TABLE prj_model_tier_candidate_traces RENAME TO prj_model_config_candidate_traces;

-- ② tier_key 列族改 config_key（五张表族同名贯通）
ALTER TABLE prj_model_configs RENAME COLUMN tier_key TO config_key;
ALTER TABLE prj_model_config_candidates RENAME COLUMN tier_key TO config_key;
ALTER TABLE prj_model_config_traces RENAME COLUMN tier_key TO config_key;
ALTER TABLE prj_model_config_switch_traces RENAME COLUMN tier_key TO config_key;
ALTER TABLE prj_model_config_candidate_traces RENAME COLUMN tier_key TO config_key;

-- ③ 约束名随表族（主键/外键/复合主键）
ALTER TABLE prj_model_configs
    RENAME CONSTRAINT prj_model_tiers_pkey TO prj_model_configs_pkey;
ALTER TABLE prj_model_configs
    RENAME CONSTRAINT fk_model_tier_provider TO fk_model_config_provider;
ALTER TABLE prj_model_config_candidates
    RENAME CONSTRAINT pk_model_tier_candidates TO pk_model_config_candidates;
ALTER TABLE prj_model_config_candidates
    RENAME CONSTRAINT fk_tier_candidate_tier TO fk_config_candidate_config;
ALTER TABLE prj_model_config_candidates
    RENAME CONSTRAINT fk_tier_candidate_provider TO fk_config_candidate_provider;
-- 三张留痕表行内 PRIMARY KEY 的隐式约束名（V45/V79/V80 的 id BIGINT PRIMARY KEY
-- ——Postgres 自动名随建表名，表改名不带约束，须显式 RENAME 收尾）
ALTER TABLE prj_model_config_traces
    RENAME CONSTRAINT prj_model_tier_traces_pkey TO prj_model_config_traces_pkey;
ALTER TABLE prj_model_config_switch_traces
    RENAME CONSTRAINT prj_model_tier_switch_traces_pkey TO prj_model_config_switch_traces_pkey;
ALTER TABLE prj_model_config_candidate_traces
    RENAME CONSTRAINT prj_model_tier_candidate_traces_pkey TO prj_model_config_candidate_traces_pkey;

-- ②（续）agent_configs 引用腿三族九列（本体三列＋留痕 old_/new_ 六列）
ALTER TABLE prj_agent_configs RENAME COLUMN model_id TO model_config_key;
ALTER TABLE prj_agent_configs
    RENAME COLUMN routing_strong_model_id TO routing_strong_model_config_key;
ALTER TABLE prj_agent_configs RENAME COLUMN verify_model_id TO verify_model_config_key;
ALTER TABLE prj_agent_config_traces RENAME COLUMN old_model_id TO old_model_config_key;
ALTER TABLE prj_agent_config_traces RENAME COLUMN new_model_id TO new_model_config_key;
ALTER TABLE prj_agent_config_traces
    RENAME COLUMN old_routing_strong_model_id TO old_routing_strong_model_config_key;
ALTER TABLE prj_agent_config_traces
    RENAME COLUMN new_routing_strong_model_id TO new_routing_strong_model_config_key;
ALTER TABLE prj_agent_config_traces
    RENAME COLUMN old_verify_model_id TO old_verify_model_config_key;
ALTER TABLE prj_agent_config_traces
    RENAME COLUMN new_verify_model_id TO new_verify_model_config_key;
