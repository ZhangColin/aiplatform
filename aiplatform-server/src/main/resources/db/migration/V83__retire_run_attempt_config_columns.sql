-- ========================================================================
-- business.project：run 尝试体旧机制退场·配置列组（阶段二-4 #604，ADR-0055
-- 砍掉清单——从零重设总索引 #573 定案）。
--
-- 两开关列拆除（判定消费面已随 #604 run 执行体旧机制退役归零）：
--   prj_agent_configs.proof_of_output_gate_enabled（V62 空转产出证明闸 #420/#433；
--     产出证明闸本体 ProofOfOutputGate 随 #604 删除——收口判据回落硬门三件套
--     〔服务探活＋入口文件完整＋git 成版〕）。
--   prj_agent_configs.generate_image_enabled（V67 构建轨出图开关 #545；构建轨
--     generate_image 槽位挂 Executor 行的增强面随 #604 拆除，designer 槽同名
--     骨架件不读本列）。
--   prj_agent_config_traces 留痕四面（old_/new_ 各二）：append-only 历史里的
--     死旋钮列无保留价值（回滚依据面已随机制退役）。
--
-- executor 装配键旧中间件的开关列拆除（#604 增强开关——生成与更新 run 全部经
-- 内核装配形态派发，装配键＝generation-kernel，executor 键不再进任何派发路径：
-- 按该键挂载的两个旧栈中间件已删除，开关无消费面）：
--   决策时微指令注入四列（V56 #437）：decision_hints_enabled /
--     decision_hint_error_streak / decision_hint_stack_cap / decision_hint_disabled；
--   verify 自决开关一列（V57 #442）：verify_self_decision_enabled；
--   prj_agent_config_traces 对应留痕十面（old_/new_ 各五）。
--
-- 增量迁移（V83），不改已应用迁移。
-- ========================================================================

ALTER TABLE prj_agent_configs
    DROP COLUMN IF EXISTS proof_of_output_gate_enabled,
    DROP COLUMN IF EXISTS generate_image_enabled,
    DROP COLUMN IF EXISTS decision_hints_enabled,
    DROP COLUMN IF EXISTS decision_hint_error_streak,
    DROP COLUMN IF EXISTS decision_hint_stack_cap,
    DROP COLUMN IF EXISTS decision_hint_disabled,
    DROP COLUMN IF EXISTS verify_self_decision_enabled;

ALTER TABLE prj_agent_config_traces
    DROP COLUMN IF EXISTS old_proof_of_output_gate_enabled,
    DROP COLUMN IF EXISTS old_generate_image_enabled,
    DROP COLUMN IF EXISTS new_proof_of_output_gate_enabled,
    DROP COLUMN IF EXISTS new_generate_image_enabled,
    DROP COLUMN IF EXISTS old_decision_hints_enabled,
    DROP COLUMN IF EXISTS old_decision_hint_error_streak,
    DROP COLUMN IF EXISTS old_decision_hint_stack_cap,
    DROP COLUMN IF EXISTS old_decision_hint_disabled,
    DROP COLUMN IF EXISTS new_decision_hints_enabled,
    DROP COLUMN IF EXISTS new_decision_hint_error_streak,
    DROP COLUMN IF EXISTS new_decision_hint_stack_cap,
    DROP COLUMN IF EXISTS new_decision_hint_disabled,
    DROP COLUMN IF EXISTS old_verify_self_decision_enabled,
    DROP COLUMN IF EXISTS new_verify_self_decision_enabled;
