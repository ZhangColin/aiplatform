import path from "node:path";

import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: { "@": path.resolve(import.meta.dirname, "src") },
  },
  test: {
    // 纯逻辑单测：SSE 层四个 seam（store 驱逐 / 信封解析 / 连接状态机 / 失效桥）。
    // .tsx 为用 renderToStaticMarkup 的组件 SSR 断言（node 环境即可，不引入 DOM）。
    // 浏览器 API（EventSource / fetch / window）在各测试内 stub，不引入 DOM 环境。
    // 例外：需断言客户端 useEffect（如 Base UI nativeButton 告警）的组件测试，
    // 用逐文件 `// @vitest-environment happy-dom` pragma 覆盖为 DOM 环境。
    environment: "node",
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
