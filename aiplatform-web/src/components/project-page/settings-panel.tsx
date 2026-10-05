"use client";

import { useMemo, useState } from "react";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { usePrd } from "@/hooks/use-prd";
import { useSwitchEndpointType } from "@/hooks/use-endpoint-type";
import type { ProjectDetail } from "@/lib/projects/detail";
import {
  DESIGN_SCOPE,
  ENDPOINT_OPTIONS,
  ENDPOINT_TYPE,
} from "@/lib/projects/endpoint";
import { parseFeatureListItems } from "@/lib/projects/feature-list";
import { cn } from "@/lib/utils";

/**
 * 设置面板（#285，ADR-0025「低频重大动作归属性之家」）：终点类型控件——项目内
 * 唯一变更位，三档单选＋确认（不即点即切）；系统→设计类切换（PRD 已产出）受理
 * 时选作用域（全部页面 / 从功能清单勾选——页面级锚定从功能清单来）。下单即冻结
 * /归档关闭（locked 全控件禁用）；切换成功后 PRD 清单章由主智能体重产轮随改
 * （对话区可见过程，文档面经 document-updated 自愈）。
 */
export function SettingsPanel({
  projectId,
  endpointType,
  designScope,
  locked,
  hasPrd,
}: {
  projectId: string;
  endpointType?: number;
  designScope?: ProjectDetail["designScope"];
  locked: boolean;
  hasPrd: boolean;
}) {
  const current = endpointType ?? ENDPOINT_TYPE.system;
  const [pending, setPending] = useState<number | null>(null);
  const [scopeType, setScopeType] = useState<number>(DESIGN_SCOPE.allPages);
  const [selectedPages, setSelectedPages] = useState<string[]>([]);

  const prd = usePrd(projectId);
  const switchEndpoint = useSwitchEndpointType(projectId);

  // 作用域勾选源＝现行 PRD 的功能清单条目（系统→设计类且 PRD 在才需要选）
  const scopeNeeded =
    !locked &&
    pending != null &&
    current === ENDPOINT_TYPE.system &&
    (pending === ENDPOINT_TYPE.design || pending === ENDPOINT_TYPE.systemDesign) &&
    hasPrd;
  const featureItems = useMemo(
    () => parseFeatureListItems(prd.data?.content),
    [prd.data?.content],
  );

  function beginSwitch(code: number) {
    setPending(code);
    setScopeType(DESIGN_SCOPE.allPages);
    setSelectedPages([]);
  }

  function confirmSwitch() {
    if (pending == null) return;
    switchEndpoint.mutate(
      {
        endpointType: pending,
        ...(scopeNeeded
          ? {
              scopeType,
              ...(scopeType === DESIGN_SCOPE.selectedPages
                ? { scopePages: selectedPages }
                : {}),
            }
          : {}),
      },
      { onSuccess: () => setPending(null) },
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col overflow-y-auto">
      <div className="mx-auto flex w-full max-w-xl flex-col gap-5 p-6">
        <section className="flex flex-col gap-3" aria-label="终点类型">
          <div>
            <h3 className="text-sm font-semibold">终点类型</h3>
            <p className="mt-1 text-xs text-muted-foreground">
              本项目最终交付什么。下单前可改，下单后随订单锁定
            </p>
          </div>
          <RadioGroup
            value={pending ?? current}
            onValueChange={(value) => {
              const code = Number(value);
              if (!locked && code !== current) beginSwitch(code);
            }}
            className="gap-2"
            aria-label="终点类型选择"
          >
            {ENDPOINT_OPTIONS.map((option) => (
              <Label
                key={option.code}
                htmlFor={`endpoint-${option.code}`}
                className={cn(
                  "flex cursor-pointer items-start gap-3 rounded-lg border p-3 font-normal",
                  (pending ?? current) === option.code
                    ? "border-primary bg-primary/5"
                    : "border-border",
                  locked && "cursor-not-allowed opacity-60",
                )}
              >
                <RadioGroupItem
                  id={`endpoint-${option.code}`}
                  value={option.code}
                  disabled={locked}
                  className="mt-0.5"
                />
                <span className="flex flex-col gap-0.5">
                  <span className="text-sm font-medium">{option.label}</span>
                  <span className="text-xs text-muted-foreground">{option.hint}</span>
                </span>
              </Label>
            ))}
          </RadioGroup>
          {locked ? (
            <p className="text-xs text-muted-foreground">
              项目已锁定（订单处理中或已归档），终点类型不可更改
            </p>
          ) : null}
          {designScope ? (
            <p className="text-xs text-muted-foreground">
              设计范围：{designScope.typeName}
              {designScope.pages && designScope.pages.length > 0
                ? `（${designScope.pages.join("、")}）`
                : ""}
            </p>
          ) : null}
        </section>

        {pending != null && !locked ? (
          <section
            className="flex flex-col gap-3 rounded-lg border p-4"
            aria-label="切换确认"
          >
            <div className="text-sm">
              切换为「{optionOf(pending)?.label ?? ""}」
              <span className="block text-xs text-muted-foreground">
                {optionOf(pending)?.hint ?? ""}；确认后 PRD 会随之调整（对话区可见）
              </span>
            </div>
            {scopeNeeded ? (
              <div className="flex flex-col gap-2">
                <p className="text-xs font-medium">设计范围</p>
                <RadioGroup
                  value={String(scopeType)}
                  onValueChange={(value) => setScopeType(Number(value))}
                  className="flex flex-col gap-1.5"
                  aria-label="设计范围选择"
                >
                  <Label className="flex items-center gap-2 font-normal text-sm">
                    <RadioGroupItem value={DESIGN_SCOPE.allPages} />
                    全部页面
                  </Label>
                  <Label className="flex items-center gap-2 font-normal text-sm">
                    <RadioGroupItem
                      value={DESIGN_SCOPE.selectedPages}
                      disabled={featureItems.length === 0}
                    />
                    从功能清单勾选
                  </Label>
                </RadioGroup>
                {scopeType === DESIGN_SCOPE.selectedPages ? (
                  featureItems.length === 0 ? (
                    <p className="text-xs text-muted-foreground">
                      没有解析到功能清单条目，请先选全部页面
                    </p>
                  ) : (
                    <div className="flex flex-col gap-1.5 rounded-md border p-3">
                      {featureItems.map((item) => (
                        <Label
                          key={item}
                          className="flex items-start gap-2 font-normal text-xs"
                        >
                          <Checkbox
                            checked={selectedPages.includes(item)}
                            onCheckedChange={(checked) =>
                              setSelectedPages((pages) =>
                                checked
                                  ? [...pages, item]
                                  : pages.filter((page) => page !== item),
                              )
                            }
                            className="mt-0.5"
                          />
                          <span className="line-clamp-2">{item}</span>
                        </Label>
                      ))}
                    </div>
                  )
                ) : null}
              </div>
            ) : null}
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                onClick={confirmSwitch}
                disabled={
                  switchEndpoint.isPending ||
                  (scopeNeeded &&
                    scopeType === DESIGN_SCOPE.selectedPages &&
                    selectedPages.length === 0)
                }
              >
                确认切换
              </Button>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => setPending(null)}
                disabled={switchEndpoint.isPending}
              >
                取消
              </Button>
            </div>
          </section>
        ) : null}
      </div>
    </div>
  );
}

function optionOf(code: number) {
  return ENDPOINT_OPTIONS.find((option) => option.code === code);
}
