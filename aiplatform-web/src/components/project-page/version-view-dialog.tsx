"use client";

import { ExternalLink, Monitor, Smartphone } from "lucide-react";
import { useState } from "react";

import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { cn } from "@/lib/utils";

/**
 * 「查看当时」快照预览弹窗（#92/#93 + #140 体验补齐）：把快照容器预览嵌进
 * iframe 的呈现面——只逛不换的当时系统（当时代码 + 现在数据），关窗即销毁
 * （快照容器随可写层消失）。本组件纯呈现：起/停快照的动作与在途态归收尾卡
 * （ClosingCard）持有，这里只消费 pending / error / previewUrl 三态（起服中 →
 * 失败 → 可浏览）。
 *
 * <p>#140 三补齐：①弹窗吃满屏幕宽高（基座 DialogContent 自带 sm:max-w-sm，384px
 * ≈ 手机宽——传入 max-w-* 与它分属不同 variant、tw-merge 不互斥，sm 档 CSS 序在
 * 后胜出，即「弹窗像手机屏幕」的根因；须同档 sm:max-w-* 压掉）；②浏览器条两件
 * 套——桌面/手机宽度切换（同 SystemPanel #80 口径：样式切换不重挂 iframe，快照
 * 不因换设备丢状态）+ 新窗口打开（window.open 快照真实地址；弹窗仍是快照宿主，
 * 关窗即销毁，新标签页随之失效——不引入保活）；③标题带轮次语境（锚 = 该轮收口
 * 时刻可见，不加 commit hash 等重版本信息）。</p>
 */

/** 设备宽度档：同 SystemPanel 的预览口径（手机档 = 390px 手机框居中，桌面档全幅）。 */
type ViewDevice = "desktop" | "mobile";

/** 浏览器条图标键样式：无地址可开时置灰（同 SystemPanel 的 BAR_BUTTON_CLASS）。 */
const BAR_BUTTON_CLASS =
  "shrink-0 rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:pointer-events-none disabled:opacity-40";

/** 标题里轮次语境摘要的字数上限（最简一行——超长截断）。 */
const TITLE_PROMPT_MAX = 20;

/**
 * 标题式样（#140）：「<该轮用户消息摘要>那轮结束时的系统」。摘要 = 本轮首条
 * 可寻回的用户消息（常态开场意见；开场被软上限裁掉可能落到同轮作答，见
 * command-area 的 roundPromptOf）。折成单行、超长截断；该轮用户消息全缺
 * （无 runId 锚 / 被裁）回落无引语境式样，不猜轮次。
 */
export function viewThenTitle(roundPrompt?: string): string {
  const flat = roundPrompt?.trim().replace(/\s+/g, " ");
  if (!flat) return "那轮结束时的系统";
  const brief = flat.length > TITLE_PROMPT_MAX ? `${flat.slice(0, TITLE_PROMPT_MAX)}…` : flat;
  return `「${brief}」那轮结束时的系统`;
}

export function VersionViewDialog({
  open,
  onOpenChange,
  pending,
  error,
  previewUrl,
  roundPrompt,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  pending: boolean;
  error: boolean;
  previewUrl?: string;
  /** 本轮首条可寻回的用户消息（标题语境源，装配层按收尾卡 runId 查对话流；可缺场）。 */
  roundPrompt?: string;
}) {
  const [device, setDevice] = useState<ViewDevice>("desktop");

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* sm:max-w-[1600px] 同档压掉基座 sm:max-w-sm（见组件注释①）；w/h 吃满屏 */}
      <DialogContent className="flex h-[90vh] w-[94vw] max-w-[1600px] flex-col gap-0 overflow-hidden p-0 sm:max-w-[1600px]">
        <DialogHeader className="flex-row items-center gap-2 border-b px-4 py-3 pr-12">
          <DialogTitle className="min-w-0 flex-1 truncate">{viewThenTitle(roundPrompt)}</DialogTitle>
          <ToggleGroup
            value={[device]}
            onValueChange={(v) => {
              // 本地 ToggleGroup 包装非泛型，值域在此收窄（空选不落地——总有一档）
              if (v.length > 0) setDevice(v[0] as ViewDevice);
            }}
            className="shrink-0 gap-0"
            aria-label="预览设备宽度"
          >
            <ToggleGroupItem
              value="desktop"
              aria-label="桌面预览"
              className="h-7 px-2 data-pressed:bg-background data-pressed:shadow-sm"
            >
              <Monitor className="size-3.5" />
            </ToggleGroupItem>
            <ToggleGroupItem
              value="mobile"
              aria-label="手机预览"
              className="h-7 px-2 data-pressed:bg-background data-pressed:shadow-sm"
            >
              <Smartphone className="size-3.5" />
            </ToggleGroupItem>
          </ToggleGroup>
          <button
            type="button"
            disabled={!previewUrl}
            onClick={() => previewUrl && window.open(previewUrl, "_blank", "noopener")}
            title="在新窗口打开当时系统"
            aria-label="在新窗口打开当时系统"
            className={BAR_BUTTON_CLASS}
          >
            <ExternalLink className="size-3.5" />
          </button>
        </DialogHeader>

        {/* 舞台浅色锁定（同 SystemPanel 口径）：快照里的系统是用户产物，不随平台翻转 */}
        <div className="light-lock min-h-0 flex-1 bg-background">
          {pending ? (
            <div className="flex h-full items-center justify-center gap-2 text-sm text-muted-foreground">
              <Spinner />
              正在准备当时系统…
            </div>
          ) : error ? (
            <div className="flex h-full items-center justify-center text-sm text-destructive">
              快照起服失败，请关闭后重试
            </div>
          ) : previewUrl ? (
            // 双层壳同构 SystemPanel（仅样式差异）：设备切换不重挂 iframe
            <div
              className={cn(
                "h-full overflow-hidden",
                device === "mobile" && "flex justify-center bg-muted p-4",
              )}
            >
              <div
                className={cn(
                  "h-full",
                  device === "mobile"
                    ? "w-[390px] shrink-0 overflow-hidden rounded-2xl border shadow-sm"
                    : "w-full",
                )}
              >
                <iframe src={previewUrl} title="当时系统快照" className="h-full w-full border-0 bg-white" />
              </div>
            </div>
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}
