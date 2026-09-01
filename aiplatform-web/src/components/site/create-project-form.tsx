"use client";

import { ArrowRight } from "lucide-react";
import { type FormEvent, useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { useCreateProject } from "@/hooks/use-create-project";
import { errorText } from "@/lib/api/api-error";
import { isSubmitEnter } from "@/lib/chat/enter";
import { buildCreateProjectCommand } from "@/lib/projects/create";

/**
 * 一句话建项目表单（issue #51 收为纯一句话，#19 回车即建）：
 * 渐变光晕卡式大输入 + 方角 primary 发送钮置输入区下方右对齐（无分隔线，输入区
 * 底直角 + 按钮行底圆角无缝接合成一体、按钮右下留距如浮起），仅此一个输入框
 * ——项目名由后端 LLM 取、模板单链默认、引擎后台统一定，不出现用户不懂的概念。
 * Enter 即建项目直跳项目页（Shift+Enter 换行，输入法组词不触发）。文本框自动
 * 增高：3 行起步、最高 8 行后内部滚动。提交 POST /api/projects → 成功后
 * onCreated(projectId) 交给调用侧导航（首页 → /projects/[id] 直进项目页开聊）。
 */

export function CreateProjectForm({
  onCreated,
  className,
}: {
  onCreated: (projectId: string) => void;
  className?: string;
}) {
  const requirementId = useId();

  const [requirement, setRequirement] = useState("");
  const createProject = useCreateProject();

  // 自动增高：3 行起步（min-h-20），随输入长到 8 行（max-h-52）后内部滚动。
  // Textarea 默认 field-sizing-content（新版 shadcn），跨浏览器不稳，这里显式
  // field-sizing-fixed 改用手动 scrollHeight 控制高度。
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, [requirement]);

  const canSubmit = requirement.trim().length > 0 && !createProject.isPending;

  function submit() {
    if (!canSubmit) return;
    const command = buildCreateProjectCommand({ requirement });
    createProject.mutate(command, {
      onSuccess: (result) => {
        const projectId = result.project?.id;
        if (!projectId) {
          toast.error("项目已创建，但未返回项目标识，请到项目列表查看");
          return;
        }
        onCreated(projectId);
      },
      onError: (error) => toast.error(errorText(error, "创建失败，请稍后重试")),
    });
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    submit();
  }

  // 回车即建项目（Shift+Enter 换行；输入法组词的 Enter 是选字不是提交）
  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (!isSubmitEnter(event)) return;
    event.preventDefault();
    submit();
  }

  return (
    <form onSubmit={onSubmit} className={className}>
      <div className="group relative">
        {/* 渐变光晕：focus 加深 */}
        <div className="absolute -inset-0.5 rounded-2xl bg-gradient-to-r from-primary to-accent opacity-30 blur transition duration-500 group-focus-within:opacity-60" />
        <div className="relative rounded-xl border bg-card p-2 transition-all focus-within:ring-2 focus-within:ring-primary">
          <Label htmlFor={requirementId} className="sr-only">
            一句话描述
          </Label>
          <Textarea
            ref={textareaRef}
            id={requirementId}
            value={requirement}
            onChange={(e) => setRequirement(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder="用一句话描述你想要的系统，例如：给宠物医院做个在线预约系统"
            rows={3}
            className="field-sizing-fixed min-h-20 max-h-52 resize-none overflow-y-auto rounded-b-none border-0 bg-transparent px-2 py-2 text-base shadow-none ring-0 focus-visible:ring-0"
          />

          <div className="flex items-center justify-end rounded-b-lg pt-1 pr-2 pb-2 dark:bg-input/30">
            <Button
              type="submit"
              size="icon"
              className="size-9 rounded-lg"
              disabled={!canSubmit}
              aria-label="开始"
            >
              {createProject.isPending ? <Spinner /> : <ArrowRight className="size-4" />}
            </Button>
          </div>
        </div>
      </div>
    </form>
  );
}
