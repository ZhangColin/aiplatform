-- ========================================================================
-- 模型通道高可用 schema/种子落地（#556，spec #552-T2，ADR-0053——承 ADR-0031
-- 通道化基座）：② 的全部表结构/数据一个迁移落。动因：2026-11-19 火山方舟
-- apiKey 供应商侧被删、ark 全线 401（生产已手工切 deepseek 官方、doubao-
-- flagship 档位行删留痕、服务器 .env key 已清）——本迁移让 dev/test 库与
-- 生产收敛同态，并为多通道自动 failover（候选队列）落表。
--
-- 六件事（对生产幂等——已手工做过的部分全部 no-op）：
--   ① 候选队列表 prj_model_tier_candidates：模型配置（旧称档位）＝有序候选
--      队列（tier_key、priority、通道行、实际模型名、行级启停）；主备＝队列
--      前两名。档位行现列保留＝首选候选（resolve 读面软着陆——候选表空时
--      回退现列，见 ModelTierAppService）。
--   ② 切换留痕表 prj_model_tier_switch_traces：运行期切候选 append-only
--      留痕（何时/从哪到哪/触发错误/run 上下文——备账号被烧运营可见）；
--      写腿随 T3 FailoverModel 落，本迁移只建表（无 FK——留痕跨一切存活）。
--   ③ prj_model_providers 加 vendor 列：归一供应商（deepseek/deepseek-2 同填
--      deepseek——按模型统计与单价匹配键的维度腿，T4/T5 接力）。
--   ④ volcengine 通道行/档位行幂等清理（先子后父）：生产已手工清＝no-op；
--      dev/test 库 V44 残留一并收敛（留痕表行是历史事实，无 FK 不动——V19 口径）。
--   ⑤ flash/pro 档位行收敛到生产手工切换后形态（deepseek 官方模型，与单价行
--      模型名对齐——backoffice-price-seed.sh deepseek 行）＋首启候选种子：
--      全新库 V44 仍种火山（Flyway 只增不改），V79 收敛后净效果＝deepseek
--      单通道，不种 volcengine/doubao-flagship。
--   ⑥ met_usage_events 加 vendor 列＋历史行同值回填：通道行将来可删、历史
--      事件维度须自持（volcengine 行已删的教训）；运行期落库腿随 T4。
-- ========================================================================

-- ① 候选队列表（ADR-0053 ③：形态＝子表，行级启停，主备无上限）
CREATE TABLE prj_model_tier_candidates (
    tier_key      VARCHAR(50)  NOT NULL,     -- 档位语义键（引用腿，同档位行）
    priority      INT          NOT NULL,     -- 队列序（小者先；档内唯一）
    provider_key  VARCHAR(50)  NOT NULL,     -- 通道行（一行＝endpoint×账号 key）
    model_name    VARCHAR(200) NOT NULL,     -- 实际模型名（计量/单价行匹配腿）
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,  -- 行级启停（false＝读面剔除）
    operator_id   VARCHAR(64),               -- 最近写者 id（null＝种子/迁移写入；写面 T5）
    operator_name VARCHAR(200),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_model_tier_candidates PRIMARY KEY (tier_key, priority),
    CONSTRAINT fk_tier_candidate_tier
        FOREIGN KEY (tier_key) REFERENCES prj_model_tiers (tier_key),
    CONSTRAINT fk_tier_candidate_provider
        FOREIGN KEY (provider_key) REFERENCES prj_model_providers (provider_key)
);

-- ② 切换留痕表（append-only；id＝TSID 写痕时生成——照 V45 两留痕表机制）
CREATE TABLE prj_model_tier_switch_traces (
    id                BIGINT PRIMARY KEY,
    tier_key          VARCHAR(50)  NOT NULL, -- 档位语义键（无 FK——留痕跨一切存活）
    from_provider_key VARCHAR(50),           -- 切出候选（首切＝null：从档位现列起）
    from_model_name   VARCHAR(200),
    to_provider_key   VARCHAR(50)  NOT NULL, -- 切入候选
    to_model_name     VARCHAR(200) NOT NULL,
    trigger_error     VARCHAR(500),          -- 触发错误摘要（401/403/402 类签名＋文案截断）
    run_id            VARCHAR(100),          -- run 上下文（可空——非 run 装配面切换）
    session_id        VARCHAR(100),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ③ vendor 归一列（可空先行：读面/写面 T4/T5 接力；本迁移只加列＋回填）
ALTER TABLE prj_model_providers ADD COLUMN vendor VARCHAR(50);

-- ④ volcengine 幂等清理（先子后父：档位行引用通道行，反序撞外键）
DELETE FROM prj_model_tiers WHERE provider_key = 'volcengine';
DELETE FROM prj_model_providers WHERE provider_key = 'volcengine';

-- ⑤ flash/pro 收敛（deepseek 官方模型——单价行模型名对齐：deepseek-flash＝
-- DeepSeek-V4.1-Flash-1017、deepseek-v4-pro＝DeepSeek-V4-Pro-0813）。
-- DO UPDATE 只收敛指向（provider/model_name）且指向已同值时不写（条件 UPDATE
-- ——生产现态＝真 no-op，不 bump updated_at）；display_name/开关/写者是运营
-- 面，生产已就位不覆写；dev 库行已被 ④ 清除、此处全新插入。
INSERT INTO prj_model_tiers (tier_key, provider_key, model_name, enabled, display_name)
VALUES ('flash', 'deepseek', 'deepseek-flash', TRUE, '快模型档'),
       ('pro', 'deepseek', 'deepseek-v4-pro', TRUE, '强模型档')
ON CONFLICT (tier_key) DO UPDATE SET
    provider_key = EXCLUDED.provider_key,
    model_name   = EXCLUDED.model_name,
    updated_at   = CURRENT_TIMESTAMP
    WHERE prj_model_tiers.provider_key IS DISTINCT FROM EXCLUDED.provider_key
       OR prj_model_tiers.model_name IS DISTINCT FROM EXCLUDED.model_name;

-- deepseek 通道行确保（全新库 V44 已种；生产已有真值 key——DO NOTHING 不覆写）
INSERT INTO prj_model_providers (provider_key, base_url, api_key, enabled)
VALUES ('deepseek', 'https://api.deepseek.com', NULLIF('${deepseekApiKey}', ''), TRUE)
ON CONFLICT (provider_key) DO NOTHING;

-- 首启候选种子：flash/pro 各一现役候选（与档位行现列同值＝主选单元素队列，
-- resolve 行为不变；DO NOTHING 防御重放）
INSERT INTO prj_model_tier_candidates (tier_key, priority, provider_key, model_name, enabled)
VALUES ('flash', 1, 'deepseek', 'deepseek-flash', TRUE),
       ('pro', 1, 'deepseek', 'deepseek-v4-pro', TRUE)
ON CONFLICT (tier_key, priority) DO NOTHING;

-- ③（续）vendor 归一回填：现存通道行 vendor＝provider_key（deepseek→deepseek；
-- 在 ④ 之后跑——volcengine 行不回填，删了就是删了）
UPDATE prj_model_providers SET vendor = provider_key WHERE vendor IS NULL;

-- ⑥ 计量事件表 vendor 列＋历史行同值回填（provider 腿保留＝按账号统计原生）
ALTER TABLE met_usage_events ADD COLUMN vendor VARCHAR(50);
UPDATE met_usage_events SET vendor = provider WHERE vendor IS NULL;
