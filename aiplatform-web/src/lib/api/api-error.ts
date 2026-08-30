/** 表单级校验错误，就地展示于对应字段，不进 toast。字段定义以 ADR 0002 为准。 */
export type FieldError = {
  field: string;
  message: string;
  errorCode: string;
};

/** 信封 / 错误共有的元字段（ADR 0002）。 */
export type ApiEnvelopeMeta = {
  /** 后端业务错误码，如 PRJ_001。 */
  code?: string;
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
  readonly code?: string;
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
