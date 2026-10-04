"use client";

/**
 * ============================================================================
 * 原 型 —— 设计过程体验（#278）：订单页＋文档页（一次性，勿当生产代码）
 * ============================================================================
 * 订单页验证定稿后动线的后半程：下单（设计单）→ 对话流报价 → 支付 →
 * 支付门下载（未付费可看不可带——「平台随便体验、带走才付费」）。
 * 文档页＝PRD 清单章二形演示（设计形＝设计物清单 / 系统形＝功能清单）。
 * ============================================================================
 */

import * as React from "react";
import {
  BadgeCheck,
  Check,
  CreditCard,
  Download,
  FileText,
  ListChecks,
  Lock,
  ReceiptText,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

import { TOWEL_DRAFTS } from "./media";
import type { DesignState } from "./engine";
import type { DesignEngine } from "./use-design-engine";

/* ---------- 订单页 ---------- */

const STEPS = ["已创建", "待支付", "已支付"] as const;

export function OrderPane({ engine }: { engine: DesignEngine }) {
  const { state } = engine;
  const [gateHint, setGateHint] = React.useState<string | null>(null);

  const step = state.order === "none" ? -1 : state.order === "placed" ? 1 : state.order === "quoted" ? 1 : 2;
  const price = state.scenario === "towel" ? "¥ 399" : "¥ 1,999";
  const deliverable = state.scenario === "towel" ? "毛巾设计图 · 设计资产包" : "巷角咖啡官网 · 系统＋设计资产包";

  const tryDownload = () => {
    if (state.order !== "paid") {
      setGateHint("完成支付后可下载——平台里随便看、随便挑，带走才付费。");
      return;
    }
    setGateHint(null);
    const first = state.versions.find((v) => v.media && TOWEL_DRAFTS[v.media]);
    if (first?.media) {
      const blob = new Blob([TOWEL_DRAFTS[first.media]], { type: "image/svg+xml" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${first.media}-定稿.svg`;
      a.click();
      URL.revokeObjectURL(url);
    } else {
      setGateHint("（原型演示）系统＋设计项目的源码包下载不在本原型范围。");
    }
  };

  return (
    <div className="flex min-h-0 flex-1 items-start justify-center overflow-y-auto p-6">
      <div className="w-full max-w-md">
        <div className="rounded-2xl border p-5 shadow-sm">
          <div className="flex items-center gap-2">
            <ReceiptText className="size-4 text-muted-foreground" />
            <span className="text-sm font-semibold">{deliverable}</span>
            {state.order === "none" ? (
              <Badge className="ml-auto bg-muted text-muted-foreground hover:bg-muted">未下单</Badge>
            ) : state.order === "paid" ? (
              <Badge className="ml-auto bg-green-600/10 text-green-700 hover:bg-green-600/10">已支付</Badge>
            ) : (
              <Badge className="ml-auto border-amber-500/40 bg-amber-500/10 text-amber-700 hover:bg-amber-500/10">待支付</Badge>
            )}
          </div>

          <div className="mt-4 flex items-center">
            {STEPS.map((s, i) => (
              <React.Fragment key={s}>
                <div className="flex flex-col items-center gap-1">
                  <span
                    className={
                      "flex size-5 items-center justify-center rounded-full text-[10px] font-bold " +
                      (i < step ? "bg-green-600 text-white" : i === step ? "bg-amber-500 text-white" : "bg-muted text-muted-foreground")
                    }
                  >
                    {i + 1}
                  </span>
                  <span className={"text-[11px] " + (i === step ? "font-medium text-foreground" : "text-muted-foreground")}>{s}</span>
                </div>
                {i < STEPS.length - 1 ? <div className={"mx-1 mb-4 h-px flex-1 " + (i < step ? "bg-green-600" : "bg-border")} /> : null}
              </React.Fragment>
            ))}
          </div>

          <Separator className="my-4" />
          <div className="space-y-2 text-[13px]">
            {state.scenario === "towel" ? (
              <>
                <div className="flex justify-between"><span className="text-muted-foreground">毛巾主图案＋包装吊牌 · 制作与商用授权</span><span>¥ 399</span></div>
                <div className="flex justify-between text-muted-foreground"><span>含选定稿源文件、规范文件、衍生尺寸</span><span className="tabular-nums">—</span></div>
              </>
            ) : (
              <>
                <div className="flex justify-between"><span className="text-muted-foreground">官网系统制作（按定稿稿）</span><span>¥ 1,599</span></div>
                <div className="flex justify-between"><span className="text-muted-foreground">首年托管与发布</span><span>¥ 400</span></div>
              </>
            )}
            <div className="flex justify-between border-t pt-2 text-sm font-semibold"><span>合计</span><span className="tabular-nums">{price}</span></div>
          </div>

          {/* 动作区：随订单状态换装 */}
          <div className="mt-4 space-y-2">
            {state.order === "none" ? (
              <Button className="w-full transition-transform active:scale-[0.98]" onClick={() => engine.onOrderPlace()}>
                <Check className="size-4" /> 下单（冻结交付内容）
              </Button>
            ) : state.order === "quoted" ? (
              <Button className="w-full transition-transform active:scale-[0.98]" onClick={() => engine.onPay()}>
                <CreditCard className="size-4" /> 去支付
              </Button>
            ) : state.order === "placed" ? (
              <div className="flex items-center justify-center gap-2 rounded-lg border border-dashed px-3 py-2.5 text-[13px] text-muted-foreground">
                后台核价中…报价会发在对话里
              </div>
            ) : (
              <Button variant="outline" className="w-full transition-transform active:scale-[0.98]" onClick={tryDownload}>
                <Download className="size-4" /> 下载{state.scenario === "towel" ? "设计资产包" : "源码包＋设计资产包"}
              </Button>
            )}
            {gateHint ? (
              <div className="flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs leading-relaxed text-amber-800 dark:text-amber-300">
                <Lock className="mt-0.5 size-3.5 shrink-0" />
                {gateHint}
              </div>
            ) : null}
            {state.order === "paid" ? (
              <p className="text-center text-xs text-muted-foreground">已支付 · 平台随便逛，资产随时回来取（归档后也可）</p>
            ) : null}
          </div>
        </div>
        <p className="mt-3 text-center text-xs text-muted-foreground/70">
          原型：订单页为动线演示——真实报价流以对话流报价卡为正本
        </p>
      </div>
    </div>
  );
}

/* ---------- 文档页（PRD 清单章二形） ---------- */

export function DocPane({ state }: { state: DesignState }) {
  const isDesign = state.scenario === "towel";
  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto max-w-xl px-6 py-6">
        <h1 className="text-lg font-semibold">{isDesign ? "毛巾设计图 · 需求文档" : "巷角咖啡官网 · 需求文档"}</h1>
        <p className="mb-5 mt-0.5 text-[13px] text-muted-foreground">由访谈整理，随每轮修改更新</p>
        <h3 className="mb-1.5 mt-4 flex items-center gap-1.5 text-sm font-semibold">
          <ListChecks className="size-4 text-muted-foreground" />
          {isDesign ? "设计物清单" : "功能清单"}
        </h3>
        <div className="space-y-2">
          {(isDesign
            ? [
                { t: "① 毛巾主图案", d: "35×75cm 浴室毛巾 · 温柔耐看、工厂可打样 · 验收：色彩不超过 4 色、可平铺印花" },
                { t: "② 包装吊牌", d: "配套吊牌 · 与主图案呼应 · 验收：含品牌名与成分标识位" },
              ]
            : [
                { t: "① 首屏页", d: "官网门面 · 一屏说清巷角咖啡 · 验收：首屏自解释、行动按钮可达菜单" },
                { t: "② 菜单页", d: "饮品与价格 · 验收：分类清晰、可维护" },
                { t: "③ 关于页", d: "门店故事 · 验收：图文结构" },
                { t: "④ 联系页", d: "地址、营业时间、地图位 · 验收：信息准确" },
              ]
          ).map((row) => (
            <div key={row.t} className="rounded-xl border p-3">
              <div className="text-sm font-medium">{row.t}</div>
              <div className="mt-0.5 text-[13px] leading-relaxed text-muted-foreground">{row.d}</div>
            </div>
          ))}
        </div>
        {!isDesign ? (
          <p className="mt-3 rounded-lg border border-dashed px-3 py-2 text-xs leading-relaxed text-muted-foreground">
            系统＋设计项目：设计范围＝本清单页面集；设计硬约束写在「关键约束」章，风格方向走设计过程。
          </p>
        ) : (
          <p className="mt-3 rounded-lg border border-dashed px-3 py-2 text-xs leading-relaxed text-muted-foreground">
            设计主线：本清单即设计过程的计划对应物——按序逐件做，改序＝改这份文档。
          </p>
        )}
        {state.spec ? (
          <div className="mt-4 flex items-center gap-2 rounded-xl border border-green-600/25 bg-green-500/[0.05] px-3 py-2 text-[13px]">
            <BadgeCheck className="size-4 text-green-600" />
            设计规范 v{state.spec.version} 已随定稿刷新（色板见「设计稿」页）
          </div>
        ) : null}
        <div className="mt-4 flex items-center gap-2 text-[11px] text-muted-foreground/70">
          <FileText className="size-3.5" /> 原型：文档页为占位演示，真实面以平台文档范式为准
        </div>
      </div>
    </div>
  );
}
