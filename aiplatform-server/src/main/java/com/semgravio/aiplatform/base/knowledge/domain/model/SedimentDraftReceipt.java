package com.semgravio.aiplatform.base.knowledge.domain.model;

/**
 * 沉淀自荐回执（#460，照技能草稿回执先例）：给智能体的单次自荐结果——accepted＝
 * 已留档待审（draftId 即后台「数据标注」寻址柄），message 是模型可读的完整回执
 * 文本（成功说明后续人审语义；拒绝说明原因：校验违例／扫描回执／在途上限）。
 * run 失败不回滚已写入的自荐（照 ADR-0022 同口径：工具执行即事实落库，无 run 级
 * 回滚概念）。
 */
public record SedimentDraftReceipt(boolean accepted, Long draftId, String message) {

    public static SedimentDraftReceipt accepted(long draftId, String message) {
        return new SedimentDraftReceipt(true, draftId, message);
    }

    public static SedimentDraftReceipt rejected(String message) {
        return new SedimentDraftReceipt(false, null, message);
    }
}
