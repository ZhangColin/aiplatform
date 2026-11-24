package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeAgentToolResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeAgentToolSlotResponse;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.AgentTool;
import com.semgravio.aiplatform.business.project.domain.model.AgentToolKind;

/**
 * 工具面清单读面（#252，ADR-0021 工具面可观测）：按职能槽位列当前挂载工具——
 * 排障/审计面，呈现「智能体到底有什么工具面」。<b>#604 拆 run 尝试体旧机制</b>：
 * executor 槽与 subagent 槽随旧执行协议/子智能体委派一并退役；<b>#610 设计线
 * 整条退役</b>：designer 槽随设计执行体一并拆除——现存槽位＝main（主智能体）。
 * 生成/更新 run 经内核装配形态派发，工具面＝恰四件经 {@code KernelTools} 结构性
 * 兑现，不进本清单。
 *
 * <p>呈现源单源引用：平台资产（骨架/增强）＝{@link AgentTool} 枚举（与装配注册
 * 同源，一致性由装配面测试钉死）；增强件挂载态＝运营配置生效开关
 * （{@link AgentConfigAppService} 解析单点——关＝在册但退出装配面）。</p>
 *
 * <p>开关写口在 {@link BackofficeAgentConfigAppService#toggleTool}（共表共留痕机制）。</p>
 */
@Service
public class BackofficeAgentToolAppService {

    private final AgentConfigAppService agentConfigs;

    public BackofficeAgentToolAppService(AgentConfigAppService agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    /** 工具面清单（main 槽，平台资产在前）。 */
    @Transactional(readOnly = true)
    public List<BackofficeAgentToolSlotResponse> inventory() {
        AgentConfigAppService.EffectiveConfig main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        return List.of(slotOf("main", AgentProfile.MAIN.getName(), main));
    }

    /**
     * 平台智能体槽位：枚举平台资产（增强按生效开关标挂载态）。
     */
    private static BackofficeAgentToolSlotResponse slotOf(String slot, String slotName,
            AgentConfigAppService.EffectiveConfig toggles) {
        List<BackofficeAgentToolResponse> tools = new ArrayList<>();
        for (AgentTool tool : AgentTool.ofSlot(slot)) {
            tools.add(BackofficeAgentToolResponse.of(tool.toolName(), tool.kind(),
                    mounted(tool, toggles), tool.description()));
        }
        return new BackofficeAgentToolSlotResponse(slot, slotName, List.copyOf(tools));
    }

    /** 挂载态：骨架恒挂载；增强按生效开关（无开关语境的槽恒挂载——缺省开）。 */
    private static boolean mounted(AgentTool tool, AgentConfigAppService.EffectiveConfig toggles) {
        if (tool.kind() != AgentToolKind.ENHANCEMENT || toggles == null) {
            return true;
        }
        return tool == AgentTool.WEB_SEARCH ? toggles.webSearchEnabled()
                : toggles.fetchUrlEnabled();
    }
}
