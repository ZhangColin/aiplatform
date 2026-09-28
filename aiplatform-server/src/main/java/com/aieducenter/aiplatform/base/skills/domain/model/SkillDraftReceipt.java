package com.aieducenter.aiplatform.base.skills.domain.model;

/**
 * 自荐回执（#259）：给智能体的单次自荐结果——accepted＝已留档待审（draftId 即
 * 管理寻址柄），message 是模型可读的完整回执文本（成功说明后续人审语义；拒绝
 * 说明原因：校验违例／撞名撞了谁／扫描回执）。run 失败不回滚已写入的自荐
 * （ADR-0022：工具执行即事实落库，无 run 级回滚概念）。
 */
public record SkillDraftReceipt(boolean accepted, Long draftId, String message) {

    public static SkillDraftReceipt accepted(long draftId, String message) {
        return new SkillDraftReceipt(true, draftId, message);
    }

    public static SkillDraftReceipt rejected(String message) {
        return new SkillDraftReceipt(false, null, message);
    }
}
