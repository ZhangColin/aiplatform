/**
 * 401 出口（client.ts）与 proxy 首跳共用的登录跳转目标——spec 0004 §2
 * 「两个入口共用同一机制」。returnTo = encodeURIComponent(pathname + search)，
 * 仅同源相对路径；开放重定向校验与兜底在后端。
 */
export function buildLoginRedirectUrl(pathname: string, search: string) {
  return `/auth/login?returnTo=${encodeURIComponent(pathname + search)}`;
}
