package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftProposal;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftReceipt;
import com.semgravio.aiplatform.business.project.infrastructure.SedimentProposalAdapter;

/**
 * 沉淀自荐工具（#460 数据飞轮采集层）：把对话中值得跨项目复用的需求案例（CASE）/
 * 功能模块（MODULE）/业务规则（RULE）写成采集层草稿——入参 kind/title/summary/
 * content 四件（载荷一期不开参数，列随档生长），落库经 {@link SedimentProposalAdapter}
 * 业务适配（血统＝来源项目／run 标识／来源槽位）。校验正本共享
 * {@link SedimentDraftProposal#violation()} 在工具面先行（违例回执原因、不触落库）；
 * 扫描拒／在途上限拒的回执由域用例透传（模型可读可修正重提）；run 标识从
 * RuntimeContext <b>每调用</b>提取——agent 实例缓存跨 run 复用，血统不能固化在
 * 构造态（{@link AgentscopeAgentClient#RUN_ID_CONTEXT_KEY} 透传腿）。
 *
 * <p><b>槽位血统随实例</b>：同一工具类按槽位各构实例（main／executor 装配面直配、
 * subagent 经子键视图挂子级面——同名后写胜出，照 propose_skill #263 先例），
 * 构造态 {@code slotKey} 即草稿来源槽位。</p>
 *
 * <p>软指引非硬步骤：无值得沉淀的内容不调用——协议层一句指引（三处工作协议），
 * 工具面不设门槛；轮收口有「该提未提」轻兜底盘问（记账次轮提醒，不绑时刻）。
 * 无需确认（权限自检恒放行）：写入有静态扫描哨兵＋「数据标注」人审门，且草稿不
 * 参与任何检索——未审内容不影响任何 run。</p>
 */
public class ProposeSedimentTool extends ToolBase {

    public static final String NAME = "propose_sediment";
    private static final String KIND_KEY = "kind";
    private static final String TITLE_KEY = "title";
    private static final String SUMMARY_KEY = "summary";
    private static final String CONTENT_KEY = "content";

    private final String workspaceId;
    private final String slotKey;
    private final SedimentProposalAdapter adapter;

    public ProposeSedimentTool(String workspaceId, String slotKey,
            SedimentProposalAdapter adapter) {
        super(ToolBase.builder()
                .name(NAME)
                .description("把对话中值得跨项目复用的沉淀物自荐为采集层草稿（供后续项目"
                        + "参考，后台「数据标注」人工审核激活后才入知识库）：kind 传"
                        + " CASE（需求案例：某类用户/场景的真实需求形态）/MODULE（功能"
                        + "模块：可复用的功能形态）/RULE（业务规则：某类业务公认的规则"
                        + "口径）；title 传一句话标题；summary 传这是什么、何时复用；"
                        + "content 传沉淀物的完整陈述（markdown）。仅在有真正值得沉淀的"
                        + "内容时调用——软指引非必做，没有就不调用，不为凑数硬写。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                KIND_KEY, Map.of(
                                        "type", "string",
                                        "description", "沉淀物类别：CASE（需求案例）/"
                                                + "MODULE（功能模块）/RULE（业务规则）"),
                                TITLE_KEY, Map.of(
                                        "type", "string",
                                        "description", "一句话标题：沉淀物是什么（≤200 字符）"),
                                SUMMARY_KEY, Map.of(
                                        "type", "string",
                                        "description", "一句话摘要：这是什么、何时复用（≤1024 字符）"),
                                CONTENT_KEY, Map.of(
                                        "type", "string",
                                        "description", "沉淀物完整陈述 markdown（≤100k 字符）")),
                        "required", List.of(KIND_KEY, TITLE_KEY, SUMMARY_KEY, CONTENT_KEY)))
                .readOnly(false)
                .concurrencySafe(false));
        this.workspaceId = workspaceId;
        this.slotKey = slotKey;
        this.adapter = adapter;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 软指引动作非破坏门：扫描哨兵＋人审门在域内，工具点不放确认
        return Mono.just(PermissionDecision.allow("自荐写入有扫描与人审门，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String kind = stringOf(param, KIND_KEY);
        String title = stringOf(param, TITLE_KEY);
        String summary = stringOf(param, SUMMARY_KEY);
        String content = stringOf(param, CONTENT_KEY);
        // 校验正本先行（模型可修正重提；违例不触落库）——静态入口与域用例同源
        var violation = SedimentDraftProposal.contentViolation(kind, title, summary, content);
        if (violation.isPresent()) {
            return Mono.just(ToolResultBlock.error(violation.get()));
        }
        String runId = runIdOf(param);
        if (runId == null || runId.isBlank()) {
            return Mono.just(ToolResultBlock.error("平台上下文缺失 run 标识，本次自荐"
                    + "未写入——请勿重试本工具，向用户说明即可。"));
        }
        SedimentDraftReceipt receipt = adapter.propose(workspaceId, runId, slotKey,
                kind, title, summary, content);
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
