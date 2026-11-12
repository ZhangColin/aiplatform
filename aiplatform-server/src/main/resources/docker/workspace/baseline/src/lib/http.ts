/**
 * 外部 HTTP 入口（基座预置·#471）：习惯入口位——只 re-export 健壮件
 * （src/lib/robust/http：默认超时＋错误归一为 HttpError、GET 缺省重试一次、
 * 副作用方法不重试）。应用代码从本文件 import 即用，不要自建请求封装文件
 * 绕过行为契约（收口 lint 强制档会拦，修不完收口被拒）。
 */
export { httpFetch, httpJson, HttpError } from "./robust/http";
export type { HttpFetchOptions, HttpErrorKind } from "./robust/http";
