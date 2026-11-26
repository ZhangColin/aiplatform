-- ========================================================================
-- base.knowledge：事后提炼与候选经验落库（#641，阶段六-3；父票 #637，ADR-0058）。
--
-- 三件事：
--   ① 清空旧草稿行：旧行来自已出局的「智能体自荐」，没有来源三件（项目／版本／
--      原句），随事后提炼换血（本机旧库零价值——ADR-0058 事实面）。
--   ② 草稿表扩展来源五列：来源项目名快照（项目删除后仍可读）、来源版本号、
--      来源提交哈希、来源原句、提炼批次标识。在途序号列保留（现有审结读面照常
--      编译与读取），新提炼行临时占固定序号 1；在途机制的彻底拆除归 #642。
--      在途部分唯一索引随本迁移拆除：事后提炼每次最多 3 条、同一项目跨版本
--      累计落待审，旧「每项目每类 3 条」索引会挡住新行（上限只由单次提炼条数
--      保证，#642 再拆列与检查约束）。
--   ③ 新增提炼处理记录表 knw_experience_distillations：每个用户面版本一条，状态
--      ＝已提炼／无修改意见／失败，另记修改意见条数、没做对条数、候选条数、
--      提炼批次标识与错误摘要；同一项目同一版本唯一（重复触发幂等）。游标＝
--      最近一条非失败记录的版本号（失败不前进，下一版本自然重试失败窗口）。
--
-- 增量迁移（V93），不改已应用迁移；干净库与存量库均可回放。
-- ========================================================================

-- ① 旧自荐草稿清空（来源不可回溯，不承载语义）
DELETE FROM knw_sediment_drafts;

-- ② 来源五列（旧 propose 路径已退场，行不再产生；新增行由事后提炼填写）
ALTER TABLE knw_sediment_drafts
    ADD COLUMN source_project_name VARCHAR(200),
    ADD COLUMN source_version_no INT,
    ADD COLUMN source_commit_hash VARCHAR(64),
    ADD COLUMN source_sentence TEXT,
    ADD COLUMN distill_batch_id VARCHAR(64);

-- 在途部分唯一索引拆除（新行固定占序号 1；跨批次同项目同类别可并存）
DROP INDEX IF EXISTS uq_knw_sediment_drafts_pending_slot;

-- ③ 提炼处理记录表（每个用户面版本一条；唯一键即重复触发幂等闸）
CREATE TABLE knw_experience_distillations (
    id              BIGINT PRIMARY KEY,        -- TSID（起草批次时生成）
    project_id      BIGINT NOT NULL,           -- 项目（软引用无 FK——照草稿血统先例）
    project_name    VARCHAR(200) NOT NULL,     -- 项目名快照（项目改名/删除后仍可读）
    version_no      INT NOT NULL,              -- 用户面版本号（v1/v2/…）
    commit_hash     VARCHAR(64),               -- 来源提交哈希（读取失败的历史失败行可空）
    status          INT NOT NULL,              -- 1=已提炼 2=无修改意见 3=失败
    opinion_count   INT NOT NULL DEFAULT 0,    -- 本次窗口内修改意见条数
    missed_count    INT NOT NULL DEFAULT 0,    -- 判定「需求写明了但没做对」条数
    candidate_count INT NOT NULL DEFAULT 0,    -- 实际落待审的候选条数
    batch_id        VARCHAR(64) NOT NULL,      -- 提炼批次标识（候选行同值互溯）
    error_summary   TEXT,                      -- 失败摘要（失败态携带）
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_knw_experience_distillations_version UNIQUE (project_id, version_no),
    CONSTRAINT ck_knw_experience_distillations_status CHECK (status IN (1, 2, 3))
);

-- 游标（最近非失败记录的版本号）与统计读面：项目内按版本倒序取
CREATE INDEX idx_knw_experience_distillations_cursor
    ON knw_experience_distillations (project_id, version_no DESC);
