import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

// shadcn 组件共用的 className 合并工具（components.json aliases.utils 指向此文件）。
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
