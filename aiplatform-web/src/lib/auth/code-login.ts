import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";

/** 发码回执（有效期秒数）。形状取 gen:api 生成物。 */
export type CodeSent = components["schemas"]["CodeSentResponse"];

/** 登录完成回执（当前账号，MeResponse 同形）。 */
export type CodeLoginAccount = components["schemas"]["MeResponse"];

/**
 * 验证码登录 API（#327/#328 M01 后端面）：/auth/** 不在 /api/** 拦截面，
 * 未登录可调——发码（邮箱/手机号单输入，通道按形态判定）与验码即登录
 * （不存在即静默建档、登录即自动平移访客项目）。错误信封数字业务码由薄
 * client 抛 ApiError.code 透出（6005 形态无效 / 6006 通道未开 / 6007 频控 /
 * 6008 验证码错或过期）。
 *
 * <p>裸前缀直发（rawPath，#381）：后端注册的就是裸 /auth/code、/auth/verify
 * （契约路径即裸前缀），Next rewrite 第二条 /auth/:path* 原样转发；薄 client
 * 缺省拼的 /api/auth/* 是契约外路径——/api/** 拦截面按访客凭证放行后无路由可
 * 匹配，404 "Resource not found"。</p>
 */
export function sendCode(target: string) {
  return api.post<CodeSent>("/auth/code", { target }, { rawPath: true });
}

export function verifyCode(target: string, code: string) {
  return api.post<CodeLoginAccount>("/auth/verify", { target, code }, { rawPath: true });
}
