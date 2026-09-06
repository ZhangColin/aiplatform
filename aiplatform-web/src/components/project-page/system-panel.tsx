"use client";

import {
  ExternalLink,
  LoaderCircle,
  Monitor,
  RotateCw,
  Smartphone,
  TriangleAlert,
} from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";

import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  previewEpochOf,
  useGenerationStore,
  type CoderRunStatus,
} from "@/lib/store/generation";
import { useWorkMessageStore, workPartsOf } from "@/lib/store/work-message";
import { cn } from "@/lib/utils";
import {
  encodeAnnotate,
  parseAnchorEvent,
  parseExitEvent,
  type AnnotationKind,
} from "@/lib/preview/annotation";
import { TROUBLE_NOTICE, previewActive, systemPanelPhase } from "@/lib/preview/state";
import { useAnnotationStore } from "@/lib/store/annotation";
import { useProjectPreview } from "@/hooks/use-project-preview";

import { PreviewToolbar } from "./preview-toolbar";
import { RestartFixButton } from "./restart-fix";
import { StartSystemButton } from "./start-generation";

/** 预览设备宽度档（#80 浏览器条）：手机档 = 390px 手机框居中，桌面档全幅。 */
type PreviewDevice = "desktop" | "mobile";

/** 浏览器条图标键（刷新 / 新窗口）共用样式：无页面可点时置灰。 */
const BAR_BUTTON_CLASS =
  "shrink-0 rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:pointer-events-none disabled:opacity-40";

/**
 * 系统范式主区域（#22 片2-1 + #26 迭代环① + #45 渐进预览第一片 + #48 修正
 * 超限终态恢复出口；#79 起为成果区「系统」tab，#80 浏览器条定稿）：恒为预览的
 * 容器。门禁解除——run 开始（含发起成功的乐观登记）即取预览地址并挂机制，
 * 不等 run-finish 纪元；后端探活通过才返回 URL，有 URL 即上真页面（空白页可
 * 接受）。空态两档（推导归 lib/preview/state 纯函数，本组件只呈现）：无应用 =
 * 占位随工作消息部件推进的步骤提示（解说自述优先、动作对象
 * 兜底，无信号「正在初始化」）；
 * 有应用且 run 中 = 保留页面 +「更新中」轻提示（生长期与修正期同一套）。跨会话
 * 与重试不闪断：有 URL 就不退占位；run 收口纪元驱动 iframe 重挂（url+epoch 为
 * key，手动刷新的本地节拍并入同 key）；超限终态给人工兜底入口——从未生成
 * 「重新发起」、修正轮「重新修改」，正常态全无。
 *
 * <p>浏览器条（#80）：地址胶囊（真地址，诚实口径）+ 桌面/手机宽度切换（样式
 * 切换不重挂 iframe——用户的系统不因换设备丢状态）+ 手动刷新（强制重挂）+
 * 新窗口打开（/preview/:id 独立页）。舞台浅色锁定：预览里的系统是用户产物，
 * 永不随平台 Light/Dark 翻转（.light-lock 钉浅色档）。</p>
 */
export function SystemPanel({
  projectId,
  generatedAt,
  coderStatus,
  onGenerated,
}: {
  projectId: string;
  /** 首次生成时点（REST 事实；null = 未生成过）。 */
  generatedAt?: string | null;
  /** 本会话编码 run 状态（undefined = 未见）。 */
  coderStatus?: CoderRunStatus;
  /** 发起成功回调（切系统模式呈现等待态），归装配层。 */
  onGenerated: () => void;
}) {
  const epoch = useGenerationStore((s) => previewEpochOf(s, projectId));
  const parts = useWorkMessageStore((s) => workPartsOf(s, projectId));
  const [device, setDevice] = useState<PreviewDevice>("desktop");
  /** 手动刷新节拍：并入预览纪元的重挂 key（自动刷新外的唯一手动机制）。 */
  const [refreshTick, setRefreshTick] = useState(0);
  // 门禁解除（#45）：run 开始或已有生成事实即取预览地址——不等收口纪元
  const active = previewActive(coderStatus, generatedAt);
  const preview = useProjectPreview(projectId, active);
  const url = preview.data?.url;
  // 圈注标注态（#97）：非常驻——activeTool 非空即标注态，对 iframe 发 postMessage
  // 进出；预览跨源（容器暴露端口），回传锚校验 origin = 预览源（防伪锚）
  const [activeTool, setActiveTool] = useState<AnnotationKind | null>(null);
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const addAnnotation = useAnnotationStore((s) => s.add);
  const previewOrigin = url ? previewOriginOf(url) : undefined;

  // 标注态进出：activeTool 变化 → 对 iframe 发 postMessage（目标 = 预览源，非通配）
  useEffect(() => {
    const win = iframeRef.current?.contentWindow;
    if (!win || !previewOrigin) return;
    win.postMessage(
      encodeAnnotate(activeTool ? "enter" : "exit", activeTool ?? undefined),
      previewOrigin,
    );
  }, [activeTool, previewOrigin]);

  // 收预览回传的圈注锚：校验 origin（防伪锚）→ 解析成功入发送框附件区；
  // 注入脚本 Esc 退出 → 收起标注态
  useEffect(() => {
    function onMessage(event: MessageEvent) {
      if (!previewOrigin || event.origin !== previewOrigin) return;
      const anchor = parseAnchorEvent(event.data);
      if (anchor) {
        addAnnotation(projectId, anchor);
        return;
      }
      if (parseExitEvent(event.data)) setActiveTool(null);
    }
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [projectId, previewOrigin, addAnnotation]);

  const phase = systemPanelPhase({
    coderStatus,
    generatedAt,
    url,
    error: preview.error,
    parts,
  });
  const pageLive = phase.kind === "page" && !!url;
  // 超限终态的人工兜底入口（页面轻提示与占位终态两处共用）：从未生成「重新发起」、
  // 修正轮「重新修改」（#48，重派终态那场的交接物）
  const restart = (
    <StartSystemButton projectId={projectId} onGenerated={onGenerated} label="重新发起" />
  );
  const refix = <RestartFixButton projectId={projectId} />;
  /** 工具点选：同键再点即退出（非常驻），异键切换。 */
  const toggleTool = (tool: AnnotationKind) =>
    setActiveTool((cur) => (cur === tool ? null : tool));

  return (
    // 平铺无圆角（#79 成果区口径）：与对话列同墙同地，不再套浮起卡片
    <div className="flex h-full min-h-0 flex-col">
      {/* 浏览器条（#80）：刷新 + 地址胶囊 + 设备切换 + 新窗口 */}
      <div className="flex h-9 shrink-0 items-center gap-2 border-b bg-muted/60 px-3">
        <button
          type="button"
          disabled={!pageLive}
          onClick={() => setRefreshTick((t) => t + 1)}
          title="刷新预览"
          aria-label="刷新预览"
          className={BAR_BUTTON_CLASS}
        >
          <RotateCw className="size-3.5" />
        </button>
        <span className="mx-auto flex w-full max-w-md min-w-0 items-center justify-center truncate rounded-full border bg-background px-3 py-1 text-xs text-muted-foreground">
          {active ? (url ?? "正在接通系统…") : "你的系统"}
        </span>
        <ToggleGroup
          value={[device]}
          onValueChange={(v) => {
            // 本地 ToggleGroup 包装非泛型，值域在此收窄（空选不落地——总有一档）
            if (v.length > 0) setDevice(v[0] as PreviewDevice);
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
          disabled={!pageLive}
          onClick={() => window.open(`/preview/${projectId}`, "_blank", "noopener")}
          title="在新窗口打开预览"
          aria-label="在新窗口打开预览"
          className={BAR_BUTTON_CLASS}
        >
          <ExternalLink className="size-3.5" />
        </button>
      </div>

      {/* 内容区 */}
      <div className="relative min-h-0 flex-1">
        {phase.kind === "page" && phase.notice ? (
          // 进行中轻提示（一套话术面：进行中「更新中」/ 重试 / 失败）——预览全程
          // 保持可见，中间态不闪断
          <div
            className={cn(
              "absolute inset-x-0 top-0 z-10 flex items-center justify-center gap-2 border-b px-3 py-1.5 text-xs backdrop-blur",
              phase.notice.failed
                ? "bg-destructive/10 text-destructive"
                : "bg-background/95 text-muted-foreground",
            )}
          >
            {phase.notice.failed ? (
              <TriangleAlert className="size-3.5 shrink-0" />
            ) : (
              <LoaderCircle className="size-3.5 shrink-0 animate-spin" />
            )}
            {phase.notice.text}
            {phase.notice.recovery === "restart" ? restart : null}
            {phase.notice.recovery === "refix" ? refix : null}
          </div>
        ) : null}
        {/* 舞台浅色锁定（#80）：预览里的系统是用户产物，html.dark 也翻转不了；
            空态提示也落锁内——视口即浅色，如同真浏览器的空白页（轻提示与工具条
            是浮在视口上的平台件，归锁外随平台走） */}
        <div className="light-lock h-full min-h-0 bg-background text-foreground">
          {pageLive ? (
            // 双层壳同构（仅样式差异）：设备切换不重挂 iframe——宽度是布局变化
            // 不是页面重建，用户的系统不丢状态
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
                {/* key 含预览纪元 + 手动刷新节拍：run 完成信号或手动刷新驱动重挂
                    （同 URL 也强制重建 iframe），设备切换不动 key */}
                <iframe
                  key={previewFrameKey(url, epoch + refreshTick)}
                  ref={iframeRef}
                  src={url}
                  title="系统预览"
                  className="h-full w-full border-0 bg-white"
                  onLoad={() => {
                    // iframe 重挂后注入脚本状态清零——标注态仍激活则重发 enter
                    const win = iframeRef.current?.contentWindow;
                    if (win && previewOrigin && activeTool) {
                      win.postMessage(encodeAnnotate("enter", activeTool), previewOrigin);
                    }
                  }}
                />
              </div>
            </div>
          ) : phase.kind === "hint" ? (
            <PanelHint>
              <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
              <p className="max-w-full truncate">{phase.text}</p>
            </PanelHint>
          ) : phase.kind === "failed" ? (
            <PanelHint>
              <TriangleAlert className="size-5 text-destructive" />
              <p>{phase.text}</p>
              {phase.recovery === "restart" ? restart : null}
              {phase.recovery === "refix" ? refix : null}
            </PanelHint>
          ) : phase.kind === "connecting" ? (
            <PanelHint>
              {phase.trouble ? (
                <span className="text-destructive">{TROUBLE_NOTICE}</span>
              ) : (
                <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
              )}
            </PanelHint>
          ) : (
            <PanelHint>
              <Monitor className="size-5 text-muted-foreground" />
              <p>系统生成后，这里会出现可以操作的你的系统</p>
            </PanelHint>
          )}
        </div>
        {/* 底部浮动工具条（#97 圈注落地）：有真页面才出场——三能力可点击进标注态，
            改字留灰；标注态可退出 */}
        {pageLive ? (
          <PreviewToolbar
            activeTool={activeTool}
            onToolToggle={toggleTool}
            onExit={() => setActiveTool(null)}
          />
        ) : null}
      </div>
    </div>
  );
}

/** 空白浏览器窗的内容提示（一句提示，无进度剧场）。 */
function PanelHint({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 px-6 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}

/**
 * 预览 iframe 的重挂 key：url + 重载序号——序号汇三路信号（编码 run 收口
 * run-finish；逐修改刷新 preview-updated 通知经 store 节流；手动刷新的本地节拍
 * #80，调用点求和传入），同 URL 也强制重建 iframe（预览刷新的唯一机制）。
 */
export function previewFrameKey(url: string, reload: number): string {
  return `${url}#${reload}`;
}

/** 预览 URL → 源（postMessage 目标 origin + 回传 origin 校验的同一事实）。 */
function previewOriginOf(url: string): string | undefined {
  try {
    return new URL(url).origin;
  } catch {
    return undefined;
  }
}
