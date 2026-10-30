package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.project.application.dto.command.ModelProviderCreateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelProviderUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelTierCreateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelTierUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelProviderResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelProviderTraceResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelTierResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelTierTraceResponse;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.ModelProvider;
import com.semgravio.aiplatform.business.project.domain.model.ModelProviderTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelTier;
import com.semgravio.aiplatform.business.project.domain.model.ModelTierTrace;
import com.semgravio.aiplatform.business.project.domain.model.Operator;
import com.semgravio.aiplatform.business.project.domain.repository.ModelChannelStore;

import lombok.extern.slf4j.Slf4j;

/**
 * 后台模型供应商/档位维护用例（#367，ADR-0031 两表的写面——读腿在
 * {@link ModelTierAppService} 装配解析）：通道 CRUD（apiKey 写收全量值、<b>读面
 * 一律掩码</b>——回执与留痕快照都只出尾 4 位，全链零明文 key 回显）＋档位 CRUD
 * （语义键/实际模型名/所属通道/显示名/启用态），全部变更 append-only 留痕
 * （操作者＋前后全量值快照）、同值幂等重写不落痕、回滚＝旧值写回即一次新变更
 * ——机制同 {@code BackofficeAgentConfigAppService}（ADR-0021）；差异＝行有
 * 创建/删除生命周期（创建痕 old 全 null、删除痕 new 全 null）。
 *
 * <p><b>apiKey 写语义特例</b>（掩码回显的必然对偶）：更新携 null/空白/现值掩码
 * 形＝保持现值（读面掩码回显的 round-trip 安全——PUT 回写不带 key 不会把 key
 * 丢了），携全量新值＝换 key；清除 key 无写面（下线通道走 enabled=false）。
 * enabled 缺省同判保持现值（终态字段不因缺省翻转——停用通道换 key 不被意外
 * 复启；创建缺省才归一 true）。</p>
 *
 * <p><b>删除守卫</b>（悬空引用不留静默坑，spec #364 故事 9）：档位被智能体运营
 * 配置绑定（model_id/routing_strong_model_id）不可删（PRJ_073）；通道被档位行
 * 引用不可删（PRJ_072——FK 裸错的读面化）。临时下线＝停用不删行（既有绑定装配
 * 明确报错、新绑定被即时校验拒绝——错误可定位不静默）。</p>
 *
 * <p><b>生效语义</b>：装配动态查库（{@code ModelTierAppService} 每轮命令构建
 * resolve），通道/档位变更下一轮命令构建即生效；模型实例缓存键含通道指纹
 * （baseUrl/key 摘要）——同模型串换 key 换地址＝新实例，进行中 run 不定格。
 * 日志一律掩码（不落明文 key）。绑定即时校验（改档拒错值）在
 * {@code BackofficeAgentConfigAppService}（绑定写口侧）。</p>
 */
@Slf4j
@Service
public class BackofficeModelChannelAppService {

    /** 空命令归一（体缺/体 null 的安全形——先例 EMPTY_COMMAND 同款意图）。 */
    private static final ModelProviderCreateCommand EMPTY_PROVIDER_CREATE =
            new ModelProviderCreateCommand(null, null, null, null);

    private static final ModelProviderUpdateCommand EMPTY_PROVIDER_UPDATE =
            new ModelProviderUpdateCommand(null, null, null);

    private static final ModelTierCreateCommand EMPTY_TIER_CREATE =
            new ModelTierCreateCommand(null, null, null, null, null);

    private static final ModelTierUpdateCommand EMPTY_TIER_UPDATE =
            new ModelTierUpdateCommand(null, null, null, null);

    private final ModelChannelStore channelStore;

    public BackofficeModelChannelAppService(ModelChannelStore channelStore) {
        this.channelStore = channelStore;
    }

    // ---------- 供应商通道（CRUD＋掩码＋留痕） ----------

    /** 通道清单读面（键序；掩码形）。 */
    @Transactional(readOnly = true)
    public List<BackofficeModelProviderResponse> providers() {
        return channelStore.findAllProviders().stream()
                .map(BackofficeModelProviderResponse::of).toList();
    }

    /**
     * 通道单行读面（掩码形）。
     *
     * @throws ApplicationException PRJ_065 通道不存在
     */
    @Transactional(readOnly = true)
    public BackofficeModelProviderResponse provider(String providerKey) {
        return BackofficeModelProviderResponse.of(requireProvider(providerKey));
    }

    /**
     * 通道创建（POST）：键名随体（已存在 409）、baseUrl 必填、apiKey 收全量值
     * （null/空白＝暂不配置）。创建即落痕（old 全 null）。
     *
     * @throws ApplicationException PRJ_067 键已存在；PRJ_069 baseUrl 缺；
     *                              PRJ_034 操作者缺
     */
    @Transactional
    public BackofficeModelProviderResponse createProvider(ModelProviderCreateCommand command,
            Operator operator) {
        requireOperator(operator);
        ModelProviderCreateCommand safe =
                command == null ? EMPTY_PROVIDER_CREATE : command;
        String providerKey = blankToNull(safe.providerKey());
        if (providerKey == null) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_FIELDS_REQUIRED);
        }
        if (channelStore.findProvider(providerKey) != null) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_EXISTS);
        }
        String baseUrl = requireBaseUrl(safe.baseUrl());
        String apiKey = blankToNull(safe.apiKey());
        boolean enabled = safe.enabled() == null || safe.enabled();
        ModelProvider created = new ModelProvider(providerKey, baseUrl, apiKey, enabled,
                operator.id(), operator.name(), null);
        persistProvider(null, created, operator);
        return BackofficeModelProviderResponse.of(requireProvider(providerKey));
    }

    /**
     * 通道更新（PUT 全量语义）：baseUrl/enabled 终态替换（enabled 缺省归一 true）；
     * apiKey＝null/空白/现值掩码形保持现值、全量新值换 key。值面无动＝幂等回执
     * 零写入（不落痕不写行）。
     *
     * @throws ApplicationException PRJ_065 通道不存在；PRJ_069 baseUrl 缺；
     *                              PRJ_034 操作者缺
     */
    @Transactional
    public BackofficeModelProviderResponse updateProvider(String providerKey,
            ModelProviderUpdateCommand command, Operator operator) {
        requireOperator(operator);
        ModelProvider current = requireProvider(providerKey);
        ModelProviderUpdateCommand safe =
                command == null ? EMPTY_PROVIDER_UPDATE : command;
        String baseUrl = requireBaseUrl(safe.baseUrl());
        String apiKey = resolveApiKey(current, safe.apiKey());
        // enabled 缺省＝保持现值（终态字段不因缺省翻转——停用通道换 key/换地址
        // 不被意外复启；显式 true/false 才翻转。与 agent-configs 开关「缺省归一
        // true」不同属有意：那边 null 有回落枚举缺省语义，这边行恒存在无回落概念）
        boolean enabled = safe.enabled() == null ? current.enabled() : safe.enabled();
        ModelProvider next = new ModelProvider(current.providerKey(), baseUrl, apiKey, enabled,
                operator.id(), operator.name(), null);
        persistProvider(current, next, operator);
        return BackofficeModelProviderResponse.of(
                channelStore.findProvider(providerKey)); // 幂等回执同形（无动即现行态）
    }

    /**
     * 通道删除（DELETE）：被档位行引用即拒（PRJ_072）；删成落痕（new 全 null，
     * 留痕跨行存活）。
     *
     * @throws ApplicationException PRJ_065 通道不存在；PRJ_072 被档位引用；
     *                              PRJ_034 操作者缺
     */
    @Transactional
    public void deleteProvider(String providerKey, Operator operator) {
        requireOperator(operator);
        ModelProvider current = requireProvider(providerKey);
        if (channelStore.providerReferencedByTiers(providerKey)) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_DELETE_REFERENCED);
        }
        channelStore.deleteProvider(providerKey);
        channelStore.insertProviderTrace(providerTrace(providerKey, current, null, operator));
        log.info("[model-channel] 通道删除 {}（key {}，baseUrl {}）", providerKey,
                current.maskedApiKey(), current.baseUrl());
    }

    /**
     * 通道变更留痕读面（倒序最近先；apiKey 快照掩码）。不要求行存在——留痕跨一切
     * 存活（删除行的历史仍可读；空清单＝从未有行或从未变更，行读面负责 404 判别）。
     */
    @Transactional(readOnly = true)
    public List<BackofficeModelProviderTraceResponse> providerTraces(String providerKey) {
        return channelStore.findProviderTraces(providerKey).stream()
                .map(BackofficeModelProviderTraceResponse::of).toList();
    }

    // ---------- 模型档位（CRUD＋留痕） ----------

    /** 档位清单读面（键序；绑定下拉数据源）。 */
    @Transactional(readOnly = true)
    public List<BackofficeModelTierResponse> tiers() {
        return channelStore.findAllTiers().stream()
                .map(BackofficeModelTierResponse::of).toList();
    }

    /**
     * 档位单行读面。
     *
     * @throws ApplicationException PRJ_066 档位不存在
     */
    @Transactional(readOnly = true)
    public BackofficeModelTierResponse tier(String tierKey) {
        return BackofficeModelTierResponse.of(requireTier(tierKey));
    }

    /**
     * 档位创建（POST）：语义键随体（已存在 409）；所属通道必须已有行（PRJ_071
     * ——FK 裸错的读面化）。创建即落痕（old 全 null）；建行即可被绑定。
     *
     * @throws ApplicationException PRJ_068 键已存在；PRJ_070 字段缺；
     *                              PRJ_071 所属通道不存在；PRJ_034 操作者缺
     */
    @Transactional
    public BackofficeModelTierResponse createTier(ModelTierCreateCommand command,
            Operator operator) {
        requireOperator(operator);
        ModelTierCreateCommand safe = command == null ? EMPTY_TIER_CREATE : command;
        String tierKey = blankToNull(safe.tierKey());
        String providerKey = blankToNull(safe.providerKey());
        String modelName = blankToNull(safe.modelName());
        if (tierKey == null || providerKey == null || modelName == null) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_FIELDS_REQUIRED);
        }
        requireProviderExists(providerKey);
        if (channelStore.findTier(tierKey) != null) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_EXISTS);
        }
        ModelTier created = new ModelTier(tierKey, providerKey, modelName,
                blankToNull(safe.displayName()),
                safe.enabled() == null || safe.enabled(),
                operator.id(), operator.name(), null);
        persistTier(null, created, operator);
        return BackofficeModelTierResponse.of(requireTier(tierKey));
    }

    /**
     * 档位更新（PUT 全量语义）：换通道/升模型版本/改显示名/停启用皆此一处改
     * 指向（全部引用自动跟上）；值面无动＝幂等回执零写入。
     *
     * @throws ApplicationException PRJ_066 档位不存在；PRJ_070 字段缺；
     *                              PRJ_071 所属通道不存在；PRJ_034 操作者缺
     */
    @Transactional
    public BackofficeModelTierResponse updateTier(String tierKey, ModelTierUpdateCommand command,
            Operator operator) {
        requireOperator(operator);
        ModelTier current = requireTier(tierKey);
        ModelTierUpdateCommand safe =
                command == null ? EMPTY_TIER_UPDATE : command;
        String providerKey = blankToNull(safe.providerKey());
        String modelName = blankToNull(safe.modelName());
        if (providerKey == null || modelName == null) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_FIELDS_REQUIRED);
        }
        requireProviderExists(providerKey);
        // enabled 缺省＝保持现值（同通道面：停用档换模型名/换通道不被意外复启）
        ModelTier next = new ModelTier(current.tierKey(), providerKey, modelName,
                blankToNull(safe.displayName()),
                safe.enabled() == null ? current.enabled() : safe.enabled(),
                operator.id(), operator.name(), null);
        persistTier(current, next, operator);
        return BackofficeModelTierResponse.of(channelStore.findTier(tierKey));
    }

    /**
     * 档位删除（DELETE）：被智能体运营配置绑定即拒（PRJ_073——model_id/
     * routing_strong_model_id 引用面）；删成落痕（new 全 null）。
     *
     * @throws ApplicationException PRJ_066 档位不存在；PRJ_073 被绑定引用；
     *                              PRJ_034 操作者缺
     */
    @Transactional
    public void deleteTier(String tierKey, Operator operator) {
        requireOperator(operator);
        ModelTier current = requireTier(tierKey);
        if (channelStore.tierBoundByAgentConfigs(tierKey)) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_DELETE_REFERENCED);
        }
        channelStore.deleteTier(tierKey);
        channelStore.insertTierTrace(tierTrace(tierKey, current, null, operator));
        log.info("[model-channel] 档位删除 {}（{}:{}）", tierKey,
                current.providerKey(), current.modelName());
    }

    /**
     * 档位变更留痕读面（倒序最近先）。不要求行存在——留痕跨一切存活（删除行的
     * 历史仍可读，回滚＝按痕旧值重建行）。
     */
    @Transactional(readOnly = true)
    public List<BackofficeModelTierTraceResponse> tierTraces(String tierKey) {
        return channelStore.findTierTraces(tierKey).stream()
                .map(BackofficeModelTierTraceResponse::of).toList();
    }

    // ---------- 共用写路径（值面比对＋落库＋留痕，同事务原子） ----------

    /**
     * 通道值面落库＋留痕：值面无动返回（幂等零写入）；有动即整行 upsert＋留痕
     * （前后全量快照＋操作者）。{@code current} 为 null＝创建（old 全 null）。
     * 日志掩码（换 key 可证、key 不落日志）。
     */
    private void persistProvider(ModelProvider current, ModelProvider next, Operator operator) {
        if (current != null && providerFacetsMatch(current, next)) {
            return;
        }
        channelStore.saveProvider(next);
        channelStore.insertProviderTrace(providerTrace(next.providerKey(), current, next, operator));
        log.info("[model-channel] 通道变更 {}（key {}→{}，baseUrl {}，enabled {}）",
                next.providerKey(),
                current == null ? null : current.maskedApiKey(),
                next.maskedApiKey(), next.baseUrl(), next.enabled());
    }

    /** 档位值面落库＋留痕（同 {@link #persistProvider} 形）。 */
    private void persistTier(ModelTier current, ModelTier next, Operator operator) {
        if (current != null && tierFacetsMatch(current, next)) {
            return;
        }
        channelStore.saveTier(next);
        channelStore.insertTierTrace(tierTrace(next.tierKey(), current, next, operator));
        log.info("[model-channel] 档位变更 {}（{}:{}，enabled {}）", next.tierKey(),
                next.providerKey(), next.modelName(), next.enabled());
    }

    /** 通道留痕构造单点（old/new 任一 null＝创建/删除痕：该侧快照腿全 null）。 */
    private static ModelProviderTrace providerTrace(String providerKey, ModelProvider oldProvider,
            ModelProvider newProvider, Operator operator) {
        return new ModelProviderTrace(TsidGenerator.newInstance().generate(), providerKey,
                oldProvider == null ? null : oldProvider.baseUrl(),
                oldProvider == null ? null : oldProvider.apiKey(),
                oldProvider == null ? null : oldProvider.enabled(),
                newProvider == null ? null : newProvider.baseUrl(),
                newProvider == null ? null : newProvider.apiKey(),
                newProvider == null ? null : newProvider.enabled(),
                operator.id(), operator.name(), LocalDateTime.now());
    }

    /** 档位留痕构造单点（同 {@link #providerTrace} 形）。 */
    private static ModelTierTrace tierTrace(String tierKey, ModelTier oldTier,
            ModelTier newTier, Operator operator) {
        return new ModelTierTrace(TsidGenerator.newInstance().generate(), tierKey,
                oldTier == null ? null : oldTier.providerKey(),
                oldTier == null ? null : oldTier.modelName(),
                oldTier == null ? null : oldTier.displayName(),
                oldTier == null ? null : oldTier.enabled(),
                newTier == null ? null : newTier.providerKey(),
                newTier == null ? null : newTier.modelName(),
                newTier == null ? null : newTier.displayName(),
                newTier == null ? null : newTier.enabled(),
                operator.id(), operator.name(), LocalDateTime.now());
    }

    /** 通道值面比对（写者/时刻不入比——幂等不落痕不写行）。 */
    private static boolean providerFacetsMatch(ModelProvider current, ModelProvider next) {
        return Objects.equals(current.baseUrl(), next.baseUrl())
                && Objects.equals(current.apiKey(), next.apiKey())
                && current.enabled() == next.enabled();
    }

    /** 档位值面比对（同上）。 */
    private static boolean tierFacetsMatch(ModelTier current, ModelTier next) {
        return Objects.equals(current.providerKey(), next.providerKey())
                && Objects.equals(current.modelName(), next.modelName())
                && Objects.equals(current.displayName(), next.displayName())
                && current.enabled() == next.enabled();
    }

    /**
     * apiKey 写值归一：null/纯空白＝保持现值（round-trip 安全）；等于现值掩码形
     * ＝保持（读面回显形直写不误伤）；其余＝全量新值（trim）。
     */
    private static String resolveApiKey(ModelProvider current, String submitted) {
        String candidate = blankToNull(submitted);
        if (candidate == null) {
            return current.apiKey();
        }
        return candidate.equals(current.maskedApiKey()) ? current.apiKey() : candidate;
    }

    /** baseUrl 必填守卫（创建与更新同拦——维护面无部分更新语义）。 */
    private static String requireBaseUrl(String baseUrl) {
        String value = blankToNull(baseUrl);
        if (value == null) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_FIELDS_REQUIRED);
        }
        return value;
    }

    /** 通道行寻址：无行 404 PRJ_065（维护面语义——装配面缺行是 PRJ_060）。 */
    private ModelProvider requireProvider(String providerKey) {
        ModelProvider provider = blankToNull(providerKey) == null
                ? null : channelStore.findProvider(providerKey);
        if (provider == null) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_NOT_FOUND);
        }
        return provider;
    }

    /** 档位行寻址：无行 404 PRJ_066。 */
    private ModelTier requireTier(String tierKey) {
        ModelTier tier = blankToNull(tierKey) == null ? null : channelStore.findTier(tierKey);
        if (tier == null) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_CONFIG_NOT_FOUND);
        }
        return tier;
    }

    /** 档位所属通道存在守卫（FK 裸错的读面化，PRJ_071）。 */
    private void requireProviderExists(String providerKey) {
        if (channelStore.findProvider(providerKey) == null) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_PROVIDER_UNKNOWN, providerKey);
        }
    }

    /** 操作者必留痕（#251 写操作全程同款）：缺头即拦。 */
    private static void requireOperator(Operator operator) {
        if (operator == null || operator.id() == null || operator.name() == null) {
            throw new ApplicationException(ProjectMessage.AGENT_CONFIG_OPERATOR_REQUIRED);
        }
    }

    /** 值空白归一：null/纯空白 → null（首尾空白去除）。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
