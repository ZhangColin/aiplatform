-- ========================================================================
-- base.knowledge：沉淀草稿表（#460 数据飞轮采集层，照 skl_skill_drafts 先例
-- ADR-0022）——智能体自荐沉淀物的暂存态，后台「数据标注」人审队列
--
-- 草稿与知识素材（knw_materials）保持隔离：未审内容不参与任何检索（#465 激活
-- 升格是正货入口）；激活升格后与来源项目脱钩（孤儿沉淀＝知识独立存在）。
--
-- 血统三件：来源项目（project_id 软引用 prj_projects 无 FK——先例 knw_chunks
-- /skl_skill_drafts；项目删除清未终结草稿是流程挂点，非库级级联）、来源 run
-- 标识（RuntimeContext 透传的本轮平台标识）、来源槽位（三职能槽位稳定键
-- main/executor/subagent，与 skl_skill_drafts.slot 同口径）。
--
-- 在途上限＝每项目每 kind 3 条（防单一项目淹没标注队列的辅闸；人审是主闸）：
-- 结构性承载＝inflight_slot 序号列（1-3）＋在途部分唯一索引
-- （(project_id, kind, inflight_slot) WHERE status=1——终态翻行即释放序号名额，
-- 应用层先查后插回执原因，索引拦并发窗）。
--
-- 扫描回执：写入前 SkillSecurityScanner 静态判定的 verdict（SAFE/CAUTION——
-- DANGEROUS 拒写不落库）＋ findings（解析态键值 JSONB 留档供审核面）。扫描是
-- 廉价哨兵非安全边界，安全边界是「数据标注」人审门。
--
-- status 照房规（枚举落库 Integer code）：1=在途 2=已激活 3=已拒绝；终态四列
-- （operator/reviewed_at/reject_reason）本迁移建齐、#464「数据标注」审结端点
-- 写入；拒绝理由库级 CHECK 锁死（终态必填）。
--
-- 载荷 payload JSONB：「kind 开放＋载荷靠自己生长」（#451 决策正本）——一期工具
-- 不传入恒空面，新类别/新载荷生长不动表结构。kind 库面同开放（无 CHECK），
-- 写入校验限定一期三类 CASE/MODULE/RULE。
-- ========================================================================

CREATE TABLE knw_sediment_drafts (
    id             BIGINT PRIMARY KEY,     -- TSID（自荐落库时生成，后台寻址柄）
    kind           VARCHAR(20) NOT NULL,   -- 沉淀物类别（CASE/MODULE/RULE；库面开放）
    title          VARCHAR(200) NOT NULL,  -- 标题（≤200 由写入校验保证）
    summary        TEXT NOT NULL,          -- 一句话摘要（≤1024 由写入校验保证）
    content        TEXT NOT NULL,          -- 正文 markdown（≤100k 由写入校验保证）
    payload        JSONB NOT NULL DEFAULT '{}'::jsonb, -- 扩展载荷（生长面，一期恒空面）
    project_id     BIGINT NOT NULL,        -- 血统：来源项目（软引用无 FK）
    run_id         VARCHAR(100) NOT NULL,  -- 血统：来源 run 标识
    slot           VARCHAR(20) NOT NULL,   -- 血统：来源槽位（main/executor/subagent）
    inflight_slot  INT NOT NULL,           -- 在途序号 1-3（在途上限 3 的结构性承载）
    scan_verdict   VARCHAR(16) NOT NULL,   -- 扫描判定（SAFE/CAUTION；DANGEROUS 拒写不落库）
    scan_findings  JSONB NOT NULL,         -- 扫描 findings 留档（审核面）
    status         INT NOT NULL DEFAULT 1, -- SedimentDraftStatus：1=在途 2=已激活 3=已拒绝
    operator_id    VARCHAR(64),            -- 终态审核操作者（#464 写入）
    operator_name  VARCHAR(200),
    reviewed_at    TIMESTAMP,              -- 终态时刻（#464 写入）
    reject_reason  TEXT,                   -- 拒绝理由（#464 写入；拒绝终态必填）
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_knw_sediment_drafts_status CHECK (status IN (1, 2, 3)),
    CONSTRAINT ck_knw_sediment_drafts_inflight_slot CHECK (inflight_slot BETWEEN 1 AND 3),
    -- 拒绝理由必填（库级锁死：终态留档拒绝须有据——与技能链「理由必填」同口径）
    CONSTRAINT ck_knw_sediment_drafts_reject_reason
        CHECK (status <> 3 OR NULLIF(BTRIM(reject_reason), '') IS NOT NULL)
);

-- 在途上限并发兜底（部分唯一索引：同项目同类别在途序号唯一，终态翻行释放名额）
CREATE UNIQUE INDEX uq_knw_sediment_drafts_pending_slot
    ON knw_sediment_drafts (project_id, kind, inflight_slot) WHERE status = 1;

-- 标注队列读面（#464 按项目/类别/状态过滤的自然序索引——读面先行，写面无感）
CREATE INDEX idx_knw_sediment_drafts_queue
    ON knw_sediment_drafts (project_id, kind, status, created_at DESC);
