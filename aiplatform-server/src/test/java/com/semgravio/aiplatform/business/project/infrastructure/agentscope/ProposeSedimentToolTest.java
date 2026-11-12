package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftReceipt;
import com.semgravio.aiplatform.business.project.infrastructure.SedimentProposalAdapter;

/**
 * {@link ProposeSedimentTool}：沉淀自荐工具的输入面与回执面（#460 工具缝——纯
 * JUnit，落库经适配器替身；全判定链在 {@code SedimentDraftAppServiceTest}）：
 * 入参契约（kind/title/summary/content 四必传）；校验先于落库（违例回执原因、
 * 适配器不被触）；run 标识从 RuntimeContext 每调用提取（实例缓存跨 run 复用，
 * 血统不能固化在构造态）；拒绝回执原因透传（扫描/在途上限，模型可读可修正重提）；
 * 成功回执留档语义。
 */
class ProposeSedimentToolTest {

    /** 适配器替身：记录调用形状，可编程回执（SedimentProposalAdapter 为具体类，子类覆写）。 */
    private static final class RecordingAdapter extends SedimentProposalAdapter {
        final List<String> calls = new ArrayList<>();
        SedimentDraftReceipt next = SedimentDraftReceipt.accepted(123L, "沉淀草稿已留档待审。");

        RecordingAdapter() {
            super(null, null, null);
        }

        @Override
        public SedimentDraftReceipt propose(String workspaceId, String runId, String slotKey,
                String kind, String title, String summary, String content) {
            calls.add(workspaceId + "|" + runId + "|" + slotKey + "|" + kind + "|" + title);
            return next;
        }
    }

    private final RecordingAdapter adapter = new RecordingAdapter();
    private final ProposeSedimentTool tool = new ProposeSedimentTool("42", "executor", adapter);

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("propose_sediment");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        // 四必传：kind/title/summary/content
        assertThat(String.valueOf(tool.getParameters().get("required")))
                .contains("kind").contains("title").contains("summary").contains("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getParameters().get("properties");
        assertThat(properties).containsOnlyKeys("kind", "title", "summary", "content");
        assertThat(tool.isReadOnly()).isFalse(); // 写平台库（采集层草稿表）
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        // 自荐是软指引动作非破坏门：写入有扫描哨兵＋人审门，工具点不放确认
        PermissionDecision decision = tool
                .checkPermissions(Map.of("kind", "CASE"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_valid_input_when_called_then_adapter_called_with_lineage() {
        ToolResultBlock result = call("CASE", "排课冲突", "K12 机构排课的核心冲突场景。",
                "正文。", "run-9");

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("留档");
        // 血统腿：工作区（构造态）＋ run 标识（每调用提取）＋ 槽位（构造态）
        assertThat(adapter.calls).containsExactly("42|run-9|executor|CASE|排课冲突");
    }

    @Test
    void given_blank_field_when_called_then_error_without_adapter_call() {
        assertThat(call(null, "标题", "摘要", "正文", "run-9").getState())
                .isEqualTo(ToolResultState.ERROR);
        assertThat(call("CASE", " ", "摘要", "正文", "run-9").getState())
                .isEqualTo(ToolResultState.ERROR);
        assertThat(call("CASE", "标题", " ", "正文", "run-9").getState())
                .isEqualTo(ToolResultState.ERROR);
        assertThat(call("CASE", "标题", "摘要", null, "run-9").getState())
                .isEqualTo(ToolResultState.ERROR);
        assertThat(adapter.calls).isEmpty();
    }

    @Test
    void given_invalid_kind_when_called_then_validation_rejects_before_adapter() {
        // 校验正本共享（SedimentDraftProposal.violation）在工具面先行——违例不触落库
        ToolResultBlock unknown = call("PRD", "标题", "摘要", "正文", "run-9");
        ToolResultBlock overlong = call("CASE", "标题", "s".repeat(1025), "正文", "run-9");

        assertThat(unknown.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(unknown)).contains("kind");
        assertThat(overlong.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(overlong)).contains("summary");
        assertThat(adapter.calls).isEmpty();
    }

    @Test
    void given_missing_run_context_when_called_then_error_without_adapter_call() {
        ToolResultBlock noContext = callVia(
                Map.of("kind", "CASE", "title", "t", "summary", "s", "content", "c"), null);
        ToolResultBlock noKey = callVia(
                Map.of("kind", "CASE", "title", "t", "summary", "s", "content", "c"),
                RuntimeContext.builder().sessionId("s").userId("u").build());

        assertThat(noContext.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(noKey.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(adapter.calls).isEmpty();
    }

    @Test
    void given_adapter_rejection_when_called_then_reason_passed_through() {
        adapter.next = SedimentDraftReceipt.rejected(
                "该项目该类别的在途沉淀草稿已满 3 条——本条未写入。");

        ToolResultBlock result = call("CASE", "标题", "摘要", "正文", "run-9");

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("在途").contains("3");
    }

    @Test
    void given_dangerous_scan_rejection_when_called_then_receipt_visible() {
        adapter.next = SedimentDraftReceipt.rejected("安全扫描判定 DANGEROUS，拒写不入库。");

        ToolResultBlock result = call("MODULE", "标题", "摘要", "正文", "run-9");

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("DANGEROUS");
    }

    // ---------- 内部 ----------

    private ToolResultBlock call(String kind, String title, String summary, String content,
            String runId) {
        Map<String, Object> input = new HashMap<>();
        if (kind != null) {
            input.put("kind", kind);
        }
        if (title != null) {
            input.put("title", title);
        }
        if (summary != null) {
            input.put("summary", summary);
        }
        if (content != null) {
            input.put("content", content);
        }
        return callVia(input, runId == null ? null : RuntimeContext.builder()
                .sessionId("session-1").userId("u-1")
                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, runId)
                .build());
    }

    private ToolResultBlock callVia(Map<String, Object> input, RuntimeContext ctx) {
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("tc-1", ProposeSedimentTool.NAME, input, null))
                .input(input)
                .runtimeContext(ctx)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
