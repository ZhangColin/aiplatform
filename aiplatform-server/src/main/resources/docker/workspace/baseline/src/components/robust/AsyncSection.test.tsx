import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AsyncSection } from "./AsyncSection";
import { ErrorBoundary } from "./ErrorBoundary";

afterEach(cleanup);

describe("三态 UX 件·状态切换（机制）", () => {
  it("loading：默认文案＋旋转指示（aria 语义）", () => {
    render(<AsyncSection status="loading" />);
    expect(screen.getByText("加载中…")).toBeTruthy();
    expect(document.querySelector("[aria-busy='true']")).toBeTruthy();
  });

  it("empty：默认空态文案", () => {
    render(<AsyncSection status="empty" />);
    expect(screen.getByText("暂无数据")).toBeTruthy();
  });

  it("error：默认错误文案＋重试按钮，onRetry 触达", () => {
    const onRetry = vi.fn();
    render(<AsyncSection status="error" onRetry={onRetry} />);
    expect(screen.getByText("出错了")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("ready：渲染子内容", () => {
    render(<AsyncSection status="ready">订单列表</AsyncSection>);
    expect(screen.getByText("订单列表")).toBeTruthy();
  });
});

describe("三态 UX 件·呈现主权（文案可覆盖＋设计 token 消费）", () => {
  it("全量文案 props 覆盖默认值（error/loading/empty 三态各证）", () => {
    const { rerender } = render(
      <AsyncSection
        status="error"
        onRetry={() => {}}
        loadingText="正在取数…"
        emptyText="还没有订单"
        errorTitle="加载失败"
        errorDescription="网络不太顺畅"
        retryText="再试一次"
      />,
    );
    expect(screen.getByText("加载失败")).toBeTruthy();
    expect(screen.getByText("网络不太顺畅")).toBeTruthy();
    expect(screen.getByRole("button", { name: "再试一次" })).toBeTruthy();
    rerender(<AsyncSection status="loading" loadingText="正在取数…" emptyText="还没有订单" />);
    expect(screen.getByText("正在取数…")).toBeTruthy();
    rerender(<AsyncSection status="empty" loadingText="正在取数…" emptyText="还没有订单" />);
    expect(screen.getByText("还没有订单")).toBeTruthy();
  });

  it("样式吃项目语义 token（类名引用设计 token 体系，非裸色）", () => {
    // 机制/呈现分离证验：组件不写死颜色——空态图标挂 muted-foreground、错误态挂
    // destructive（token 名与 globals.css @theme 映射同源），换肤只动 :root。
    const { container, rerender } = render(<AsyncSection status="empty" />);
    expect(container.querySelector(".text-muted-foreground")).toBeTruthy();
    rerender(<AsyncSection status="error" onRetry={() => {}} />);
    expect(container.querySelector(".text-destructive")).toBeTruthy();
    expect(container.innerHTML).not.toMatch(/#[0-9a-f]{3,8}\b|oklch\(|rgb\(/i);
  });

  it("className 追加不顶掉机制类", () => {
    const { container } = render(
      <AsyncSection status="empty" className="min-h-64" />,
    );
    const root = container.firstElementChild as HTMLElement;
    expect(root.classList.contains("min-h-64")).toBe(true);
    expect(root.classList.contains("text-foreground")).toBe(true);
  });
});

describe("页面级错误边界", () => {
  function Boom({ shouldThrow }: { shouldThrow: boolean }) {
    if (shouldThrow) {
      throw new Error("渲染炸了");
    }
    return <p>正常内容</p>;
  }

  it("子树渲染崩溃被捕获：默认兜底文案＋重试复位", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      let shouldThrow = true;
      const { rerender } = render(
        <ErrorBoundary>
          <Boom shouldThrow={shouldThrow} />
        </ErrorBoundary>,
      );
      expect(screen.getByText("页面出错了")).toBeTruthy();
      // 重试以「当下 props」重进子树：先换非炸子树、再点重试
      shouldThrow = false;
      rerender(
        <ErrorBoundary>
          <Boom shouldThrow={shouldThrow} />
        </ErrorBoundary>,
      );
      fireEvent.click(screen.getByRole("button", { name: "重试" }));
      expect(screen.getByText("正常内容")).toBeTruthy();
    } finally {
      spy.mockRestore();
    }
  });

  it("文案可覆盖＋onError 钩子触达（埋点/日志面）", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      const onError = vi.fn();
      render(
        <ErrorBoundary title="崩了" retryText="刷新看看" onError={onError}>
          <Boom shouldThrow />
        </ErrorBoundary>,
      );
      expect(screen.getByText("崩了")).toBeTruthy();
      expect(screen.getByRole("button", { name: "刷新看看" })).toBeTruthy();
      expect(onError).toHaveBeenCalledTimes(1);
      expect(onError.mock.calls[0][0]).toBeInstanceOf(Error);
    } finally {
      spy.mockRestore();
    }
  });

  it("自定义 fallback（函数形拿 error 与 reset）", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      render(
        <ErrorBoundary fallback={(error, reset) => (
          <button type="button" onClick={reset}>
            自定义兜底:{error.message}
          </button>
        )}>
          <Boom shouldThrow />
        </ErrorBoundary>,
      );
      expect(screen.getByText(/自定义兜底:渲染炸了/)).toBeTruthy();
    } finally {
      spy.mockRestore();
    }
  });

  it("resetKey 变更自动复位（路由参数变化重进页面）", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      let shouldThrow = true;
      const { rerender } = render(
        <ErrorBoundary resetKey="a">
          <Boom shouldThrow={shouldThrow} />
        </ErrorBoundary>,
      );
      expect(screen.getByText("页面出错了")).toBeTruthy();
      shouldThrow = false;
      rerender(
        <ErrorBoundary resetKey="b">
          <Boom shouldThrow={shouldThrow} />
        </ErrorBoundary>,
      );
      expect(screen.getByText("正常内容")).toBeTruthy();
    } finally {
      spy.mockRestore();
    }
  });
});
