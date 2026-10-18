import type { Metadata } from "next";

import { ContractStagePage } from "@/components/contract-stage/contract-stage-page";

export const metadata: Metadata = { title: "签约付款" };

/** 签约付款阶段页 P06（#348，spec #320 实施序⑥）：阶段头卡＋三子步＋黄金门禁
 *  横幅＋范围与报价/合同/付款三子步重组新建，替换 #346 的过渡桥。 */
export default async function ContractStagePageRoute(
  props: PageProps<"/projects/[id]/contract">,
) {
  const { id } = await props.params;
  return <ContractStagePage projectId={id} />;
}
