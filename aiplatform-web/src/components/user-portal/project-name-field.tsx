"use client";

import { Pencil } from "lucide-react";
import { type FormEvent, useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { useProjectJourney, useRenameProject } from "@/hooks/use-project";
import { errorText } from "@/lib/api/api-error";
import { buildRenameProjectCommand, validateProjectName } from "@/lib/projects/rename";

/**
 * 项目名 inline 改名（issue #55，spec 0002 §4）：右栏「项目信息」区的项目名
 * 行——展示态铅笔入口，编辑态输入 + 就地校验（空名 / 超长，口径与建项目契约
 * 一致）+ 保存 / 取消（Esc 同取消，Enter 同保存）。提交 POST …/rename → 成功
 * 由 useRenameProject 播种详情缓存 + 失效项目域（列表 / 顶栏随刷新显示新名）；
 * 失败 toast 直出后端 message、停留编辑态可重试。
 */
export function ProjectNameField({ projectId }: { projectId: string }) {
  const { data: detail } = useProjectJourney(projectId);
  const renameProject = useRenameProject(projectId);

  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // 进入编辑即聚焦全选：改的是整名，全选后直接覆盖输入
  useEffect(() => {
    if (!editing) return;
    inputRef.current?.focus();
    inputRef.current?.select();
  }, [editing]);

  function startEdit() {
    setDraft(detail?.name ?? "");
    setError(null);
    setEditing(true);
  }

  function cancelEdit() {
    setEditing(false);
    setError(null);
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (renameProject.isPending) return;
    const validation = validateProjectName(draft);
    if (validation) {
      setError(validation);
      return;
    }
    renameProject.mutate(buildRenameProjectCommand({ name: draft }), {
      onSuccess: () => {
        setEditing(false);
        setError(null);
      },
      onError: (error) => toast.error(errorText(error, "改名失败，请稍后重试")),
    });
  }

  if (editing) {
    return (
      <form onSubmit={onSubmit} className="space-y-1.5">
        <span className="block text-muted-foreground">项目名称</span>
        <Input
          ref={inputRef}
          aria-label="项目名称"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Escape") cancelEdit();
          }}
          aria-invalid={error ? true : undefined}
        />
        {error ? (
          <p role="alert" className="text-xs text-destructive">
            {error}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" size="sm" onClick={cancelEdit}>
            取消
          </Button>
          <Button type="submit" size="sm" disabled={renameProject.isPending}>
            {renameProject.isPending ? <Spinner className="size-3.5" /> : null}
            保存
          </Button>
        </div>
      </form>
    );
  }

  return (
    <div className="flex items-center justify-between gap-2 text-muted-foreground">
      <span className="shrink-0">项目名称</span>
      <span className="flex min-w-0 items-center gap-0.5 text-foreground">
        <span className="truncate">{detail?.name || "—"}</span>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="size-6 shrink-0"
          aria-label="编辑项目名"
          onClick={startEdit}
        >
          <Pencil className="size-3" />
        </Button>
      </span>
    </div>
  );
}
