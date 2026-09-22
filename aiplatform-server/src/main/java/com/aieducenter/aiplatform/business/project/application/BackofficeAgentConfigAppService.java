package com.aieducenter.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.project.application.dto.command.AgentConfigUpdateCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeAgentConfigResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeAgentConfigTraceResponse;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.AgentConfigTrace;
import com.aieducenter.aiplatform.business.project.domain.model.AgentOperationalConfig;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.Operator;
import com.aieducenter.aiplatform.business.project.domain.repository.AgentConfigStore;

/**
 * 后台智能体配置用例（#251，ADR-0021 身份与配置分治）：两座智能体（main/
 * executor——{@link AgentProfile#byKey} 单点寻址，classify/naming 等一次性判定
 * 不在配置面）的覆盖态读／全量写／变更留痕读。装配读腿（库值优先、缺省回落）
 * 在 {@link AgentConfigAppService}（运行面），本服务只做后台管理面。
 *
 * <p>写语义＝PUT 全量替换：请求体即覆盖态终态，值面四件（systemPrompt、模型
 * 档位、两工具开关）同写；null/空白＝清空覆盖回落枚举默认。变更留痕
 * append-only（操作者＋变更前后全量值快照）：值面有动才落痕——幂等回执（同值
 * 重写）不落痕不写行（留痕链每痕都是真实变更，回滚才可逐痕回溯）；回滚＝把留痕
 * 旧值快照写回，即一次新变更、留新痕（不做版本树）。工具开关两件是存储面先行
 * （装配生效属 #252，与本配置共表共留痕机制）。事务：覆盖写入与留痕追加同事务
 * 原子（见 {@link AgentConfigStore}）。</p>
 */
@Service
public class BackofficeAgentConfigAppService {

    /** 空命令归一（体缺落 null 形——字段全缺省即「全清空回落＋开关全开」）。 */
    private static final AgentConfigUpdateCommand EMPTY_COMMAND =
            new AgentConfigUpdateCommand(null, null, null, null);

    private final AgentConfigStore configStore;

    public BackofficeAgentConfigAppService(AgentConfigStore configStore) {
        this.configStore = configStore;
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
        AgentOperationalConfig current = currentOf(profile);
        AgentOperationalConfig next = new AgentOperationalConfig(profile.key(),
                blankToNull(safe.systemPrompt()), blankToNull(safe.modelId()),
                safe.webSearchEnabled() == null || safe.webSearchEnabled(),
                safe.fetchUrlEnabled() == null || safe.fetchUrlEnabled(),
                operator.id(), operator.name(), null);
        if (!valueFacetsChanged(current, next)) {
            return BackofficeAgentConfigResponse.of(profile, current); // 幂等回执：值面无动零写入
        }
        configStore.save(next);
        configStore.insertTrace(new AgentConfigTrace(TsidGenerator.newInstance().generate(),
                profile.key(), current.systemPrompt(), current.modelId(),
                current.webSearchEnabled(), current.fetchUrlEnabled(),
                next.systemPrompt(), next.modelId(), next.webSearchEnabled(),
                next.fetchUrlEnabled(), operator.id(), operator.name(), LocalDateTime.now()));
        return BackofficeAgentConfigResponse.of(profile, configStore.find(profile.key()));
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

    /** 现行覆盖态（无行＝全回落缺省态：值面缺省、无写者——留痕前态与比对基准）。 */
    private AgentOperationalConfig currentOf(AgentProfile profile) {
        AgentOperationalConfig override = configStore.find(profile.key());
        return override != null ? override : AgentOperationalConfig.defaults(profile.key());
    }

    /** 值面比对（写者/时刻不入比——值面无动即幂等，操作者列不因空写漂移）。 */
    private static boolean valueFacetsChanged(AgentOperationalConfig current,
            AgentOperationalConfig next) {
        return !Objects.equals(current.systemPrompt(), next.systemPrompt())
                || !Objects.equals(current.modelId(), next.modelId())
                || current.webSearchEnabled() != next.webSearchEnabled()
                || current.fetchUrlEnabled() != next.fetchUrlEnabled();
    }

    /** 配置面智能体寻址（单点）：{@link AgentProfile#byKey} 解析，未知/空键 404。 */
    private static AgentProfile requireProfile(String agentKey) {
        return AgentProfile.byKey(agentKey)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.AGENT_CONFIG_NOT_FOUND));
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
