"use client";

import { useState, type ReactNode } from "react";
import { toast } from "sonner";
import { Send } from "lucide-react";

import type { ProjectAgentTaskCommand } from "@/lib/agent/task-command";
import { errorText } from "@/lib/api/api-error";
import { useDispatchTask } from "@/hooks/use-dispatch-task";

import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";

/**
 * 底部输入条（spec 0001 §4.1 / spec 0002 §4，issue #40/#43）：开发平台「下任务」与
 * 需求端「补充需求」共用同一输入形态——draft + trim 守卫 + dispatch（成功清空 /
 * 失败保留可重试）+ Enter/IME 提交。差异（角色卡、placeholder、出错文案、载荷
 * 构造）由场景层经 `roleSelect` / `buildCommand` 注入。
 */
export function PromptComposer({
  projectId,
  placeholder,
  errorLabel,
  buildCommand,
  roleSelect,
}: {
  projectId: string;
  placeholder: string;
  errorLabel: string;
  /** 载荷构造（角色卡缺省语义收在场景层，不落进共用输入条）。 */
  buildCommand: (prompt: string) => ProjectAgentTaskCommand;
  /** 发送钮左侧的可选角色卡（开发平台）；需求端不传。 */
  roleSelect?: ReactNode;
}) {
  const [draft, setDraft] = useState("");
  const dispatch = useDispatchTask(projectId);

  function submit() {
    const prompt = draft.trim();
    if (!prompt) return;
    dispatch.mutate(buildCommand(prompt), {
      onSuccess: () => setDraft(""),
      onError: (err) => toast.error(errorText(err, errorLabel)),
    });
  }

  return (
    <div className="flex items-end gap-2">
      <Textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            submit();
          }
        }}
        placeholder={placeholder}
        className="min-h-9 flex-1 resize-none"
      />
      {roleSelect}
      <Button size="icon" onClick={submit} disabled={!draft.trim() || dispatch.isPending}>
        <Send />
      </Button>
    </div>
  );
}
