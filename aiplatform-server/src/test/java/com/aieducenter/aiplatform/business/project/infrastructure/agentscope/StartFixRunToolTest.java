package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.application.IterationAppService;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * {@link StartFixRunTool}：按工作区寻址派修正任务，派发/排队结果如实回模型
 * （BA 据此回复用户）；守卫拒绝回错误结果（可转告）；权限自检恒放行（迭代无门）。
 */
class StartFixRunToolTest {

    private final IterationAppService iterationAppService = mock(IterationAppService.class);
    private final StartFixRunTool tool = new StartFixRunTool("42", iterationAppService);

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("startFixRun");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        assertThat(String.valueOf(tool.getParameters().get("required"))).contains("task");
        assertThat(tool.isReadOnly()).isFalse(); // 派修正 run（触发编码动作）
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        // 修正是迭代协议的预期动作：工具点不放确认（唯一不可逆门在下单快照）
        PermissionDecision decision = tool
                .checkPermissions(Map.of("task", "改首页布局"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_dispatched_when_called_then_task_forwarded_with_run_id() {
        when(iterationAppService.startFixRunByWorkspace("42", "改首页布局"))
                .thenReturn(new IterationAppService.FixDispatch("fix-run-1", false));

        ToolResultBlock result = call("改首页布局");

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("fix-run-1").contains("已下发");
    }

    @Test
    void given_fix_in_flight_when_called_then_queued_result_visible_to_model() {
        when(iterationAppService.startFixRunByWorkspace("42", "加导出"))
                .thenReturn(new IterationAppService.FixDispatch(null, true));

        ToolResultBlock result = call("加导出");

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("排入下一轮").contains("合并处理");
    }

    @Test
    void given_guard_rejected_when_called_then_error_result_with_reason() {
        when(iterationAppService.startFixRunByWorkspace("42", "改一下"))
                .thenThrow(new ApplicationException(ProjectMessage.FIX_RUN_NOT_GENERATED));

        ToolResultBlock result = call("改一下");

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result))
                .contains(ProjectMessage.FIX_RUN_NOT_GENERATED.message());
    }

    @Test
    void given_blank_task_when_called_then_error_without_dispatch() {
        ToolResultBlock result = call(" ");

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("task 不能为空");
    }

    // ---------- 内部 ----------

    private ToolResultBlock call(String task) {
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock(
                        "tc-1", StartFixRunTool.NAME, Map.of("task", task), null))
                .input(Map.of("task", task))
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
