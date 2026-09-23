package com.aieducenter.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 智能体运营配置覆盖态（#251，ADR-0021 修订 ADR-0006 边界——身份与配置分治）：
 * {@code prj_agent_configs} 一行的读模型。智能体身份（有哪些智能体、职能、寻址
 * 键）仍以 {@link AgentProfile} 枚举为正本；本配置只是<b>覆盖态</b>——systemPrompt
 * /modelId 列 null＝无覆盖，装配回落枚举默认（「库值优先、缺省回落」的缺省腿）。
 * 增强工具开关两列已生效装配（#252）：默认 true＝开；关＝该工具退出槽位装配面
 * （生效值经 {@code AgentConfigAppService#effectiveOf} 编入工具面规格串随命令透传）。
 *
 * <p>行不存在与「行存在但两覆盖列全 null」语义等价（全回落）；行经 upsert 持续
 * 存在（清空覆盖不删行——操作者列留最近写者，运营可见「谁最后动过」）。</p>
 *
 * @param agentKey         智能体稳定键（{@link AgentProfile#key()}，寻址腿）
 * @param systemPrompt     运营覆盖 systemPrompt（null＝回落枚举默认）
 * @param modelId          运营覆盖模型档位（null＝回落枚举默认；裸档位名——provider
 *                         前缀由 {@link AgentProfile#chatModelStringOf} 统一拼）
 * @param webSearchEnabled 增强工具开关：联网搜索（关即退出槽位装配面，#252）
 * @param fetchUrlEnabled  增强工具开关：网页抓取（同上）
 * @param operatorId       最近写者 id（admin 侧 TSID；null＝从未配置）
 * @param operatorName     最近写者名（直读展示）
 * @param updatedAt        最近写入时刻（读模型列——回读填充；写路径构造时 null，
 *                         落库时刻由库定，同 {@code SkillUpdateTrace#createdAt} 房规）
 */
public record AgentOperationalConfig(
        String agentKey,
        String systemPrompt,
        String modelId,
        boolean webSearchEnabled,
        boolean fetchUrlEnabled,
        String operatorId,
        String operatorName,
        LocalDateTime updatedAt) {

    /**
     * 无覆盖缺省态（行不存在的等价形）：两覆盖列 null、开关 true、无写者——
     * 装配解析与留痕前态共用的归一形（null 行先落此形再走生效拼装）。
     */
    public static AgentOperationalConfig defaults(String agentKey) {
        return new AgentOperationalConfig(agentKey, null, null, true, true, null, null, null);
    }

    /** 生效 systemPrompt：本覆盖非空用覆盖，否则回落缺省参（枚举默认）——单点。 */
    public String effectiveSystemPrompt(String fallback) {
        return systemPrompt != null ? systemPrompt : fallback;
    }

    /** 生效模型档位：本覆盖非空用覆盖，否则回落缺省参（枚举默认）——单点。 */
    public String effectiveModelId(String fallback) {
        return modelId != null ? modelId : fallback;
    }
}
