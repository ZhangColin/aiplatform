import { CircleAlert, Inbox, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * 三态 UX 件（基座健壮件·#421）：加载/空/错/就绪四态切换的机制收口。
 * 机制/呈现分离——本件担「状态切换」行为契约，呈现主权在项目侧：
 *
 * - 样式吃项目设计 token：只引用语义类（text-muted-foreground / text-destructive
 *   / bg-background 等，与 globals.css @theme 映射同源）——换肤只动 :root，
 *   组件零改动；不写死色值（禁裸色是带规范项目的 lint 面）。
 * - 文案默认可覆盖：loadingText/emptyText/errorTitle/retryText 等 props 全量
 *   可换，业务语气各随项目。
 *
 * 用法骨架：
 *   const [state, setState] = useState<AsyncStatus>("loading");
 *   <AsyncSection status={state} onRetry={load}>…列表…</AsyncSection>
 */

export type AsyncStatus = "loading" | "empty" | "error" | "ready";

export interface AsyncSectionProps {
  status: AsyncStatus;
  /** ready 态渲染的内容。 */
  children?: React.ReactNode;
  /** error 态重试回调；缺省不渲染重试按钮。 */
  onRetry?: () => void;
  className?: string;
  /** 以下文案全量可覆盖（呈现主权在项目侧）。 */
  loadingText?: string;
  emptyText?: string;
  errorTitle?: string;
  errorDescription?: string;
  retryText?: string;
}

const iconClass = "size-6";

export function AsyncSection({
  status,
  children,
  onRetry,
  className,
  loadingText = "加载中…",
  emptyText = "暂无数据",
  errorTitle = "出错了",
  errorDescription,
  retryText = "重试",
}: AsyncSectionProps) {
  return (
    <div
      aria-busy={status === "loading"}
      className={cn(
        "flex min-h-40 w-full flex-col items-center justify-center gap-3 rounded-lg border border-border bg-background p-8 text-foreground",
        className,
      )}
    >
      {status === "loading" && (
        <>
          <Loader2 aria-hidden className={cn(iconClass, "animate-spin text-muted-foreground")} />
          <p className="text-sm text-muted-foreground">{loadingText}</p>
        </>
      )}
      {status === "empty" && (
        <>
          <Inbox aria-hidden className={cn(iconClass, "text-muted-foreground")} />
          <p className="text-sm text-muted-foreground">{emptyText}</p>
        </>
      )}
      {status === "error" && (
        <>
          <CircleAlert aria-hidden className={cn(iconClass, "text-destructive")} />
          <div className="flex flex-col items-center gap-1 text-center">
            <p className="text-sm font-medium text-foreground">{errorTitle}</p>
            {errorDescription ? (
              <p className="text-xs text-muted-foreground">{errorDescription}</p>
            ) : null}
          </div>
          {onRetry ? (
            <button
              type="button"
              onClick={onRetry}
              className="rounded-md border border-border bg-background px-3 py-1.5 text-sm text-foreground transition-colors hover:bg-accent"
            >
              {retryText}
            </button>
          ) : null}
        </>
      )}
      {status === "ready" && children}
    </div>
  );
}
