import { NextResponse, type NextRequest } from "next/server";

import { buildLoginRedirectUrl } from "@/lib/auth/login-redirect";

/** BFF 会话 cookie（A2：不透明 sessionId，HttpOnly + SameSite=Lax）。 */
const SESSION_COOKIE = "aiplatform_session";

/**
 * 路由守卫（spec 0004 §4）：非白名单路径无会话 cookie → 302 BFF 登录并带 returnTo。
 * 只验存在性不验真——cookie 在而后端会话已死时，靠页面首个 /api 401 全局出口兜底。
 * （Next.js 16 起 middleware 文件约定更名为 proxy，行为一致。）
 */
export function proxy(request: NextRequest) {
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
 * 白名单：/auth/*（代理路径，拦了死循环）、/api/*、_next、favicon.ico、
 * /prototype/*（UX 原型）、静态资源。前缀用 (?:/|$) 锚定，避免误伤相似前缀路径。
 */
export const config = {
  matcher: [
    "/((?!auth(?:/|$)|api(?:/|$)|_next(?:/|$)|favicon\\.ico$|prototype(?:/|$)|.*\\.(?:svg|png|jpg|jpeg|gif|webp)$).*)",
  ],
};
