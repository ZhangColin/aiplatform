-- ========================================================================
-- business.project：智能体运营配置面增整轨终验修复预算列（#427）。
--
-- prj_agent_configs（executor 行消费）加一列：
--   final_verify_fix_budget —— 整轨终验的修复 run 预算（#406/#427：终验 fail
--                               自动起 fix run 修→复走终验，预算耗尽版本照成
--                               ＋已知缺陷清单如实呈现；null＝回落代码缺省 2
--                               轮；0＝不起修复 run——fail 直达已知缺陷形态，
--                               合法运营选择）。
--
-- 生效语义复用既有机制：回路动态查库不缓存（每轮终验判定时读）——运营面改完
-- 下一轮判定即生效，不发版、进行中轨道不定格。既有行经列缺省自动获 null
-- （全回落，行为与切换前一致）。prj_agent_config_traces 同步两列（变更前后
-- 全量快照，回滚腿完整）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN final_verify_fix_budget INT;      -- null＝回落代码缺省（2 轮）

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_final_verify_fix_budget INT,
    ADD COLUMN new_final_verify_fix_budget INT;
