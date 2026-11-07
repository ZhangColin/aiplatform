-- ========================================================================
-- business.project：模型路由信号面修订（#426，spec #416 路由信号修复——承
-- #414 裁决）：片复杂度信号（枚举密度）退役、规模标签升档映射替换其配置项语义；
-- 文件面阈值随「排除基座文件基数」重校（代码缺省 60 → 30，探针在容器内扣
-- /opt/baseline 基数、基座基数随镜像 tag 单源——代码面改动，无列变更）。
--
-- 枚举密度信号退役依据（调研 2026-11-06-llm-segment-efficiency-profile）：枚举
-- 段数度量描述写法而非实现面积——「描述短、功能重」的大片（锚案 ord8 双题型
-- 测试引擎 38.3 分钟、365 次迭代）信号面 1 项、系统性误留快档。替换形态＝规模
-- 标签（小/中/大封闭集，主智能体产计划时自报，#423 结构化产出随计划落库）：
-- 缺省映射「大」升强档、小/中回落人工档；映射可经运营配置面调整不发版。
--
-- 列变更（configs 一列＋traces old/new 两列）：
--   退役 routing_slice_feature_threshold（INTEGER，V43 引入）——枚举密度阈值，
--   语义不连续（「N 项枚举」无对应物），值不迁移、直接 DROP；
--   新增 routing_escalating_size_labels（VARCHAR，逗号接小/中/大 token、按
--   code 定序；NULL＝未设回落代码缺省「大」；空串＝空映射＝规模标签信号整体
--   关停〔合法运营选择〕）。留痕表同形两列（存储形快照，回滚腿完整）。
--
-- 他环境执行前同核：routing_slice_feature_threshold 若有非 null 覆盖值（运营
-- 曾调过枚举阈值），DROP 即弃——语义已退役无对应物，弃值如实（留痕链可查旧值）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    DROP COLUMN routing_slice_feature_threshold,
    ADD COLUMN routing_escalating_size_labels VARCHAR(32);
    -- NULL＝未设（回落代码缺省「大」）；空串＝空映射（信号关停）；如「中,大」

ALTER TABLE prj_agent_config_traces
    DROP COLUMN old_routing_slice_feature_threshold,
    DROP COLUMN new_routing_slice_feature_threshold,
    ADD COLUMN old_routing_escalating_size_labels VARCHAR(32),
    ADD COLUMN new_routing_escalating_size_labels VARCHAR(32);
