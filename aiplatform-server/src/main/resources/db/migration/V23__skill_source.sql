-- ========================================================================
-- base.skills：技能库来源列化（#262 自产线 T2，ADR-0022）——V15 注释预留的
-- 正缝兑现：「行在 skl_skills 即安装」在自产晋升落地后不再成立，行级来源需要
-- 显式列。存量行 DEFAULT 回填 INSTALLED；晋升写入 SELF_PRODUCED；内置仍
-- classpath 合成无行（SkillSource.BUILTIN 只在读模型——清单/详情合成呈现，
-- CHECK 不收 1 即库级钉死「内置无行」）。
--
-- 自产行的来源包/版本用固定虚拟值 'self'：套既有 uq_skl_skills_identity
-- (source_package, name) 唯一约束——自产同名唯一即结构性保证（晋升撞名复查
-- 之外的第二道闸，DuplicateKeyException 归一为撞名拒）；skl_packages 包表
-- 不落 'self' 行（无远端可查——更新检查遍历面按 source 过滤排除自产行）。
-- ========================================================================

ALTER TABLE skl_skills ADD COLUMN source INT NOT NULL DEFAULT 2;

ALTER TABLE skl_skills ADD CONSTRAINT ck_skl_skills_source CHECK (source IN (2, 3));
