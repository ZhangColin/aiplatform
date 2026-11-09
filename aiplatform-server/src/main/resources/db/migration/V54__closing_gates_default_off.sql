-- ========================================================================
-- business.project：收口三硬门开关缺省翻转（#435，#432 Step 1 正式落地——
-- 砍验证税：三闸由「缺省开」翻为「缺省关」，秒级确定性探活三件套〔8081 探活＋
-- 入口文件完整＋git 成版〕成为唯一收口要件；#433 的开关机制原样保留，运营
-- 可在后台一键切回旧行为，闸代码全留仓）。
--
-- 两件：
--   1. 三列缺省 TRUE→FALSE（V53 承载面翻转）：此后新建行缺省闸关；
--   2. 既有行 UPDATE 三列=FALSE：在跑/存量环境随发版切到砍税态（运营想
--      切回旧行为＝后台 PUT 三开关 true，留痕回滚腿完整）。
--
-- prj_agent_config_traces 六列缺省同步翻 FALSE（前后快照列，与主表口径一致；
-- 应用写痕时全量显式落列，缺省仅兜底）。
--
-- 生效语义不变（#433 口径）：闸判定处动态查库不缓存——运营面改完下一次闸
-- 判定即生效，不发版、进行中 run 不定格。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ALTER COLUMN verification_presence_gate_enabled SET DEFAULT FALSE,
    ALTER COLUMN proof_of_output_gate_enabled       SET DEFAULT FALSE,
    ALTER COLUMN final_verification_gate_enabled    SET DEFAULT FALSE;

UPDATE prj_agent_configs
SET verification_presence_gate_enabled = FALSE,
    proof_of_output_gate_enabled       = FALSE,
    final_verification_gate_enabled    = FALSE;

ALTER TABLE prj_agent_config_traces
    ALTER COLUMN old_verification_presence_gate_enabled SET DEFAULT FALSE,
    ALTER COLUMN old_proof_of_output_gate_enabled       SET DEFAULT FALSE,
    ALTER COLUMN old_final_verification_gate_enabled    SET DEFAULT FALSE,
    ALTER COLUMN new_verification_presence_gate_enabled SET DEFAULT FALSE,
    ALTER COLUMN new_proof_of_output_gate_enabled       SET DEFAULT FALSE,
    ALTER COLUMN new_final_verification_gate_enabled    SET DEFAULT FALSE;
