import type { Metadata } from "next";

import { RequirementAnalysisPage } from "@/components/requirement-analysis/requirement-analysis-page";

export const metadata: Metadata = { title: "需求分析" };

/** 需求分析页 P02/P03（#346，spec #320 ⑥ IA 全量重建）：项目页本路由＝交付
 *  生命周期第一阶段「需求分析 (PRD)」的阶段页。 */
export default async function ProjectPage(props: PageProps<"/projects/[id]">) {
  const { id } = await props.params;
  return <RequirementAnalysisPage projectId={id} />;
}
