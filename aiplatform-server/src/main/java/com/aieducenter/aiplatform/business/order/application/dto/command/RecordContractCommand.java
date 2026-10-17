package com.aieducenter.aiplatform.business.order.application.dto.command;

/**
 * 合同草稿重录命令（#336「需更新」处置正路）：同号重录——预览字段集整组
 * 录入＋冻结集取订单现值重新定格＋版本 +1。合法性由聚合守卫裁决
 * （ORD_031/032/022/023），不在命令层重复校验。
 *
 * @param partyA       甲方主体（可空＝未录入；至多 200 字）
 * @param partyB       乙方主体（可空；至多 200 字）
 * @param projectScope 项目范围（可空；至多 2000 字）
 * @param paymentPlan  付款计划文本面（可空；至多 2000 字——节点对象归付款计划
 *                    域后续票）
 * @param basis        依据（必填，至多 1000 字）
 */
public record RecordContractCommand(
        String partyA,
        String partyB,
        String projectScope,
        String paymentPlan,
        String basis
) {
}
