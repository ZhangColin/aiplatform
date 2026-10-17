import { NextResponse, type NextRequest } from "next/server";

import { hasCredentialCookie } from "@/lib/auth/cookies";
import { buildLoginRedirectUrl } from "@/lib/auth/login-redirect";

/** 被删旧路由前缀（issue #17 前端清场）：书签 / 外链 302 回 /，不带 returnTo。 */
const DELETED_ROUTE_PREFIXES = ["/dev", "/opc", "/admin", "/prototype"];

/** 前缀命中 = 前缀本身或其子路径（等值/加斜杠判定，/developer 不误伤）。 */
function isDeletedRoute(pathname: string): boolean {
  return DELETED_ROUTE_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

/**
 * 路由守卫（spec 0004 §4；#326 白名单放宽）：非白名单路径需会话 cookie 或访客
 * 凭证 cookie（#326 免注册先用——访客项目面刷新可恢复）其一，否则 302 BFF 登录
 * 并带 returnTo。首页 "/" 对完全匿名开放（访客入口：不登录一句话建项目、只逛
 * 零足迹——未提交不建访客；页面上的 /api 调用按 401 出口事件化处理，不整页跳转）。
 * 只验存在性不验真——cookie 在而后端会话/凭证已死时，靠页面首个 /api 401 全局
 * 出口兜底。（Next.js 16 起 middleware 文件约定更名为 proxy，行为一致。）
 */
export function proxy(request: NextRequest) {
  // 一次性原型路由（#68 过程可见性走查，/proto/*）：仅开发环境放行，随原型归档一并删除。
  if (
    process.env.NODE_ENV !== "production" &&
    (request.nextUrl.pathname === "/proto" || request.nextUrl.pathname.startsWith("/proto/"))
  ) {
    return NextResponse.next();
  }

  if (isDeletedRoute(request.nextUrl.pathname)) {
    return NextResponse.redirect(new URL("/", request.url), 302);
  }

  // 首页＝访客入口（#326）：完全匿名可逛（免注册先用、只逛零足迹——提交首条
  // 需求消息才建访客发凭证）；OIDC 回调失败落地页 /?error=* 亦在此放行（原
  // 死循环防线的语义由本开放吸收：/ 无条件 next）
  if (request.nextUrl.pathname === "/") {
    return NextResponse.next();
  }

  if (hasCredentialCookie(request.cookies)) {
    return NextResponse.next();
  }

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
