package com.semgravio.aiplatform.business.project.domain.repository;

import java.util.List;

import com.semgravio.aiplatform.business.project.domain.model.ModelProvider;
import com.semgravio.aiplatform.business.project.domain.model.ModelProviderTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfig;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigCandidate;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigCandidateTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigSwitchTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigTrace;

/**
 * 模型供应商通道/配置存取（ADR-0031 两表；#367 起读写双面，平台资产 JdbcTemplate
 * 原生 SQL，照 {@code AgentConfigStore} 先例——单表读写不挂 JPA 映射面）。读腿
 * 供装配解析 {@code ModelConfigAppService}（每轮命令构建动态查库，配置变更下一轮
 * 自然生效）；写腿供后台维护面 {@code BackofficeModelChannelAppService}（CRUD＋
 * append-only 留痕，覆盖写入与留痕追加同事务原子——事务由应用层界定）。
 *
 * <p>删除守卫查询也在此（SQL 归宿）：配置被智能体运营配置引用、通道被配置行
 * 引用时不可删（悬空引用不留静默坑，spec #364 故事 9）。</p>
 */
public interface ModelChannelStore {

    /** 按语义键取配置行；无行＝null（配置缺行由解析/校验服务报错，不在此发明）。 */
    ModelConfig findConfig(String configKey);

    /** 按键名取通道行；无行＝null。 */
    ModelProvider findProvider(String providerKey);

    /** 全部通道行（后台清单读面；键序稳定返回）。 */
    List<ModelProvider> findAllProviders();

    /** 全部配置行（后台清单读面与绑定下拉数据源；键序稳定返回）。 */
    List<ModelConfig> findAllConfigs();

    /**
     * 该配置的全部候选行（含停用行——启停过滤是解析语义，归 {@code
     * ModelConfigAppService}；priority 升序、同序按通道键/模型名稳定排序）。
     * 空列表＝配置未配置候选（解析回退配置行现列，软着陆 ADR-0053 ③）。
     */
    List<ModelConfigCandidate> findConfigCandidates(String configKey);

    /** 通道行整行 upsert（键＝provider_key；后台维护面唯一写口）。 */
    void saveProvider(ModelProvider provider);

    /** 配置行整行 upsert（键＝config_key；后台维护面唯一写口）。 */
    void saveConfig(ModelConfig config);

    /**
     * 首选候选镜像 upsert（键＝config_key+priority 1；配置行写口的伴随写）：
     * 配置行现列＝首选候选（ADR-0053 ③ 软着陆不变式）——写面改配置同步物化
     * priority 1 候选，带候选行的配置改配置不静默失效。候选 CRUD 全面化在 T5。
     */
    void saveConfigPrimaryCandidate(String configKey, String providerKey, String modelName,
            String operatorId, String operatorName);

    /**
     * 档内候选队列整队原子重写（#559/T5 候选编排写口）：delete＋insert 保序落
     * 地（priority 是 PK 肢——单行 UPDATE 换位撞唯一键，整队重写是排序语义的
     * 最简形；调用方携完整新队列、事务由应用层界定）。created_at 随重写取当下
     * （行身份＝位次而非连续性，历史在留痕表）。
     */
    void replaceConfigCandidates(String configKey, List<ModelConfigCandidate> candidates);

    /** 通道行删除（引用守卫在应用层先查，此处纯删）。 */
    void deleteProvider(String providerKey);

    /** 配置行删除（引用守卫在应用层先查，此处纯删）。 */
    void deleteConfig(String configKey);

    /** 配置是否被智能体运营配置引用（model_config_key＝配置键）。 */
    boolean configBoundByAgentConfigs(String configKey);

    /** 通道是否被引用（配置行直引或候选行引用——V79 起候选 FK 也指通道；裸 FK 报错不可读的先检面）。 */
    boolean providerReferenced(String providerKey);

    /** 通道变更留痕追加（append-only，仅实际变更落痕）。 */
    void insertProviderTrace(ModelProviderTrace trace);

    /** 通道留痕读面：该通道全部留痕按时间倒序（最近先）。 */
    List<ModelProviderTrace> findProviderTraces(String providerKey);

    /** 配置变更留痕追加（append-only，仅实际变更落痕）。 */
    void insertConfigTrace(ModelConfigTrace trace);

    /** 配置留痕读面：该配置全部留痕按时间倒序（最近先）。 */
    List<ModelConfigTrace> findConfigTraces(String configKey);

    /**
     * 运行期切候选留痕追加（#557，ADR-0053 ③，append-only）：FailoverModel 每次
     * 链式切换一痕——写腿经 base 端口 {@code ModelSwitchTraceSink} 适配进来
     * （观察面：库故障不烧穿生成流，适配层吞错记日志）。
     */
    void insertConfigSwitchTrace(ModelConfigSwitchTrace trace);

    /** 候选编排留痕追加（#559/T5，append-only，仅实际变更落痕——一次编排可多痕）。 */
    void insertConfigCandidateTrace(ModelConfigCandidateTrace trace);

    /** 候选编排留痕读面：该配置全部留痕按时间倒序（最近先）。 */
    List<ModelConfigCandidateTrace> findConfigCandidateTraces(String configKey);
}
