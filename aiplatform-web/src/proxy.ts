import { NextResponse, type NextRequest } from "next/server";

import { buildLoginRedirectUrl } from "@/lib/auth/login-redirect";

/** BFF 会话 cookie（A2：不透明 sessionId，HttpOnly + SameSite=Lax）。 */
const SESSION_COOKIE = "aiplatform_session";

/** 被删旧门户路由前缀（issue #17 前端清场）：书签 / 外链 302 回 /，不带 returnTo。 */
const DELETED_ROUTE_PREFIXES = ["/dev", "/opc", "/admin", "/prototype"];

/** 前缀命中 = 前缀本身或其子路径（等值/加斜杠判定，/developer 不误伤）。 */
function isDeletedRoute(pathname: string): boolean {
  return DELETED_ROUTE_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

/**
 * 路由守卫（spec 0004 §4）：非白名单路径无会话 cookie → 302 BFF 登录并带 returnTo。
 * 只验存在性不验真——cookie 在而后端会话已死时，靠页面首个 /api 401 全局出口兜底。
 * （Next.js 16 起 middleware 文件约定更名为 proxy，行为一致。）
 */
export function proxy(request: NextRequest) {
  if (isDeletedRoute(request.nextUrl.pathname)) {
    return NextResponse.redirect(new URL("/", request.url), 302);
  }

  if (request.cookies.has(SESSION_COOKIE)) return NextResponse.next();

  return NextResponse.redirect(
    new URL(
      buildLoginRedirectUrl(request.nextUrl.pathname, request.nextUrl.search),
      request.url,
    ),
    302,
  );
}

/**
 * 白名单：/auth/*（代理路径，拦了死循环）、/api/*、_next、favicon.ico、静态资源。
 * 被删旧路由（/dev /opc /admin /prototype）须进 proxy 才能吃到 302 回 /。
 * matcher 正则以 (?:/|$) 锚定前缀，避免误伤相似前缀路径。
 */
export const config = {
  matcher: [
    "/((?!auth(?:/|$)|api(?:/|$)|_next(?:/|$)|favicon\\.ico$|.*\\.(?:svg|png|jpg|jpeg|gif|webp)$).*)",
  ],
};
