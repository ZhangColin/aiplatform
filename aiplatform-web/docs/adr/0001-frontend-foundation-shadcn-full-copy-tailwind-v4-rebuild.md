# 前端基建：shadcn/ui 全量拷贝 + Tailwind v4 基准重建

本项目以 AI 辅助开发为主，agent 倾向走最省事的路——没有现成组件时就写裸 HTML，UI 质量不稳定。决定：组件库用 shadcn/ui 并 **CLI 全量 add**（~50 个组件全部落在 `src/components/ui/`），图标 lucide、toast sonner、暗色 next-themes（class 策略，`defaultTheme='system'` + 手动切换器），品牌色 `#308ce8` 收编进 token 体系（OKLCH `--primary` + 补全 foreground 映射）；Tailwind 不做 v3→v4 迁移，而是**按官方最新初始化输出（create-next-app + shadcn init）为唯一基准重建模板层**，不受历史模板产物约束。决议过程见 wayfinder 票 #3。

## Considered Options

- **全量拷贝 vs curated 预置 + 规则防线**（curated 清单 / CLAUDE.md 硬规则 / ESLint `forbid-elements` 三层）：选全量。未 import 的组件不进打包产物，死代码零运行时代价；对 agent「素材越全，手写越少」，供给最大化比规则防线更有效，规则越少越省维护。CC（Code-Canvas，视觉基准）全量拷 5,700 行的出发点正是如此。若后续观察到 agent 仍写裸交互元素，再补 lint 强制。
- **迁移 v4 vs 基准重建**：选重建。仓库业务代码为 0 时成本历史最低；shadcn 生态已 v4-first（新组件、OKLCH 色板、`tw-animate-css` 只落 v4，v3 项目被按「旧世界」降级对待）。重建产物同时作为脚手架模板蓝本。
- **shadcn/ui vs Ant Design / Radix 裸用 / 手写**：视觉基准 CC 即 shadcn(Radix) 系，选他库等于让 agent 做视觉翻译（正是 UI 难看的根源）；组件源码拷在仓库内，agent 可读可改。

## Consequences

- `src/components/ui/` 里的未使用组件是**刻意供给，不要清理**。
- `tailwind.config.ts` 消亡（v4 CSS-first，配置进 globals.css `@theme inline`）；`tailwindcss-animate` 换 `tw-animate-css`。
- 使用约定只有一条，写在 CLAUDE.md 样式行内（改行不增条目）：UI 组件一律取 `@/components/ui/`（全量已备），缺的 `pnpm dlx shadcn@latest add`，图标用 lucide。
- 重建执行见独立执行票；#1（工程初始化）的三门户骨架在新地基上继续。

## 2026-08-20 澄清：原语底座为 Base UI（官方默认）

本 ADR 未指定原语底座。#13 按规格「init 全跟官方默认交互」执行时，shadcn 官方默认已是 Base UI（base-nova 预设，2026-07 起为默认并向新项目推荐），故仓库落在 `@base-ui/react`。上文「CC 即 shadcn(Radix) 系」是对 CC 出身的描述（Replit 模板带来的偶然属性），非本仓库约束——同一预设下两底座的视觉产出相同，CC 视觉基准不受影响。复核与确认记录见 #13 comment。
