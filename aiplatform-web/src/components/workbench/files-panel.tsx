"use client";

import { ChevronRight, FileText, Folder, FolderOpen } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { Components } from "react-markdown";

import { Badge } from "@/components/ui/badge";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { usePrd } from "@/hooks/use-prd";
import { useProjectFileContent } from "@/hooks/use-project-file-content";
import { useProjectFiles } from "@/hooks/use-project-files";
import { errorText } from "@/lib/api/api-error";
import {
  PRD_PATH,
  buildFileTree,
  formatFileSize,
  isAncestorDir,
  selectableFile,
  type FileTreeNode,
} from "@/lib/projects/files";
import { hasPrdUpdate, usePrdNoticesStore } from "@/lib/store/prd-notices";
import { formatRelativeTime } from "@/lib/utils/time";
import { cn } from "@/lib/utils";

/**
 * 文件模式（#27 文件树浏览，吸收 #20 PRD 呈现）：左侧交付文件树（目录缩进
 * 展开、文件随生成/修正 run 长出——数据失效搭 projects 域粗粒度现成车），
 * 右侧点看内容。PRD 是特殊一篇：走专用端点（markdown + mtime + 修订「已更新」
 * 标记 + markSeen 挂载兜底，口径自 #20 平移）；其余文本文件走内容端点直出。
 * 选中保持/树合成是纯逻辑（files.ts）：缺省落 PRD、目录随选中祖先自动展开、
 * 用户手动收展优先。
 */
export function FilesPanel({
  projectId,
  actions,
}: {
  projectId: string;
  /** 头部操作条（「开始做系统」等动作槽，#22 文件模式入口）。 */
  actions?: ReactNode;
}) {
  const files = useProjectFiles(projectId);
  const [selectedByUser, setSelectedByUser] = useState<string | null>(null);
  const [toggledDirs, setToggledDirs] = useState<Record<string, boolean>>({});

  const entries = useMemo(() => files.data ?? [], [files.data]);
  const tree = useMemo(() => buildFileTree(entries), [entries]);
  const selected = selectableFile(entries, selectedByUser);
  const isDirOpen = (dirPath: string) =>
    toggledDirs[dirPath] ?? (selected !== null && isAncestorDir(dirPath, selected));

  return (
    <div className="flex h-full min-h-0">
      <nav className="w-52 shrink-0 overflow-y-auto border-r py-2" aria-label="文件树">
        {files.isPending ? (
          <div className="space-y-2 px-3">
            <Skeleton className="h-4 w-4/5" />
            <Skeleton className="h-4 w-3/5" />
            <Skeleton className="h-4 w-2/3" />
          </div>
        ) : files.isError ? (
          <p className="px-3 text-xs text-muted-foreground">
            {errorText(files.error, "暂时读不到文件列表")}
          </p>
        ) : tree.length === 0 ? (
          <p className="px-3 text-xs text-muted-foreground">PRD 与系统文件会随进展出现在这里</p>
        ) : (
          <TreeRows
            nodes={tree}
            depth={0}
            selected={selected}
            isOpen={isDirOpen}
            onToggleDir={(path) =>
              setToggledDirs((prev) => ({ ...prev, [path]: !isDirOpen(path) }))
            }
            onSelectFile={setSelectedByUser}
          />
        )}
      </nav>
      <div className="flex min-w-0 flex-1 flex-col">
        {selected === PRD_PATH ? (
          <PrdView projectId={projectId} actions={actions} />
        ) : selected !== null ? (
          <FileView
            projectId={projectId}
            path={selected}
            size={entries.find((file) => file.path === selected)?.size}
            actions={actions}
          />
        ) : (
          <div className="flex flex-1 items-center justify-center p-6 text-sm text-muted-foreground">
            PRD 与系统文件会随进展出现在这里
          </div>
        )}
      </div>
    </div>
  );
}

/** 树行渲染：目录行收展（chevron + folder）、文件行点选；缩进随深度。 */
function TreeRows({
  nodes,
  depth,
  selected,
  isOpen,
  onToggleDir,
  onSelectFile,
}: {
  nodes: FileTreeNode[];
  depth: number;
  selected: string | null;
  isOpen: (dirPath: string) => boolean;
  onToggleDir: (dirPath: string) => void;
  onSelectFile: (path: string) => void;
}) {
  return (
    <ul className="space-y-0.5">
      {nodes.map((node) =>
        node.kind === "dir" ? (
          <li key={node.path}>
            {(() => {
              const open = isOpen(node.path);
              return (
                <>
                  <button
                    type="button"
                    data-tree-dir={node.path}
                    style={{ paddingLeft: depth * 14 + 6 }}
                    onClick={() => onToggleDir(node.path)}
                    className="flex w-full items-center gap-1 rounded-sm py-1 pr-2 text-left text-xs hover:bg-accent hover:text-accent-foreground"
                  >
                    <ChevronRight
                      className={cn(
                        "size-3.5 shrink-0 text-muted-foreground transition-transform",
                        open && "rotate-90",
                      )}
                    />
                    {open ? (
                      <FolderOpen className="size-3.5 shrink-0 text-muted-foreground" />
                    ) : (
                      <Folder className="size-3.5 shrink-0 text-muted-foreground" />
                    )}
                    <span className="truncate">{node.name}</span>
                  </button>
                  {open ? (
                    <TreeRows
                      nodes={node.children}
                      depth={depth + 1}
                      selected={selected}
                      isOpen={isOpen}
                      onToggleDir={onToggleDir}
                      onSelectFile={onSelectFile}
                    />
                  ) : null}
                </>
              );
            })()}
          </li>
        ) : (
          <li key={node.path}>
            <button
              type="button"
              data-tree-file={node.path}
              style={{ paddingLeft: depth * 14 + 6 + 14 }}
              onClick={() => onSelectFile(node.path)}
              className={cn(
                "flex w-full items-center gap-1 rounded-sm py-1 pr-2 text-left text-xs hover:bg-accent hover:text-accent-foreground",
                selected === node.path && "bg-accent text-accent-foreground",
              )}
            >
              <FileText className="size-3.5 shrink-0 text-muted-foreground" />
              <span className="truncate">{node.name}</span>
            </button>
          </li>
        ),
      )}
    </ul>
  );
}

/** PRD 篇（#20 口径平移）：markdown 正文 + 修订「已更新」标记 + markSeen 兜底。 */
function PrdView({ projectId, actions }: { projectId: string; actions?: ReactNode }) {
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
              <h2 className="text-base font-semibold">{PRD_PATH}</h2>
              {hasUpdate ? <Badge variant="secondary">已更新</Badge> : null}
              {actions ? <div className="ml-auto">{actions}</div> : null}
            </div>
            <p className="text-xs text-muted-foreground">
              {prd.data
                ? updated
                  ? `需求分析师整理 · 更新于 ${updated}`
                  : "需求分析师整理"
                : "需求分析师正在和你梳理需求；整理好的 PRD 会出现在这里，随时可以提意见让它更新。"}
            </p>
          </header>
          {prd.data ? (
            <Markdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
              {prd.data.content ?? ""}
            </Markdown>
          ) : (
            <p className="text-muted-foreground">
              你的想法、确认过的要点和待定的事项，都会写进这份 PRD。
            </p>
          )}
        </article>
      )}
    </ScrollArea>
  );
}

/** 其余文本文件篇：头部路径 + 大小，正文 pre 直出（等宽、横向滚动）。 */
function FileView({
  projectId,
  path,
  size,
  actions,
}: {
  projectId: string;
  path: string;
  size: number | undefined;
  actions?: ReactNode;
}) {
  const entry = useProjectFileContent(projectId, path);
  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex h-10 shrink-0 items-center gap-2 border-b px-3">
        <FileText className="size-4 shrink-0 text-muted-foreground" />
        <span className="truncate font-mono text-xs">{path}</span>
        {size !== undefined ? (
          <span className="shrink-0 text-xs text-muted-foreground">{formatFileSize(size)}</span>
        ) : null}
        {actions ? <div className="ml-auto shrink-0">{actions}</div> : null}
      </header>
      <ScrollArea className="min-h-0 flex-1">
        {entry.isPending ? (
          <div className="space-y-2 p-4">
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-5/6" />
            <Skeleton className="h-4 w-4/6" />
          </div>
        ) : entry.isError ? (
          <p className="p-4 text-xs text-muted-foreground">
            {errorText(entry.error, "暂时读不到这个文件")}
          </p>
        ) : (
          <pre className="p-4 font-mono text-xs leading-relaxed">{entry.data?.content}</pre>
        )}
      </ScrollArea>
    </div>
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
