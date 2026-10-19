import type { Metadata } from "next";

import { ProductionStagePage } from "@/components/production-stage/production-stage-page";

export const metadata: Metadata = { title: "生产承接" };

/** 生产承接阶段页 P07（#349，spec #320 实施序⑥）：四源六态、人工进展、下一步、
 * 交付联系人与 PRD/Demo/合同/付款记录四个成果入口。 */
export default async function ProductionStagePageRoute(
  props: PageProps<"/projects/[id]/production">,
) {
  const { id } = await props.params;
  return <ProductionStagePage projectId={id} />;
}
