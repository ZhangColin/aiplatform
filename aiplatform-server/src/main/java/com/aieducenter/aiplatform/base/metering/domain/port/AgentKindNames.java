package com.aieducenter.aiplatform.base.metering.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 智能体展示名查询端口（CLIENT 直调，#186）：agentKind 的值域与中文名正本在
 * 业务层（AgentProfile 登记的主链 main/executor；naming/classify 等一次性辅助
 * 用途标记无展示名）——base 不依赖 business，后台成本读面（byAgentKind 分桶）
 * 经本端口回解展示名，枚举出口配 *Name（后台直读零映射）。
 *
 * <p>非主链用途标记返回 null（消费端落「—」桶，照用户面 agentKindLabel 先例
 * 口径）。迁出独立计量服务时本端口换 REST 适配器，签名不动（A1 §2.1）。</p>
 */
@Port(PortType.CLIENT)
public interface AgentKindNames {

    /**
     * agentKind 稳定键 → 展示名（主链回解 AgentProfile；辅助标记/未知键返回 null）。
     */
    String displayNameOf(String agentKind);
}
