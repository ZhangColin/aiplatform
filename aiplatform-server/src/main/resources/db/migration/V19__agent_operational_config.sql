-- ========================================================================
-- business.project：智能体运营配置＋变更留痕（#246-T5/#251，ADR-0021 修订
-- ADR-0006 边界——身份与配置分治）
--
-- prj_agent_configs：两座智能体（main/executor）的运营覆盖态，一行一智能体。
-- 覆盖语义：system_prompt/model_id 列 null＝无覆盖（装配回落 AgentProfile 枚举
-- 默认——枚举仍是身份与缺省正本）；行不存在＝全回落（等价于无覆盖）。增强工具
-- 开关两列（web_search/fetch_url，默认 TRUE＝开）存储面先行落库——工具面生效
-- （关即退出槽位装配、骨架锁死）属 #246-T6/#252。操作者两列＝最近写者（V16
-- 房规——admin 侧标识 VARCHAR 存外域标识，签名面明示信任不校验真实性）。
--
-- prj_agent_config_traces：配置变更留痕（append-only，历史可查）。每次实际变更
-- （值面有动）一痕：变更前后全量值快照＋操作者。旧值快照＝回滚依据——回滚＝把
-- 旧值写回（即一次新变更、留新痕，不做版本树）。无 FK——留痕跨一切存活（历史
-- 事实不随配置行消失），agent_key 以字符串身份随行。
-- ========================================================================

CREATE TABLE prj_agent_configs (
    agent_key          VARCHAR(20) PRIMARY KEY,  -- AgentProfile 稳定键（main/executor，寻址腿）
    system_prompt      TEXT,                     -- 运营覆盖 systemPrompt（null＝回落枚举默认）
    model_id           VARCHAR(100),             -- 运营覆盖模型档位（null＝回落枚举默认）
    web_search_enabled BOOLEAN NOT NULL DEFAULT TRUE,  -- 增强工具开关：联网搜索（生效属 #252）
    fetch_url_enabled  BOOLEAN NOT NULL DEFAULT TRUE,  -- 增强工具开关：网页抓取（生效属 #252）
    operator_id        VARCHAR(64),              -- 最近写者 id（admin 侧 TSID；null＝从未配置）
    operator_name      VARCHAR(200),             -- 最近写者名（直读展示）
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE prj_agent_config_traces (
    id                      BIGINT PRIMARY KEY,   -- TSID（写痕时生成）
    agent_key               VARCHAR(20) NOT NULL, -- 智能体稳定键（无 FK——留痕跨行存活）
    old_system_prompt       TEXT,                 -- 变更前 systemPrompt（null＝此前即缺省回落）
    old_model_id            VARCHAR(100),
    old_web_search_enabled  BOOLEAN NOT NULL,     -- 变更前全量快照（缺省态恒有值）
    old_fetch_url_enabled   BOOLEAN NOT NULL,
    new_system_prompt       TEXT,                 -- 变更后快照（与旧行对照即本次变更面）
    new_model_id            VARCHAR(100),
    new_web_search_enabled  BOOLEAN NOT NULL,
    new_fetch_url_enabled   BOOLEAN NOT NULL,
    operator_id             VARCHAR(64),          -- 变更操作者（写操作必留痕）
    operator_name           VARCHAR(200),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
