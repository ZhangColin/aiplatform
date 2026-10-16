import type { ComponentProps } from "react";

import { BRAND_LOGO } from "@/lib/brand";
import { cn } from "@/lib/utils";

/**
 * 品牌徽标两版（#342 品牌落位）：dark＝深色圆底（浅色场合——导航位缺省）；
 * white＝白色透明（深色场合留存）。素材正本＝交接包 assets（public/brand）。
 */
export function BrandLogo({
  tone = "dark",
  alt = "语义引力",
  className,
  ...props
}: Omit<ComponentProps<"img">, "src"> & {
  tone?: keyof typeof BRAND_LOGO;
}) {
  return (
    // eslint-disable-next-line @next/next/no-img-element -- 品牌徽标＝固定小尺寸静态展示（SVG 内嵌位图），Image 优化无增益
    <img
      src={BRAND_LOGO[tone]}
      alt={alt}
      className={cn("size-7 w-auto shrink-0 object-contain", className)}
      {...props}
    />
  );
}
