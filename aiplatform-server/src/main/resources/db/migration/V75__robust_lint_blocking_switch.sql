-- ========================================================================
-- business.project：基座 lint 强制档阻断开关（#541，ADR-0051「lint 拆半让路」
-- 的回滚保险，#442 verify 自决开关同款先例）。
--
-- 让路缺省（ADR-0051 用户裁定）：基座强制档 lint（拦裸写）从「阻断＋修轮＋
-- 失败链」降级为「收口照扫、违规只记录标注」（标注进收尾卡 robustKitLint
-- mandatory 分节、用户可见）——不喂修轮、不触发失败、不间接升档（11-12 复演
-- 7 次升档全 lint 族的耗时反复实源）；修轮触发面收窄至样式合规（设计规范
-- lint 软形态不动）；预置档维持 #471 目标形态（如实标注不拦收口）。残余风险
-- ＝裸写访问层无超时可能挂死，由机器探活三件套兜底（「能打开不白屏」仍硬保）。
--
-- prj_agent_configs（executor 行消费）加一列：
--   robust_lint_blocking_enabled BOOLEAN NOT NULL DEFAULT FALSE：
--      false＝让路形态（缺省——强制档违规只标注，收口链路无分岔）；
--      true＝恢复 #425 原阻断形态（强制档违规喂修轮修一轮、修不完结构性拒绝
--      ——拒绝回执喂回同 run，耗尽走段级回滚重跑升档兜底）——未来再收紧时
--      运营面一键切回、不发版。
--
-- 布尔开关同 web_search_enabled/final_verification_gate_enabled 先例（#435
-- 翻转缺省族）：NOT NULL DEFAULT FALSE，既有行经列缺省自动获 false（行为与
-- 本迁移前的一致——让路随发版生效）；行不存在与「行在、列 false」语义等价
-- （全回落缺省）。
--
-- 生效语义（#442 口径同）：收口判定处随 effectiveOf 每次尝试动态取值不缓存、
-- 不进 agent 实例缓存键——运营面改完下一次收口判定即生效，不发版、进行中
-- run 不定格。
-- prj_agent_config_traces 同步两列（变更前后全量快照，回滚腿完整）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN robust_lint_blocking_enabled BOOLEAN NOT NULL DEFAULT FALSE;  -- 基座 lint 强制档阻断开关（#541；true＝恢复原阻断形态——回滚保险）

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_robust_lint_blocking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN new_robust_lint_blocking_enabled BOOLEAN NOT NULL DEFAULT FALSE;
