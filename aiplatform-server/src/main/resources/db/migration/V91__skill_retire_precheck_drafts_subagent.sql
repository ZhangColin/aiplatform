-- ========================================================================
-- base.skills / business.project：阶段六技能清场（#638，父票 #637；ADR-0058）。
--
-- 拆除面（技能侧从未运行或被采纳的机制）：
--   ① 指派双头预检端点与其 `skillcheck` 边缘调用点整条退场——删 `precheck`
--      边缘点配置行（prj_agent_configs）与留痕行（prj_agent_config_traces）；
--   ② 技能草稿表 skl_skill_drafts（V21）整表退场——自荐、人审晋升与拒绝端点
--      一并拆除，技能库来源「自产」(source=3) 保留为历史读值、不再产生新行；
--   ③ 技能槽位收窄为 main／executor——删存量 subagent 指派行（本机为 0）。
-- 保留不动：内置「需求文档写作」技能、后台安装／明示更新／启停／卸载／指派、
-- 技能使用计数；生成内核四件工具与生成判据不动。
--
-- 增量迁移（V91），不改已应用迁移；干净库与存量库均可回放。
-- ========================================================================

DELETE FROM prj_agent_config_traces WHERE agent_key = 'precheck';

DELETE FROM prj_agent_configs WHERE agent_key = 'precheck';

DELETE FROM skl_slot_assignments WHERE slot = 'subagent';

DROP TABLE IF EXISTS skl_skill_drafts;
