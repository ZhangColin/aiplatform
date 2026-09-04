"use client";

/**
 * 原型共享：对话区部件（#68 已验证形态）+ 输入条（含附件/物料区呼出）。
 * 各方向壳复用；壳只决定「对话区放在哪」，不碰部件。
 */

import * as React from "react";
import {
  ArrowUp,
  Check,
  ChevronDown,
  Clock3,
  Eye,
  FileCode2,
  FileText,
  Files,
  Image as ImageIcon,
  Link2,
  Mic,
  Monitor,
  Package,
  Palette,
  Paperclip,
  RotateCcw,
  Search,
  Sparkles,
  TriangleAlert,
  Upload,
  X,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Message,
  MessageAvatar,
  MessageContent,
  MessageGroup,
} from "@/components/ui/message";
import {
  MessageScroller,
  MessageScrollerContent,
  MessageScrollerProvider,
  MessageScrollerViewport,
} from "@/components/ui/message-scroller";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";

import type { ActionIcon, Ev, Msg, Part, RunState } from "./run-engine";

const ACTION_ICONS: Record<ActionIcon, React.ReactNode> = {
  file: <FileCode2 className="size-3.5" />,
  doc: <FileText className="size-3.5" />,
  package: <Package className="size-3.5" />,
  palette: <Palette className="size-3.5" />,
  link: <Link2 className="size-3.5" />,
  search: <Search className="size-3.5" />,
};

/** 消息流（含受理空窗指示）；放进壳的任意容器。 */
export function ChatMessages({
  state,
  onAnswer,
  onDispatch,
  onRetry,
}: {
  state: RunState;
  onAnswer: (choice: number) => void;
  onDispatch: (ev: Ev) => void;
  onRetry: () => void;
}) {
  return (
    <MessageScrollerProvider>
      <MessageScroller className="min-h-0 flex-1">
        <MessageScrollerViewport>
          <MessageScrollerContent className="gap-5 p-4 pb-2">
            <MessageGroup>
              {state.messages.map((m, i) => {
                if (m.kind === "user") {
                  return (
                    <Message key={i} align="end">
                      <Bubble variant="tinted" align="end">
                        <BubbleContent className="whitespace-pre-wrap">{m.text}</BubbleContent>
                      </Bubble>
                    </Message>
                  );
                }
                if (m.kind === "agent") {
                  return (
                    <Message key={i}>
                      <MessageAvatar className="size-6 bg-muted">
                        <Sparkles className="size-3.5 text-muted-foreground" />
                      </MessageAvatar>
                      <MessageContent>
                        <Bubble variant="muted" align="start">
                          <BubbleContent className="whitespace-pre-wrap">{m.text}</BubbleContent>
                        </Bubble>
                      </MessageContent>
                    </Message>
                  );
                }
                return (
                  <WorkMessage
                    key={i}
                    msg={m}
                    waitingAnswer={!!state.waitingAnswer}
                    onAnswer={onAnswer}
                    onDispatch={onDispatch}
                    onRetry={onRetry}
                  />
                );
              })}
              {state.accepting ? (
                <Message>
                  <MessageAvatar className="size-6 bg-muted">
                    <Sparkles className="size-3.5 text-muted-foreground" />
                  </MessageAvatar>
                  <MessageContent>
                    <div className="flex w-fit items-center gap-2 rounded-xl border px-3 py-2 text-[13px] text-muted-foreground">
                      <TypingDots /> 正在处理您的消息…
                    </div>
                  </MessageContent>
                </Message>
              ) : null}
            </MessageGroup>
          </MessageScrollerContent>
        </MessageScrollerViewport>
      </MessageScroller>
    </MessageScrollerProvider>
  );
}

function TypingDots() {
  return (
    <span className="inline-flex gap-1">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="size-1.5 animate-pulse rounded-full bg-muted-foreground/60"
          style={{ animationDelay: `${i * 0.2}s` }}
        />
      ))}
    </span>
  );
}

function WorkMessage({
  msg,
  waitingAnswer,
  onAnswer,
  onDispatch,
  onRetry,
}: {
  msg: Extract<Msg, { kind: "work" }>;
  waitingAnswer: boolean;
  onAnswer: (choice: number) => void;
  onDispatch: (ev: Ev) => void;
  onRetry: () => void;
}) {
  const growing = msg.status === "growing";
  return (
    <Message>
      <MessageAvatar className="size-6 bg-muted">
        <Sparkles className="size-3.5 text-muted-foreground" />
      </MessageAvatar>
      <MessageContent>
        <div
          className={cn(
            "rounded-xl border p-3",
            growing && "border-foreground/15 shadow-[0_0_0_3px_var(--color-muted)]",
          )}
        >
          {growing ? <WorkHeader /> : null}
          {msg.parts.map((p, i) => (
            <PartView key={i} part={p} onAnswer={onAnswer} onDispatch={onDispatch} onRetry={onRetry} />
          ))}
          {growing && !waitingAnswer ? (
            <div className="mt-2 flex items-center gap-2 text-[13px] text-muted-foreground">
              <TypingDots /> 正在干活…
            </div>
          ) : null}
        </div>
      </MessageContent>
    </Message>
  );
}

function WorkHeader() {
  const [sec, setSec] = React.useState(0);
  React.useEffect(() => {
    const t = setInterval(() => setSec((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, []);
  return (
    <div className="mb-1 flex items-center gap-2 text-[13px] font-medium">
      <span className="relative flex size-2">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-foreground/50" />
        <span className="relative inline-flex size-2 rounded-full bg-foreground/70" />
      </span>
      正在做
      <span className="ml-auto font-mono tabular-nums text-muted-foreground">
        {Math.floor(sec / 60)}:{String(sec % 60).padStart(2, "0")}
      </span>
    </div>
  );
}

function PartView({
  part,
  onAnswer,
  onDispatch,
  onRetry,
}: {
  part: Part;
  onAnswer: (choice: number) => void;
  onDispatch: (ev: Ev) => void;
  onRetry: () => void;
}) {
  switch (part.type) {
    case "narration":
      return <p className="py-1 text-sm leading-relaxed">{part.text}</p>;
    case "step":
      return (
        <div className="mb-1 mt-3 flex items-center gap-2 text-[13px] font-medium text-muted-foreground">
          {part.title}
          <Separator className="flex-1" />
        </div>
      );
    case "action":
      return <ActionRow part={part} />;
    case "question":
      return <QuestionView part={part} onAnswer={onAnswer} />;
    case "wrap":
      return <WrapCard part={part} onDispatch={onDispatch} />;
    case "wrap-failed":
      return <FailedCard part={part} onRetry={onRetry} />;
  }
}

function ActionRow({ part }: { part: Extract<Part, { type: "action" }> }) {
  return (
    <div className="flex items-center gap-2 rounded-md px-1 py-1.5 text-sm">
      <span className="text-muted-foreground">{ACTION_ICONS[part.icon]}</span>
      <span className={cn("min-w-0 flex-1", part.status === "done" && "text-muted-foreground")}>
        {part.label}
      </span>
      {part.status === "running" ? (
        <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Spinner className="size-3" /> 进行中
        </span>
      ) : part.status === "done" ? (
        <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Check className="size-3.5 text-green-600" strokeWidth={3} />
          {part.duration}
        </span>
      ) : (
        <span className="flex items-center gap-1.5 text-xs text-destructive">
          <X className="size-3.5" strokeWidth={3} /> 没做成
        </span>
      )}
    </div>
  );
}

function QuestionView({
  part,
  onAnswer,
}: {
  part: Extract<Part, { type: "question" }>;
  onAnswer: (choice: number) => void;
}) {
  const interactive = part.picked === null;
  return (
    <div
      className={cn(
        "mt-2 rounded-xl border p-3",
        interactive ? "border-primary/40 bg-primary/[0.04]" : "border-border bg-muted/30",
      )}
    >
      <div className="mb-1.5 flex items-center gap-2">
        <span
          className={cn(
            "rounded-full px-2 py-0.5 text-xs font-medium",
            interactive ? "bg-primary/15 text-primary" : "bg-muted text-muted-foreground",
          )}
        >
          需要您定一下
        </span>
        {!interactive ? <span className="ml-auto text-xs text-muted-foreground">已回答</span> : null}
      </div>
      <p className="text-sm font-medium">{part.question}</p>
      <div className="mt-2.5 flex flex-col gap-1.5">
        {part.options.map((o, i) =>
          interactive ? (
            <Button
              key={i}
              size="sm"
              variant="outline"
              className="h-auto justify-start rounded-lg px-3 py-2 text-left whitespace-normal"
              onClick={() => onAnswer(i)}
            >
              {o}
            </Button>
          ) : (
            <span
              key={i}
              className={cn(
                "rounded-lg border px-3 py-2 text-sm",
                part.picked === i ? "border-primary/50 bg-primary/10 font-medium" : "opacity-50",
              )}
            >
              {o}
            </span>
          ),
        )}
      </div>
    </div>
  );
}

function WrapCard({
  part,
  onDispatch,
}: {
  part: Extract<Part, { type: "wrap" }>;
  onDispatch: (ev: Ev) => void;
}) {
  const [filesOpen, setFilesOpen] = React.useState(false);
  const shown = filesOpen ? part.files : part.files.slice(0, 5);
  return (
    <div className="mt-2.5 rounded-xl border border-green-600/25 bg-green-500/[0.06] p-3">
      <div className="mb-1.5 flex items-center gap-2">
        <Check className="size-4 text-green-600" strokeWidth={3} />
        <span className="text-sm font-semibold">本轮完成</span>
        <Badge variant="outline" className="border-green-600/30 bg-background text-green-700">
          版本 {part.version}
        </Badge>
      </div>
      <p className="text-sm leading-relaxed">{part.summary}</p>
      {part.lines.map((l, i) => (
        <div key={i} className="mt-1.5 flex items-start gap-2 text-sm">
          {l.side === "doc" ? (
            <FileText className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
          ) : (
            <Monitor className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
          )}
          <span>{l.text}</span>
        </div>
      ))}
      {part.files.length > 0 ? (
        <Collapsible
          open={filesOpen}
          onOpenChange={setFilesOpen}
          className="mt-2.5 rounded-lg border bg-background px-2.5 py-2"
        >
          <div className="font-mono text-xs">
            {shown.map(([name, add, del]) => (
              <div key={name} className="flex items-center justify-between gap-2 py-0.5">
                <span className="min-w-0 truncate text-foreground/80">{name}</span>
                <span className="shrink-0 tabular-nums">
                  <span className="text-green-600">+{add}</span>
                  {del > 0 ? <span className="text-destructive"> −{del}</span> : null}
                </span>
              </div>
            ))}
          </div>
          {part.files.length > 5 ? (
            <CollapsibleTrigger className="mt-1 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
              <ChevronDown className={cn("size-3.5 transition-transform", filesOpen && "rotate-180")} />
              {filesOpen ? "收起" : `查看全部 ${part.files.length} 个文件`}
            </CollapsibleTrigger>
          ) : null}
          <CollapsibleContent />
        </Collapsible>
      ) : null}
      <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span className="flex items-center gap-1">
          <Clock3 className="size-3.5" /> 用时 <b className="font-medium text-foreground/80">{part.stats.time}</b>
        </span>
        <span className="flex items-center gap-1">
          <Files className="size-3.5" /> <b className="font-medium text-foreground/80">{part.stats.files}</b> 个文件
        </span>
        <span className="tabular-nums">
          <span className="font-medium text-green-600">+{part.stats.add}</span>
          {part.stats.del > 0 ? <span className="font-medium text-destructive"> −{part.stats.del}</span> : null} 行
        </span>
      </div>
      <div className="mt-3 flex gap-2">
        <Button size="sm" variant="outline" className="h-8 bg-background text-[13px]" onClick={() => onDispatch({ t: "view-version", n: part.version })}>
          <Eye className="size-3.5" /> 查看当时
        </Button>
        <Button size="sm" variant="outline" className="h-8 bg-background text-[13px]" onClick={() => onDispatch({ t: "view-version", n: part.version })}>
          <RotateCcw className="size-3.5" /> 回滚到此
        </Button>
      </div>
    </div>
  );
}

function FailedCard({
  part,
  onRetry,
}: {
  part: Extract<Part, { type: "wrap-failed" }>;
  onRetry: () => void;
}) {
  return (
    <div className="mt-2.5 rounded-xl border border-destructive/25 bg-destructive/[0.05] p-3">
      <div className="mb-1.5 flex items-center gap-2">
        <TriangleAlert className="size-4 text-destructive" />
        <span className="text-sm font-semibold text-destructive">这轮没做完</span>
      </div>
      <p className="text-sm leading-relaxed">{part.reason}</p>
      <div className="mt-3">
        <Button size="sm" className="h-7 text-xs" onClick={onRetry}>
          重新尝试
        </Button>
      </div>
    </div>
  );
}

/** 假物料（原型）：对话时上传的附件。 */
type Material = { name: string; size: string; icon: React.ReactNode };
const INITIAL_MATERIALS: Material[] = [
  { name: "门店照片.jpg", size: "2.1 MB", icon: <ImageIcon className="size-3.5" /> },
  { name: "旧价目表.pdf", size: "380 KB", icon: <FileText className="size-3.5" /> },
];

/**
 * 消息发送框（Lovable/Bolt 形态）：立体卡片（ring + 分层阴影），
 * 输入区 → 附件 chip 行（可删）→ 工具行（附件物料区 / 类型下拉 / 语音 / 圆形发送）。
 * 首页 hero 与项目页共用，hero 加大一号。
 */
export function Composer({ hero = false }: { hero?: boolean }) {
  const [materials, setMaterials] = React.useState(INITIAL_MATERIALS);
  const [open, setOpen] = React.useState(false);
  const [mode, setMode] = React.useState("做系统");
  const addMock = () => {
    setMaterials((m) => [...m, { name: `新拍的价目表${m.length - 1}.jpg`, size: "1.4 MB", icon: <ImageIcon className="size-3.5" /> }]);
    setOpen(false);
  };
  return (
    <div
      className={cn(
        "rounded-2xl bg-background ring-1 ring-border/60 transition-shadow",
        "shadow-[0_12px_32px_-16px_rgb(0_0_0/0.25)]",
        "focus-within:shadow-[0_16px_40px_-16px_rgb(0_0_0/0.3)] focus-within:ring-primary/30",
        hero ? "p-4" : "p-3",
      )}
    >
      <div className={cn("text-muted-foreground/70", hero ? "min-h-12 text-base" : "min-h-7 text-sm")}>
        {hero ? "一句话说说你想做什么…（原型里请到项目页播放场景）" : "说说想改什么…"}
      </div>
      {materials.length > 0 ? (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {materials.map((m) => (
            <span
              key={m.name}
              className="flex items-center gap-1.5 rounded-lg border bg-muted/50 py-1 pl-2 pr-1 text-xs text-foreground/80"
            >
              <span className="text-muted-foreground">{m.icon}</span>
              {m.name}
              <button
                className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-background hover:text-foreground"
                onClick={() => setMaterials((ms) => ms.filter((x) => x.name !== m.name))}
                aria-label={`移除${m.name}`}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      ) : null}
      <div className={cn("flex items-center gap-1", hero ? "mt-3" : "mt-2")}>
        <Popover open={open} onOpenChange={setOpen}>
          <PopoverTrigger className="flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground">
            <Paperclip className="size-4" /> 附件
          </PopoverTrigger>
          <PopoverContent align="start" className="w-80 p-0">
            <div className="border-b px-3 py-2 text-xs font-semibold">项目物料</div>
            <div className="p-1.5">
              {materials.map((m) => (
                <div key={m.name} className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted/60">
                  <span className="text-muted-foreground">{m.icon}</span>
                  <span className="min-w-0 flex-1 truncate">{m.name}</span>
                  <span className="text-xs text-muted-foreground">{m.size}</span>
                </div>
              ))}
              {materials.length === 0 ? (
                <div className="px-2 py-3 text-center text-xs text-muted-foreground">还没有物料</div>
              ) : null}
            </div>
            <div className="border-t p-2">
              <button
                onClick={addMock}
                className="flex w-full flex-col items-center gap-1 rounded-lg border border-dashed px-3 py-4 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
              >
                <Upload className="size-4" />
                点击上传（原型：点我模拟传一张）
              </button>
              <p className="px-1 pt-2 text-[11px] leading-relaxed text-muted-foreground">
                照片、价目表、旧系统截图都可以传，做系统时智能体会参考。
              </p>
            </div>
          </PopoverContent>
        </Popover>
        <DropdownMenu>
          <DropdownMenuTrigger className="flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground">
            <Sparkles className="size-3.5" /> {mode} <ChevronDown className="size-3" />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            {["做系统", "做页面", "写文档"].map((m) => (
              <DropdownMenuItem key={m} disabled={m !== "做系统"} onClick={() => setMode(m)}>
                <span className="flex-1">{m}</span>
                {m === mode ? <Check className="size-3.5" /> : null}
                {m !== "做系统" ? <span className="text-[10px] text-muted-foreground/60">敬请期待</span> : null}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
        <span className="ml-auto" />
        <Button variant="ghost" size="icon" className="size-8 rounded-full text-muted-foreground" aria-label="语音输入（原型占位）" disabled>
          <Mic className="size-4" />
        </Button>
        <Button
          size="icon"
          className={cn("rounded-full transition-transform active:scale-90", hero ? "size-9" : "size-8")}
          disabled
        >
          <ArrowUp className="size-4" />
        </Button>
      </div>
    </div>
  );
}

/** 顶栏 LIVE 计时（真实时间，纯摆件）。 */
export function LivePill() {
  const [sec, setSec] = React.useState(0);
  React.useEffect(() => {
    const t = setInterval(() => setSec((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, []);
  return (
    <span className="flex items-center gap-2 rounded-full border border-red-500/40 bg-red-500/10 px-2.5 py-1">
      <span className="relative flex size-2">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-red-500 opacity-60" />
        <span className="relative inline-flex size-2 rounded-full bg-red-500" />
      </span>
      <span className="text-xs font-semibold text-red-600">LIVE</span>
      <span className="font-mono text-xs tabular-nums text-red-600">
        {Math.floor(sec / 60)}:{String(sec % 60).padStart(2, "0")}
      </span>
    </span>
  );
}

/** 品牌位（与现行侧栏一致）。 */
export function Brand({ nameClassName }: { nameClassName?: string }) {
  return (
    <span className="flex items-center gap-2">
      <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-primary text-xs font-bold text-primary-foreground">
        AI
      </span>
      <span className={cn("text-sm font-semibold", nameClassName)}>AI 开发平台</span>
    </span>
  );
}
