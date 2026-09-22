-- ========================================================================
-- base.skills：技能库条目表（#246-T1/#247）——安装技能的快照落点
--
-- 管理单元＝技能条目＝一行（#246 指派模型）：库内技能以「来源包＋名称」唯一
-- （跨包同名共存，指派面展示区分——uq 即此语义）；装时固化版本标识（commit），
-- 更新显式点（T4）。行即「安装」来源——来源不设列：classpath 合成即「内置」，
-- 自产（#244）到来时再议列化。
--
-- 内容面收解析态而非原文：frontmatter 全量（JSONB）＋正文（frontmatter 剥离
-- 后的 SKILL.md body）——审核面所见即运行时注入面（解析器口径同一）。name/
-- description 冗余列供清单直读与唯一键，值来自 frontmatter。scripts/ 资源表
-- 属 T2 安装票，本表不预留。
--
-- status 照 knw_materials 房规（#34：枚举落库 Integer code，1=启用 2=停用）；
-- T1 只建读侧，写口（安装/启停/卸载）T2 落。
-- ========================================================================

CREATE TABLE skl_skills (
    id             BIGINT PRIMARY KEY,     -- TSID（安装时生成，管理端点 URL 柄）
    name           VARCHAR(100) NOT NULL,  -- 技能名（frontmatter name）
    description    TEXT NOT NULL,          -- 简介（frontmatter description，清单直读冗余）
    source_package VARCHAR(300) NOT NULL,  -- 来源包标识（安装仓库，跨包同名区分键）
    version        VARCHAR(100) NOT NULL,  -- 版本标识（装时 commit，快照锚）
    status         INT NOT NULL DEFAULT 1, -- SkillStatus：1=启用 2=停用
    frontmatter    JSONB NOT NULL,         -- SKILL.md frontmatter 全量（解析态，审核面）
    content        TEXT NOT NULL,          -- SKILL.md 正文（frontmatter 剥离后全文）
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     BIGINT,
    updated_by     BIGINT,
    CONSTRAINT uq_skl_skills_identity UNIQUE (source_package, name),
    CONSTRAINT ck_skl_skills_status CHECK (status IN (1, 2))
);
