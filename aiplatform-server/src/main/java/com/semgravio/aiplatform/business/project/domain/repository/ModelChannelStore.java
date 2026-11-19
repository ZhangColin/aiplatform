package com.semgravio.aiplatform.business.project.domain.repository;

import java.util.List;

import com.semgravio.aiplatform.business.project.domain.model.ModelProvider;
import com.semgravio.aiplatform.business.project.domain.model.ModelProviderTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelTier;
import com.semgravio.aiplatform.business.project.domain.model.ModelTierCandidate;
import com.semgravio.aiplatform.business.project.domain.model.ModelTierSwitchTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelTierTrace;

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

    /**
     * 该档位的全部候选行（含停用行——启停过滤是解析语义，归 {@code
     * ModelTierAppService}；priority 升序、同序按通道键/模型名稳定排序）。
     * 空列表＝档位未配置候选（解析回退档位行现列，软着陆 ADR-0053 ③）。
     */
    List<ModelTierCandidate> findTierCandidates(String tierKey);

    /** 通道行整行 upsert（键＝provider_key；后台维护面唯一写口）。 */
    void saveProvider(ModelProvider provider);

    /** 档位行整行 upsert（键＝tier_key；后台维护面唯一写口）。 */
    void saveTier(ModelTier tier);

    /**
     * 首选候选镜像 upsert（键＝tier_key+priority 1；档位行写口的伴随写）：
     * 档位行现列＝首选候选（ADR-0053 ③ 软着陆不变式）——写面改档同步物化
     * priority 1 候选，带候选行的档位改档不静默失效。候选 CRUD 全面化在 T5。
     */
    void saveTierPrimaryCandidate(String tierKey, String providerKey, String modelName,
            String operatorId, String operatorName);

    /** 通道行删除（引用守卫在应用层先查，此处纯删）。 */
    void deleteProvider(String providerKey);

    /** 档位行删除（引用守卫在应用层先查，此处纯删）。 */
    void deleteTier(String tierKey);

    /** 档位是否被智能体运营配置引用（model_id/routing_strong_model_id 任一＝档位键）。 */
    boolean tierBoundByAgentConfigs(String tierKey);

    /** 通道是否被引用（档位行直引或候选行引用——V79 起候选 FK 也指通道；裸 FK 报错不可读的先检面）。 */
    boolean providerReferenced(String providerKey);

    /** 通道变更留痕追加（append-only，仅实际变更落痕）。 */
    void insertProviderTrace(ModelProviderTrace trace);

    /** 通道留痕读面：该通道全部留痕按时间倒序（最近先）。 */
    List<ModelProviderTrace> findProviderTraces(String providerKey);

    /** 档位变更留痕追加（append-only，仅实际变更落痕）。 */
    void insertTierTrace(ModelTierTrace trace);

    /** 档位留痕读面：该档位全部留痕按时间倒序（最近先）。 */
    List<ModelTierTrace> findTierTraces(String tierKey);

    /**
     * 运行期切候选留痕追加（#557，ADR-0053 ③，append-only）：FailoverModel 每次
     * 链式切换一痕——写腿经 base 端口 {@code ModelSwitchTraceSink} 适配进来
     * （观察面：库故障不烧穿生成流，适配层吞错记日志）。
     */
    void insertTierSwitchTrace(ModelTierSwitchTrace trace);
}
