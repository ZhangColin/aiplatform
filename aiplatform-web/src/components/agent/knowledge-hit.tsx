"use client";

import { ChevronDown, ChevronUp } from "lucide-react";
import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { AgentStreamSegment } from "@/lib/store/agent-streams";
import { cn } from "@/lib/utils";

/**
 * 知识命中卡（通用层，spec 0001 §5）：来源 kind 徽标 + 标题 + 所属项目 + chunk
 * 摘要两行（超阈值可展开）。对话模式消息流（#40）与主面板直播 tab（#23）共用同一
 * 条目渲染，避免「知识命中」概念两处漂移——来源项目名必须显示（跨项目命中是特性，
 * CONTEXT.md「知识命中」）。
 */

export type KnowledgeItem = Extract<AgentStreamSegment, { kind: "knowledge" }>["items"][number];

/** 五类沉淀的徽标口径（A5：ARTIFACT / QA / FEEDBACK / TEST_REPORT / BUG）；名外 kind 原样兜底。 */
export const KIND_META: Record<string, { label: string; className: string }> = {
  ARTIFACT: {
    label: "阶段产物",
    className: "border-blue-500/40 text-blue-700 dark:text-blue-300",
  },
  QA: {
    label: "问答",
    className: "border-emerald-500/40 text-emerald-700 dark:text-emerald-300",
  },
  FEEDBACK: {
    label: "门反馈",
    className: "border-amber-500/40 text-amber-700 dark:text-amber-300",
  },
  TEST_REPORT: {
    label: "测试报告",
    className: "border-violet-500/40 text-violet-700 dark:text-violet-300",
  },
  BUG: {
    label: "Bug",
    className: "border-red-500/40 text-red-700 dark:text-red-300",
  },
};

/** 片段折叠阈值：超过约两行才给「展开」钮（免 DOM 测量的粗略口径）。 */
const SNIPPET_EXPAND_THRESHOLD = 80;

export function KnowledgeHitCard({ item }: { item: KnowledgeItem }) {
  const [expanded, setExpanded] = useState(false);
  const meta = KIND_META[item.kind];
  const snippet = item.snippet ?? "";

  return (
    <li className="rounded-md bg-background/70 p-2.5 text-xs">
      <p className="flex items-center gap-1.5">
        <Badge variant="outline" className={cn("shrink-0", meta?.className)}>
          {meta?.label ?? item.kind}
        </Badge>
        <span className="truncate font-medium">{item.title}</span>
        <span className="ml-auto shrink-0 text-muted-foreground">{item.projectName}</span>
      </p>
      {snippet && (
        <>
          <p
            className={cn(
              "mt-1 whitespace-pre-line text-muted-foreground",
              !expanded && "line-clamp-2",
            )}
          >
            {snippet}
          </p>
          {snippet.length > SNIPPET_EXPAND_THRESHOLD && (
            <Button
              variant="link"
              size="xs"
              onClick={() => setExpanded((v) => !v)}
              className="mt-0.5 h-auto px-0 text-indigo-600 dark:text-indigo-400"
            >
              {expanded ? (
                <>
                  收起 <ChevronUp />
                </>
              ) : (
                <>
                  展开 <ChevronDown />
                </>
              )}
            </Button>
          )}
        </>
      )}
    </li>
  );
}
