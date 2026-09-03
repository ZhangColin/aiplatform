package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.aieducenter.aiplatform.base.agentscope.DockerExecFilesystem;
import com.aieducenter.aiplatform.business.project.application.PrdRevisionFacts;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

/**
 * {@link SavePrdTool}：覆盖写落盘（业务正本路径）+ 落盘成功业务登记 + summary
 * 必传校验（#52：漏传报错逼补——修订事实从工具调用面观测，不自报）+ 修订事实登记
 * （{@link PrdRevisionFacts}，终值胜出）；失败回错误结果（模型可见可重试）；权限
 * 自检恒放行（PRD 无最终版一说，确认动作在下单快照）。
 */
class SavePrdToolTest {

    /** 执行缝替身：记录命令与 stdin，返回预设结果。 */
    private static final class RecordingExec implements DockerExecFilesystem.ExecCommand {
        final List<String> commands = new ArrayList<>();
        final List<byte[]> stdins = new ArrayList<>();
        DockerExecFilesystem.ExecOutput next =
                new DockerExecFilesystem.ExecOutput(0, new byte[0], "");

        @Override
        public DockerExecFilesystem.ExecOutput run(String command, byte[] stdin) {
            commands.add(command);
            stdins.add(stdin);
            return next;
        }
    }

    /** 登记替身：记录回调，可预设抛出（PrdArtifactAdapter 为具体类，以子类覆写）。 */
    private static final class RecordingAdapter extends PrdArtifactAdapter {
        final List<String> callbacks = new ArrayList<>();
        RuntimeException failure;

        RecordingAdapter() {
            super(null, null, null);
        }

        @Override
        public String workspacePath() {
            return "docs/PRD.md";
        }

        @Override
        public void onWritten(String workspaceId) {
            if (failure != null) {
                throw failure;
            }
            callbacks.add(workspaceId);
        }
    }

    private final RecordingExec exec = new RecordingExec();
    private final RecordingAdapter port = new RecordingAdapter();
    private final PrdRevisionFacts prdRevisions = new PrdRevisionFacts();
    private final SavePrdTool tool = new SavePrdTool("docs/PRD.md", "42", port, prdRevisions, exec);

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("savePrd");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        // content + summary 双必传（#52：修订事实从 summary 参数观测）
        assertThat(String.valueOf(tool.getParameters().get("required")))
                .contains("content").contains("summary");
        assertThat(tool.isReadOnly()).isFalse(); // 写工作区产物
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        // PRD 产出是访谈协议的预期终点：工具点不放确认（用户的确认在 G1 门拍板）
        PermissionDecision decision = tool
                .checkPermissions(Map.of("content", "# PRD"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_content_when_called_then_written_and_port_called() {
        ToolResultBlock result = call("# PRD\n\n做一个企业官网。", "初版产出：单页展示");

        // text 工厂缺省 RUNNING（同 ask_user 形制），非 ERROR 即成功载体
        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("已保存");
        // 覆盖写（cat >，无 noclobber——修订即更新）+ mkdir 兜底目录；内容经 stdin
        assertThat(exec.commands).containsExactly(
                "mkdir -p '/workspace/docs' && cat > '/workspace/docs/PRD.md'");
        assertThat(new String(exec.stdins.get(0), StandardCharsets.UTF_8))
                .isEqualTo("# PRD\n\n做一个企业官网。");
        assertThat(port.callbacks).containsExactly("42");
    }

    @Test
    void given_second_call_when_revision_then_overwrite_and_callback_again() {
        call("# PRD v1", "初版产出");
        call("# PRD v2（修订）", "修订：主色调改绿");

        // 修订再执行：文件重写 + 状态位/事件再触发（三更新）
        assertThat(exec.commands).hasSize(2);
        assertThat(port.callbacks).containsExactly("42", "42");
    }

    @Test
    void given_blank_content_when_called_then_error_without_write() {
        ToolResultBlock result = call(" ", "初版产出");

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(exec.commands).isEmpty();
        assertThat(port.callbacks).isEmpty();
    }

    @Test
    void given_missing_or_blank_summary_when_called_then_error_without_write_or_fact() {
        // #52 漏传逼补：summary 必传——校验先于任何落盘/登记/事实（模型可见可补传）
        ToolResultBlock missing = call("# PRD", null);
        ToolResultBlock blank = call("# PRD", " ");

        assertThat(missing.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(missing)).contains("summary");
        assertThat(blank.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(exec.commands).isEmpty();
        assertThat(port.callbacks).isEmpty();
        assertThat(prdRevisions.consume("42")).as("漏传不落修订事实").isNull();
    }

    @Test
    void given_summary_resupplied_when_called_after_error_then_succeeds() {
        // 脚本化补传链路：漏传报错 → 补传后成功（事实随成功调用落定）
        call("# PRD", null);
        ToolResultBlock resupplied = call("# PRD", "补传的修订说明");

        assertThat(resupplied.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(exec.commands).hasSize(1);
        assertThat(prdRevisions.consume("42")).isEqualTo("补传的修订说明");
    }

    @Test
    void given_successful_save_when_called_then_revision_fact_recorded() {
        // #52 修订事实从工具调用面观测：成功即登记（平台侧交接物的「改了什么」来源）
        call("# PRD", "按意见把主色调改为绿");

        assertThat(prdRevisions.consume("42")).isEqualTo("按意见把主色调改为绿");
    }

    @Test
    void given_multiple_saves_in_one_round_when_consumed_then_latest_summary_wins() {
        // 一轮多次 savePrd：交接物取终值不混杂（后写胜出——同 FinishFixFacts 口径）
        call("# PRD v1", "第一次修订说明");
        call("# PRD v2", "第二次修订说明（终值）");

        assertThat(prdRevisions.consume("42")).isEqualTo("第二次修订说明（终值）");
    }

    @Test
    void given_exec_failure_when_called_then_error_and_no_port_callback_or_fact() {
        exec.next = new DockerExecFilesystem.ExecOutput(125, new byte[0],
                "docker: No such container");

        ToolResultBlock result = call("# PRD", "初版产出");

        // 落盘失败：文件未成——不置位不发事件不落事实（门禁事实只认落盘成功）
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("写入工作区失败");
        assertThat(port.callbacks).isEmpty();
        assertThat(prdRevisions.consume("42")).isNull();
    }

    @Test
    void given_port_failure_when_called_then_error_retriable() {
        port.failure = new RuntimeException("置位失败");

        ToolResultBlock result = call("# PRD", "初版产出");

        // 文件已写但业务登记失败：回错误让模型可重试（写幂等覆盖，重试只前进）
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("登记失败");
    }

    // ---------- 内部 ----------

    private ToolResultBlock call(String content, String summary) {
        Map<String, Object> input = summary != null
                ? Map.of("content", content, "summary", summary)
                : Map.of("content", content);
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock(
                        "tc-1", SavePrdTool.NAME, input, null))
                .input(input)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
