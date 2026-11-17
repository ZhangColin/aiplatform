-- ========================================================================
-- business.project：构建轨出图工具开关（#545，ADR-0052 图片能力下放
-- 构建轨——ADR-0026 备案触发器兑现）。
--
-- 出图工具件（generate_image）发放面从设计执行体扩到 run 执行体（构建轨）：
-- 落 public/（演示数据配图——首屏 hero/关键实体图/空态美化，应用静态资产），
-- 按张计量事件 dims 归 executor、24h URL 即时转存机制全沿用既有。本列只管
-- 构建轨发放面：关＝run 执行体槽位不挂载该工具（回无图现状），设计执行体
-- 槽位（designer 行、design/ 落点）行为不变、不读本列。
--
-- prj_agent_configs（executor 行消费）加一列：
--   generate_image_enabled BOOLEAN NOT NULL DEFAULT TRUE：
--      true＝缺省下放（新能力随发版生效，演示配图走快档 CogView-4，
--      供应商/模型仍由 app.image-generation.provider 与单价表行承载，
--      运营升档＝换行零机制）；
--      false＝构建执行体不挂载 generate_image（模型不可见、回无图现状，
--      速度与成本的运营保险）。
--
-- 缺省口径异 #541 robust_lint_blocking_enabled（新能力缺省开＝TRUE，
-- 非翻转缺省族）；写法同 web_search_enabled/fetch_url_enabled 增强工具
-- 开关列（#252）：既有行经列缺省自动获 true；行不存在与「行在、列 true」
-- 语义等价。经既有工具面开关（PUT /agent-tools/generate_image）写，
-- 不进配置全量 PUT 命令面（全量写携现行值，不重置）。
--
-- 生效语义：toolSpec 随命令透传并进 agent 实例缓存键（#252 同缝）——
-- 改开关＝规格变＝新实例新装配，下一轮命令构建即生效，不发版、进行中
-- run 不定格。
-- prj_agent_config_traces 同步两列（变更前后全量快照，回滚腿完整）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN generate_image_enabled BOOLEAN NOT NULL DEFAULT TRUE;  -- 构建轨出图工具开关（#545；false＝run 执行体槽位不挂载，设计执行体不读本列）

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_generate_image_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN new_generate_image_enabled BOOLEAN NOT NULL DEFAULT TRUE;
