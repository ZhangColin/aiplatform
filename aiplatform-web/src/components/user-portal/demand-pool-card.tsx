"use client";

import { Lightbulb, Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { useAddDemandEntry, useDemandPool } from "@/hooks/use-demand-pool";
import { errorText } from "@/lib/api/api-error";
import { DEMAND_CONTENT_MAX, validateDemandContent } from "@/lib/projects/demand-pool";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * 下一期想法池（spec 0002 §4，issue #20）：随时记的收件清单。提交只传
 * `content`（kind/source 缺省 = 用户需求，不暴露选择器）；空内容前端校验
 * 拦截（就地展示，不进 toast）；列表新→旧；交付后保留。
 */
export function DemandPoolCard({ projectId }: { projectId: string }) {
  const pool = useDemandPool(projectId);
  const add = useAddDemandEntry(projectId);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const content = text.trim();
    const invalid = validateDemandContent(content);
    if (invalid) {
      setError(invalid);
      return;
    }
    setError(null);
    add.mutate(content, {
      onSuccess: () => setText(""),
      onError: (err) => toast.error(errorText(err, "记录失败，请稍后重试")),
    });
  };

  const entries = pool.data ?? [];

  return (
    <Card className="gap-3 py-4">
      <CardHeader>
        <CardTitle className="flex items-center gap-1.5 text-sm">
          <Lightbulb className="size-4 text-amber-500" /> 下一期想法池
        </CardTitle>
        <CardDescription>随时记，不打扰当前项目</CardDescription>
      </CardHeader>
      <CardContent className="space-y-2">
        {pool.isPending ? (
          <Skeleton className="h-16 w-full" />
        ) : entries.length === 0 ? (
          <p className="text-xs text-muted-foreground">还没有记录</p>
        ) : (
          <ul className="space-y-1.5">
            {entries.map((entry) => (
              <li key={entry.id} className="rounded-md bg-muted/60 px-2 py-1.5">
                <p className="text-sm leading-relaxed">{entry.content}</p>
                <p className="mt-0.5 text-[10px] text-muted-foreground">
                  {formatRelativeTime(entry.createdAt)}
                </p>
              </li>
            ))}
          </ul>
        )}
        <form className="flex gap-2 pt-1" onSubmit={submit}>
          <div className="min-w-0 flex-1">
            <Input
              value={text}
              onChange={(e) => {
                setText(e.target.value);
                if (error) setError(null);
              }}
              maxLength={DEMAND_CONTENT_MAX}
              placeholder="记一个新想法…"
              aria-label="新想法"
              className="h-8 text-sm"
            />
            {error && <p className="mt-1 text-xs text-destructive">{error}</p>}
          </div>
          <Button type="submit" size="sm" variant="ghost" disabled={add.isPending}>
            <Plus className="size-3.5" /> 记下
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
