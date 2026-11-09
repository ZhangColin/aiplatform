-- ========================================================================
-- business.project：智能体运营配置面增收口三硬门开关（#433，#432 Step 1 的
-- 开关承载面——砍验证税不改代码，闸由运营面开关控制、不发版可回退）。
--
-- prj_agent_configs（executor 行消费）加三列：
--   verification_presence_gate_enabled —— 验证在场闸（#424：本 run 无
--                                          report_verification 登记不准收口）
--   proof_of_output_gate_enabled       —— 空转产出证明闸（#420：段起点以来
--                                          零 diff 且无「能力已存在」确认不收口）
--   final_verification_gate_enabled    —— 整轨终验拦「生成完成」（#427：轨道
--                                          完成前以核心旅程为轴终验，fail 拦叙事）
--
-- 三列缺省 TRUE＝闸开＝现行为（#433 本票零行为变化）；FALSE＝该闸跳过。
-- 布尔开关同 web_search_enabled/routing_enabled 先例：NOT NULL DEFAULT TRUE，
-- 既有行经列缺省自动获 true（行为与切换前一致）；行不存在与「行在、三列 true」
-- 语义等价（全回落缺省）。
--
-- 生效语义复用既有机制：闸判定处动态查库不缓存——运营面改完下一次闸判定即
-- 生效，不发版、进行中 run 不定格（对偶 final_verify_fix_budget 动态读口径）。
-- prj_agent_config_traces 同步六列（变更前后全量快照，回滚腿完整）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN verification_presence_gate_enabled BOOLEAN NOT NULL DEFAULT TRUE,  -- 验证在场闸（#424；false＝闸跳过）
    ADD COLUMN proof_of_output_gate_enabled       BOOLEAN NOT NULL DEFAULT TRUE,  -- 空转产出证明闸（#420；false＝闸跳过）
    ADD COLUMN final_verification_gate_enabled    BOOLEAN NOT NULL DEFAULT TRUE;  -- 整轨终验拦「生成完成」（#427；false＝终验不起 run）

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_verification_presence_gate_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN old_proof_of_output_gate_enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN old_final_verification_gate_enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN new_verification_presence_gate_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN new_proof_of_output_gate_enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN new_final_verification_gate_enabled    BOOLEAN NOT NULL DEFAULT TRUE;
