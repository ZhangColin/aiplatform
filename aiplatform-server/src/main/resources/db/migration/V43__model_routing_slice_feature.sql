-- ========================================================================
-- business.project：模型路由片复杂度信号重定（#361——上线改版·40）：实测切片
-- 标题无一低于 20 字（主智能体「宁粗勿碎」的标题形），切片描述字数信号恒燃、
-- 生成轨全部切片误升强档——Demo 阶段快模型档（#341 缺省 flash）在生成轨空转
-- （llm 等待是逐片最大耗时桶，升强档直接放大）。信号重定＝切片功能条目数
-- （切片描述的枚举段数——、，,;；/／+＋ 切分非空段计数）：能区分普通纵切片
-- （一两个枚举段）与粗片（一包多功能），长而少枚举的标题不再误升档；真实粗片
-- （≥ 阈值条目）仍升强档，信号语义不丢。
--
-- 列改名（configs 一列＋traces old/new 两列）：值面全 null（留痕零条）、语义
-- 随信号重定，旧名「length」不再表达事实——保留能力不保留结构，旧词条随改。
-- 代码缺省同步 20 字 → 4 项（ModelRoutingAppService，null 仍＝回落代码缺省）。
-- 他环境执行前同核：configs 该列与 traces old/new 两列须全 null（非 null 值会带
-- 数值漂语义——「N 字」变「N 项」，核出即先清空再迁）。
--
-- 文件面信号核对（#361 票面一并核）：基座工程面＝镜像内置模板 12 件
-- （ADR-0013，node_modules/数据/平台产物均不入版），对 60 件阈值影响可忽略、
-- 不改。
-- ========================================================================

ALTER TABLE prj_agent_configs
    RENAME COLUMN routing_slice_length_threshold TO routing_slice_feature_threshold;

ALTER TABLE prj_agent_config_traces
    RENAME COLUMN old_routing_slice_length_threshold TO old_routing_slice_feature_threshold;

ALTER TABLE prj_agent_config_traces
    RENAME COLUMN new_routing_slice_length_threshold TO new_routing_slice_feature_threshold;
