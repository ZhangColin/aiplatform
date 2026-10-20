-- ========================================================================
-- business.project：模型路由策略列（#354 生成体系升级——简单活快模型、难活强
-- 模型）：run 执行体的模型档位按任务特征可观测信号自动选档（重试/回滚重跑失败
-- 升档＋文件面大小＋片复杂度），策略走智能体运营配置面（同表扩展、不发版）。
--
-- 协同口径（ADR-0030）：路由判定优先、人工覆盖兜底——routing_enabled 开＝信号
-- 升强档优先，无信号回落人工档兜底（model_id 覆盖 → 枚举缺省——覆盖即基础档，
-- 快档位可经 model_id 配置、不关路由）；routing_enabled 关＝钉死位（显式关断即
-- 钉死，人工档恒生效——对账/对比场景的定档通道，既有行为零变化）。
-- routing_enabled 缺省 TRUE＝开（无信号触发时与既有快档缺省行为一致）；阈值/
-- 强档列 null＝回落代码缺省（库值优先、缺省回落同 model_id 口径）。主智能体/
-- 设计执行体不参与路由（Demo 阶段模型档＝run 执行体的模型档，#341），其行同
-- 列恒无消费。
--
-- 留痕表同扩 old/new 各四列（留痕＝变更前后全量值快照，回滚依据口径不变）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN routing_enabled               BOOLEAN NOT NULL DEFAULT TRUE,  -- 模型路由开关（缺省开＝路由判定优先）
    ADD COLUMN routing_strong_model_id       VARCHAR(100),                   -- 强模型档（路由升档落点；null＝代码缺省 deepseek-v4-pro）
    ADD COLUMN routing_file_count_threshold  INTEGER,                        -- 文件面阈值：工作区已跟踪文件数 ≥ 此值升强档（null＝代码缺省 60）
    ADD COLUMN routing_slice_length_threshold INTEGER;                       -- 片复杂度阈值：切片描述字数 ≥ 此值升强档（null＝代码缺省 20）

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_routing_enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN old_routing_strong_model_id       VARCHAR(100),
    ADD COLUMN old_routing_file_count_threshold  INTEGER,
    ADD COLUMN old_routing_slice_length_threshold INTEGER,
    ADD COLUMN new_routing_enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN new_routing_strong_model_id       VARCHAR(100),
    ADD COLUMN new_routing_file_count_threshold  INTEGER,
    ADD COLUMN new_routing_slice_length_threshold INTEGER;
