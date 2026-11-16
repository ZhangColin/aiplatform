import { Loader2 } from "lucide-react";
import { useId, useRef, useState } from "react";
import { cn } from "@/lib/utils";

/**
 * 表单模式件（ADR-0051 基座模式件）：「表单＋校验＋提交态」的行为骨架
 * 收口——字段定义驱动渲染、提交前校验（必填＋自定义 validate）、提交状态机
 * （idle / submitting / success / error）。机制/呈现分离（三态 UX 件同款律）：
 *
 * - 样式只吃语义 token 类（text-foreground / text-destructive / border-input
 *   等，与 globals.css @theme 映射同源）——换肤只动 :root，本件零改动；
 *   禁裸色、禁任意值、禁 inline style（呈现零主张）。
 * - 文案默认可覆盖（submitText / errorText / requiredMessage 等 props）。
 * - 定制三通道封顶：参数（fields/onSubmit props）、文案覆盖（*Text props）、
 *   wrapper（外层包卡片/分区）；本件内部不改（基座件不改约）。
 *
 * 用法骨架：
 *   <ValidatedForm
 *     fields={[{ name: 'phone', label: '手机号', required: true }]}
 *     onSubmit={async (values) => { await save(values); }} />
 */

export type PatternFormFieldType =
  | "text"
  | "password"
  | "email"
  | "number"
  | "textarea"
  | "select";

export interface PatternFormField {
  /** 字段名——提交值对象的键。 */
  name: string;
  label: string;
  type?: PatternFormFieldType;
  required?: boolean;
  placeholder?: string;
  /** select 型选项。 */
  options?: { value: string; label: string }[];
  /** 初始值。 */
  defaultValue?: string;
  /** 输入框下方弱化说明文案。 */
  hint?: string;
  /** 自定义校验：返回错误文案或 null（在必填检查之后执行）。 */
  validate?: (
    value: string,
    values: Record<string, string>,
  ) => string | null;
}

export type PatternFormStatus = "idle" | "submitting" | "success" | "error";

export interface ValidatedFormProps {
  fields: PatternFormField[];
  /** 校验全过后调用，收到字段名 → 值 的对象（值一律字符串，与表单控件一致）。 */
  onSubmit: (values: Record<string, string>) => Promise<void> | void;
  className?: string;
  /** 表单字段区上方的说明文案（如「带 * 为必填」）。 */
  description?: string;
  /** 以下文案全量可覆盖（呈现主权在项目侧）。 */
  submitText?: string;
  submittingText?: string;
  successText?: string;
  errorText?: string;
  requiredMessage?: string;
}

const controlClass =
  "flex min-h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm text-foreground transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50";

export function ValidatedForm({
  fields,
  onSubmit,
  className,
  description,
  submitText = "提交",
  submittingText = "提交中…",
  successText = "已提交",
  errorText = "提交失败，请稍后重试",
  requiredMessage = "此项必填",
}: ValidatedFormProps) {
  const formId = useId();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      fields.map((f) => [f.name, f.defaultValue ?? ""]),
    ),
  );
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<PatternFormStatus>("idle");
  const controls = useRef<Record<string, HTMLElement | null>>({});

  function validateAll(): Record<string, string> {
    const next: Record<string, string> = {};
    for (const field of fields) {
      const value = values[field.name] ?? "";
      if (field.required && !value.trim()) {
        next[field.name] = requiredMessage;
        continue;
      }
      const custom = field.validate?.(value, values);
      if (custom) {
        next[field.name] = custom;
      }
    }
    return next;
  }

  function setValue(name: string, value: string) {
    setValues((prev) => ({ ...prev, [name]: value }));
    setErrors((prev) => {
      if (!(name in prev)) {
        return prev;
      }
      const next = { ...prev };
      delete next[name];
      return next;
    });
    setStatus((prev) => (prev === "error" ? "idle" : prev));
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (status === "submitting") {
      return;
    }
    const next = validateAll();
    setErrors(next);
    if (Object.keys(next).length > 0) {
      const first = fields.find((f) => next[f.name]);
      first && controls.current[first.name]?.focus();
      return;
    }
    setStatus("submitting");
    try {
      await onSubmit(values);
      setStatus("success");
    } catch {
      setStatus("error");
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      className={cn("flex w-full flex-col gap-4", className)}
      noValidate
    >
      {description ? (
        <p className="text-sm text-muted-foreground">{description}</p>
      ) : null}
      {fields.map((field) => {
        const id = `${formId}-${field.name}`;
        const error = errors[field.name];
        const describedBy = [error ? `${id}-error` : null, field.hint ? `${id}-hint` : null]
          .filter(Boolean)
          .join(" ");
        return (
          <div key={field.name} className="flex flex-col gap-1.5">
            <div className="flex items-center gap-1">
              <label htmlFor={id} className="text-sm font-medium text-foreground">
                {field.label}
              </label>
              {field.required ? (
                <span aria-hidden className="text-xs text-destructive">
                  *
                </span>
              ) : null}
            </div>
            {field.type === "textarea" ? (
              <textarea
                id={id}
                ref={(el) => {
                  controls.current[field.name] = el;
                }}
                value={values[field.name] ?? ""}
                placeholder={field.placeholder}
                aria-required={field.required || undefined}
                aria-invalid={error ? true : undefined}
                aria-describedby={describedBy || undefined}
                className={cn(controlClass, "min-h-20 py-2")}
                onChange={(e) => setValue(field.name, e.target.value)}
              />
            ) : field.type === "select" ? (
              <select
                id={id}
                ref={(el) => {
                  controls.current[field.name] = el;
                }}
                value={values[field.name] ?? ""}
                aria-required={field.required || undefined}
                aria-invalid={error ? true : undefined}
                aria-describedby={describedBy || undefined}
                className={controlClass}
                onChange={(e) => setValue(field.name, e.target.value)}
              >
                {field.options?.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            ) : (
              <input
                id={id}
                ref={(el) => {
                  controls.current[field.name] = el;
                }}
                type={field.type ?? "text"}
                value={values[field.name] ?? ""}
                placeholder={field.placeholder}
                aria-required={field.required || undefined}
                aria-invalid={error ? true : undefined}
                aria-describedby={describedBy || undefined}
                className={controlClass}
                onChange={(e) => setValue(field.name, e.target.value)}
              />
            )}
            {error ? (
              <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
                {error}
              </p>
            ) : field.hint ? (
              <p id={`${id}-hint`} className="text-xs text-muted-foreground">
                {field.hint}
              </p>
            ) : null}
          </div>
        );
      })}
      {status === "success" ? (
        <p role="status" className="text-sm text-muted-foreground">
          {successText}
        </p>
      ) : null}
      {status === "error" ? (
        <p role="alert" className="text-sm text-destructive">
          {errorText}
        </p>
      ) : null}
      <button
        type="submit"
        disabled={status === "submitting"}
        className="inline-flex h-9 w-fit items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50"
      >
        {status === "submitting" ? (
          <>
            <Loader2 aria-hidden className="size-4 animate-spin" />
            {submittingText}
          </>
        ) : (
          submitText
        )}
      </button>
    </form>
  );
}
