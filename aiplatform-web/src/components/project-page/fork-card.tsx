"use client";

import { MessageCircle, MessagesSquare, PlayCircle, Rocket } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useResumeGeneration } from "@/hooks/use-resume-generation";

/**
 * 岔路口三选卡（#542 访谈②，ADR-0048/0049——自动派发两入口退役，首建点火归
 * 用户）：试用态访谈自然收口（判定明确含提前明确——非快道）后的选择卡，持久
 * 可回（关页回来卡仍在，呈现条件＝GET /build-gate forkCardPending 服务端单源）。
 * 三选：「继续讨论（由我的账户付费）」／「继续讨论（由本项目付费）」＝付费轨
 * 引导入口（M01 登录＋按池判定＋到账翻转——#544 引导流落位前的占位态）；
 * 「立即生成」＝触发首个构建派发（POST /generate，平台承担本次——快道
 * 〔收敛令/零必答短路〕不经本卡直派）。
 */
export function ForkCard({
  projectId,
  onSeePrd,
}: {
  projectId: string;
  /** 「查看 PRD」跳转口（归装配层；缺席不渲染该按钮）。 */
  onSeePrd?: () => void;
}) {
  const generate = useResumeGeneration(projectId);

  return (
    <div
      data-slot="fork-card"
      className="flex w-full max-w-lg flex-col gap-3 rounded-2xl border border-primary/25 bg-primary/[0.04] p-4 shadow-xs"
    >
      <div className="flex items-start gap-2">
        <Rocket className="mt-0.5 size-4 shrink-0 text-primary" />
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="text-sm font-semibold">
            需求初稿已就绪，接下来想怎么继续？
          </span>
          <span className="text-xs leading-relaxed text-muted-foreground">
            您的 PRD 已整理好，可以先回看再决定；继续提问讨论会转为付费，立即生成本次由平台承担。
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-1.5" data-fork-choices>
        <ChoiceRow
          icon={<MessagesSquare className="size-3.5" />}
          title="继续讨论（由我的账户付费）"
          hint="还想再聊聊？继续提问讨论，按用量从我的账户余额扣减"
          disabled
        />
        <ChoiceRow
          icon={<MessageCircle className="size-3.5" />}
          title="继续讨论（由本项目付费）"
          hint="继续提问讨论，按用量从本项目余额扣减"
          disabled
        />
        <ChoiceRow
          icon={<PlayCircle className="size-3.5" />}
          title="立即生成"
          hint="按当前 PRD 立即生成系统先体验，想修改再付费继续"
          action={
            <Button
              size="sm"
              data-fork-generate
              disabled={generate.isPending}
              onClick={() => generate.mutate(undefined)}
            >
              {generate.isPending ? <Spinner className="size-3.5" /> : null}
              立即生成
            </Button>
          }
        />
      </div>

      {onSeePrd ? (
        <Button
          size="sm"
          variant="outline"
          data-fork-see-prd
          className="self-start"
          onClick={onSeePrd}
        >
          查看 PRD
        </Button>
      ) : null}
    </div>
  );
}

/** 一条选择行（付费两选＝#544 引导流落位前的占位禁用态；立即生成＝可点火动作行）。 */
function ChoiceRow({
  icon,
  title,
  hint,
  disabled,
  action,
}: {
  icon: React.ReactNode;
  title: string;
  hint: string;
  disabled?: boolean;
  action?: React.ReactNode;
}) {
  return (
    <div
      data-fork-choice={title}
      aria-disabled={disabled ? "true" : undefined}
      className={
        disabled
          ? "flex items-center gap-2 rounded-lg border border-border/60 bg-background/50 px-3 py-2 opacity-60"
          : "flex items-center gap-2 rounded-lg border border-primary/30 bg-background px-3 py-2"
      }
    >
      <span className="shrink-0 text-muted-foreground">{icon}</span>
      <div className="flex min-w-0 flex-col">
        <span className="text-xs font-medium">{title}</span>
        <span className="text-xs text-muted-foreground">
          {hint}
          {disabled ? "（付费续用引导即将上线）" : ""}
        </span>
      </div>
      <div className="ml-auto shrink-0">{action}</div>
    </div>
  );
}
