-- ========================================================================
-- base.skills：职能槽位指派表（#246-T3/#249）——装配合成的指派正本
--
-- 指派模型（ADR-0021）：按职能槽位（main=主智能体 / executor=run 执行体 /
-- subagent=子智能体）全局配置、不分项目；管理单元＝（槽位, 技能条目）一行。
-- 槽位键为平台稳定字符串（business 侧 AgentProfile 键与其对齐），不设 code 列。
-- 写口＝整包替换（PUT 全量语义）：该槽位行集 delete+insert 同事务，operator
-- 两列＝最近动作者（V16 房规）。
--
-- 装配生效语义：装配合成只收「已指派且启用」——本表不冗余状态（启停在
-- skl_skills.status，停用即退出装配候选、指派关系保留）；装配视图动态查库
-- （非装配时固化），指派/启停变更下一轮自然生效（ADR-0021）。
--
-- FK 不带 ON DELETE：有指派在身拒绝卸载（SKL_008）是域守卫，库级 NO ACTION
-- 兜底防旁路删除——与 V1/V12 的 CASCADE 房规不同（彼处是父删子随的从属行，
-- 此处指派是卸载守卫关系）。
-- ========================================================================

CREATE TABLE skl_slot_assignments (
    slot           VARCHAR(20) NOT NULL,  -- 职能槽位键（SkillSlot：main/executor/subagent）
    skill_id       BIGINT NOT NULL,       -- 技能条目（skl_skills 库行；内置非库行不可指派）
    operator_id    VARCHAR(64),           -- 最近指派动作操作者（整包替换留最新一次）
    operator_name  VARCHAR(200),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_skl_slot_assignments PRIMARY KEY (slot, skill_id),
    CONSTRAINT fk_skl_slot_assignments_skill
        FOREIGN KEY (skill_id) REFERENCES skl_skills (id)
);
