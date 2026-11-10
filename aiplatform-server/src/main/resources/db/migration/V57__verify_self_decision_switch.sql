-- ========================================================================
-- business.project：verify 自决开关（#442，#441-1——#439 终裁 verify 窗
-- 20.9min〔真实独占估 12-15min〕的最大单一可收窄面；终裁 run 中 verify 子
-- 智能体按 #435 自决口径仍被 6/7 段拉起〔仅 stage0 未拉〕）。
--
-- prj_agent_configs（executor 行消费）加一列：
--   verify_self_decision_enabled BOOLEAN NOT NULL DEFAULT TRUE：
--      verify 自决开关——true＝自决维持现状（#435 自决化落地态：执行体生成过程
--      中自决调用 verify 自测〔工作协议第 9 条〕，验证不是收口要件）；
--      false＝自决关闭——执行体 ReAct 循环中间件（VerifySelfDecisionMiddleware）
--      每迭代动态查库，关闭态注入一条提示层指令（每会话一次、消息列回读防重灌）：
--      生成过程中不自发委派 verify 子智能体、不发起修复—复验循环；平台驱动验证
--      腿（三闸回执／整轨终验编排任务说明）不受影响。收口唯一硬判据仍是 8081
--      探活三件套。
--
-- 布尔开关同 web_search_enabled/routing_enabled/decision_hints_enabled 先例：
-- NOT NULL DEFAULT TRUE，既有行经列缺省自动获 true（行为与切换前一致）；行不
-- 存在与「行在、列 true」语义等价（全回落缺省）。
--
-- 生效语义（#437 口径同）：中间件每迭代动态查库不缓存、装配不进 agent 实例
-- 缓存键——运营面改完下一次迭代即生效，不发版、进行中 run 不定格（注入过的
-- 指令随消息列留在本会话——粘性语义对偶「进行中 run 不定格」）。
-- prj_agent_config_traces 同步两列（变更前后全量快照，回滚腿完整）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN verify_self_decision_enabled BOOLEAN NOT NULL DEFAULT TRUE;  -- verify 自决开关（#442；false＝自决关闭——提示层收窄，平台驱动验证腿不受影响）

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_verify_self_decision_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN new_verify_self_decision_enabled BOOLEAN NOT NULL DEFAULT TRUE;
