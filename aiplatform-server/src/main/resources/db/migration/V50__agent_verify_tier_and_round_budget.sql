-- ========================================================================
-- business.project：智能体运营配置面增验证面两列（#424 片轻验常开硬门）。
--
-- prj_agent_configs（executor 行消费）加两列：
--   verify_model_id     —— verify 验证子智能体模型档位键（ADR-0031 语义键；
--                          null＝随执行体生效档——含路由升档，缺省同执行体档
--                          不降，#406 裁决「验证判错代价高于省的秒数」）；
--   verify_round_budget —— 单场验证轮次预算（防浏览器迭代失控/无限复验：
--                          report_verification 登记达预算仍有未过项即停
--                          修复—复验、未过项如实带入收口；null＝回落代码
--                          缺省 3＝首轮＋两轮复验）。
--
-- 生效语义复用既有机制：装配动态查库不缓存（子智能体档位进智能体实例缓存键
-- 指纹、预算随工具调用动态读）——运营面改完下一轮命令构建/登记即生效，不发版、
-- 进行中 run 不定格。既有行经列缺省自动获 null（全回落，行为与切换前一致）。
-- prj_agent_config_traces 同步四列（变更前后全量快照，回滚腿完整）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN verify_model_id VARCHAR(64),      -- null＝随执行体生效档（缺省同档不降）
    ADD COLUMN verify_round_budget INT;          -- null＝回落代码缺省（3 轮）

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_verify_model_id VARCHAR(64),
    ADD COLUMN old_verify_round_budget INT,
    ADD COLUMN new_verify_model_id VARCHAR(64),
    ADD COLUMN new_verify_round_budget INT;
