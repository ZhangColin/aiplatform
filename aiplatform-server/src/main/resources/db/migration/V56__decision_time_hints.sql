-- ========================================================================
-- business.project：决策时微指令注入开关（#437，#432 Step 3 落地——执行体
-- ReAct 循环每迭代由确定性分类器判定轨迹、注入一条微指令〔首批库：并行工具
-- 调用/反 mock 数据/失败止损/上下文经济性〕；堆叠 ≤3〔再堆收益递减转负〕；
-- 注入内容用户可见〔part-hint 部件〕；分类器零模型调用〔成本口径见
-- docs/research/ 报告〕）。
--
-- 四件（缺省即 #437 落地态，ADD COLUMN 缺省回填存量行）：
--   1. decision_hints_enabled BOOLEAN NOT NULL DEFAULT TRUE：
--      注入总开关——true＝判定注入生效（默认）；false＝全停（原样透传、零注入
--      零观测）。仅 run 执行体行消费（中间件只挂在执行体装配面）。
--   2. decision_hint_error_streak INT NULL：
--      失败止损阈值——同类错误（同工具＋错误首行归一化）连续失败 ≥ 此值即注入
--      「换策略」微指令；null＝回落代码缺省 3；运营可配范围 2-10（PRJ_081）。
--   3. decision_hint_stack_cap INT NULL：
--      微指令堆叠上限——单轮（段）最多注入几条不同微指令；null＝回落代码缺省 3
--      （研究口径 ≤3——再堆收益递减转负）；运营可配范围 1-3（PRJ_082）。
--   4. decision_hint_disabled VARCHAR NULL：
--      禁用集——库条目键逗号串（封闭集：failure_stop_loss/anti_mock/
--      context_economy/parallel_tools；非法 token 拒写 PRJ_083）；null/空串＝
--      全启用。库正文在代码正本，运营面只调开关与参数（措辞迭代走发版）。
--
-- prj_agent_config_traces 八列（前后快照）同步加列——留痕全量值快照口径不变。
--
-- 生效语义（#433/#436 口径同）：判定处每迭代动态查库不缓存——运营面改完下
-- 一次判定即生效，不发版、进行中 run 不定格；装配不进 agent 实例缓存键（改
-- 配置无需重建实例）。
-- ========================================================================

ALTER TABLE prj_agent_configs
    ADD COLUMN decision_hints_enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN decision_hint_error_streak INT        NULL,
    ADD COLUMN decision_hint_stack_cap    INT        NULL,
    ADD COLUMN decision_hint_disabled     VARCHAR(255) NULL;

ALTER TABLE prj_agent_config_traces
    ADD COLUMN old_decision_hints_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN old_decision_hint_error_streak INT NULL,
    ADD COLUMN old_decision_hint_stack_cap    INT NULL,
    ADD COLUMN old_decision_hint_disabled     VARCHAR(255) NULL,
    ADD COLUMN new_decision_hints_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN new_decision_hint_error_streak INT NULL,
    ADD COLUMN new_decision_hint_stack_cap    INT NULL,
    ADD COLUMN new_decision_hint_disabled     VARCHAR(255) NULL;
