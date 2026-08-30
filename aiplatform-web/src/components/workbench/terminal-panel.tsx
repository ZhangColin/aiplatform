"use client";

import { useState } from "react";
import { toast } from "sonner";
import { CornerDownLeft, Terminal } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Spinner } from "@/components/ui/spinner";
import { useProjectJourney } from "@/hooks/use-project";
import { useWorkspaceExec } from "@/hooks/use-workspace-exec";
import { errorText } from "@/lib/api/api-error";
import {
  buildExecCommand,
  isCommandFailure,
  type ExecResult,
} from "@/lib/workspace/exec";
import { cn } from "@/lib/utils";

/**
 * 主面板「终端」tab（spec 0001 §5，issue #42）：命令输入 → `POST …/exec` →
 * stdout / stderr / exitCode 归化呈现。workspaceId 取自 project.workspaceId
 * （详情缺省 = 工作区未就绪占位）。**非 0 退出码 = 命令失败非环境故障**，如实
 * 呈现 exitCode（红）；HTTP 4xx/5xx 才是环境故障走 toast。
 */
type ExecEntry = { command: string; result: ExecResult };

export function TerminalPanel({ projectId }: { projectId: string }) {
  const { data: detail } = useProjectJourney(projectId);
  const workspaceId = detail?.workspaceId;
  const exec = useWorkspaceExec(workspaceId);
  const [draft, setDraft] = useState("");
  const [entries, setEntries] = useState<ExecEntry[]>([]);

  function submit() {
    const command = buildExecCommand(draft);
    if (!command) return;
    exec.mutate(command, {
      onSuccess: (result) => {
        setEntries((prev) => [...prev, { command: command.command, result }]);
        setDraft("");
      },
      onError: (err) => toast.error(errorText(err, "命令执行失败")),
    });
  }

  if (!workspaceId) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 p-6 text-center">
        <Terminal className="size-8 text-muted-foreground/40" />
        <p className="text-sm font-medium text-muted-foreground">工作区尚未就绪</p>
        <p className="text-xs text-muted-foreground/70">工作区创建完成后，这里可以执行命令</p>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <ScrollArea className="min-h-0 flex-1">
        {entries.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 p-6 text-center text-sm text-muted-foreground">
            <Terminal className="size-8 text-muted-foreground/40" />
            <p>在工作区里执行命令，输出会显示在这里</p>
          </div>
        ) : (
          <div className="space-y-3 p-3">
            {entries.map((entry, i) => (
              <ExecEntryBlock key={i} command={entry.command} result={entry.result} />
            ))}
          </div>
        )}
      </ScrollArea>

      <div className="flex items-center gap-2 border-t p-2">
        <span className="pl-1 font-mono text-xs text-muted-foreground">$</span>
        <Input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.nativeEvent.isComposing) {
              e.preventDefault();
              submit();
            }
          }}
          placeholder="输入命令…（Enter 执行）"
          disabled={exec.isPending}
          className="h-8 flex-1 font-mono text-xs"
        />
        <Button size="sm" onClick={submit} disabled={!draft.trim() || exec.isPending}>
          {exec.isPending ? <Spinner className="size-3.5" /> : <CornerDownLeft className="size-3.5" />}
          执行
        </Button>
      </div>
    </div>
  );
}

/** 单条执行记录：`$ command` + exitCode（0 成功 / 非 0 失败红）+ stdout / stderr。 */
export function ExecEntryBlock({ command, result }: { command: string; result: ExecResult }) {
  const failed = isCommandFailure(result);
  return (
    <div className="overflow-hidden rounded-md border bg-muted/30 font-mono text-xs">
      <div className="flex items-center gap-2 border-b bg-muted/50 px-2 py-1">
        <span className="text-primary">$</span>
        <span className="min-w-0 flex-1 truncate">{command}</span>
        <span
          className={cn(
            "shrink-0 tabular-nums",
            failed ? "text-destructive" : "text-emerald-600 dark:text-emerald-400",
          )}
        >
          exit {result.exitCode}
        </span>
      </div>
      {result.stdout && (
        <pre className="whitespace-pre-wrap px-2 py-1 text-foreground">{result.stdout}</pre>
      )}
      {result.stderr && (
        <pre className="whitespace-pre-wrap px-2 py-1 text-destructive">{result.stderr}</pre>
      )}
    </div>
  );
}
