"use client";

import { FileText } from "lucide-react";
import { useEffect, type ReactNode } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { Components } from "react-markdown";

import { Badge } from "@/components/ui/badge";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { usePrd } from "@/hooks/use-prd";
import { hasPrdUpdate, usePrdNoticesStore } from "@/lib/store/prd-notices";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * PRD 篇（#20 口径，#79 从文件模式抽出为「文档」范式正文）：markdown 正文 +
 * 修订「已更新」标记 + markSeen 兜底。文档范式（默认挂载）与文件树模式
 * （PRD 选中）共用同一篇。用户面不带角色署名（ADR 0006 方向：一个「它」）。
 */
export function PrdDoc({
  projectId,
  actions,
}: {
  projectId: string;
  /** 头部操作条槽（「开始做系统」等，文件树模式沿用）。 */
  actions?: ReactNode;
}) {
  const prd = usePrd(projectId);
  const hasUpdate = usePrdNoticesStore((s) => hasPrdUpdate(s, projectId));
  const markSeen = usePrdNoticesStore((s) => s.markSeen);

  useEffect(() => {
    if (prd.data) markSeen(projectId);
  }, [prd.data, projectId, markSeen]);

  // 更新时间缺失 / 不可解析就不标（不臆造兜底时间）
  const updated = prd.data ? formatRelativeTime(prd.data.updatedAt) : "";

  return (
    <ScrollArea className="h-full min-h-0">
      {prd.isPending ? (
        <div className="mx-auto max-w-2xl space-y-4 p-6">
          <Skeleton className="h-6 w-40" />
          <Skeleton className="h-32 w-full" />
        </div>
      ) : (
        <article className="mx-auto max-w-2xl space-y-5 p-6 text-sm leading-relaxed">
          <header className="space-y-1.5 border-b pb-3">
            <div className="flex items-center gap-2">
              <FileText className="size-4 shrink-0 text-muted-foreground" />
              <h2 className="text-base font-semibold">需求文档</h2>
              {hasUpdate ? <Badge variant="secondary">已更新</Badge> : null}
              {actions ? <div className="ml-auto">{actions}</div> : null}
            </div>
            <p className="text-xs text-muted-foreground">
              {prd.data
                ? updated
                  ? `随访谈整理 · 更新于 ${updated}`
                  : "随访谈整理"
                : "平台正在和你梳理需求；整理好的需求文档会出现在这里，随时可以提意见让它更新。"}
            </p>
          </header>
          {prd.data ? (
            <Markdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
              {prd.data.content ?? ""}
            </Markdown>
          ) : (
            <p className="text-muted-foreground">
              你的想法、确认过的要点和待定的事项，都会写进这份需求文档。
            </p>
          )}
        </article>
      )}
    </ScrollArea>
  );
}

/**
 * markdown 元素样式（GFM：表格 / 删除线 / 任务列表等随 remark-gfm 启用；
 * react-markdown 默认不渲染内嵌 HTML、URL 白名单转换，BA 产物安全呈现）。
 * 覆写只解构需要的 props——注入的 node 等不透传 DOM。
 */
const markdownComponents: Components = {
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
  // 内嵌图片域名不可穷举（BA 产物），next/image 的 remotePatterns 不适用
  img: ({ src, alt }) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={src} alt={alt ?? ""} className="max-w-full rounded-lg border" />
  ),
};
