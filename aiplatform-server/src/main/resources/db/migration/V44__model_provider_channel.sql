-- ========================================================================
-- business.project：模型供应商通道化＋档位语义键（#365，spec #364-T1，
-- ADR-0031——与 ADR-0021 智能体运营配置、ADR-0030 模型路由构成模型选用三件套）。
--
-- prj_model_providers：模型供应商＝接入通道（键名、baseUrl、apiKey、启用态）。
-- 键名同时是协议路由前缀——模型串「前缀:实际模型名」的前缀即本表键名；
-- OpenAI 兼容通道（volcengine）由平台代码注册模型工厂，SDK 内置前缀
-- （deepseek 等）走原生 provider，baseUrl/key 经创建上下文注入覆盖默认。
-- apiKey 落库为唯一正本（无环境变量回落——缺失即报错，不静默兜底）；
-- key 值经部署环境注入种子（Flyway placeholder，git 永不含 key 字面），
-- 本机 dev 由实施者直写库；后续换 key 走后台维护面（#367）。
--
-- prj_model_tiers：模型档位＝语义键（flash/pro/doubao-flagship 这类运营短名）
-- →实际模型名＋所属通道。模型版本升级（方舟名带日期后缀、季度级更新）＝
-- 改一处指向、全部引用自动跟上。档位行被智能体运营配置（prj_agent_configs
-- .model_id、routing_strong_model_id）与边缘调用点引用，引用值＝档位键。
-- FK 指向通道行：通道删除被引用时库级拦截（悬空引用不留静默坑——spec #364
-- 故事 9「明确拦截或报错」的结构半边；停用走 enabled 软态、装配报错半边）。
--
-- 种子：volcengine 主用（https://ark.cn-beijing.volces.com/api/v3，OpenAI 兼容、
-- SSE 格式一致）＋deepseek 官方备胎两通道行；flash/pro（火山 GA 对齐版——与
-- 切换前同代同档、行为漂移最小）/doubao-flagship（备选档）三档位行。
--
-- 存量刷档：三座智能体 model_id 与路由强档的旧裸模型名值（deepseek-v4-flash/
-- deepseek-v4-pro）一步刷成档位键——不留新旧两制；留痕表（prj_agent_config_
-- traces）的旧值快照是历史事实，不随刷（留痕跨一切存活，V19 口径）。
-- ========================================================================

CREATE TABLE prj_model_providers (
    provider_key  VARCHAR(50)  PRIMARY KEY,   -- 键名＝协议路由前缀（volcengine/deepseek）
    base_url      VARCHAR(500) NOT NULL,      -- 通道 baseUrl（创建上下文注入 SDK）
    api_key       TEXT,                       -- 唯一正本；空＝未配置（装配明确报错）
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,  -- 停用＝该通道装配报错（不静默换道）
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE prj_model_tiers (
    tier_key      VARCHAR(50)  PRIMARY KEY,   -- 语义键（运营短名，引用腿）
    provider_key  VARCHAR(50)  NOT NULL,
    model_name    VARCHAR(200) NOT NULL,      -- 实际模型名（方舟 Model ID，带日期后缀）
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,  -- 停用＝引用它的装配报错
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_model_tier_provider
        FOREIGN KEY (provider_key) REFERENCES prj_model_providers (provider_key)
);

INSERT INTO prj_model_providers (provider_key, base_url, api_key, enabled) VALUES
    ('volcengine', 'https://ark.cn-beijing.volces.com/api/v3',
        NULLIF('${volcengineApiKey}', ''), TRUE),
    ('deepseek', 'https://api.deepseek.com', NULLIF('${deepseekApiKey}', ''), TRUE);

INSERT INTO prj_model_tiers (tier_key, provider_key, model_name, enabled) VALUES
    ('flash', 'volcengine', 'deepseek-v4-flash-ga-260731', TRUE),
    ('pro', 'volcengine', 'deepseek-v4-pro-ga-260813', TRUE),
    ('doubao-flagship', 'volcengine', 'doubao-seed-2-1-pro-260915', TRUE);

-- 存量刷档（旧裸模型名 → 档位键；未知旧值不猜——残留值装配时按档位缺行明确报错）
UPDATE prj_agent_configs SET model_id = 'flash', updated_at = CURRENT_TIMESTAMP
    WHERE model_id = 'deepseek-v4-flash';
UPDATE prj_agent_configs SET model_id = 'pro', updated_at = CURRENT_TIMESTAMP
    WHERE model_id = 'deepseek-v4-pro';
UPDATE prj_agent_configs SET routing_strong_model_id = 'flash', updated_at = CURRENT_TIMESTAMP
    WHERE routing_strong_model_id = 'deepseek-v4-flash';
UPDATE prj_agent_configs SET routing_strong_model_id = 'pro', updated_at = CURRENT_TIMESTAMP
    WHERE routing_strong_model_id = 'deepseek-v4-pro';
