import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

/**
 * ESLint 9 flat config（#633 阶段五-1）：模板原先有 lint 脚本却没有配置，
 * `pnpm lint` 直接报「找不到 eslint.config.*」——模型只能自己补配置烧轮次。
 * 预设取模板已装的 eslint-config-next（Next 16 原生 flat config 导出）。
 * 镜像构建期在 pnpm test 之后跑 pnpm lint（dev-image.Dockerfile），配置缺失
 * 或腐烂在构建期就红。模板变更随 DEV_IMAGE 版本生效，存量工作区不回补。
 */
export default defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts"]),
]);
