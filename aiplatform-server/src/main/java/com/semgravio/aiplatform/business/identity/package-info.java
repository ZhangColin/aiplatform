/**
 * Identity Context（business.identity）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>账号档案（首次登录自动建档，无角色概念；#327 绑定表拆分后档案极简——
 *       id＋显示名＋时间戳，凭据一律住绑定表 idn_account_bindings，
 *       「迁 SSO 只动绑定表」）</li>
 *   <li>OIDC BFF 认证（/auth/login、/auth/callback、/auth/logout + /api/me；
 *       自管内存会话，token 只在服务端，id_token 第一天 JWKS RS256 验签；
 *       #327 起与验证码登录并存——302 流收而不删，SSO 迁移时再启用）</li>
 *   <li>验证码登录（#327，/auth/code + /auth/verify，M01 弹窗后端面）：平台内
 *       自建发码/验码/建账户/种会话，无密码、登录即注册（验码即 upsert）；
 *       通道机制双开（email/phone）、上线先邮箱（开关与频控走配置）；
 *       发送服务商＝上线参数（端口 VerificationCodeSender＋日志适配器先行）</li>
 *   <li>会话 → RequestContext 过滤器 + /api/** 鉴权拦截（全端点 401 语义）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>认证以 identity 服务为正主（照 semgravio-identity/demo 形态）；不引入
 * cartisan-security，401/403 全局映射见
 * {@code com.semgravio.aiplatform.web.AuthExceptionHandler}（本上下文的
 * ApiAuthInterceptor 抛出）。表前缀 {@code idn_}，错误码前缀 {@code IDN_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain：Account 聚合（极简档案）+ AccountBinding 聚合（凭据下落，
 *       provider=oidc|email|phone）+ OauthTransaction / IdTokenClaims /
 *       VerificationTarget 值对象 + port（VerificationCodeSender 发码端口）</li>
 *   <li>application：AuthAppService（OIDC 登录/回调/登出编排）+ CodeLoginAppService
 *       （验证码登录编排）+ AccountAppService（账号取数链/两档登录 upsert）+
 *       MeAppService（当前账号）</li>
 *   <li>infrastructure：oidc（SsoProperties / OidcClient / JWKS 验签）、
 *       session（内存会话存储 + RequestContext 过滤器）、code（在途验证码存储
 *       + 日志发送适配器）</li>
 *   <li>endpoints：AuthController / CodeLoginController / MeController +
 *       ApiAuthInterceptor</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Identity", subDomain = SubDomain.SUPPORTING)
package com.semgravio.aiplatform.business.identity;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
