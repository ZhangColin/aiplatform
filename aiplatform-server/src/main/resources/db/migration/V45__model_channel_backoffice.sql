-- ========================================================================
-- business.project：模型供应商/档位后台维护面（#367，spec #364-T3，ADR-0031
-- 续 V44：两表只读装配跑通后，写面（CRUD＋变更留痕）落本迁移）。
--
-- prj_model_providers/prj_model_tiers 各加最近写者两列（V16 房规——admin 侧
-- 标识 VARCHAR 存外域标识，签名面明示信任不校验真实性）；prj_model_tiers 另加
-- display_name（运营显示名——后台绑定下拉的人话标签，null＝回落语义键）。
--
-- 两张留痕表（照 prj_agent_config_traces 机制，ADR-0021）：每次实际变更（值面
-- 有动）一痕——变更前后全量值快照＋操作者，append-only、无 FK（留痕跨一切
-- 存活）；回滚＝旧值写回即一次新变更。与配置面留痕的差异：通道/档位行有创建
-- 与删除生命周期，故 old_*/new_* 快照可空（创建痕 old 全 null、删除痕 new 全
-- null）。apiKey 快照明文落库（与行本体同保护级——明文落库是 ADR-0031 决定），
-- 但留痕读面一律掩码回显（尾 4 位），回显面零明文 key。
--
-- display_name 种子：三档位行补运营显示名（快模型档/强模型档＝#341 既有运营
-- 话语，豆包旗舰＝spec #364 备选档原词）。
-- ========================================================================

ALTER TABLE prj_model_providers
    ADD COLUMN operator_id   VARCHAR(64),              -- 最近写者 id（null＝种子/迁移写入）
    ADD COLUMN operator_name VARCHAR(200);             -- 最近写者名（直读展示）

ALTER TABLE prj_model_tiers
    ADD COLUMN display_name  VARCHAR(200),             -- 运营显示名（null＝回落语义键）
    ADD COLUMN operator_id   VARCHAR(64),
    ADD COLUMN operator_name VARCHAR(200);

CREATE TABLE prj_model_provider_traces (
    id             BIGINT PRIMARY KEY,   -- TSID（写痕时生成）
    provider_key   VARCHAR(50) NOT NULL, -- 通道键（无 FK——留痕跨行存活）
    old_base_url   VARCHAR(500),         -- 变更前快照（创建痕＝全 null：行此前不存在）
    old_api_key    TEXT,
    old_enabled    BOOLEAN,
    new_base_url   VARCHAR(500),         -- 变更后快照（删除痕＝全 null：行已不存在）
    new_api_key    TEXT,
    new_enabled    BOOLEAN,
    operator_id    VARCHAR(64),          -- 变更操作者（写操作必留痕）
    operator_name  VARCHAR(200),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE prj_model_tier_traces (
    id               BIGINT PRIMARY KEY, -- TSID（写痕时生成）
    tier_key         VARCHAR(50) NOT NULL, -- 档位语义键（无 FK——留痕跨行存活）
    old_provider_key VARCHAR(50),
    old_model_name   VARCHAR(200),
    old_display_name VARCHAR(200),
    old_enabled      BOOLEAN,
    new_provider_key VARCHAR(50),
    new_model_name   VARCHAR(200),
    new_display_name VARCHAR(200),
    new_enabled      BOOLEAN,
    operator_id      VARCHAR(64),
    operator_name    VARCHAR(200),
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

UPDATE prj_model_tiers SET display_name = '快模型档' WHERE tier_key = 'flash';
UPDATE prj_model_tiers SET display_name = '强模型档' WHERE tier_key = 'pro';
UPDATE prj_model_tiers SET display_name = '豆包旗舰' WHERE tier_key = 'doubao-flagship';
