import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

// 组件测试间的卸载（RTL 自动清理依赖全局 afterEach，本配置不用 globals——显式挂）
afterEach(cleanup);
