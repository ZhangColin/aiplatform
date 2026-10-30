package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.base.agentscope.HarnessBuiltinTools;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeAgentToolResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeAgentToolSlotResponse;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.AgentTool;
import com.semgravio.aiplatform.business.project.domain.model.AgentToolKind;
import com.semgravio.aiplatform.business.project.infrastructure.agentscope.ProfileSubagentSupplier;

/**
 * 工具面清单读面（#252，ADR-0021 工具面可观测）：按职能槽位列当前挂载工具——
 * 排障/审计面，呈现「智能体到底有什么工具面」。三个呈现源单源引用：
 * <ul>
 *   <li>平台资产（骨架/增强）：{@link AgentTool} 枚举（与装配注册同源，一致性由
 *       装配面测试钉死）；增强件挂载态＝运营配置生效开关（{@link AgentConfigAppService}
 *       解析单点——关＝在册但退出装配面）；</li>
 *   <li>harness 内建编码工具（executor 槽）：{@link HarnessBuiltinTools} 注册自省
 *       （名字跟框架版本走，不手工抄录）；main 槽只读面结构性无（#86 内核文件/shell
 *       已关，不呈现空组）；</li>
 *   <li>子智能体声明工具面（subagent 槽）：{@code ProfileSubagentSupplier#VERIFY_TOOLS}
 *       同源引用（#260 装配一等化——工具面按声明 allowlist 呈现，技能面走 subagent
 *       槽位装配视图）；#352 report_verification 与 #263 propose_skill 平台资产件
 *       经声明接入，类别归 {@link AgentTool} 正本（描述按子级职能语境）。</li>
 * </ul>
 * 开关写口在 {@link BackofficeAgentConfigAppService#toggleTool}（共表共留痕机制）。
 */
@Service
public class BackofficeAgentToolAppService {

    /** subagent 槽呈现描述（verify 声明面——工具面按声明 allowlist 呈现）。 */
    private static final String SUBAGENT_TOOL_DESCRIPTION =
            "verify 子智能体声明工具面（声明 allowlist 收窄，只读交付代码＋三面验证跑命令＋脚本报告写隔离根）";

    /** subagent 槽平台资产件呈现描述（#263 自荐经声明接入——按子级职能语境）。 */
    private static final String SUBAGENT_PROPOSE_SKILL_DESCRIPTION =
            "技能草稿自荐（验证经验沉淀待审——经声明 allowlist 挂子级面，#263 三槽位齐开）";

    /** subagent 槽验证登记件呈现描述（#352 经声明接入——收尾卡验证统计的事实源）。 */
    private static final String SUBAGENT_REPORT_VERIFICATION_DESCRIPTION =
            "验证结果登记（三面逐项结果落平台事实——收尾卡验证统计的事实源，经声明 allowlist 挂子级面）";

    private final AgentConfigAppService agentConfigs;

    public BackofficeAgentToolAppService(AgentConfigAppService agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    /** 四槽位工具面清单（main/executor/designer/subagent，平台资产在前、内建呈现口径在后）。 */
    @Transactional(readOnly = true)
    public List<BackofficeAgentToolSlotResponse> inventory() {
        AgentConfigAppService.EffectiveConfig main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        return List.of(
                slotOf("main", AgentProfile.MAIN.getName(), main),
                slotOf("executor", AgentProfile.EXECUTOR.getName(), null),
                designerSlot(),
                subagentSlot());
    }

    /** 平台智能体槽位：枚举平台资产（增强按生效开关标挂载态）＋harness 内建（executor）。 */
    private static BackofficeAgentToolSlotResponse slotOf(String slot, String slotName,
            AgentConfigAppService.EffectiveConfig toggles) {
        List<BackofficeAgentToolResponse> tools = new ArrayList<>();
        for (AgentTool tool : AgentTool.ofSlot(slot)) {
            tools.add(BackofficeAgentToolResponse.of(tool.toolName(), tool.kind(),
                    mounted(tool, toggles), tool.description()));
        }
        if (toggles == null) { // executor 槽：harness 内建编码工具呈现口径（main 只读面结构性无）
            for (HarnessBuiltinTools.BuiltinTool builtin : HarnessBuiltinTools.codingTools()) {
                tools.add(BackofficeAgentToolResponse.of(builtin.name(),
                        AgentToolKind.HARNESS_BUILTIN, true, builtin.description()));
            }
        }
        return new BackofficeAgentToolSlotResponse(slot, slotName, List.copyOf(tools));
    }

    /**
     * designer 槽（#289 设计执行体，ADR-0025 无 shell）：平台资产（generate_image）
     * ＋ harness 内建<b>文件</b>工具（写文件件——设计稿落工作区；shell 结构性关闭，
     * 呈现面与装配面同源不含 execute）。
     */
    private static BackofficeAgentToolSlotResponse designerSlot() {
        List<BackofficeAgentToolResponse> tools = new ArrayList<>();
        for (AgentTool tool : AgentTool.ofSlot("designer")) {
            tools.add(BackofficeAgentToolResponse.of(tool.toolName(), tool.kind(),
                    true, tool.description()));
        }
        for (HarnessBuiltinTools.BuiltinTool builtin : HarnessBuiltinTools.fileTools()) {
            tools.add(BackofficeAgentToolResponse.of(builtin.name(),
                    AgentToolKind.HARNESS_BUILTIN, true, builtin.description()));
        }
        return new BackofficeAgentToolSlotResponse("designer", AgentProfile.DESIGNER.getName(),
                List.copyOf(tools));
    }

    /** subagent 槽：verify 声明工具面（呈现即声明事实；平台资产件归自身类别正本）。 */
    private static BackofficeAgentToolSlotResponse subagentSlot() {
        List<BackofficeAgentToolResponse> tools = ProfileSubagentSupplier.VERIFY_TOOLS.stream()
                .map(BackofficeAgentToolAppService::subagentToolOf)
                .toList();
        return new BackofficeAgentToolSlotResponse("subagent", "子智能体", tools);
    }

    /**
     * subagent 槽单件呈现：平台资产件（#352 验证登记 / #263 自荐，经声明 allowlist
     * 接入）类别归 {@link AgentTool} 正本（骨架锁死同口径——开关面同样不可及）、
     * 描述按子级职能语境按名分派（勿把专属文案错挂）；其余按 harness 内建呈现口径。
     */
    private static BackofficeAgentToolResponse subagentToolOf(String name) {
        if (AgentTool.PROPOSE_SKILL_MAIN.toolName().equals(name)) {
            return BackofficeAgentToolResponse.of(name, AgentToolKind.SKELETON, true,
                    SUBAGENT_PROPOSE_SKILL_DESCRIPTION);
        }
        if (AgentTool.REPORT_VERIFICATION.toolName().equals(name)) {
            return BackofficeAgentToolResponse.of(name, AgentToolKind.SKELETON, true,
                    SUBAGENT_REPORT_VERIFICATION_DESCRIPTION);
        }
        return BackofficeAgentToolResponse.of(name, AgentToolKind.HARNESS_BUILTIN, true,
                SUBAGENT_TOOL_DESCRIPTION);
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
