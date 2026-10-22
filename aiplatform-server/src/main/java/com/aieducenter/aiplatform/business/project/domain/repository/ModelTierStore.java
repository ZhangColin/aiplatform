package com.aieducenter.aiplatform.business.project.domain.repository;

import com.aieducenter.aiplatform.business.project.domain.model.ModelProvider;
import com.aieducenter.aiplatform.business.project.domain.model.ModelTier;

/**
 * 模型档位/供应商通道读腿（平台资产，JdbcTemplate 原生 SQL，照
 * {@code AgentConfigStore} 先例——单表读写不挂 JPA 映射面）。装配解析
 * {@code ModelTierAppService} 每轮命令构建时动态查库（配置变更下一轮自然
 * 生效，进行中 run 不定格）；写面（后台维护＋留痕）属 #367。
 */
public interface ModelTierStore {

    /** 按语义键取档位行；无行＝null（档位缺行由解析服务报错，不在此发明）。 */
    ModelTier findTier(String tierKey);

    /** 按键名取通道行；无行＝null。 */
    ModelProvider findProvider(String providerKey);
}
