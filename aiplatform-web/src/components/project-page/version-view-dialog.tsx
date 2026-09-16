"use client";

import { ExternalLink } from "lucide-react";
import { useState } from "react";

import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";

import {
  BAR_BUTTON_CLASS,
  DeviceFrame,
  DeviceToggle,
  LIGHT_LOCK_STAGE_CLASS,
  type PreviewDevice,
} from "./device-frame";

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
 * 套——桌面/手机宽度切换（样式切换不重挂 iframe，快照不因换设备丢状态；#201 起
 * 与系统 tab 同一件，外壳/切换/舞台口径归 ./device-frame 单源——浅色锁定文字色
 * 随单源归正，以系统 tab 为正本）+ 新窗口打开（window.open 快照真实地址；弹窗仍
 * 是快照宿主，关窗即销毁，新标签页随之失效——不引入保活）；③标题带轮次语境——
 * #142 换源为轮次序数 + 收尾摘要（双源恒在场、多弹窗可分辨；原「本轮用户发言」
 * 尽力而为管线退役，见 viewThenTitle 注释）。</p>
 */
/** 标题里收尾摘要的字数上限（最简一行——超长截断）。 */
const TITLE_SUMMARY_MAX = 20;

/**
 * 标题式样（#142 换源）：「第 N 轮结束时的系统——<收尾摘要截断>」。语境双源
 * 恒在场——序数 = 对话流收尾卡序数（装配层数出）、摘要 = closing.summary（#88
 * 服务端权威事实，收尾卡正文同源）。#140 的「本轮用户发言摘要」尽力而为管线
 * （roundPromptOf，无 runId 锚/被软上限裁即缺场，走查实测全回落不可分辨）已
 * 随本片退役；序数缺场（防御）回落裸式样。
 */
export function viewThenTitle(round?: number, summary?: string): string {
  const base = round && round > 0 ? `第 ${round} 轮结束时的系统` : "那轮结束时的系统";
  const flat = summary?.trim().replace(/\s+/g, " ");
  if (!flat) return base;
  const brief = flat.length > TITLE_SUMMARY_MAX ? `${flat.slice(0, TITLE_SUMMARY_MAX)}…` : flat;
  return `${base}——${brief}`;
}

export function VersionViewDialog({
  open,
  onOpenChange,
  pending,
  error,
  previewUrl,
  round,
  summary,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  pending: boolean;
  error: boolean;
  previewUrl?: string;
  /** 轮次序数（标题语境源一：对话流收尾卡序数，装配层数出；防御可缺场）。 */
  round?: number;
  /** 收尾摘要（标题语境源二：closing.summary 服务端权威事实，恒在场）。 */
  summary?: string;
}) {
  const [device, setDevice] = useState<PreviewDevice>("desktop");

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* sm:max-w-[1600px] 同档压掉基座 sm:max-w-sm（见组件注释①）；w/h 吃满屏 */}
      <DialogContent className="flex h-[90vh] w-[94vw] max-w-[1600px] flex-col gap-0 overflow-hidden p-0 sm:max-w-[1600px]">
        <DialogHeader className="flex-row items-center gap-2 border-b px-4 py-3 pr-12">
          <DialogTitle className="min-w-0 flex-1 truncate">{viewThenTitle(round, summary)}</DialogTitle>
          <DeviceToggle device={device} onDeviceChange={setDevice} />
          {/* 与系统 tab 新窗口钮不同身、不进共享件（#201 钉住）：系统 tab 三入口带
              #182 触碰先行（refetch 预览查询经后端触碰拦截器唤醒休眠沙箱）；本弹窗
              不补——快照会话闲置计时锚死起服时刻（startedAt），项目触碰不保活快照，
              触碰先行对快照零效果。 */}
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

        {/* 舞台浅色锁定（#80 口径，#201 起随 ./device-frame 单源归正——抄本曾抄漏
            文字色）：快照里的系统是用户产物，不随平台翻转；在途/失败提示同落锁内 */}
        {pending ? (
          <div className={LIGHT_LOCK_STAGE_CLASS}>
            <div className="flex h-full items-center justify-center gap-2 text-sm text-muted-foreground">
              <Spinner />
              正在准备当时系统…
            </div>
          </div>
        ) : error ? (
          <div className={LIGHT_LOCK_STAGE_CLASS}>
            <div className="flex h-full items-center justify-center text-sm text-destructive">
              快照起服失败，请关闭后重试
            </div>
          </div>
        ) : previewUrl ? (
          <DeviceFrame device={device}>
            <iframe src={previewUrl} title="当时系统快照" className="h-full w-full border-0 bg-white" />
          </DeviceFrame>
        ) : (
          // 三态俱缺（防御）：空舞台占位，同原恒在场舞台口径
          <div className={LIGHT_LOCK_STAGE_CLASS} />
        )}
      </DialogContent>
    </Dialog>
  );
}
