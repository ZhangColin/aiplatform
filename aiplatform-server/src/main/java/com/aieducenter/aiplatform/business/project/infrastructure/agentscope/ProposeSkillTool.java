package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftProposal;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftReceipt;
import com.aieducenter.aiplatform.business.project.infrastructure.SkillProposalAdapter;

/**
 * 技能自荐工具（run 执行体平台资产，#259 自产线 T1）：把本轮验证过的可复用编码
 * 模式写成技能草稿——入参仅 name/description/body（<b>无 scripts 参数，自产
 * a-only 结构性锁死</b>，ADR-0022），落库经 {@link SkillProposalAdapter} 业务适配
 * （血统＝来源项目／run 标识／来源槽位）。校验正本共享
 * {@link SkillDraftProposal#violation()} 在工具面先行（违例回执原因、不触落库）；
 * 撞名／DANGEROUS 的拒因由域用例回执透传（模型可读可修正重提）；run 失败不回滚
 * 已写入的自荐（工具执行即事实落库）。run 标识从 RuntimeContext <b>每调用</b>提取
 * ——agent 实例缓存跨 run 复用，血统不能固化在构造态
 * （{@link AgentscopeAgentClient#RUN_ID_CONTEXT_KEY} 透传腿）。
 *
 * <p>软指引非硬步骤（ADR-0022）：无模式可沉淀的 run 不调用——协议层一句指引，
 * 工具面不设门槛。无需确认（权限自检恒放行）：写入有静态扫描哨兵＋后台人审门，
 * 且草稿不参与任何装配——未审内容不影响任何 run。</p>
 */
public class ProposeSkillTool extends ToolBase {

    public static final String NAME = "propose_skill";
    private static final String NAME_KEY = "name";
    private static final String DESCRIPTION_KEY = "description";
    private static final String BODY_KEY = "body";

    private final String workspaceId;
    private final SkillSlot slot;
    private final SkillProposalAdapter adapter;

    public ProposeSkillTool(String workspaceId, SkillSlot slot, SkillProposalAdapter adapter) {
        super(ToolBase.builder()
                .name(NAME)
                .description("把本轮验证过的可复用编码模式自荐为技能草稿（供后续项目"
                        + "复用，后台人工审核后才生效）：name 传小写连字符技能名"
                        + "（如 react-form-pattern）；description 传一句话简介"
                        + "（何时用、解决什么）；body 传技能正文（markdown，写清"
                        + "做法与步骤）。仅在本轮确实沉淀出可复用模式时调用——"
                        + "软指引非必做，无模式不调用。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                NAME_KEY, Map.of(
                                        "type", "string",
                                        "description", "技能名：^[a-z0-9][a-z0-9._-]*$、≤64 字符"),
                                DESCRIPTION_KEY, Map.of(
                                        "type", "string",
                                        "description", "一句话简介：何时用、解决什么（≤1024 字符）"),
                                BODY_KEY, Map.of(
                                        "type", "string",
                                        "description", "技能正文 markdown（≤100k 字符）：写清做法与步骤")),
                        "required", List.of(NAME_KEY, DESCRIPTION_KEY, BODY_KEY)))
                .readOnly(false)
                .concurrencySafe(false));
        this.workspaceId = workspaceId;
        this.slot = slot;
        this.adapter = adapter;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 软指引动作非破坏门：静态扫描哨兵＋人审门在域内，工具点不放确认
        return Mono.just(PermissionDecision.allow("自荐写入有扫描与人审门，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String name = stringOf(param, NAME_KEY);
        String description = stringOf(param, DESCRIPTION_KEY);
        String body = stringOf(param, BODY_KEY);
        // 校验正本先行（模型可修正重提；违例不触落库）——静态入口与域用例同源
        var violation = SkillDraftProposal.contentViolation(name, description, body);
        if (violation.isPresent()) {
            return Mono.just(ToolResultBlock.error(violation.get()));
        }
        String runId = runIdOf(param);
        if (runId == null || runId.isBlank()) {
            return Mono.just(ToolResultBlock.error("平台上下文缺失 run 标识，本次自荐"
                    + "未写入——请勿重试本工具，向用户说明即可。"));
        }
        SkillDraftReceipt receipt = adapter.propose(workspaceId, runId, slot,
                name, description, body);
        return receipt.accepted()
                ? Mono.just(ToolResultBlock.text(receipt.message()))
                : Mono.just(ToolResultBlock.error(receipt.message()));
    }

    private static String stringOf(ToolCallParam param, String key) {
        Object value = param.getInput() != null ? param.getInput().get(key) : null;
        return value == null ? null : String.valueOf(value);
    }

    /** run 标识每调用提取（RuntimeContext 属性腿；无上下文＝防御性缺失）。 */
    private static String runIdOf(ToolCallParam param) {
        RuntimeContext ctx = param.getRuntimeContext();
        return ctx == null ? null : ctx.get(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY);
    }
}
