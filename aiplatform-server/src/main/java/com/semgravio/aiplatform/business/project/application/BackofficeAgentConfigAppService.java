package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.base.agentscope.HarnessBuiltinTools;
import com.semgravio.aiplatform.business.project.application.dto.command.AgentConfigUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.EdgeModelUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeAgentConfigResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeAgentConfigTraceResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeEdgeModelConfigResponse;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.AgentConfigTrace;
import com.semgravio.aiplatform.business.project.domain.model.AgentEdgePoint;
import com.semgravio.aiplatform.business.project.domain.model.AgentOperationalConfig;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.AgentTool;
import com.semgravio.aiplatform.business.project.domain.model.AgentToolKind;
import com.semgravio.aiplatform.business.project.domain.model.ModelTier;
import com.semgravio.aiplatform.business.project.domain.model.Operator;
import com.semgravio.aiplatform.business.project.domain.repository.AgentConfigStore;
import com.semgravio.aiplatform.business.project.domain.repository.ModelChannelStore;

/**
 * 后台智能体配置用例（#251，ADR-0021 身份与配置分治）：三座智能体（main/
 * executor/designer——{@link AgentProfile#byKey} 单点寻址）的覆盖态读／全量写／
 * 变更留痕读，＋边缘调用点四件（取名/压缩/分类/预检，#366 同表收编——值面仅
 * 模型档位，{@link AgentEdgePoint#byKey} 寻址）的档位读／写／留痕读。装配读腿
 * （库值优先、缺省回落）在 {@link AgentConfigAppService}（运行面），本服务只做
 * 后台管理面。
 *
 * <p>写语义＝PUT 全量替换：请求体即覆盖态终态，值面八件（systemPrompt、模型
 * 档位、两工具开关、路由策略四件〔#354〕）同写；null/空白＝清空覆盖回落枚举
 * 默认。改档（modelId 与路由强档、边缘点档位）即时校验档位键存在且启用
 * （#367，ADR-0031——错值写不进去，不再靠留痕回滚兜底）。变更留痕
 * append-only（操作者＋变更前后全量值快照）：值面有动才落痕——幂等回执（同值
 * 重写）不落痕不写行（留痕链每痕都是真实变更，回滚才可逐痕回溯）；回滚＝把留痕
 * 旧值快照写回，即一次新变更、留新痕（不做版本树）。工具开关已生效装配（#252
 * {@link #toggleTool}：关即退出槽位装配面、开即回归——与本配置共表共留痕机制，
 * 增强工具窄幅写口）。边缘点写同构（{@link #updateEdgeModel} 窄值面）。事务：
 * 覆盖写入与留痕追加同事务原子（见 {@link AgentConfigStore}）。</p>
 */
@Service
public class BackofficeAgentConfigAppService {

    /** 空命令归一（体缺落 null 形——字段全缺省即「全清空回落＋开关全开〔路由开〕」）。 */
    private static final AgentConfigUpdateCommand EMPTY_COMMAND =
            new AgentConfigUpdateCommand(null, null, null, null, null, null, null, null);

    /** 边缘点空命令归一（体缺落 null 形＝清空档位覆盖）。 */
    private static final EdgeModelUpdateCommand EMPTY_EDGE_COMMAND =
            new EdgeModelUpdateCommand(null);

    private final AgentConfigStore configStore;

    private final ModelChannelStore channelStore;

    public BackofficeAgentConfigAppService(AgentConfigStore configStore,
            ModelChannelStore channelStore) {
        this.configStore = configStore;
        this.channelStore = channelStore;
    }

    /**
     * 配置读面（GET）：生效值（库值或枚举默认）＋覆盖标记＋枚举默认预览＋工具
     * 开关存储态＋最近写者。无覆盖行＝全回落（覆盖标记 false、写者 null）。
     *
     * @throws ApplicationException PRJ_033 智能体不存在（未知/空键——配置面只有
     *                              main/executor 两座）
     */
    @Transactional(readOnly = true)
    public BackofficeAgentConfigResponse config(String agentKey) {
        AgentProfile profile = requireProfile(agentKey);
        return BackofficeAgentConfigResponse.of(profile, configStore.find(profile.key()));
    }

    /**
     * 配置全量写（PUT）：命令归一（覆盖两件空白＝null 清空、开关两件缺省 true）
     * → 与现行值面比对：无动即幂等回执（不落痕不写行）→ 有动即整行 upsert＋
     * 留痕（变更前全量快照→变更后全量快照＋操作者）同事务落。回执与 GET 同形。
     *
     * @throws ApplicationException PRJ_033 智能体不存在；PRJ_034 操作者缺（写
     *                              操作必留痕，无落空通道）
     */
    @Transactional
    public BackofficeAgentConfigResponse update(String agentKey, AgentConfigUpdateCommand command,
            Operator operator) {
        AgentProfile profile = requireProfile(agentKey);
        requireOperator(operator);
        AgentConfigUpdateCommand safe = command == null ? EMPTY_COMMAND : command;
        AgentOperationalConfig current = currentOf(profile.key());
        AgentOperationalConfig next = new AgentOperationalConfig(profile.key(),
                blankToNull(safe.systemPrompt()), blankToNull(safe.modelId()),
                safe.webSearchEnabled() == null || safe.webSearchEnabled(),
                safe.fetchUrlEnabled() == null || safe.fetchUrlEnabled(),
                safe.routingEnabled() == null || safe.routingEnabled(),
                blankToNull(safe.routingStrongModelId()),
                safe.routingFileCountThreshold(), safe.routingSliceFeatureThreshold(),
                operator.id(), operator.name(), null);
        // 绑定即时校验（#367，ADR-0031）：改档写的档位键必须存在且启用——错值
        // 写不进去（不再靠留痕回滚兜底）；清空（null＝回落缺省）不校验（缺省归
        // 枚举/代码正本，装配面自会校验）
        requireBindableTier(next.modelId());
        requireBindableTier(next.routingStrongModelId());
        AgentOperationalConfig persisted =
                persistIfChanged(profile.key(), current, next, operator);
        return BackofficeAgentConfigResponse.of(profile,
                persisted != null ? persisted : current); // 幂等回执：值面无动零写入
    }

    /**
     * 变更留痕读面（GET traces）：该智能体全部留痕按时间倒序（最近先）。
     *
     * @throws ApplicationException PRJ_033 智能体不存在
     */
    @Transactional(readOnly = true)
    public List<BackofficeAgentConfigTraceResponse> traces(String agentKey) {
        AgentProfile profile = requireProfile(agentKey);
        return configStore.findTraces(profile.key()).stream()
                .map(BackofficeAgentConfigTraceResponse::of).toList();
    }

    /**
     * 工具面开关写口（#252，PUT /agent-tools/{toolName}）：按工具注册名窄幅开关——
     * 仅增强工具（web_search/fetch_url，挂 {@link AgentProfile#MAIN main} 行开关列，
     * 与配置全量写共表共留痕机制）；骨架工具与 harness 内建编码工具结构性锁死
     * （接口层拒绝）。写语义与全量写同构：只动目标开关列、其余值面原样携带，值面
     * 有动才落痕（幂等回执零写入）。
     *
     * @throws ApplicationException PRJ_035 工具不存在（平台工具与 harness 内建面之外）；
     *                              PRJ_036 骨架/harness 内建不开放开关；
     *                              PRJ_037 目标态缺（enabled 无缺省语义）；
     *                              PRJ_034 操作者缺
     */
    @Transactional
    public ToolToggle toggleTool(String toolName, Boolean enabled, Operator operator) {
        requireOperator(operator);
        if (enabled == null) {
            throw new ApplicationException(ProjectMessage.AGENT_TOOL_TOGGLE_TARGET_REQUIRED);
        }
        AgentTool tool = AgentTool.byName(toolName)
                .orElseThrow(() -> harnessBuiltinOrNotFound(toolName));
        if (tool.kind() != AgentToolKind.ENHANCEMENT) {
            throw new ApplicationException(ProjectMessage.AGENT_TOOL_TOGGLE_FORBIDDEN);
        }
        AgentProfile profile = AgentProfile.byKey(tool.slot()).orElseThrow();
        AgentOperationalConfig current = currentOf(profile.key());
        AgentOperationalConfig next = new AgentOperationalConfig(profile.key(),
                current.systemPrompt(), current.modelId(),
                tool == AgentTool.WEB_SEARCH ? enabled : current.webSearchEnabled(),
                tool == AgentTool.FETCH_URL ? enabled : current.fetchUrlEnabled(),
                current.routingEnabled(), current.routingStrongModelId(),
                current.routingFileCountThreshold(), current.routingSliceFeatureThreshold(),
                operator.id(), operator.name(), null);
        persistIfChanged(profile.key(), current, next, operator); // 幂等（开关已是目标态）零写入
        return new ToolToggle(tool, enabled);
    }

    /** 开关回执（工具＋写定生效态——幂等重写时即现行态）。 */
    public record ToolToggle(AgentTool tool, boolean enabled) {
    }

    // ---------- 边缘调用点（#366：值面仅模型档位，同表同留痕机制） ----------

    /**
     * 边缘调用点档位读面（GET）：生效值（库值或代码缺省档位键）＋覆盖标记＋缺省
     * 预览＋最近写者。无覆盖行＝回落代码缺省（覆盖标记 false、写者 null）。
     *
     * @throws ApplicationException PRJ_064 边缘调用点不存在（档位面只有四点）
     */
    @Transactional(readOnly = true)
    public BackofficeEdgeModelConfigResponse edgeConfig(String edgeKey) {
        AgentEdgePoint point = requireEdgePoint(edgeKey);
        return BackofficeEdgeModelConfigResponse.of(point, configStore.find(point.key()));
    }

    /**
     * 边缘调用点档位写（PUT）：值面仅模型档位（窄命令形——边缘点无 prompt/开关/
     * 路由语义），写语义与全量写同构（null/纯空白＝清空覆盖回落代码缺省；值面有
     * 动才落痕，幂等回执零写入）。装配腿 {@code AgentConfigAppService
     * #effectiveEdgeModelId} 动态查库——改档下一轮命令构建即生效。
     *
     * @throws ApplicationException PRJ_064 边缘调用点不存在；PRJ_034 操作者缺
     */
    @Transactional
    public BackofficeEdgeModelConfigResponse updateEdgeModel(String edgeKey,
            EdgeModelUpdateCommand command, Operator operator) {
        AgentEdgePoint point = requireEdgePoint(edgeKey);
        requireOperator(operator);
        EdgeModelUpdateCommand safe = command == null ? EMPTY_EDGE_COMMAND : command;
        AgentOperationalConfig current = currentOf(point.key());
        // 值面仅档位：其余覆盖列结构性缺省（prompt null、开关开、路由缺省态）
        AgentOperationalConfig next = new AgentOperationalConfig(point.key(),
                null, blankToNull(safe.modelId()),
                true, true, true, null, null, null,
                operator.id(), operator.name(), null);
        requireBindableTier(next.modelId()); // 绑定即时校验同三座（#367）
        AgentOperationalConfig persisted =
                persistIfChanged(point.key(), current, next, operator);
        return BackofficeEdgeModelConfigResponse.of(point,
                persisted != null ? persisted : current); // 幂等回执：值面无动零写入
    }

    /**
     * 边缘调用点留痕读面（GET traces）：该点全部留痕按时间倒序（快照列与三座同形
     * ——边缘行档位列之外的快照件恒缺省态）。
     *
     * @throws ApplicationException PRJ_064 边缘调用点不存在
     */
    @Transactional(readOnly = true)
    public List<BackofficeAgentConfigTraceResponse> edgeTraces(String edgeKey) {
        AgentEdgePoint point = requireEdgePoint(edgeKey);
        return configStore.findTraces(point.key()).stream()
                .map(BackofficeAgentConfigTraceResponse::of).toList();
    }

    /** 边缘点寻址（单点）：{@link AgentEdgePoint#byKey} 解析，未知/空键 404。 */
    private static AgentEdgePoint requireEdgePoint(String edgeKey) {
        return AgentEdgePoint.byKey(edgeKey)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.EDGE_POINT_NOT_FOUND));
    }

    /**
     * harness 内建名判别腿（骨架锁死的前置）：平台枚举之外的注册名若属 harness
     * 内建编码工具集 → PRJ_036 同锁死语义；两者皆非 → PRJ_035 不存在。
     */
    private static ApplicationException harnessBuiltinOrNotFound(String toolName) {
        if (HarnessBuiltinTools.codingToolNames().contains(toolName)) {
            return new ApplicationException(ProjectMessage.AGENT_TOOL_TOGGLE_FORBIDDEN);
        }
        return new ApplicationException(ProjectMessage.AGENT_TOOL_NOT_FOUND);
    }

    /**
     * 值面变更落库＋留痕（全量写、工具开关写与边缘档位写的共用写路径，同事务原子）：
     * 值面无动返回 null（幂等回执零写入）；有动即整行 upsert＋留痕（变更前后全量值
     * 快照＋操作者）并回读新现行。
     */
    private AgentOperationalConfig persistIfChanged(String agentKey,
            AgentOperationalConfig current, AgentOperationalConfig next, Operator operator) {
        if (!valueFacetsChanged(current, next)) {
            return null;
        }
        configStore.save(next);
        configStore.insertTrace(new AgentConfigTrace(TsidGenerator.newInstance().generate(),
                agentKey, current.systemPrompt(), current.modelId(),
                current.webSearchEnabled(), current.fetchUrlEnabled(),
                current.routingEnabled(), current.routingStrongModelId(),
                current.routingFileCountThreshold(), current.routingSliceFeatureThreshold(),
                next.systemPrompt(), next.modelId(), next.webSearchEnabled(),
                next.fetchUrlEnabled(), next.routingEnabled(), next.routingStrongModelId(),
                next.routingFileCountThreshold(), next.routingSliceFeatureThreshold(),
                operator.id(), operator.name(), LocalDateTime.now()));
        return configStore.find(agentKey);
    }

    /** 现行覆盖态（无行＝全回落缺省态：值面缺省、无写者——留痕前态与比对基准）。 */
    private AgentOperationalConfig currentOf(String agentKey) {
        AgentOperationalConfig override = configStore.find(agentKey);
        return override != null ? override : AgentOperationalConfig.defaults(agentKey);
    }

    /** 值面比对（写者/时刻不入比——值面无动即幂等，操作者列不因空写漂移）。 */
    private static boolean valueFacetsChanged(AgentOperationalConfig current,
            AgentOperationalConfig next) {
        return !Objects.equals(current.systemPrompt(), next.systemPrompt())
                || !Objects.equals(current.modelId(), next.modelId())
                || current.webSearchEnabled() != next.webSearchEnabled()
                || current.fetchUrlEnabled() != next.fetchUrlEnabled()
                || current.routingEnabled() != next.routingEnabled()
                || !Objects.equals(current.routingStrongModelId(), next.routingStrongModelId())
                || !Objects.equals(current.routingFileCountThreshold(), next.routingFileCountThreshold())
                || !Objects.equals(current.routingSliceFeatureThreshold(), next.routingSliceFeatureThreshold());
    }

    /** 配置面智能体寻址（单点）：{@link AgentProfile#byKey} 解析，未知/空键 404。 */
    private static AgentProfile requireProfile(String agentKey) {
        return AgentProfile.byKey(agentKey)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.AGENT_CONFIG_NOT_FOUND));
    }

    /**
     * 绑定即时校验（#367，ADR-0031——「错值靠留痕回滚兜底」的退役面）：非空档位键
     * 必须在档位表有行且启用——白名单从档位表来（替代硬编码集合）。缺行 PRJ_074、
     * 停用 PRJ_075；null（清空回落缺省）放行。
     */
    private void requireBindableTier(String tierKey) {
        if (tierKey == null) {
            return;
        }
        ModelTier tier = channelStore.findTier(tierKey);
        if (tier == null) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_BINDING_NOT_FOUND, tierKey);
        }
        if (!tier.enabled()) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_BINDING_DISABLED, tierKey);
        }
    }

    /** 操作者必留痕（#251 写操作全程）：缺头/缺肢即拦（技能库/知识治理同款）。 */
    private static void requireOperator(Operator operator) {
        if (operator == null || operator.id() == null || operator.name() == null) {
            throw new ApplicationException(ProjectMessage.AGENT_CONFIG_OPERATOR_REQUIRED);
        }
    }

    /** 覆盖值空白归一：null/纯空白 → null（清空回落；首尾空白去除）。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
