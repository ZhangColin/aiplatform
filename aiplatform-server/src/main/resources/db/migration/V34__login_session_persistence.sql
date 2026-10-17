-- ========================================================================
-- business.identity：登录会话持久化 idn_sessions（#329 上线改版·09，spec #320
-- 段②实施序）。
--
-- BFF 会话从内存 Map 落库：服务重启登录态不丢（现状一次 SSO 弹回重登）、M01 与
-- OIDC 两路种的会话同享一表、上线多实例的前置条件。会话 id 即 cookie 值（不透明
-- 高熵随机，自然主键——无其他访问路径，不设代理键）。token 三件套只存服务端
-- （TEXT 可空：验证码登录无 token 语义全 NULL）。expires_at 创建即定
-- （now + app.session.ttl）：过即不认（get 谓词内联）、定时扫描清行
-- （app.session.cleanup-interval）。无存量搬迁：内存会话随旧进程消失，不可恢复。
-- ========================================================================

CREATE TABLE idn_sessions (
    session_id    VARCHAR(64) PRIMARY KEY,  -- 会话 id（= cookie 值；256bit Base64url 无填充 43 字符，列宽留余量）
    account_id    BIGINT NOT NULL,          -- 归属账号（idn_accounts 软引用，无 FK）
    display_name  VARCHAR(200) NOT NULL,    -- 显示名缓存（callback 建档映射，每请求还原上下文免查库）
    id_token      TEXT,                     -- OIDC token 三件套（登出 hint；code 登录全 NULL）
    access_token  TEXT,
    refresh_token TEXT,
    expires_at    TIMESTAMP NOT NULL,       -- 会话过期（创建即定 now+ttl；过即不认，扫描清行）
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    BIGINT,
    updated_by    BIGINT
);
