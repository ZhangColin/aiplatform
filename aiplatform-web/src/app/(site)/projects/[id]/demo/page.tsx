import type { Metadata } from "next";

import { ProjectPageView } from "@/components/project-page/project-page-view";

export const metadata: Metadata = { title: "Demo 体验" };

/**
 * Demo 体验阶段页（P04/P05）的【过渡桥】（#346）：Demo 阶段页照原型重建归
 * #347——过渡期内本路由暂挂旧项目页布局（对话区＋成果区 tab 簇），系统预览/
 * 下单入口/订单面板/设计画布等未迁件经此保持可达（阶段侧栏 Demo 链接不空转）。
 * #347 重建时整页替换本文件、旧布局组件随 #350 退役。
 */
export default async function DemoStagePage(props: PageProps<"/projects/[id]/demo">) {
  const { id } = await props.params;
  return <ProjectPageView projectId={id} />;
}
