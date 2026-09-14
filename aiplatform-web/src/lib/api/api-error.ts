/** 表单级校验错误，就地展示于对应字段，不进 toast。字段定义以 ADR 0002 为准。 */
export type FieldError = {
  field: string;
  message: string;
  errorCode: string;
};

/** 信封 / 错误共有的元字段（ADR 0002）。 */
export type ApiEnvelopeMeta = {
  /**
   * 信封 code：数字业务码＝域码×1000＋序号（WSP_012→1012、PRJ_015→4015，正本
   * aiplatform-server ErrorCodePrefix，#169）。与 HTTP 状态独立——判定认业务码，
   * 勿拿 status 当业务语义（auth 等非业务码面仍是 httpStatus 数字）。
   */
  code?: number;
  /** 后端中文 message，可直接 toast（sonner 全局出口）。 */
  message?: string;
  errors?: FieldError[];
  /** 对后端日志排障。 */
  requestId?: string;
};

export type ApiErrorInit = ApiEnvelopeMeta & {
  /** HTTP 状态码，4xx / 5xx 的分派依据。 */
  status: number;
};

/** 后端错误统一形态：薄 client 在非 2xx 时抛出，业务层只 catch 这一种。ADR 0002。 */
export class ApiError extends Error {
  readonly status: number;
  readonly code?: number;
  readonly errors?: FieldError[];
  readonly requestId?: string;

  constructor(init: ApiErrorInit) {
    super(init.message ?? `请求失败（HTTP ${init.status}）`);
    this.name = "ApiError";
    this.status = init.status;
    this.code = init.code;
    this.errors = init.errors;
    this.requestId = init.requestId;
  }
}

/** toast 兜底文案：ApiError 直出后端中文 message（ADR 0002，如 409 PRJ_013），其余给通用 fallback。 */
export function errorText(error: unknown, fallback = "操作失败，请稍后重试"): string {
  return error instanceof ApiError ? error.message : fallback;
}
