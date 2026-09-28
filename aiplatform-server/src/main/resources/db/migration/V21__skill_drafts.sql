-- ========================================================================
-- base.skills：技能草稿表（#259 自产线 T1，ADR-0022 库制草稿）——智能体自荐
-- 的暂存态，人审队列
--
-- 草稿与技能库（skl_skills）保持隔离：「库行即正货」不变式——草稿、废稿、
-- 审计都在本表，永不入 skl_skills；草稿不参与任何装配（装配视图查询结构性
-- 不触本表，同项目亦不可用——未审内容不影响任何 run）。
--
-- 血统三件：来源项目（project_id 软引用 prj_projects 无 FK——先例 knw_chunks
-- /gen_segments；项目删除清未终结草稿是 T6 流程挂点，非库级级联）、来源 run
-- 标识（RuntimeContext 透传的本轮平台标识）、来源槽位（SkillSlot 稳定键，
-- 与 skl_slot_assignments.slot 同口径）。
--
-- 扫描回执：写入前 SkillSecurityScanner 静态判定的 verdict（SAFE/CAUTION——
-- DANGEROUS 拒写不落库）＋ findings（解析态键值 JSONB 留档供审核面）。扫描是
-- 廉价哨兵非安全边界，安全边界是后台人审门（ADR-0022）。
--
-- status 照房规（枚举落库 Integer code）：1=在途 2=已晋升 3=已拒绝；终态四列
-- （operator/reviewed_at/reject_reason）T1 建齐、T2 晋升/拒绝端点写入。在途
-- 撞名＝部分唯一索引（应用层先查后插回执原因，索引拦并发窗）；已拒绝不占名
-- （终态可重提为新草稿）、已晋升占名由技能库侧同名行承担。
--
-- 内容面 a-only：无 scripts 列——自荐工具无此参数，结构性锁死（ADR-0022）。
-- ========================================================================

CREATE TABLE skl_skill_drafts (
    id             BIGINT PRIMARY KEY,     -- TSID（自荐落库时生成，管理端点 URL 柄）
    name           VARCHAR(100) NOT NULL,  -- 技能名（撞名判断键；正则/≤64 由写入校验保证）
    description    TEXT NOT NULL,          -- 简介
    content        TEXT NOT NULL,          -- 技能正文（自荐只有正文，无 scripts）
    project_id     BIGINT NOT NULL,        -- 血统：来源项目（prj_projects 软引用无 FK）
    run_id         VARCHAR(100) NOT NULL,  -- 血统：来源 run 标识
    slot           VARCHAR(20) NOT NULL,   -- 血统：来源槽位（SkillSlot 键：main/executor/subagent）
    scan_verdict   VARCHAR(16) NOT NULL,   -- 扫描判定（SAFE/CAUTION；DANGEROUS 拒写不落库）
    scan_findings  JSONB NOT NULL,         -- 扫描 findings 留档（审核面）
    status         INT NOT NULL DEFAULT 1, -- SkillDraftStatus：1=在途 2=已晋升 3=已拒绝
    operator_id    VARCHAR(64),            -- 终态审核操作者（V16 房规；T2 写入）
    operator_name  VARCHAR(200),
    reviewed_at    TIMESTAMP,              -- 终态时刻（T2 写入）
    reject_reason  TEXT,                   -- 拒绝理由（T2 写入）
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_skl_skill_drafts_status CHECK (status IN (1, 2, 3))
);

-- 在途撞名并发兜底（部分唯一索引：只在途占名，终态释放名字）
CREATE UNIQUE INDEX uq_skl_skill_drafts_pending_name
    ON skl_skill_drafts (name) WHERE status = 1;
