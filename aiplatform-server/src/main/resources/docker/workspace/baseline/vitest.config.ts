import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// 测试跑道（#421）：零 setup 写/跑测试——配置随基座模板预置，测试文件与源码
// 同目录（src/**/*.test.ts(x)），`pnpm test` 直跑。环境 happy-dom（组件渲染需要
// DOM；纯逻辑测试同环境无感）。plugin-react 承担 tsx 转译（工程 tsconfig 的
// jsx: preserve 是 Next 构建所需、测试面须覆盖）。@ 别名与 tsconfig paths 对齐。
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "happy-dom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["vitest.setup.ts"],
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
