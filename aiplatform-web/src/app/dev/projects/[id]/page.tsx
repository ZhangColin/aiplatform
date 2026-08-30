import type { Metadata } from "next";

import { WorkbenchView } from "@/components/workbench/workbench-view";
import { parseWorkbenchDeepLinkFromSearchParams } from "@/lib/todos/deep-link";

export const metadata: Metadata = { title: "项目工作台" };

/**
 * 开发平台工作台（spec 0001 §3）：Agent 区三模式 tab（对话 / 直播 / 待处理）。
 * 消费待办深链（issue #44）：`?wait=` / `?focus=` → 对话模式聚焦 / 主面板 tab。
 */
export default async function DevProjectWorkbenchPage(props: PageProps<"/dev/projects/[id]">) {
  const { id } = await props.params;
  const deepLink = parseWorkbenchDeepLinkFromSearchParams(await props.searchParams);
  return <WorkbenchView projectId={id} variant="dev" deepLink={deepLink} />;
}
