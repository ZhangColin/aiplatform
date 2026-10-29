import type { Metadata, Viewport } from "next";
import { Geist, JetBrains_Mono } from "next/font/google";

import { QueryProvider } from "@/components/query-provider";
import { ThemeProvider } from "@/components/theme-provider";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "@/components/ui/sonner";
import { M01Dialog } from "@/components/auth/m01-dialog";
import { SITE_DESCRIPTION, SITE_NAME } from "@/lib/brand";
import { SseProvider } from "@/lib/sse/provider";

import "./globals.css";

const geistSans = Geist({
  variable: "--font-sans",
  subsets: ["latin"],
});

// 等宽＝JetBrains Mono（#342 DESIGN.md：负载/代码检视用字；Geist 保留正文/标题）
const jetbrainsMono = JetBrains_Mono({
  variable: "--font-jetbrains-mono",
  subsets: ["latin"],
});

/** 平台 title（#342 品牌落位）：首页无前缀，内页「页面 · 语义引力」。 */
export const metadata: Metadata = {
  title: {
    default: SITE_NAME,
    template: `%s · ${SITE_NAME}`,
  },
  description: SITE_DESCRIPTION,
};

/** 软键盘交互适配（#350，交接包 13.2「主要操作不被软键盘遮挡」）：Chromium 系
 *  键盘弹起即收缩布局视口（固定高壳与钉底输入区随升）；iOS Safari 不支持本
 *  属性——聚焦滚动兜底在共享 Composer。 */
export const viewport: Viewport = {
  interactiveWidget: "resizes-content",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="zh-CN"
      suppressHydrationWarning
      className={`${geistSans.variable} ${jetbrainsMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        {/* 现阶段 theme 未设计 dark：强制 light，避免跟随系统暗色偏好时呈现未设计的暗色布局；
            设计 dark 后移除 forcedTheme、恢复 defaultTheme="system" + enableSystem */}
        <ThemeProvider
          attribute="class"
          forcedTheme="light"
          disableTransitionOnChange
        >
          <QueryProvider>
            <SseProvider>
              <TooltipProvider>{children}</TooltipProvider>
            </SseProvider>
            <M01Dialog />
            <Toaster />
          </QueryProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
