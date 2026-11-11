-- ========================================================================
-- base.knowledge：激活升格＋知识检索混检（#465 数据飞轮第二层，承 ADR-0038
-- 采集层与 #451 决策正本）——
--
-- ① pg_trgm 扩展：检索混检的关键词通道承载（三元组相似度 similarity()——
--    中文无分词依赖的库内原生模糊匹配；pgvector 官方镜像自带 contrib）。
--    混检＝向量通道＋关键词通道 RRF 融合（findRelevant），替代纯向量检索。
--
-- ② project_id 置空化（孤儿沉淀）：激活升格的沉淀素材（kind CASE/MODULE/
--    RULE）与来源项目脱钩——「孤儿沉淀＝知识独立存在，来源项目删除不影响
--    已激活知识」（#457 决策）。NULL 永不命中 purgeByProject 级联
--    （WHERE project_id = ?），级联语义无需改码即正确。既有 PRD 素材行
--    project_id 原值不动（仍随来源项目级联清理）。
-- ========================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE knw_materials ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE knw_chunks ALTER COLUMN project_id DROP NOT NULL;
