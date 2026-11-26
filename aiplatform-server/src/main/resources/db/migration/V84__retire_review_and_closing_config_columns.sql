-- ========================================================================
-- business.project：审查与收口组退场·配置列组（阶段二-5 #605，ADR-0055
-- 砍掉清单——从零重设总索引 #573 定案；#579 逐项判决）。
--
-- prj_agent_configs（executor 行旧消费面）六列拆除，判定消费面已随
-- #602/#604 旧轨道与 run 尝试体退役归零：
--   verify_model_config_key / verify_round_budget（V50 #424 片轻验 verify
--     子智能体配置与轮次预算）——验证子智能体与 report_verification 上报
--     工具已随 #604 委派机制退役，验证事实登记件随 #605 删除；
--   final_verify_fix_budget（V52 #427 整轨终验修复预算）——终验机器随
--     #602 轨道退场；
--   verification_presence_gate_enabled / final_verification_gate_enabled
--     （V53 #433 收口三开关之二；第三列 proof_of_output_gate_enabled 已经
--     V83 拆除）——收口三运营开关整体出局（#579 判决 7：宿主机制全删，
--     开关无意义）；
--   robust_lint_blocking_enabled（V75 #541 基座 lint 强制档阻断开关）——
--     基座 lint 扫描半边已随收口判决出局（ADR-0055 结论一），扫描服务与
--     收尾卡标注行随 #605 删除。
--
-- prj_agent_config_traces 留痕十二面（old_/new_ 各六）同步拆除：
-- append-only 历史里的死旋钮列无保留价值（回滚依据面已随机制退役）。
--
-- 增量迁移（V84），不改已应用迁移。
-- ========================================================================

ALTER TABLE prj_agent_configs
    DROP COLUMN IF EXISTS verify_model_config_key,
    DROP COLUMN IF EXISTS verify_round_budget,
    DROP COLUMN IF EXISTS final_verify_fix_budget,
    DROP COLUMN IF EXISTS verification_presence_gate_enabled,
    DROP COLUMN IF EXISTS final_verification_gate_enabled,
    DROP COLUMN IF EXISTS robust_lint_blocking_enabled;

ALTER TABLE prj_agent_config_traces
    DROP COLUMN IF EXISTS old_verify_model_config_key,
    DROP COLUMN IF EXISTS old_verify_round_budget,
    DROP COLUMN IF EXISTS old_final_verify_fix_budget,
    DROP COLUMN IF EXISTS old_verification_presence_gate_enabled,
    DROP COLUMN IF EXISTS old_final_verification_gate_enabled,
    DROP COLUMN IF EXISTS old_robust_lint_blocking_enabled,
    DROP COLUMN IF EXISTS new_verify_model_config_key,
    DROP COLUMN IF EXISTS new_verify_round_budget,
    DROP COLUMN IF EXISTS new_final_verify_fix_budget,
    DROP COLUMN IF EXISTS new_verification_presence_gate_enabled,
    DROP COLUMN IF EXISTS new_final_verification_gate_enabled,
    DROP COLUMN IF EXISTS new_robust_lint_blocking_enabled;
