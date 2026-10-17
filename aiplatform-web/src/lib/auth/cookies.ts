/**
 * 凭证 cookie 名（proxy 路由守卫与首页会话感知共用单源）：BFF 会话 cookie
 *（A2：不透明 sessionId）与访客凭证 cookie（#326：值即凭证）。两者 HttpOnly，
 * 浏览器脚本不可读——只由服务端（proxy / 页面）验存在性。
 */
export const SESSION_COOKIE = "aiplatform_session";
export const GUEST_COOKIE = "aiplatform_guest";

/** cookie 只读面（next/headers 与 proxy request.cookies 同形的最小结构）。 */
type CookiePresence = { has(name: string): boolean };

/**
 * 凭证存在性（只验存在不验真——cookie 在而后端会话/凭证已死时，靠页面首个
 * /api 401 全局出口兜底）。首页据此决定是否发「最近项目/会话身份」背景查询：
 * 完全匿名（只逛零足迹）不发请求，也就不弹登录框。
 */
export function hasCredentialCookie(cookies: CookiePresence): boolean {
  return cookies.has(SESSION_COOKIE) || cookies.has(GUEST_COOKIE);
}
