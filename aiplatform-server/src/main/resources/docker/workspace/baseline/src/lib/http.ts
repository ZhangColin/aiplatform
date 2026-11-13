/**
 * HTTP 入口（基座预置·#471）：习惯入口位——任何 HTTP 调用（含页面/组件调
 * 自家 /api 路由——内部调用不豁免）都从本文件 import；只 re-export 健壮件
 * （src/lib/robust/http：默认超时＋错误归一为 HttpError、GET 缺省重试一次、
 * 副作用方法不重试）。应用代码不裸用 fetch、不自建请求封装文件绕过行为
 * 契约（收口 lint 强制档会拦，修不完收口被拒）。
 */
export { httpFetch, httpJson, HttpError } from "./robust/http";
export type { HttpFetchOptions, HttpErrorKind } from "./robust/http";
