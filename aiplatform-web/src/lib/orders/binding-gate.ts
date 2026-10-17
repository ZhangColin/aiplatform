import { ApiError } from "@/lib/api/api-error";

/**
 * 签约门（#328，#320 段②）：进入下单/签约流程要求先登录绑定账号——未登录的
 * 访客走下单入口被后端拦（ORD_017）。前端认数字业务码（域码 5×1000＋17＝5017，
 * 正本 aiplatform-server ErrorCodePrefix；勿拿 status 当业务语义），弹 M01
 * 登录绑定、绑定后放行（登录即平移，账户会话接管）。
 */
export const ORDER_BINDING_REQUIRED_CODE = 5017;

/** 下单被签约门拦下（未登录绑定的访客）——判据即业务码，非 HTTP status。 */
export function isBindingGateError(error: unknown): boolean {
  return error instanceof ApiError && error.code === ORDER_BINDING_REQUIRED_CODE;
}
