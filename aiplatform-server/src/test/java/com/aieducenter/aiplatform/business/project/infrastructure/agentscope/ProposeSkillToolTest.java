package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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

import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftReceipt;
import com.aieducenter.aiplatform.business.project.infrastructure.SkillProposalAdapter;

/**
 * {@link ProposeSkillTool}：自荐工具的输入面与回执面（#259 工具缝——纯 JUnit，
 * 落库经适配器替身；全判定链在 {@code SkillDraftAppServiceTest}、SQL 真面在 REST
 * 缝）：入参契约（name/description/body 三必传——<b>无 scripts 参数，自产 a-only
 * 结构性锁死</b>）；校验先于落库（违例回执原因、适配器不被触）；run 标识从
 * RuntimeContext 每调用提取（实例缓存跨 run 复用，血统不能固化在构造态）；拒绝
 * 回执原因透传（撞名/扫描，模型可读可修正重提）；成功回执留档语义。
 */
class ProposeSkillToolTest {

    /** 适配器替身：记录调用形状，可编程回执（SkillProposalAdapter 为具体类，子类覆写）。 */
    private static final class RecordingAdapter extends SkillProposalAdapter {
        final List<String> calls = new ArrayList<>();
        SkillDraftReceipt next = SkillDraftReceipt.accepted(123L, "技能草稿已留档待审。");

        RecordingAdapter() {
            super(null, null);
        }

        @Override
        public SkillDraftReceipt propose(String workspaceId, String runId, SkillSlot slot,
                String name, String description, String content) {
            calls.add(workspaceId + "|" + runId + "|" + slot.key() + "|" + name);
            return next;
        }
    }

    private final RecordingAdapter adapter = new RecordingAdapter();
    private final ProposeSkillTool tool = new ProposeSkillTool("42", SkillSlot.EXECUTOR, adapter);

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("propose_skill");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        // 三必传：name/description/body——无 scripts 参数（a-only 结构性锁死）
        assertThat(String.valueOf(tool.getParameters().get("required")))
                .contains("name").contains("description").contains("body");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getParameters().get("properties");
        assertThat(properties).containsOnlyKeys("name", "description", "body");
        assertThat(tool.isReadOnly()).isFalse(); // 写平台库（草稿表）
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        // 自荐是软指引动作非破坏门：写入有扫描哨兵＋人审门，工具点不放确认
        PermissionDecision decision = tool
                .checkPermissions(Map.of("name", "x"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_valid_input_when_called_then_adapter_called_with_lineage() {
        ToolResultBlock result = call("react-form-pattern", "表单校验的稳妥写法。", "正文。",
                "run-9");

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("留档");
        // 血统腿：工作区（构造态）＋ run 标识（每调用提取）＋ 槽位（构造态）
        assertThat(adapter.calls).containsExactly("42|run-9|executor|react-form-pattern");
    }

    @Test
    void given_blank_field_when_called_then_error_without_adapter_call() {
        assertThat(call(null, "desc", "body", "run-9").getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(call("ok-name", " ", "body", "run-9").getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(call("ok-name", "desc", null, "run-9").getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(adapter.calls).isEmpty();
    }

    @Test
    void given_invalid_name_when_called_then_validation_rejects_before_adapter() {
        // 校验正本共享（SkillDraftProposal.violation）在工具面先行——违例不触落库
        ToolResultBlock upper = call("Bad-Name", "desc", "body", "run-9");
        ToolResultBlock overlong = call("ok-name", "d".repeat(1025), "body", "run-9");

        assertThat(upper.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(upper)).contains("name");
        assertThat(overlong.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(overlong)).contains("description");
        assertThat(adapter.calls).isEmpty();
    }

    @Test
    void given_missing_run_context_when_called_then_error_without_adapter_call() {
        ToolResultBlock noContext = callVia(
                Map.of("name", "ok-name", "description", "d", "body", "b"), null);
        ToolResultBlock noKey = callVia(
                Map.of("name", "ok-name", "description", "d", "body", "b"),
                RuntimeContext.builder().sessionId("s").userId("u").build());

        assertThat(noContext.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(noKey.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(adapter.calls).isEmpty();
    }

    @Test
    void given_adapter_rejection_when_called_then_reason_passed_through() {
        adapter.next = SkillDraftReceipt.rejected("与技能库现有技能同名：\"tdd\"——请换名重提。");

        ToolResultBlock result = call("tdd", "desc", "body", "run-9");

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("同名").contains("tdd");
    }

    @Test
    void given_dangerous_scan_rejection_when_called_then_receipt_visible() {
        adapter.next = SkillDraftReceipt.rejected("安全扫描判定 DANGEROUS，拒写不入库。");

        ToolResultBlock result = call("evil", "desc", "body", "run-9");

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("DANGEROUS");
    }

    // ---------- 内部 ----------

    private ToolResultBlock call(String name, String description, String body, String runId) {
        Map<String, Object> input = new java.util.HashMap<>();
        if (name != null) {
            input.put("name", name);
        }
        if (description != null) {
            input.put("description", description);
        }
        if (body != null) {
            input.put("body", body);
        }
        return callVia(input, runId == null ? null : RuntimeContext.builder()
                .sessionId("session-1").userId("u-1")
                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, runId)
                .build());
    }

    private ToolResultBlock callVia(Map<String, Object> input, RuntimeContext ctx) {
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("tc-1", ProposeSkillTool.NAME, input, null))
                .input(input)
                .runtimeContext(ctx)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
