import type { Metadata } from "next";

import { WorkbenchView } from "@/components/workbench/workbench-view";
import { parseWorkbenchDeepLinkFromSearchParams } from "@/lib/todos/deep-link";

export const metadata: Metadata = { title: "项目工作台" };

/**
 * 项目详情 = 需求端工作台（spec 0002 §3.3，顾问单对话模式）。消费待办深链
 * （issue #49）：`?wait=` / `?focus=` → 顾问对话聚焦（等待胶囊 / 门卡）。
 */
export default async function ProjectWorkbenchPage(props: PageProps<"/projects/[id]">) {
  const { id } = await props.params;
  const deepLink = parseWorkbenchDeepLinkFromSearchParams(await props.searchParams);
  return <WorkbenchView projectId={id} variant="advisor" deepLink={deepLink} />;
}
