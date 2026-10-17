package com.aieducenter.aiplatform.business.identity.domain.model;

/**
 * 认证 cookie 契约（A2 §2 表 8-③：cookie 名独一，防裸 localhost 共享 jar 撞名）。
 *
 * <p>名字与生命周期是认证契约的一部分（filter / controller / 应用层共用），
 * 放领域模型层——无框架依赖，端点层可直达（ArchUnit 只禁 controller 依赖
 * domain.aggregate/entity 与 infrastructure）。</p>
 */
public final class AuthCookies {

    /** BFF 业务会话 cookie（不透明 sessionId；HttpOnly + SameSite=Lax，会话级无 maxAge） */
    public static final String SESSION_COOKIE_NAME = "aiplatform_session";

    /**
     * 访客凭证 cookie（#326 访客域）：值即凭证（服务端按 idn_guests.credential 直查，
     * 无状态校验）；HttpOnly + SameSite=Lax，MaxAge 对齐保留期、随滑动续期（写节流）
     * 刷新。Secure 随 {@code sso.cookie-secure}（与账户会话 cookie 同一取舍）。
     */
    public static final String GUEST_COOKIE_NAME = "aiplatform_guest";

    /** OAuth 往返事务 cookie（"{state}:{nonce}:{returnTo}"，600s） */
    public static final String TXN_COOKIE_NAME = "oauth_txn";

    /** 事务 cookie 生命周期（秒）——授权码往返的时间上限 */
    public static final int TXN_COOKIE_MAX_AGE_SECONDS = 600;

    private AuthCookies() {
    }
}
