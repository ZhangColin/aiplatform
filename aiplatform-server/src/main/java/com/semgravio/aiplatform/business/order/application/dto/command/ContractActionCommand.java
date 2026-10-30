package com.semgravio.aiplatform.business.order.application.dto.command;

/**
 * 合同后台动作命令（#336，推进/失败或过期/撤回/作废共用）：依据＝人工推进
 * 留痕的必填自由文本（操作人/时间由服务端随行落）。合法性由聚合守卫裁决
 * （ORD_021/022/023），不在命令层重复校验。
 *
 * @param basis 依据（必填，至多 1000 字——append-only 留痕行的正身）
 */
public record ContractActionCommand(
        String basis
) {
}
