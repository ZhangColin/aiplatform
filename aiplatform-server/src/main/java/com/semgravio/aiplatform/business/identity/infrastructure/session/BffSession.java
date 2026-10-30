package com.semgravio.aiplatform.business.identity.infrastructure.session;

/**
 * BFF 服务端会话（A2 §2 表 5）：token 三件套只存服务端，浏览器只持不透明
 * {@code aiplatform_session} cookie。
 *
 * <p>{@code accountId} 与显示名在 callback 建档时一并映射缓存——每请求还原
 * RequestContext 免查库。会话生命周期＝{@code idn_sessions} 行的存续（#329 起持久
 * 化：重启不丢；登出删行、过 TTL 不认、扫描清行——过期时点是 store 侧关注点，
 * 不进本值对象）。token 三件套可空：验证码登录（M01）无 token 语义。</p>
 */
public record BffSession(
        Long accountId,
        String displayName,
        String idToken,
        String accessToken,
        String refreshToken) {
}
