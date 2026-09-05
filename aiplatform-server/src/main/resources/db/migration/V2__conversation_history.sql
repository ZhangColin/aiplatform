-- ========================================================================
-- business.project：对话史 prj_conversation_entries（#89 对话史落库④）
-- squash 基线后的首个增量迁移（V2，不改基线）。对话面全量落库的 append-only
-- 日志：用户发言 / 智能体回复 / 问答卡（挂起重放重建的作答面）/ 问答作答 /
-- 收尾卡（#88 closing 同载荷——版本锚定 #91 复用）/ 平台轻引导；过程明细
-- （解说段 / 动作卡流水）不落——收尾卡已是凝聚物。
--
-- id 用库序列（BIGSERIAL）而非 TSID：读序 = 写入序（pk 升序即对话序），不
-- 信任进程内时钟的多线程序；纯插入不更新（问答卡的 answered 位是唯一 UPDATE
-- 面——作答即置位，读模型自洽）。run_id 为对话轮锚（收尾卡 = 编码 run 的
-- 用户面 runId；可空防御——写口逐类保证携带）。project_id 跨表软引用
-- （prj_projects，无 FK——同表族约定），项目删除经编排级联清理。
-- ========================================================================

CREATE TABLE prj_conversation_entries (
    id         BIGSERIAL PRIMARY KEY,  -- 库序列：append-only 写入序（对话读序正本）
    project_id BIGINT NOT NULL,        -- 所属项目（prj_projects 软引用，无 FK）
    run_id     VARCHAR(100),           -- 对话轮锚（问答/收尾卡的 run 归属；可空防御）
    kind       INT NOT NULL,           -- ConversationEntryKind：1=user 2=agent 3=question 4=answer 5=closing 6=guide
    text       TEXT,                   -- 发言/回复/作答/引导文本（question/closing 为 NULL）
    question   JSONB,                  -- kind=question：问答卡载荷（question-raised 事件 payload 原样——刷新后问答卡可重建可作答）
    closing    JSONB,                  -- kind=closing：收尾卡权威事实（#88 closing 载荷原样）
    answered   BOOLEAN,                -- kind=question 的已答位（作答即置 true；其余 kind NULL）
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT
);

-- 项目对话读面（全量按 id 升序）
CREATE INDEX idx_prj_conversation_entries_project ON prj_conversation_entries (project_id, id);
