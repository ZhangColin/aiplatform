import type { Metadata } from "next";

import { DemoExperiencePage } from "@/components/demo-experience/demo-experience-page";

export const metadata: Metadata = { title: "Demo 体验" };

/** Demo 体验阶段页 P04/P05（#347，spec #320 实施序⑥）：生成等待/真实预览、
 *  版本基准、对话迭代与 Demo 确认门同页装配。 */
export default async function DemoStagePage(props: PageProps<"/projects/[id]/demo">) {
  const { id } = await props.params;
  return <DemoExperiencePage projectId={id} />;
}
