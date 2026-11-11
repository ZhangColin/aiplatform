"use client";

import { Check } from "lucide-react";

import { Button } from "@/components/ui/button";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import type { ChatMessage } from "@/lib/store/chat";
import { composeAnswer } from "@/lib/chat/qa";
import { cn } from "@/lib/utils";

/**
 * 问答卡（issue #19 需求环①，CC 式）：主智能体每轮一问的呈现与作答面——单选 chip
 * 点即答、多选勾选后一次提交（提交按钮无勾选禁用）、纯开放题（无选项）靠输入条
 * 自由输入作答。已答（或被新问题取代）转只读终态，勾选状态由父层（对话区）持有
 * ——输入条 Enter 作答要与已勾选合并。
 *
 * <p>推荐答案（#459）：带 recommended 的问题，推荐项标「推荐」chip；单选也从
 * 「点即答」升为「勾选＋提交」（预选缺省由父层初始勾选承载）——用户动作降为
 * 检查并改，可一键确认、改选或自由输入。无推荐的问题行为与现状完全一致。
 */
export type QuestionCardProps = {
  question: Extract<ChatMessage, { kind: "question" }>;
  /** 待答中的当前问题才可交互（尾问唯一）。 */
  interactive: boolean;
  /** 勾选（父层持有，Enter 作答合并用；新问题时初始化为 [recommended] 或 []）。 */
  selection: readonly string[];
  onSelectionChange: (next: string[]) => void;
  onAnswer: (text: string) => void;
};

/** 推荐标记 chip（#459）：标在推荐选项旁。 */
function RecommendedBadge() {
  return (
    <span
      data-slot="recommended-badge"
      className="rounded-full bg-primary/15 px-1.5 py-0.5 text-[10px] font-medium text-primary"
    >
      推荐
    </span>
  );
}

export function QuestionCard({
  question,
  interactive,
  selection,
  onSelectionChange,
  onAnswer,
}: QuestionCardProps) {
  // 推荐答案（#459）：带推荐的单选不走「点即答」——勾选＋提交（预选＝父层初始勾选），
  // 用户可改选后提交、或自由输入作答；多选恒勾选提交
  const needsSubmit = question.multiple || question.recommended !== undefined;
  return (
    <div
      data-slot="question-card"
      className={cn(
        "max-w-full rounded-xl border p-3",
        interactive ? "border-primary/40 bg-primary/[0.04]" : "border-border bg-muted/30",
      )}
    >
      <div className="mb-2 flex items-center gap-2">
        <span
          className={cn(
            "rounded-full px-2 py-0.5 text-xs font-medium",
            interactive ? "bg-primary/15 text-primary" : "bg-muted text-muted-foreground",
          )}
        >
          {question.header}
        </span>
        {question.multiple && interactive ? (
          <span className="text-xs text-muted-foreground">可多选</span>
        ) : null}
        {!interactive ? (
          <span className="ml-auto text-xs text-muted-foreground">已回答</span>
        ) : null}
      </div>
      <p className="text-sm leading-relaxed">{question.question}</p>

      {question.options.length > 0 ? (
        <div className="mt-3">
          {needsSubmit ? (
            // 勾选＋提交：多选恒此形态；带推荐的单选也走此形态（预选由父层初始
            // 勾选承载）——不再点即答，用户可改选后提交，或自由输入（输入条
            // Enter 与勾选合并）。multiple 缺省 false：同组只许一项 pressed
            <ToggleGroup
              variant="outline"
              multiple={question.multiple}
              disabled={!interactive}
              value={selection}
              onValueChange={(value) => onSelectionChange(value)}
              className="flex-wrap"
            >
              {question.options.map((label) => (
                <ToggleGroupItem
                  key={label}
                  value={label}
                  aria-label={label}
                  className="whitespace-normal data-pressed:border-primary data-pressed:bg-primary/10 data-pressed:text-primary"
                >
                  <Check className="size-3.5 opacity-30 group-data-pressed/toggle:opacity-100" />
                  {label}
                  {label === question.recommended ? <RecommendedBadge /> : null}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          ) : (
            <div className="flex flex-wrap gap-2">
              {question.options.map((label) =>
                interactive ? (
                  <Button
                    key={label}
                    size="sm"
                    variant="outline"
                    className="rounded-full whitespace-normal text-left"
                    onClick={() => onAnswer(label)}
                  >
                    {label}
                  </Button>
                ) : (
                  <span
                    key={label}
                    className="rounded-full border px-3 py-1.5 text-sm opacity-70"
                  >
                    {label}
                  </span>
                ),
              )}
            </div>
          )}
        </div>
      ) : null}

      {interactive && needsSubmit && question.options.length > 0 ? (
        <div className="mt-3 flex justify-end">
          <Button
            size="sm"
            disabled={selection.length === 0}
            onClick={() => onAnswer(composeAnswer(selection, ""))}
          >
            提交
          </Button>
        </div>
      ) : null}
    </div>
  );
}
