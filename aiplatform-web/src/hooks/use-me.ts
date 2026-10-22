import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";

/**
 * 会话身份（A2 §3）：形状取 gen:api 生成物，accountId 为字符串 TSID（超 JS 安全整数），禁转 Number。
 * Required 收紧：spec 0004 §5 最小契约两字段必有；swagger（springdoc）未标 required，投影恢复必填。
 */
export type Me = Required<components["schemas"]["MeResponse"]>;

/**
 * 会话身份（spec 0004 §5）：staleTime Infinity——会话内不重拉，
 * 失效感知交给 401 全局出口（client.ts），此处不设 error 分支。
 * {@code enabled=false}＝匿名可逛面（首页无凭证 cookie）：不发 /me 请求
 *（「只逛零足迹」——匿名首访不该因背景查询弹登录框）。
 *
 * <p><b>探针静默（访客 era 修正）</b>：/me 是账户端点——访客凭证过得了
 * /api/** 拦截面、过不了本端点（后端按设计 401＝「未登录」这个答案本身，
 * 非会话故障）。因此本查询显式 {@code silentUnauthorized}：401 不发全局
 * 出口事件、不弹 M01（访客每次挂载都弹「登录保存项目」的缺陷正出于此）；
 * 头像位照常按 me=undefined 呈现「登录」入口。登录墙语义留给用户动作的
 * 401（发消息/保存等被拦才弹）。</p>
 */
export function useMe(enabled = true) {
  return useQuery({
    queryKey: queryKeys.me,
    queryFn: () => api.get<Me>("/me", { silentUnauthorized: true }),
    staleTime: Infinity,
    enabled,
  });
}
