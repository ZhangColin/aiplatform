"use client";

import { ChevronRight, FileText, Folder, FolderOpen } from "lucide-react";
import { useMemo, useState } from "react";

import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
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
import { cn } from "@/lib/utils";

import { PrdDoc } from "./prd-doc";

/**
 * 文件范式（#27 文件树浏览，吸收 #20 PRD 呈现；#79 归入范式注册表按需挂载）：
 * 左侧交付文件树（目录缩进展开、文件随生成/修正 run 长出——数据失效搭
 * projects 域粗粒度现成车），右侧点看内容。PRD 是特殊一篇：走「文档」范式
 * 同一篇 PrdDoc（markdown + 修订「已更新」标记 + markSeen 挂载兜底）；其余
 * 文本文件走内容端点直出。选中保持/树合成是纯逻辑（files.ts）：缺省落 PRD、
 * 目录随选中祖先自动展开、用户手动收展优先。
 */
export function FilesPanel({
  projectId,
}: {
  projectId: string;
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
          <PrdDoc projectId={projectId} />
        ) : selected !== null ? (
          <FileView
            projectId={projectId}
            path={selected}
            size={entries.find((file) => file.path === selected)?.size}
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

/** 其余文本文件篇：头部路径 + 大小，正文 pre 直出（等宽、横向滚动）。 */
function FileView({
  projectId,
  path,
  size,
}: {
  projectId: string;
  path: string;
  size: number | undefined;
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
