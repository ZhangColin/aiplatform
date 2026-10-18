import { redirect } from "next/navigation";

/**
 * 生产承接阶段页（P07）的【过渡桥】（#346）：生产承接页新建归 #349——过渡期
 * 内本路由回落到 /demo 过渡桥（阶段侧栏链接不空转）。#349 落页时整页替换本文件。
 */
export default async function ProductionStagePage(
  props: PageProps<"/projects/[id]/production">,
) {
  const { id } = await props.params;
  redirect(`/projects/${id}/demo`);
}
