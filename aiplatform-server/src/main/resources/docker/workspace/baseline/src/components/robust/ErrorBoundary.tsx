"use client";

import { Component, type ErrorInfo, type ReactNode } from "react";
import { CircleAlert } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * 页面级错误边界（基座健壮件·#421 三态 UX 件的页面兜底腿）：渲染崩溃捕获＋
 * 兜底呈现＋复位出路。机制/呈现分离——捕获/复位是机制（本件担保），兜底长相
 * 吃项目设计 token（语义类）、文案默认可覆盖；整兜底可经 fallback prop 全换。
 *
 * 用法骨架：页面最外层包住内容（resetKey 传路由关键参数，换路由自动复位）：
 *   <ErrorBoundary resetKey={params.id}>…页面内容…</ErrorBoundary>
 */

export interface ErrorBoundaryProps {
  children: ReactNode;
  /** 整兜底替换（函数形拿 error 与 reset；节点形直接替换）。 */
  fallback?: ReactNode | ((error: Error, reset: () => void) => ReactNode);
  /** 崩溃钩子（埋点/日志面）。 */
  onError?: (error: Error, info: ErrorInfo) => void;
  /** 变更即自动复位（路由参数变化重进页面）。 */
  resetKey?: string | number;
  className?: string;
  title?: string;
  description?: string;
  retryText?: string;
}

interface ErrorBoundaryState {
  error: Error | null;
  prevResetKey: string | number | undefined;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null, prevResetKey: this.props.resetKey };

  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    this.props.onError?.(error, info);
  }

  componentDidUpdate(prevProps: ErrorBoundaryProps) {
    // resetKey 变更自动复位（未崩溃时不搅状态）
    if (this.state.error !== null && prevProps.resetKey !== this.props.resetKey) {
      this.reset();
    }
  }

  reset = () => {
    this.setState({ error: null });
  };

  render() {
    const { error } = this.state;
    if (error === null) {
      return this.props.children;
    }
    const { fallback, title = "页面出错了", description = "渲染时遇到问题，可重试或返回。", retryText = "重试", className } = this.props;
    if (fallback !== undefined) {
      return typeof fallback === "function" ? fallback(error, this.reset) : fallback;
    }
    return (
      <div
        role="alert"
        className={cn(
          "flex min-h-40 w-full flex-col items-center justify-center gap-3 rounded-lg border border-border bg-background p-8 text-foreground",
          className,
        )}
      >
        <CircleAlert aria-hidden className="size-6 text-destructive" />
        <div className="flex flex-col items-center gap-1 text-center">
          <p className="text-sm font-medium text-foreground">{title}</p>
          <p className="text-xs text-muted-foreground">{description}</p>
        </div>
        <button
          type="button"
          onClick={this.reset}
          className="rounded-md border border-border bg-background px-3 py-1.5 text-sm text-foreground transition-colors hover:bg-accent"
        >
          {retryText}
        </button>
      </div>
    );
  }
}
