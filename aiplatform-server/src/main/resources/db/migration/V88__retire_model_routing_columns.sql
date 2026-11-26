-- ========================================================================
-- business.project：模型路由死旋钮退场·配置列组（阶段二-6 #606，ADR-0055
-- 砍掉清单——从零重设总索引 #573 定案；#583-4 逐项判决）。
--
-- 模型路由与升档窗口整组退场：路由判定服务 ModelRoutingAppService 删除、
-- 后台 agent-configs 读写面与留痕面去 routing 字段、模型配置删除护栏不再校验
-- 路由强档绑定、升档窗口 attempt 级概念随旧机器消失（run 单发无重试、纠偏轮
-- 不再升档）。模型配置／候选队列体系与供给链各表原样保留（ADR-0055 结论一）。
--
-- prj_agent_configs（executor 行旧消费面）六列拆除：
--   routing_enabled（V42 #354 路由总开关）；
--   routing_strong_model_config_key（V42 强模型档落点）；
--   routing_file_count_threshold（V42 文件面阈值，V51 #426 语义重校）；
--   routing_escalating_size_labels（V51 #426 规模标签升档映射）；
--   routing_file_count_signal_enabled / routing_size_label_signal_enabled
--     （V58 #454/ADR-0037 结构性信号两开关）。
--
-- prj_agent_config_traces 留痕镜像十二面（old_/new_ 各六）同步拆除：
-- append-only 历史里的死旋钮列无保留价值（回滚依据面已随机制退役）。
--
-- 重启条件（ADR-0055 组⑤同门）：实证出现「快配置稳定干不动、强配置干得动」
-- 且运营手动换档跟不上——届时按准入标准从零重判，不复活本列组。
--
-- 增量迁移（V88），不改已应用迁移。
-- ========================================================================

ALTER TABLE prj_agent_configs
    DROP COLUMN IF EXISTS routing_enabled,
    DROP COLUMN IF EXISTS routing_strong_model_config_key,
    DROP COLUMN IF EXISTS routing_file_count_threshold,
    DROP COLUMN IF EXISTS routing_escalating_size_labels,
    DROP COLUMN IF EXISTS routing_file_count_signal_enabled,
    DROP COLUMN IF EXISTS routing_size_label_signal_enabled;

ALTER TABLE prj_agent_config_traces
    DROP COLUMN IF EXISTS old_routing_enabled,
    DROP COLUMN IF EXISTS old_routing_strong_model_config_key,
    DROP COLUMN IF EXISTS old_routing_file_count_threshold,
    DROP COLUMN IF EXISTS old_routing_escalating_size_labels,
    DROP COLUMN IF EXISTS old_routing_file_count_signal_enabled,
    DROP COLUMN IF EXISTS old_routing_size_label_signal_enabled,
    DROP COLUMN IF EXISTS new_routing_enabled,
    DROP COLUMN IF EXISTS new_routing_strong_model_config_key,
    DROP COLUMN IF EXISTS new_routing_file_count_threshold,
    DROP COLUMN IF EXISTS new_routing_escalating_size_labels,
    DROP COLUMN IF EXISTS new_routing_file_count_signal_enabled,
    DROP COLUMN IF EXISTS new_routing_size_label_signal_enabled;
