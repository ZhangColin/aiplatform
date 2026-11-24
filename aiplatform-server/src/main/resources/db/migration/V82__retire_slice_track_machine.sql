-- ========================================================================
-- business.project：切片与轨道机器整组退场（阶段二-2 #602，ADR-0055 生成执行栈组
-- 砍掉清单——从零重设总索引 #573 定案）。
--
-- 三张整表拆除（机制已裁，表无消费面）：
--   prj_generation_segments（V14 轨道片表＋V41 depends_on＋V49 size_label）：
--     切片计划与片收口/失败状态的落库面——单会话/并行波两形态与断点续跑
--     （#221）的事实源，随轨道编排本体一并退役（#602）。
--   prj_generation_plan_journeys（V49 核心旅程整表）：整轨终验（#406/#427）
--     的载荷轴——终验机器随旧轨道退役（#602 提前处置，原归 #605 审查收口组）。
--   prj_generation_plan_deferrals（V71 推迟清单整表）：计划侧推迟清单副本
--     （PRD「推迟清单」章是保留腿、不受本迁移影响——本表只是计划工具
--     saveBuildPlan 的伴生产物）。
--
-- 两开关列拆除（V55 生成会话形态两开关，#436——单会话连续编码＋切片并行机器，
-- 判定消费面随 #602 轨道编排退役归零）：prj_agent_configs 两列＋
-- prj_agent_config_traces 留痕四面（old_/new_ 各二）。留痕列一并拆——
-- append-only 历史里的死旋钮列无保留价值（回滚依据面已随机制退役）。
--
-- 增量迁移（V82），不改已应用迁移。
-- ========================================================================

DROP TABLE IF EXISTS prj_generation_plan_deferrals;
DROP TABLE IF EXISTS prj_generation_plan_journeys;
DROP TABLE IF EXISTS prj_generation_segments;

ALTER TABLE prj_agent_configs
    DROP COLUMN IF EXISTS single_session_generation_enabled,
    DROP COLUMN IF EXISTS parallel_slice_machine_enabled;

ALTER TABLE prj_agent_config_traces
    DROP COLUMN IF EXISTS old_single_session_generation_enabled,
    DROP COLUMN IF EXISTS old_parallel_slice_machine_enabled,
    DROP COLUMN IF EXISTS new_single_session_generation_enabled,
    DROP COLUMN IF EXISTS new_parallel_slice_machine_enabled;
