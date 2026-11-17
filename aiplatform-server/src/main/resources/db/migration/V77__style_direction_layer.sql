-- ========================================================================
-- business.project：风格方向层（#543，ADR-0052 商业化一期⑤切片①）——
-- 风格方向库物化通道的落库面。
--
-- ① 设计规范正本表加 direction_key 列（NULL＝设计线来源行〔#295/296 既有语义
--    不动〕；非空＝风格方向来源行〔键＝StyleDirections 库稳定键〕——物化通道
--    据此识别「生成式来源正本」：首个构建派发物化、其后每次派发按 PRD 字段
--    重物化〔改风格＝PRD 修订→派发对齐，零新通道〕）。
--
-- ② 风格层运营 kill-switch 单表（#442/#517 开关保险同款「改档即生效、不发版」
--    语义；独立成表的原因＝本票无后台接口变更声明〔验收口径〕——不进
--    prj_agent_configs 值面/留痕/admin 管理面，运营经 SQL 直改本表行）：
--      enabled TRUE＝风格层生效（缺省——新项目全量物化）；
--      FALSE＝回无规范现状（物化通道零动作零写入，收口扫描不扫）。
--    读面动态查单行、进程内不缓存——改表下一次派发判定即生效。
-- ========================================================================

ALTER TABLE prj_design_specs
    ADD COLUMN direction_key VARCHAR(50);  -- 风格方向来源标记（#543；NULL＝设计线来源行）

CREATE TABLE prj_style_direction_switch (
    id         BIGINT PRIMARY KEY,
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,  -- 风格层 kill-switch（TRUE＝生效缺省；FALSE＝回无规范现状）
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 缺省种行（风格层生效＝落地态；运营关停 UPDATE 本行即可）
INSERT INTO prj_style_direction_switch (id, enabled) VALUES (1, TRUE);
