package com.aieducenter.aiplatform.business.project.infrastructure;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.metering.domain.port.AgentKindNames;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

/**
 * 智能体展示名端口适配器（#186）：正本 = {@link AgentProfile}（ADR-0006 智能体
 * 配置不落库），按稳定键回解展示名——主链 main/executor 有名；naming/classify
 * 等一次性辅助用途标记非登记配置，返回 null（消费端落「—」桶）。知识在业务层、
 * 读面在底座（base 不依赖 business），经端口反转接线（单体进程内直调）。
 */
@Adapter(PortType.CLIENT)
public class AgentKindNameAdapter implements AgentKindNames {

    @Override
    public String displayNameOf(String agentKind) {
        return AgentProfile.displayNameOf(agentKind);
    }
}
