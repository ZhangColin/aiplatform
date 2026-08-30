"use client";

import { LogOutIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useMe } from "@/hooks/use-me";

/**
 * 会话用户区：displayName + 登出入口。登出走 form POST /auth/logout（非 fetch，
 * 后端带 id_token hint 杀 identity 会话后 302 回 /）。摆位最终随工程初始化
 * #1 的 Layout（spec 0004 §5 只定契约），此组件自包含可整体挪。
 */
export function UserMenu() {
  const { data: me } = useMe();

  // data 未就绪不渲染；会话过期由 401 全局出口整页接管，无 error 分支
  if (!me) return null;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button variant="ghost" size="sm" aria-label="用户菜单" />}
      >
        {me.displayName}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <form action="/auth/logout" method="post">
          {/* render 原生 submit button + nativeButton：菜单关闭后原生 form POST 照常走（缺 nativeButton 会触发 Base UI 告警） */}
          <DropdownMenuItem render={<button type="submit" />} nativeButton>
            <LogOutIcon />
            退出登录
          </DropdownMenuItem>
        </form>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
