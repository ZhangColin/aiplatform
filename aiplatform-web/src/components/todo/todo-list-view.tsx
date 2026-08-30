"use client";

import Link from "next/link";

import { badgeToneClass } from "@/components/badges";
import { PortalContent } from "@/components/layout/portal-sidebar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useTodoList } from "@/hooks/use-todos";
import { errorText } from "@/lib/api/api-error";
import { todoHref, todoTypeMeta, type TodoItem, type TodoView } from "@/lib/todos/todo";
import { cn } from "@/lib/utils";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * 待办列表（issue #21，spec 0003 §3）：同一组件双端场景配置（view + 文案）。
 * v1 不分组不过滤——时间倒序单列表 + 类型徽章；点击去向由 `todoHref` 落深链
 * （issue #44：AGENT_WAIT → 工作台对话模式定位等待点、GATE_PENDING → 门卡、
 * 任务两型 → 任务面板、opc 两型 → 任务详情）。
 */

export type TodoListViewProps = {
  view: TodoView;
  title: string;
  description?: string;
  emptyText: string;
};

export function TodoListView({ view, title, description, emptyText }: TodoListViewProps) {
  const list = useTodoList(view);
  const items = list.data ?? [];

  return (
    <PortalContent>
      <div className="mx-auto max-w-3xl p-6">
        {/* 非工作台页页头（spec 0001 §2）：标题 + 说明（收起归品牌行，issue #50） */}
        <header className="mb-5 flex items-center gap-2">
          <div>
            <h1 className="text-lg font-semibold">{title}</h1>
            {description && <p className="text-xs text-muted-foreground">{description}</p>}
          </div>
        </header>

        {list.isPending ? (
          <div className="space-y-2">
            {[0, 1, 2].map((i) => (
              <Card key={i} className="gap-2 py-4">
                <Skeleton className="ml-5 h-4 w-2/3" />
                <Skeleton className="ml-5 h-3 w-1/4" />
              </Card>
            ))}
          </div>
        ) : list.isError ? (
          <div className="flex flex-col items-center gap-3 py-16 text-sm text-muted-foreground">
            <p>{errorText(list.error, "待办列表加载失败")}</p>
            <Button variant="outline" size="sm" onClick={() => void list.refetch()}>
              重试
            </Button>
          </div>
        ) : items.length === 0 ? (
          <div className="rounded-xl border border-dashed py-16 text-center text-sm text-muted-foreground">
            {emptyText}
          </div>
        ) : (
          <ul className="space-y-2">
            {items.map((todo) => (
              <TodoRow key={`${todo.type}-${todo.refId}-${todo.createdAt}`} todo={todo} />
            ))}
          </ul>
        )}
      </div>
    </PortalContent>
  );
}

function TodoRow({ todo }: { todo: TodoItem }) {
  const meta = todoTypeMeta(todo.type);
  const href = todoHref(todo);
  const time = formatRelativeTime(todo.createdAt);

  const body = (
    <Card className="flex-row items-center gap-3 py-4 pr-4 pl-5">
      <Badge variant="secondary" className={cn(badgeToneClass(meta.tone))}>
        {meta.label}
      </Badge>
      <span className="min-w-0 flex-1 truncate text-sm">{todo.title}</span>
      {time && <span className="shrink-0 text-xs text-muted-foreground">{time}</span>}
    </Card>
  );

  return (
    <li>
      {href ? (
        <Link href={href} className="block transition-colors hover:bg-accent/50">
          {body}
        </Link>
      ) : (
        body
      )}
    </li>
  );
}
