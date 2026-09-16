"use client";

import { Monitor, Smartphone } from "lucide-react";
import type { ReactNode } from "react";

import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { cn } from "@/lib/utils";

/**
 * 设备壳两件套单源（#201，正本 = 系统 tab #80 口径）：系统 tab 与「查看当时」
 * 弹窗的设备外壳（DeviceFrame）与设备切换控件（DeviceToggle）原系两份手抄实现，
 * 漂移已实证（弹窗浅色锁定舞台抄漏文字色；#182 触碰先行、#139 Esc 补位的演进
 * 从未回流弹窗侧）——共享核心收敛本模块，两处调用点仅存一份。
 *
 * <p>随附归一：设备档类型（PreviewDevice，弹窗侧 ViewDevice 抄本删除）、浅色锁定
 * 舞台口径常量（LIGHT_LOCK_STAGE_CLASS）、浏览器条钮样式常量（BAR_BUTTON_CLASS）
 * 同出本模块。</p>
 *
 * <p>不进共享件、各归原位：地址框／手动刷新／更新中内联／新窗口打开（两处不同身
 * ——系统 tab 带 #182 触碰先行，弹窗无：快照闲置计时锚起服时刻，项目触碰不保活
 * 快照）、iframe 纪元接线、圈注通道、phase 呈现。</p>
 */

/**
 * 预览设备宽度档：手机档 = 390px 手机框居中，桌面档全幅。
 * （单源——原系统 tab PreviewDevice 与弹窗 ViewDevice 两份抄本归一。）
 */
export type PreviewDevice = "desktop" | "mobile";

/**
 * 浅色锁定舞台口径（#80）：预览里的系统是用户产物，永不随平台 Light/Dark 翻转
 * （.light-lock 钉浅色档，globals.css 单源两用）。含文字色 text-foreground——
 * 弹窗抄本曾抄漏，随本单源归正（以系统 tab 为正本，#201 唯一连带修复）。
 * 消费面：DeviceFrame 根 + 两处调用点的无壳相（空态／在途／失败提示也落锁内——
 * 视口即浅色，如同真浏览器的空白页）。
 */
export const LIGHT_LOCK_STAGE_CLASS =
  "light-lock min-h-0 flex-1 bg-background text-foreground";

/** 浏览器条图标键（刷新 / 新窗口）共用样式：无页面可点时置灰。 */
export const BAR_BUTTON_CLASS =
  "shrink-0 rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:pointer-events-none disabled:opacity-40";

/**
 * DeviceFrame · 设备外壳：受控设备档＋内容槽（含浅色锁定舞台）。两处调用点各把
 * 自己的 iframe 放入槽中（纪元接线／回传校验各归调用点，本件只负责壳与舞台）。
 *
 * <p>双层壳同构（仅样式差异）：设备切换不重挂内容——宽度是布局变化不是页面重建，
 * 用户的系统／快照不因换设备丢状态。</p>
 */
export function DeviceFrame({
  device,
  children,
}: {
  device: PreviewDevice;
  /** 内容槽：调用点的 iframe（重挂 key／onLoad 接线留在调用点）。 */
  children: ReactNode;
}) {
  return (
    <div className={LIGHT_LOCK_STAGE_CLASS}>
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
          {children}
        </div>
      </div>
    </div>
  );
}

/**
 * DeviceToggle · 受控设备切换控件。两处各摆进自己的条形容器（系统 tab 的浏览器
 * 条／弹窗的标题条）——条形容器不进共享件（地址框／刷新为系统 tab 独占，弹窗
 * 标题条带轮次语境）。
 */
export function DeviceToggle({
  device,
  onDeviceChange,
}: {
  device: PreviewDevice;
  onDeviceChange: (device: PreviewDevice) => void;
}) {
  return (
    <ToggleGroup
      value={[device]}
      onValueChange={(v) => {
        // 本地 ToggleGroup 包装非泛型，值域在此收窄（空选不落地——总有一档）
        if (v.length > 0) onDeviceChange(v[0] as PreviewDevice);
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
  );
}
