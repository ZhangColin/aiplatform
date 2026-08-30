"use client";

import { Download, PackageCheck } from "lucide-react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { Components } from "react-markdown";

import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { usePrd } from "@/hooks/use-prd";
import { useProjectJourney } from "@/hooks/use-project";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * 主面板「文档」tab 内容（spec 0002 §4，issue #20 / #54）：交付前 = 当前版 PRD
 * （`GET …/prd` markdown + 更新时间，v1 无版本链；未产出 404 PRJ_015 归一 null →
 * 引导占位）；`document-updated` SSE → 桥失效文档域重拉（失效为主，ADR 0003）。
 * 已交付 = 交付说明 + 源码包下载。源码包是 binary 流——不走 ApiResponse 信封
 * （ADR-0001 修订例外），直挂 `<a download>` 旁路薄 client 的统一解包，浏览器
 * 经同源 rewrite 真实下载。tab 条与右栏开关归 main-panel.tsx（#22 主面板 tabs 化）。
 */
export function DocPanel({ projectId }: { projectId: string }) {
  const { data: detail, isPending, current } = useProjectJourney(projectId);
  const delivered = current?.terminal === true;
  const prd = usePrd(projectId);
  // 交付分支不消费 PRD；交付前的首次加载给骨架（null 是已落定的「未产出」态）
  const loading = isPending || (!delivered && prd.isPending);
  // 更新时间缺失 / 不可解析就不标（不臆造兜底时间）
  const updated = delivered || !prd.data ? "" : formatRelativeTime(prd.data.updatedAt);

  return (
    <ScrollArea className="h-full min-h-0">
      {loading ? (
        <div className="mx-auto max-w-2xl space-y-4 p-6">
          <Skeleton className="h-6 w-40" />
          <Skeleton className="h-32 w-full" />
        </div>
      ) : (
        <article className="mx-auto max-w-2xl space-y-5 p-6 text-sm leading-relaxed">
          <header className="space-y-1 border-b pb-3">
            <h2 className="text-lg font-semibold">{delivered ? "交付说明" : "PRD"}</h2>
            <p className="text-xs text-muted-foreground">
              {delivered
                ? "交付物清单与下载"
                : prd.data
                  ? updated
                    ? `顾问整理 · 更新于 ${updated}`
                    : "顾问整理"
                  : "顾问整理 · 你确认过的都会记在这里"}
            </p>
          </header>
          {delivered ? (
            <div className="space-y-3">
              <p className="font-medium">你拿到的东西</p>
              <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
                <li>源码包（tar.gz）：系统的全部代码，可交给任何团队继续维护</li>
              </ul>
              <div className="pt-2">
                <Button
                  variant="secondary"
                  size="sm"
                  nativeButton={false}
                  render={
                    <a
                      href={`/api/projects/${projectId}/source-package`}
                      download={`${detail?.name || "项目"}.tar.gz`}
                    />
                  }
                >
                  <Download className="size-3.5" /> 下载源码包
                </Button>
              </div>
              <p className="flex items-center gap-1.5 pt-2 text-xs text-muted-foreground">
                <PackageCheck className="size-3.5" />
                想法池里记的新想法会留给下一期，随时可查。
              </p>
            </div>
          ) : prd.data ? (
            <Markdown remarkPlugins={[remarkGfm]} components={prdComponents}>
              {prd.data.content ?? ""}
            </Markdown>
          ) : (
            <div className="space-y-3">
              <p className="text-muted-foreground">
                顾问正在陪你把想做的事聊清楚；整理好的 PRD 会出现在这里，确认后才开始制作。
              </p>
            </div>
          )}
        </article>
      )}
    </ScrollArea>
  );
}

/**
 * PRD markdown 元素样式（GFM：表格 / 删除线 / 任务列表等随 remark-gfm 启用；
 * react-markdown 默认不渲染内嵌 HTML、URL 白名单转换，BA 产物安全呈现）。
 * 覆写只解构需要的 props——注入的 node 等不透传 DOM。
 */
const prdComponents: Components = {
  h1: ({ children }) => <h1 className="mt-6 text-xl font-semibold first:mt-0">{children}</h1>,
  h2: ({ children }) => <h2 className="mt-6 text-lg font-semibold first:mt-0">{children}</h2>,
  h3: ({ children }) => <h3 className="mt-5 text-base font-semibold first:mt-0">{children}</h3>,
  p: ({ children }) => <p className="leading-relaxed">{children}</p>,
  ul: ({ children }) => <ul className="list-disc space-y-1 pl-5">{children}</ul>,
  ol: ({ children }) => <ol className="list-decimal space-y-1 pl-5">{children}</ol>,
  li: ({ children }) => <li className="leading-relaxed">{children}</li>,
  a: ({ children, href }) => (
    <a href={href} target="_blank" rel="noopener noreferrer" className="text-primary underline underline-offset-2">
      {children}
    </a>
  ),
  blockquote: ({ children }) => (
    <blockquote className="border-l-2 pl-3 text-muted-foreground">{children}</blockquote>
  ),
  hr: () => <hr className="my-4 border-t" />,
  strong: ({ children }) => <strong className="font-semibold">{children}</strong>,
  code: ({ children }) => (
    <code className="rounded bg-muted px-1 py-0.5 font-mono text-[0.8125rem]">{children}</code>
  ),
  pre: ({ children }) => (
    <pre className="overflow-x-auto rounded-lg border bg-muted/50 p-3 font-mono text-xs [&_code]:rounded-none [&_code]:bg-transparent [&_code]:px-0 [&_code]:py-0">
      {children}
    </pre>
  ),
  table: ({ children }) => (
    <table className="w-full border-collapse text-xs [&_td]:border [&_td]:px-2 [&_td]:py-1.5 [&_th]:border [&_th]:bg-muted/50 [&_th]:px-2 [&_th]:py-1.5 [&_th]:text-left">
      {children}
    </table>
  ),
  // PRD 内嵌图片域名不可穷举（BA 产物），next/image 的 remotePatterns 不适用
  img: ({ src, alt }) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={src} alt={alt ?? ""} className="max-w-full rounded-lg border" />
  ),
};
