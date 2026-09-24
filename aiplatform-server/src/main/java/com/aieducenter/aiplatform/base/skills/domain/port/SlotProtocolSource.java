package com.aieducenter.aiplatform.base.skills.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;

/**
 * 槽位生效工作协议来源（#255 指派双头预检的对照头之一）：技能库底座对「该槽位
 * 智能体现在跑什么协议」的知识反转口——正本在 business 侧智能体运营配置（库值
 * 优先、缺省回落，#251/ADR-0021 生效值解析单点），经端口接线（单体进程内直调，
 * 先例 {@code AgentKindNames}）。无协议面（subagent 无运营配置正本）返回
 * null——预检对照＝已指派＋候选集（无协议面），机制同一不特判。
 */
@Port(PortType.CLIENT)
public interface SlotProtocolSource {

    /**
     * 该槽位生效工作协议全文。
     *
     * @return 生效 systemPrompt；该槽位无运营配置正本返回 null
     */
    String effectiveProtocolOf(SkillSlot slot);
}
