package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.project.application.dto.command.ModelProviderCreateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelProviderUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelConfigCandidateCreateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelConfigCandidateUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelConfigCreateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelConfigUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelProviderResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelProviderTraceResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelConfigCandidateResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelConfigCandidateTraceResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelConfigResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelConfigTraceResponse;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.ModelProvider;
import com.semgravio.aiplatform.business.project.domain.model.ModelProviderTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfig;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigCandidate;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigCandidateTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigTrace;
import com.semgravio.aiplatform.business.project.domain.model.Operator;
import com.semgravio.aiplatform.business.project.domain.repository.ModelChannelStore;

import lombok.extern.slf4j.Slf4j;

/**
 * 后台模型供应商/配置维护用例（#367，ADR-0031 两表的写面——读腿在
 * {@link ModelConfigAppService} 装配解析）：通道 CRUD（apiKey 写收全量值、<b>读面
 * 一律掩码</b>——回执与留痕快照都只出尾 4 位，全链零明文 key 回显）＋配置 CRUD
 * （语义键/实际模型名/所属通道/显示名/启用态/深度思考开关〔#370——模型行为
 * 属性跟配置走，缺省 false＝显式关〕），全部变更 append-only 留痕
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
 * <p><b>删除守卫</b>（悬空引用不留静默坑，spec #364 故事 9）：配置被智能体运营
 * 配置绑定（model_config_key/routing_strong_model_config_key）不可删（PRJ_073）；通道被配置行
 * 引用不可删（PRJ_072——FK 裸错的读面化）。临时下线＝停用不删行（既有绑定装配
 * 明确报错、新绑定被即时校验拒绝——错误可定位不静默）。</p>
 *
 * <p><b>生效语义</b>：装配动态查库（{@code ModelConfigAppService} 每轮命令构建
 * resolve），通道/配置变更下一轮命令构建即生效；模型实例缓存键含通道指纹
 * （baseUrl/key 摘要）——同模型串换 key 换地址＝新实例，进行中 run 不定格。
 * 日志一律掩码（不落明文 key）。绑定即时校验（改配置拒错值）在
 * {@code BackofficeAgentConfigAppService}（绑定写口侧）。</p>
 */
@Slf4j
@Service
public class BackofficeModelChannelAppService {

    /** 空命令归一（体缺/体 null 的安全形——先例 EMPTY_COMMAND 同款意图）。 */
    private static final ModelProviderCreateCommand EMPTY_PROVIDER_CREATE =
            new ModelProviderCreateCommand(null, null, null, null, null);

    private static final ModelProviderUpdateCommand EMPTY_PROVIDER_UPDATE =
            new ModelProviderUpdateCommand(null, null, null, null);

    private static final ModelConfigCreateCommand EMPTY_CONFIG_CREATE =
            new ModelConfigCreateCommand(null, null, null, null, null, null);

    private static final ModelConfigUpdateCommand EMPTY_CONFIG_UPDATE =
            new ModelConfigUpdateCommand(null, null, null, null, null);

    private static final ModelConfigCandidateCreateCommand EMPTY_CANDIDATE_CREATE =
            new ModelConfigCandidateCreateCommand(null, null, null, null);

    private static final ModelConfigCandidateUpdateCommand EMPTY_CANDIDATE_UPDATE =
            new ModelConfigCandidateUpdateCommand(null, null, null, null);

    private final ModelChannelStore channelStore;

    /**
     * vendor 受控值形（#559/T5）：小写字母/数字/连字符、1-50 位、字母数字起止
     * （列宽 VARCHAR(50) 对齐）。
     */
    private static final Pattern VENDOR_PATTERN =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,48}[a-z0-9])?");

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
        // vendor 受控值（#559/T5）：显式值校验小写 slug 形（防错字分裂统计与单价
        // 匹配）；缺省＝provider_key 同值落定（V79 历史回填语义的写面延续——缺省
        // 值不校验：provider_key 本身非 slug 时不强拦既有建行惯例）
        String vendor = blankToNull(safe.vendor());
        vendor = vendor == null ? providerKey : requireVendorFormat(vendor);
        ModelProvider created = new ModelProvider(providerKey, vendor, baseUrl, apiKey, enabled,
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
        // vendor 缺省＝保持现值（同终态字段不因缺省翻转——换 key/换地址不动归一；
        // 归一纠正＝显式写新值，受控值校验同创建）
        String vendor = blankToNull(safe.vendor());
        vendor = vendor == null ? current.vendor() : requireVendorFormat(vendor);
        ModelProvider next = new ModelProvider(current.providerKey(), vendor, baseUrl,
                apiKey, enabled, operator.id(), operator.name(), null);
        persistProvider(current, next, operator);
        return BackofficeModelProviderResponse.of(
                channelStore.findProvider(providerKey)); // 幂等回执同形（无动即现行态）
    }

    /**
     * 通道删除（DELETE）：被配置行引用即拒（PRJ_072）；删成落痕（new 全 null，
     * 留痕跨行存活）。
     *
     * @throws ApplicationException PRJ_065 通道不存在；PRJ_072 被配置引用；
     *                              PRJ_034 操作者缺
     */
    @Transactional
    public void deleteProvider(String providerKey, Operator operator) {
        requireOperator(operator);
        ModelProvider current = requireProvider(providerKey);
        if (channelStore.providerReferenced(providerKey)) {
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

    // ---------- 模型配置（CRUD＋留痕） ----------

    /** 配置清单读面（键序；绑定下拉数据源）。 */
    @Transactional(readOnly = true)
    public List<BackofficeModelConfigResponse> configs() {
        return channelStore.findAllConfigs().stream()
                .map(BackofficeModelConfigResponse::of).toList();
    }

    /**
     * 配置单行读面。
     *
     * @throws ApplicationException PRJ_066 配置不存在
     */
    @Transactional(readOnly = true)
    public BackofficeModelConfigResponse config(String configKey) {
        return BackofficeModelConfigResponse.of(requireConfig(configKey));
    }

    /**
     * 配置创建（POST）：语义键随体（已存在 409）；所属通道必须已有行（PRJ_071
     * ——FK 裸错的读面化）。创建即落痕（old 全 null）；建行即可被绑定。
     *
     * @throws ApplicationException PRJ_068 键已存在；PRJ_070 字段缺；
     *                              PRJ_071 所属通道不存在；PRJ_034 操作者缺
     */
    @Transactional
    public BackofficeModelConfigResponse createConfig(ModelConfigCreateCommand command,
            Operator operator) {
        requireOperator(operator);
        ModelConfigCreateCommand safe = command == null ? EMPTY_CONFIG_CREATE : command;
        String configKey = blankToNull(safe.configKey());
        String providerKey = blankToNull(safe.providerKey());
        String modelName = blankToNull(safe.modelName());
        if (configKey == null || providerKey == null || modelName == null) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_FIELDS_REQUIRED);
        }
        requireProviderExists(providerKey);
        if (channelStore.findConfig(configKey) != null) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_EXISTS);
        }
        ModelConfig created = new ModelConfig(configKey, providerKey, modelName,
                blankToNull(safe.displayName()),
                safe.enabled() == null || safe.enabled(),
                // thinking 缺省＝显式关（#370：不配置不默认拖慢首 token）
                safe.thinkingEnabled() != null && safe.thinkingEnabled(),
                operator.id(), operator.name(), null);
        persistConfig(null, created, operator);
        return BackofficeModelConfigResponse.of(requireConfig(configKey));
    }

    /**
     * 配置更新（PUT 全量语义）：换通道/升模型版本/改显示名/停启用皆此一处改
     * 指向（全部引用自动跟上）；值面无动＝幂等回执零写入。V79 起改指向同步镜像
     * priority 1 首选候选；指向撞队列他候选身份即拒（PRJ_091——双写径不变式：
     * 队列身份唯一不被配置直改绕过，换首选备选位走候选面的 move）。
     *
     * @throws ApplicationException PRJ_066 配置不存在；PRJ_070 字段缺；
     *                              PRJ_071 所属通道不存在；PRJ_091 撞候选身份；
     *                              PRJ_034 操作者缺
     */
    @Transactional
    public BackofficeModelConfigResponse updateConfig(String configKey, ModelConfigUpdateCommand command,
            Operator operator) {
        requireOperator(operator);
        ModelConfig current = requireConfig(configKey);
        ModelConfigUpdateCommand safe =
                command == null ? EMPTY_CONFIG_UPDATE : command;
        String providerKey = blankToNull(safe.providerKey());
        String modelName = blankToNull(safe.modelName());
        if (providerKey == null || modelName == null) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_FIELDS_REQUIRED);
        }
        requireProviderExists(providerKey);
        // 双写径不变式（#559）：配置行现列＝priority 1 候选（镜像），直改指向若
        // 撞队列他候选（p2+）身份＝队列出现重复故障单元——拒之，换位走候选面
        // move（那是排序语义不是改指向）
        for (ModelConfigCandidate candidate : channelStore.findConfigCandidates(configKey)) {
            if (candidate.priority() != 1
                    && candidate.providerKey().equals(providerKey)
                    && candidate.modelName().equals(modelName)) {
                throw new ApplicationException(ProjectMessage.MODEL_CONFIG_CANDIDATE_DUPLICATE,
                        providerKey, modelName);
            }
        }
        // enabled/thinking 缺省＝保持现值（同通道面：停用档换模型名/换通道不被
        // 意外复启；开思考的档换模型名不被意外关——终态字段不因缺省翻转）
        ModelConfig next = new ModelConfig(current.configKey(), providerKey, modelName,
                blankToNull(safe.displayName()),
                safe.enabled() == null ? current.enabled() : safe.enabled(),
                safe.thinkingEnabled() == null ? current.thinkingEnabled()
                        : safe.thinkingEnabled(),
                operator.id(), operator.name(), null);
        persistConfig(current, next, operator);
        return BackofficeModelConfigResponse.of(channelStore.findConfig(configKey));
    }

    /**
     * 配置删除（DELETE）：被智能体运营配置绑定即拒（PRJ_073——model_config_key/
     * routing_strong_model_config_key 引用面）；删成落痕（new 全 null）。
     *
     * @throws ApplicationException PRJ_066 配置不存在；PRJ_073 被绑定引用；
     *                              PRJ_034 操作者缺
     */
    @Transactional
    public void deleteConfig(String configKey, Operator operator) {
        requireOperator(operator);
        ModelConfig current = requireConfig(configKey);
        if (channelStore.configBoundByAgentConfigs(configKey)) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_DELETE_REFERENCED);
        }
        channelStore.deleteConfig(configKey);
        channelStore.insertConfigTrace(configTrace(configKey, current, null, operator));
        log.info("[model-channel] 配置删除 {}（{}:{}）", configKey,
                current.providerKey(), current.modelName());
    }

    /**
     * 配置变更留痕读面（倒序最近先）。不要求行存在——留痕跨一切存活（删除行的
     * 历史仍可读，回滚＝按痕旧值重建行）。
     */
    @Transactional(readOnly = true)
    public List<BackofficeModelConfigTraceResponse> configTraces(String configKey) {
        return channelStore.findConfigTraces(configKey).stream()
                .map(BackofficeModelConfigTraceResponse::of).toList();
    }

    // ---------- 候选队列（配置子资源 CRUD＋留痕，#559/T5） ----------

    /**
     * 候选队列读面（priority 升序＝队列序，含停用行——启停过滤是解析语义）。
     * 编排基准同源（{@link #effectiveQueue}）：无候选行的存量配置读面亦出物化
     * 首选（配置行现列＝隐式 priority 1，寻址/判重与编排一致）。
     *
     * @throws ApplicationException PRJ_066 配置不存在
     */
    @Transactional(readOnly = true)
    public List<BackofficeModelConfigCandidateResponse> configCandidates(String configKey) {
        return effectiveQueue(requireConfig(configKey)).stream()
                .map(BackofficeModelConfigCandidateResponse::of).toList();
    }

    /**
     * 候选增（POST）：追加缺省队尾、显式 priority＝插位（既有候选顺移）。候选
     * 身份（通道×实际模型名）队列内唯一（PRJ_091——同身份重复无备胎价值）。
     * 软着陆物化：配置无候选行时配置行现列即隐式首选候选，先进队列再插新行
     * （ incumbent 不被新行顶掉，回退腿与显式队列同形）。变更下一轮命令构建
     * 即生效（resolve 动态查库——进行中 run 不定格）。
     *
     * @throws ApplicationException PRJ_066 配置不存在；PRJ_088 字段缺；
     *                              PRJ_071 通道不存在；PRJ_090 队列序越界；
     *                              PRJ_091 身份重复；PRJ_034 操作者缺
     */
    @Transactional
    public List<BackofficeModelConfigCandidateResponse> addConfigCandidate(String configKey,
            ModelConfigCandidateCreateCommand command, Operator operator) {
        requireOperator(operator);
        ModelConfig config = requireConfig(configKey);
        ModelConfigCandidateCreateCommand safe =
                command == null ? EMPTY_CANDIDATE_CREATE : command;
        String providerKey = blankToNull(safe.providerKey());
        String modelName = blankToNull(safe.modelName());
        if (providerKey == null || modelName == null) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_CANDIDATE_FIELDS_REQUIRED);
        }
        requireProviderExists(providerKey);
        List<ModelConfigCandidate> oldQueue = effectiveQueue(config);
        requireCandidateAbsent(oldQueue, providerKey, modelName);
        int position = safe.priority() == null ? oldQueue.size() + 1
                : requirePriorityWithin(safe.priority(), oldQueue.size() + 1);
        ModelConfigCandidate added = new ModelConfigCandidate(configKey, position, providerKey,
                modelName, safe.enabled() == null || safe.enabled(),
                operator.id(), operator.name(), null);
        List<ModelConfigCandidate> newQueue = new ArrayList<>(oldQueue);
        newQueue.add(position - 1, added);
        applyQueueChange(config, oldQueue, renumber(newQueue), operator);
        log.info("[model-channel] 候选增 {}#{}（{}:{}）", configKey, position,
                providerKey, modelName);
        return configCandidates(configKey);
    }

    /**
     * 候选编排留痕读面（倒序最近先）。不要求配置行存在——留痕跨一切存活。
     */
    @Transactional(readOnly = true)
    public List<BackofficeModelConfigCandidateTraceResponse> configCandidateTraces(String configKey) {
        return channelStore.findConfigCandidateTraces(configKey).stream()
                .map(BackofficeModelConfigCandidateTraceResponse::of).toList();
    }

    /**
     * 候选改（PUT，寻址＝队列序）：换指向（通道/实际模型名各自缺省保持）、行级
     * 启停（缺省保持）、移动（priority＝目标队列序，区间顺移补位——排序即此）。
     * 换指向即换身份（旧身份删除痕＋新身份新增痕）；值面无动＝幂等回执零写入。
     * 动到队列头＝配置行镜像跟随（现列恒＝priority 1 候选）。变更下一轮命令构建
     * 即生效。
     *
     * @throws ApplicationException PRJ_066 配置不存在；PRJ_089 候选无行；
     *                              PRJ_071 通道不存在；PRJ_090 移动目标越界；
     *                              PRJ_091 换指向撞身份；PRJ_034 操作者缺
     */
    @Transactional
    public List<BackofficeModelConfigCandidateResponse> updateConfigCandidate(String configKey,
            int priority, ModelConfigCandidateUpdateCommand command, Operator operator) {
        requireOperator(operator);
        ModelConfig config = requireConfig(configKey);
        ModelConfigCandidateUpdateCommand safe =
                command == null ? EMPTY_CANDIDATE_UPDATE : command;
        List<ModelConfigCandidate> oldQueue = effectiveQueue(config);
        int index = requireCandidateIndex(oldQueue, configKey, priority);
        ModelConfigCandidate current = oldQueue.get(index);
        String providerKey = blankToNull(safe.providerKey());
        String modelName = blankToNull(safe.modelName());
        providerKey = providerKey == null ? current.providerKey() : providerKey;
        modelName = modelName == null ? current.modelName() : modelName;
        if (!providerKey.equals(current.providerKey()) || !modelName.equals(current.modelName())) {
            requireProviderExists(providerKey);
            List<ModelConfigCandidate> others = new ArrayList<>(oldQueue);
            others.remove(index);
            requireCandidateAbsent(others, providerKey, modelName);
        }
        boolean enabled = safe.enabled() == null ? current.enabled() : safe.enabled();
        // 移动缺省＝原地（与增的「缺省＝队尾」不同属：改是寻址动词，缺省不动位）
        int target = safe.priority() == null ? index + 1
                : requirePriorityWithin(safe.priority(), oldQueue.size());
        ModelConfigCandidate updated = new ModelConfigCandidate(configKey, target, providerKey,
                modelName, enabled, operator.id(), operator.name(), null);
        List<ModelConfigCandidate> newQueue = new ArrayList<>(oldQueue);
        newQueue.remove(index);
        newQueue.add(target - 1, updated);
        applyQueueChange(config, oldQueue, renumber(newQueue), operator);
        log.info("[model-channel] 候选改 {}#{}（{}:{}，enabled {}，序 {}→{}）", configKey,
                priority, providerKey, modelName, enabled, index + 1, target);
        return configCandidates(configKey);
    }

    /**
     * 候选删（DELETE，寻址＝队列序）：删成＝队列压实（后续候选顺移补位、priority
     * 密集无空洞）；最后一个候选不可删（PRJ_092——配置供给定义非空，整队下线走
     * 配置停用）。删成落痕（new 全 null）＋补位痕；回滚＝按痕旧值原位重建。
     *
     * @throws ApplicationException PRJ_066 配置不存在；PRJ_089 候选无行；
     *                              PRJ_092 最后候选；PRJ_034 操作者缺
     */
    @Transactional
    public List<BackofficeModelConfigCandidateResponse> deleteConfigCandidate(String configKey,
            int priority, Operator operator) {
        requireOperator(operator);
        ModelConfig config = requireConfig(configKey);
        List<ModelConfigCandidate> oldQueue = effectiveQueue(config);
        int index = requireCandidateIndex(oldQueue, configKey, priority);
        if (oldQueue.size() == 1) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_CANDIDATE_LAST_DELETE_FORBIDDEN);
        }
        ModelConfigCandidate removed = oldQueue.get(index);
        List<ModelConfigCandidate> newQueue = new ArrayList<>(oldQueue);
        newQueue.remove(index);
        applyQueueChange(config, oldQueue, renumber(newQueue), operator);
        log.info("[model-channel] 候选删 {}#{}（{}:{}）", configKey, priority,
                removed.providerKey(), removed.modelName());
        return configCandidates(configKey);
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

    /** 配置值面落库＋留痕（同 {@link #persistProvider} 形）。 */
    private void persistConfig(ModelConfig current, ModelConfig next, Operator operator) {
        if (current != null && configFacetsMatch(current, next)) {
            return;
        }
        channelStore.saveConfig(next);
        // 首选候选镜像（#556，ADR-0053 ③：配置行现列＝首选候选）：V79 起候选表
        // 非空＝resolve 队列唯一数据源——写面改配置须同步物化 priority 1 候选，
        // 否则对带候选行的配置（flash/pro 种子）改配置静默失效（demo 改配置 SOP
        // 失真）。镜像只跟指向，配置级启停仍由配置行 enabled 管（resolve 先检）；
        // 候选 CRUD 全面化（多候选编排/行级启停）在 T5。
        channelStore.saveConfigPrimaryCandidate(next.configKey(), next.providerKey(),
                next.modelName(), operator.id(), operator.name());
        channelStore.insertConfigTrace(configTrace(next.configKey(), current, next, operator));
        log.info("[model-channel] 配置变更 {}（{}:{}，enabled {}，thinking {}）", next.configKey(),
                next.providerKey(), next.modelName(), next.enabled(), next.thinkingEnabled());
    }

    /** 通道留痕构造单点（old/new 任一 null＝创建/删除痕：该侧快照腿全 null）。 */
    private static ModelProviderTrace providerTrace(String providerKey, ModelProvider oldProvider,
            ModelProvider newProvider, Operator operator) {
        return new ModelProviderTrace(TsidGenerator.newInstance().generate(), providerKey,
                oldProvider == null ? null : oldProvider.vendor(),
                oldProvider == null ? null : oldProvider.baseUrl(),
                oldProvider == null ? null : oldProvider.apiKey(),
                oldProvider == null ? null : oldProvider.enabled(),
                newProvider == null ? null : newProvider.vendor(),
                newProvider == null ? null : newProvider.baseUrl(),
                newProvider == null ? null : newProvider.apiKey(),
                newProvider == null ? null : newProvider.enabled(),
                operator.id(), operator.name(), LocalDateTime.now());
    }

    /** 配置留痕构造单点（同 {@link #providerTrace} 形）。 */
    private static ModelConfigTrace configTrace(String configKey, ModelConfig oldTier,
            ModelConfig newTier, Operator operator) {
        return new ModelConfigTrace(TsidGenerator.newInstance().generate(), configKey,
                oldTier == null ? null : oldTier.providerKey(),
                oldTier == null ? null : oldTier.modelName(),
                oldTier == null ? null : oldTier.displayName(),
                oldTier == null ? null : oldTier.enabled(),
                oldTier == null ? null : oldTier.thinkingEnabled(),
                newTier == null ? null : newTier.providerKey(),
                newTier == null ? null : newTier.modelName(),
                newTier == null ? null : newTier.displayName(),
                newTier == null ? null : newTier.enabled(),
                newTier == null ? null : newTier.thinkingEnabled(),
                operator.id(), operator.name(), LocalDateTime.now());
    }

    /** 通道值面比对（写者/时刻不入比——幂等不落痕不写行）。 */
    private static boolean providerFacetsMatch(ModelProvider current, ModelProvider next) {
        return Objects.equals(current.vendor(), next.vendor())
                && Objects.equals(current.baseUrl(), next.baseUrl())
                && Objects.equals(current.apiKey(), next.apiKey())
                && current.enabled() == next.enabled();
    }

    /** 配置值面比对（同上）。 */
    private static boolean configFacetsMatch(ModelConfig current, ModelConfig next) {
        return Objects.equals(current.providerKey(), next.providerKey())
                && Objects.equals(current.modelName(), next.modelName())
                && Objects.equals(current.displayName(), next.displayName())
                && current.enabled() == next.enabled()
                && current.thinkingEnabled() == next.thinkingEnabled();
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

    // ---------- 候选队列共用机制（#559/T5） ----------

    /**
     * 编排基准队列：候选表非空＝本体；空＝配置行现列物化单元素（软着陆对偶腿
     * ——#556 起配置行现列是隐式首选候选，首次编排把它落成显式 priority 1，
     * incumbent 不被新行顶掉、回退腿与显式队列同形）。
     */
    private List<ModelConfigCandidate> effectiveQueue(ModelConfig config) {
        List<ModelConfigCandidate> stored = channelStore.findConfigCandidates(config.configKey());
        if (!stored.isEmpty()) {
            return stored;
        }
        return List.of(new ModelConfigCandidate(config.configKey(), 1, config.providerKey(),
                config.modelName(), true, null, null, null));
    }

    /** 候选身份重复守卫（PRJ_091）：通道×实际模型名＝故障物理单元，队列内唯一。 */
    private static void requireCandidateAbsent(List<ModelConfigCandidate> queue,
            String providerKey, String modelName) {
        boolean present = queue.stream()
                .anyMatch(candidate -> candidate.providerKey().equals(providerKey)
                        && candidate.modelName().equals(modelName));
        if (present) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_CANDIDATE_DUPLICATE,
                    providerKey, modelName);
        }
    }

    /**
     * 队列序校验（PRJ_090）：显式值须在 1..maxAllowed（增＝队尾+1 可取、移动＝
     * 队列长；缺省位由调用方定——增缺省＝追加队尾、改缺省＝原地）。
     */
    private static int requirePriorityWithin(int priority, int maxAllowed) {
        if (priority < 1 || priority > maxAllowed) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_CANDIDATE_PRIORITY_INVALID,
                    String.valueOf(priority), String.valueOf(maxAllowed));
        }
        return priority;
    }

    /** 候选行寻址（PRJ_089）：按队列序取下标；无行＝404。 */
    private static int requireCandidateIndex(List<ModelConfigCandidate> queue, String configKey,
            int priority) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).priority() == priority) {
                return i;
            }
        }
        throw new ApplicationException(ProjectMessage.MODEL_CONFIG_CANDIDATE_NOT_FOUND,
                configKey, String.valueOf(priority));
    }

    /** 重排编号：列表序即队列序（priority 密集 1..n——换位/补位不留空洞）。 */
    private static List<ModelConfigCandidate> renumber(List<ModelConfigCandidate> queue) {
        List<ModelConfigCandidate> renumbered = new ArrayList<>(queue.size());
        for (int i = 0; i < queue.size(); i++) {
            ModelConfigCandidate candidate = queue.get(i);
            renumbered.add(new ModelConfigCandidate(candidate.configKey(), i + 1,
                    candidate.providerKey(), candidate.modelName(), candidate.enabled(),
                    candidate.operatorId(), candidate.operatorName(), null));
        }
        return renumbered;
    }

    /**
     * 队列换装单点：值面无动＝幂等零写入；有动＝整队原子重写＋身份轴留痕（一次
     * 编排可多痕）＋镜像跟随。同事务原子（覆盖与留痕不留中间态）。值面有动的行
     * （直接编排对象＋被顺移补位的行）统一盖本次操作者——「最近写者」与留痕
     * 同口径；未动行保留原写者。并发口径：读-改-写无锁、last-writer-wins——
     * 维护面单操作者节奏（demo 阶段），队列个位数行无热点。
     */
    private void applyQueueChange(ModelConfig config, List<ModelConfigCandidate> oldQueue,
            List<ModelConfigCandidate> newQueue, Operator operator) {
        if (queuesMatch(oldQueue, newQueue)) {
            return;
        }
        List<ModelConfigCandidate> stamped = stampChangedRows(oldQueue, newQueue, operator);
        channelStore.replaceConfigCandidates(config.configKey(), stamped);
        candidateTraces(config.configKey(), oldQueue, stamped, operator)
                .forEach(channelStore::insertConfigCandidateTrace);
        mirrorConfigHead(config, stamped, operator);
    }

    /** 值面有动的行盖本次操作者（未动行原写者保留——含 materialized incumbent）。 */
    private static List<ModelConfigCandidate> stampChangedRows(List<ModelConfigCandidate> oldQueue,
            List<ModelConfigCandidate> newQueue, Operator operator) {
        Map<String, ModelConfigCandidate> oldByIdentity = new LinkedHashMap<>();
        oldQueue.forEach(candidate -> oldByIdentity.put(identityOf(candidate), candidate));
        return newQueue.stream().map(next -> {
            ModelConfigCandidate prev = oldByIdentity.get(identityOf(next));
            if (prev != null && candidateFacetsMatch(prev, next)) {
                return next;
            }
            return new ModelConfigCandidate(next.configKey(), next.priority(), next.providerKey(),
                    next.modelName(), next.enabled(), operator.id(), operator.name(), null);
        }).toList();
    }

    /** 队列值面比对（写者/时刻不入比——幂等不落痕不写行）。 */
    private static boolean queuesMatch(List<ModelConfigCandidate> oldQueue,
            List<ModelConfigCandidate> newQueue) {
        if (oldQueue.size() != newQueue.size()) {
            return false;
        }
        for (int i = 0; i < oldQueue.size(); i++) {
            if (!candidateFacetsMatch(oldQueue.get(i), newQueue.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 身份轴留痕 diff：候选身份（通道×实际模型名，队列内唯一）为轴比对前后队列
     * ——新增痕 old 全 null、删除痕 new 全 null、移动/启停/换指向＝同身份前后
     * 快照（priority 变化即排序事实）。时刻腿＝同一基准逐痕单调加 1µs（显式落
     * created_at——多痕若共享事务时间戳，次序腿只剩 TSID 而其跨调用非单调，
     * 读面次序会随机；纳秒步进会被 Postgres 微秒精度截平，µs 步进保证「同编排
     * 内＝生成序的逆」确定可读）。回滚＝按痕旧值写回（重排/重建＝一次新编排
     * 留新痕）。
     */
    private static List<ModelConfigCandidateTrace> candidateTraces(String configKey,
            List<ModelConfigCandidate> oldQueue, List<ModelConfigCandidate> newQueue,
            Operator operator) {
        Map<String, ModelConfigCandidate> oldByIdentity = new LinkedHashMap<>();
        oldQueue.forEach(candidate -> oldByIdentity.put(identityOf(candidate), candidate));
        TsidGenerator tsid = TsidGenerator.newInstance();
        LocalDateTime base = LocalDateTime.now();
        List<ModelConfigCandidateTrace> traces = new ArrayList<>();
        for (ModelConfigCandidate next : newQueue) {
            ModelConfigCandidate prev = oldByIdentity.remove(identityOf(next));
            if (prev == null || !candidateFacetsMatch(prev, next)) {
                traces.add(candidateTrace(tsid.generate(),
                        base.plusNanos(traces.size() * 1_000L), configKey, prev, next, operator));
            }
        }
        for (ModelConfigCandidate prev : oldQueue) {
            if (oldByIdentity.remove(identityOf(prev)) != null) {
                traces.add(candidateTrace(tsid.generate(),
                        base.plusNanos(traces.size() * 1_000L), configKey, prev, null, operator));
            }
        }
        return traces;
    }

    /** 候选留痕构造单点（old/new 任一 null＝新增/删除痕：该侧快照腿全 null）。 */
    private static ModelConfigCandidateTrace candidateTrace(long id, LocalDateTime createdAt,
            String configKey, ModelConfigCandidate oldCandidate, ModelConfigCandidate newCandidate,
            Operator operator) {
        return new ModelConfigCandidateTrace(id, configKey,
                oldCandidate == null ? null : oldCandidate.priority(),
                oldCandidate == null ? null : oldCandidate.providerKey(),
                oldCandidate == null ? null : oldCandidate.modelName(),
                oldCandidate == null ? null : oldCandidate.enabled(),
                newCandidate == null ? null : newCandidate.priority(),
                newCandidate == null ? null : newCandidate.providerKey(),
                newCandidate == null ? null : newCandidate.modelName(),
                newCandidate == null ? null : newCandidate.enabled(),
                operator.id(), operator.name(), createdAt);
    }

    /** 候选身份键（通道×实际模型名；队列内唯一由写面守卫保证）。 */
    private static String identityOf(ModelConfigCandidate candidate) {
        return candidate.providerKey() + '\u0000' + candidate.modelName();
    }

    /** 候选值面比对（队列序/启停/指向全比——写者/时刻不入比）。 */
    private static boolean candidateFacetsMatch(ModelConfigCandidate current,
            ModelConfigCandidate next) {
        return current.priority() == next.priority()
                && current.enabled() == next.enabled()
                && current.providerKey().equals(next.providerKey())
                && current.modelName().equals(next.modelName());
    }

    /**
     * 镜像跟随（#556 不变式的对偶腿）：配置行现列恒＝队列头（priority 1）——
     * 候选编排动了头即跟写配置行指向。不落配置痕：候选痕即本次编排的审计，
     * 配置行现列是队列头的派生面（软着陆回退腿），双痕反而口径分裂。
     */
    private void mirrorConfigHead(ModelConfig config, List<ModelConfigCandidate> newQueue,
            Operator operator) {
        ModelConfigCandidate head = newQueue.get(0);
        if (head.providerKey().equals(config.providerKey())
                && head.modelName().equals(config.modelName())) {
            return;
        }
        channelStore.saveConfig(new ModelConfig(config.configKey(), head.providerKey(),
                head.modelName(), config.displayName(), config.enabled(),
                config.thinkingEnabled(), operator.id(), operator.name(), null));
    }

    /** baseUrl 必填守卫（创建与更新同拦——维护面无部分更新语义）。 */
    private static String requireBaseUrl(String baseUrl) {
        String value = blankToNull(baseUrl);
        if (value == null) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_FIELDS_REQUIRED);
        }
        return value;
    }

    /**
     * vendor 受控值校验（#559/T5，ADR-0053「受控下拉＋可新建」）：小写字母/数字/
     * 连字符、1-50 位、字母数字起止——供应商定价与按模型统计挂 vendor（错字即
     * 单价 miss/统计分裂，防脏即全部校验面；新建值即下拉新选项，无独立供应商
     * 档案页）。不归一大小写（DeepSeek 直接拒——静默归一会把错值落成「看起来对」）。
     */
    private static String requireVendorFormat(String vendor) {
        if (!VENDOR_PATTERN.matcher(vendor).matches()) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_VENDOR_INVALID, vendor);
        }
        return vendor;
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

    /** 配置行寻址：无行 404 PRJ_066。 */
    private ModelConfig requireConfig(String configKey) {
        ModelConfig config = blankToNull(configKey) == null ? null : channelStore.findConfig(configKey);
        if (config == null) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_ROW_NOT_FOUND);
        }
        return config;
    }

    /** 配置所属通道存在守卫（FK 裸错的读面化，PRJ_071）。 */
    private void requireProviderExists(String providerKey) {
        if (channelStore.findProvider(providerKey) == null) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_PROVIDER_UNKNOWN, providerKey);
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
