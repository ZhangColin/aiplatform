-- ========================================================================
-- business.project：单会话连续编码＋切片并行机器开关（#436，#432 Step 2 正式
-- 落地——切片机器退役：Demo 首建改为单一长会话连续编码，切片计划保留但降级为
-- 进度/预览叙事、不再驱动新 session；fork 隔离工作区/波调度/并行执行/合并
-- 机器开关保留、默认关；代码全留仓，运营可后台一键切回旧行为）。
--
-- 两件（缺省即 #436 落地态，ADD COLUMN 缺省回填存量行）：
--   1. single_session_generation_enabled BOOLEAN NOT NULL DEFAULT TRUE：
--      单会话连续编码开关（仅 run 执行体行消费）——true＝阶段 0＋全部切片在
--      同一执行会话槽位（coder-{projectId}-single）内连续追加（每段新对话＋
--      交接传递，#434 T2 排练实证形态），段级自愈＝同会话返工提示（不传段级
--      回滚钩）；false＝旧「每片新会话」形态（切片机器）。
--   2. parallel_slice_machine_enabled BOOLEAN NOT NULL DEFAULT FALSE：
--      切片并行机器开关（fork 隔离工作区/波调度/并行执行/合并，#351）——
--      true＝宽波 fork 并行（旧行为）；false＝全轨主工作区串行。仅单会话
--      关闭后有消费面（单会话开启时全轨串行、天然无 fork）。
--
-- prj_agent_config_traces 四列（前后快照）同步加列——留痕全量值快照口径不变。
--
-- 生效语义（#433 口径同）：轨道起跑时动态查库不缓存——运营面改完下一次生成
-- 轨道派发即生效，不发版、进行中 run 不定格。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN single_session_generation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN parallel_slice_machine_enabled    BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_single_session_generation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN old_parallel_slice_machine_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN new_single_session_generation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN new_parallel_slice_machine_enabled    BOOLEAN NOT NULL DEFAULT FALSE;
