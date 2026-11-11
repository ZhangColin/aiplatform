-- ========================================================================
-- business.project：路由结构性信号开关（#454，ADR-0037——用户裁决 2026-11-11）：
-- 文件面/规模标签两信号默认关、仅失败重试升档；效果不好随时开回来。
--
-- 背景：文件面只增不减（过阈值后永不回落），「越大越强模」激励错位；规模标签
-- 同理是静态自报。生产已用现有旋钮 interim 关停（routingFileCountThreshold=999999、
-- routingEscalatingSizeLabels=[]——#426 已有空映射语义）；本迁移把 interim 态
-- 转正为信号级开关（正式缺省 false＝关），路由总开关 routingEnabled 不动——
-- 失败重试升档照常工作（seg 重试时升 routingStrongModelId，缺省 pro）。
--
-- prj_agent_configs（executor 行消费）加两列：
--   routing_file_count_signal_enabled BOOLEAN NOT NULL DEFAULT FALSE：
--      文件面信号开关——false＝关＝文件面观测值照留进判定快照（closing.modelTier.
--      signals.fileCount）但不参与升档判定；true＝恢复阈值判据（阈值旋钮照旧）。
--   routing_size_label_signal_enabled BOOLEAN NOT NULL DEFAULT FALSE：
--      规模标签信号开关——同构：false＝关＝标签观测值照留不参与判定；true＝恢复
--      标签映射判据（映射旋钮照旧）。
-- 失败重试信号（重试/回滚重跑）不受两开关影响、常开。
--
-- 布尔开关同收口三闸/生成会话形态开关先例（#433/#436 翻转缺省族）：NOT NULL
-- DEFAULT FALSE，既有行经列缺省自动获 false＝结构性信号关（本迁移的目的本身）；
-- 行不存在与「行在、列 false」语义等价（全回落缺省）。
--
-- 生效语义（#437 口径同）：路由判定处每次命令构建动态查库取生效配置束——运营面
-- 改完下一次判定即生效，不发版、进行中 run 不定格。观测兼容：收尾卡 modelTier.
-- signals 照旧全量留档（关了的信号留观测值＋fileCountSignalEnabled/
-- sizeLabelSignalEnabled 参与标记），对照实验数据不断档。
-- prj_agent_config_traces 同步四列（变更前后全量快照，回滚腿完整）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN routing_file_count_signal_enabled BOOLEAN NOT NULL DEFAULT FALSE,  -- 文件面信号开关（#454；false＝关＝不参与升档判定〔观测值照留〕；true＝恢复阈值判据）
    ADD COLUMN routing_size_label_signal_enabled BOOLEAN NOT NULL DEFAULT FALSE;  -- 规模标签信号开关（#454；false＝关＝不参与升档判定；true＝恢复标签映射判据）

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_routing_file_count_signal_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN old_routing_size_label_signal_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN new_routing_file_count_signal_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN new_routing_size_label_signal_enabled BOOLEAN NOT NULL DEFAULT FALSE;
