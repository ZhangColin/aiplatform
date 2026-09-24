package com.aieducenter.aiplatform.business.project.infrastructure;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.port.SlotProtocolSource;
import com.aieducenter.aiplatform.business.project.application.AgentConfigAppService;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

/**
 * 槽位生效工作协议端口适配器（#255 指派双头预检）：槽位稳定键与 AgentProfile
 * 稳定键同串对齐（main/executor）——经 {@link AgentConfigAppService} 生效值
 * 解析单点（库值优先、缺省回落，#251）回解协议全文；subagent 无运营配置正本
 * （byKey 空）返回 null，预检对照＝已指派＋候选集（无协议面）。知识在业务层、
 * 读面在底座（base 不依赖 business），经端口反转接线（单体进程内直调，
 * {@code AgentKindNames} 先例）。
 */
@Adapter(PortType.CLIENT)
public class SlotProtocolAdapter implements SlotProtocolSource {

    private final AgentConfigAppService agentConfigAppService;

    public SlotProtocolAdapter(AgentConfigAppService agentConfigAppService) {
        this.agentConfigAppService = agentConfigAppService;
    }

    @Override
    public String effectiveProtocolOf(SkillSlot slot) {
        return AgentProfile.byKey(slot.key())
                .map(agentConfigAppService::effectiveOf)
                .map(AgentConfigAppService.EffectiveConfig::systemPrompt)
                .orElse(null);
    }
}
