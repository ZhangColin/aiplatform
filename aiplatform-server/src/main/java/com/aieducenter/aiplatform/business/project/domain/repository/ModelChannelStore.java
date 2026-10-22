package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;

import com.aieducenter.aiplatform.business.project.domain.model.ModelProvider;
import com.aieducenter.aiplatform.business.project.domain.model.ModelProviderTrace;
import com.aieducenter.aiplatform.business.project.domain.model.ModelTier;
import com.aieducenter.aiplatform.business.project.domain.model.ModelTierTrace;

/**
 * 模型供应商通道/档位存取（ADR-0031 两表；#367 起读写双面，平台资产 JdbcTemplate
 * 原生 SQL，照 {@code AgentConfigStore} 先例——单表读写不挂 JPA 映射面）。读腿
 * 供装配解析 {@code ModelTierAppService}（每轮命令构建动态查库，配置变更下一轮
 * 自然生效）；写腿供后台维护面 {@code BackofficeModelChannelAppService}（CRUD＋
 * append-only 留痕，覆盖写入与留痕追加同事务原子——事务由应用层界定）。
 *
 * <p>删除守卫查询也在此（SQL 归宿）：档位被智能体运营配置引用、通道被档位行
 * 引用时不可删（悬空引用不留静默坑，spec #364 故事 9）。</p>
 */
public interface ModelChannelStore {

    /** 按语义键取档位行；无行＝null（档位缺行由解析/校验服务报错，不在此发明）。 */
    ModelTier findTier(String tierKey);

    /** 按键名取通道行；无行＝null。 */
    ModelProvider findProvider(String providerKey);

    /** 全部通道行（后台清单读面；键序稳定返回）。 */
    List<ModelProvider> findAllProviders();

    /** 全部档位行（后台清单读面与绑定下拉数据源；键序稳定返回）。 */
    List<ModelTier> findAllTiers();

    /** 通道行整行 upsert（键＝provider_key；后台维护面唯一写口）。 */
    void saveProvider(ModelProvider provider);

    /** 档位行整行 upsert（键＝tier_key；后台维护面唯一写口）。 */
    void saveTier(ModelTier tier);

    /** 通道行删除（引用守卫在应用层先查，此处纯删）。 */
    void deleteProvider(String providerKey);

    /** 档位行删除（引用守卫在应用层先查，此处纯删）。 */
    void deleteTier(String tierKey);

    /** 档位是否被智能体运营配置引用（model_id/routing_strong_model_id 任一＝档位键）。 */
    boolean tierBoundByAgentConfigs(String tierKey);

    /** 通道是否被档位行引用（provider_key 外键的先检面——裸 FK 报错不可读）。 */
    boolean providerReferencedByTiers(String providerKey);

    /** 通道变更留痕追加（append-only，仅实际变更落痕）。 */
    void insertProviderTrace(ModelProviderTrace trace);

    /** 通道留痕读面：该通道全部留痕按时间倒序（最近先）。 */
    List<ModelProviderTrace> findProviderTraces(String providerKey);

    /** 档位变更留痕追加（append-only，仅实际变更落痕）。 */
    void insertTierTrace(ModelTierTrace trace);

    /** 档位留痕读面：该档位全部留痕按时间倒序（最近先）。 */
    List<ModelTierTrace> findTierTraces(String tierKey);
}
