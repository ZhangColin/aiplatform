import { redirect } from "next/navigation";

/**
 * 签约付款阶段页（P06）的【过渡桥】（#346）：签约付款页重建归 #348——过渡期
 * 内本路由回落到 /demo 过渡桥（订单面板/下单入口暂挂彼处，阶段侧栏链接不空转）。
 * #348 落页时整页替换本文件。
 */
export default async function ContractStagePage(
  props: PageProps<"/projects/[id]/contract">,
) {
  const { id } = await props.params;
  redirect(`/projects/${id}/demo`);
}
