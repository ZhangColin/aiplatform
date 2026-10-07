package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;

import reactor.core.publisher.Mono;

import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.FileChange;
import com.aieducenter.aiplatform.base.agentscope.LandedFileFacts;
import com.aieducenter.aiplatform.business.project.application.DesignSpecAppService;
import com.aieducenter.aiplatform.business.project.application.ImageGenerationAppService;
import com.aieducenter.aiplatform.business.project.application.ImageGenerationAppService.ImageGenerationOutcome;

/**
 * {@link GenerateImageTool}：出图工具件的输入面与回执面（#288 内核，纯 JUnit——
 * 供数/转存/计量链在 {@code ImageGenerationAppServiceTest}）：入参契约（prompt
 * 必传、count 1~5 缺省 1、size/name 可选透传）；run/会话标识从 RuntimeContext
 * 每调用提取（实例缓存跨 run 复用，血统不固化在构造态）；成功回执只含落盘路径
 * 与字节（<b>供应商 URL 永不透出</b>）、部分失败如实附注；失败与平台异常如实回
 * 给模型不炸会话。#295：设计参数（palette/style）在场随稿物化（规范草稿归
 * {@code DesignSpecAppService}）、缺席不物化空壳。
 */
class GenerateImageToolTest {

    /** 应用服务替身：记录调用形状，可编程回执（记录器语义直白，不用 Mockito）。 */
    private static class RecordingService extends ImageGenerationAppService {
        final List<String> calls = new ArrayList<>();
        ImageGenerationOutcome next = new ImageGenerationOutcome.Generated("zhipu", "glm-image",
                List.of(new ImageGenerationOutcome.LandedImage("design/123-hero.png", 2048)),
                List.of());

        RecordingService() {
            super(null, null, null, null, 3);
        }

        @Override
        public ImageGenerationOutcome generate(String workspaceId, String runId,
                String sessionId, String prompt, int count, String size, String name) {
            calls.add(workspaceId + "|" + runId + "|" + sessionId + "|" + prompt
                    + "|" + count + "|" + size + "|" + name);
            return next;
        }
    }

    /** 设计规范服务替身（#295 物化面记录器，同 RecordingService 形制）。 */
    private static class RecordingSpecService extends DesignSpecAppService {
        final List<String> materializations = new ArrayList<>();

        RecordingSpecService() {
            super(null, null);
        }

        @Override
        public void materializePrintParams(String workspaceId, List<String> landedPaths,
                List<String> palette, String style) {
            materializations.add(workspaceId + "|" + landedPaths + "|" + palette + "|" + style);
        }
    }

    private final RecordingService service = new RecordingService();
    private final RecordingSpecService specService = new RecordingSpecService();
    private final LandedFileFacts landedFiles = new LandedFileFacts();
    private final GenerateImageTool tool = new GenerateImageTool("77", service, landedFiles,
            specService);

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("generate_image");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        assertThat(String.valueOf(tool.getParameters().get("required"))).contains("prompt");
        assertThat(tool.isReadOnly()).isFalse(); // 写工作区（design/ 落盘）
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        PermissionDecision decision = tool.checkPermissions(Map.of("prompt", "p"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_valid_input_when_called_then_kernel_called_with_lineage_and_paths_returned() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "夏日饮品海报");
        input.put("count", 3);
        input.put("size", "1024x1024");
        input.put("name", "hero-方案A");

        ToolResultBlock result = tool.callAsync(call(input, "run-5", "designer-9")).block();

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        // 血统腿：工作区（构造态）＋ run/会话（每调用提取）＋ 参数面全透传
        assertThat(service.calls).containsExactly(
                "77|run-5|designer-9|夏日饮品海报|3|1024x1024|hero-方案A");
        // 回执＝落盘路径＋字节（供应商 URL 不透出——ADR-0027 转存口径）
        assertThat(textOf(result)).contains("design/123-hero.png").contains("2048");
        assertThat(textOf(result)).doesNotContain("https://");
    }

    @Test
    void given_defaults_when_called_then_count_defaults_to_one_and_optionals_null() {
        ToolResultBlock result = tool.callAsync(call(Map.of("prompt", "logo"), null, null)).block();

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(service.calls).containsExactly("77|null|null|logo|1|null|null");
    }

    @Test
    void given_blank_prompt_or_bad_count_when_called_then_error_without_kernel_call() {
        assertThat(tool.callAsync(call(Map.of("count", "2"), null, null)).block().getState())
                .isEqualTo(ToolResultState.ERROR);
        assertThat(tool.callAsync(call(mapOf("prompt", " ", "count", 1), null, null)).block()
                .getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(tool.callAsync(call(mapOf("prompt", "p", "count", 0), null, null)).block()
                .getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(tool.callAsync(call(mapOf("prompt", "p", "count", 6), null, null)).block()
                .getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(tool.callAsync(call(mapOf("prompt", "p", "count", "x"), null, null)).block()
                .getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(service.calls).isEmpty();
    }

    @Test
    void given_generated_when_called_then_landed_files_reported_anchored() {
        // #292 落盘事实进变更事实流：转存不经引擎写文件工具，流内观察看不见——
        // 工具携 runId 报锚定形路径（收口判据与收尾卡清单由此看见图片模型路的稿）
        service.next = new ImageGenerationOutcome.Generated("zhipu", "glm-image",
                List.of(
                        new ImageGenerationOutcome.LandedImage("design/123-a.png", 2048),
                        new ImageGenerationOutcome.LandedImage("design/123-b.png", 4096)),
                List.of());

        tool.callAsync(call(Map.of("prompt", "p"), "run-7", "designer-1")).block();

        assertThat(landedFiles.drain("run-7")).containsExactly(
                new FileChange("/design/123-a.png", 1, 0),
                new FileChange("/design/123-b.png", 1, 0));
        // 取走即清：二次取空（无跨 run 残留）
        assertThat(landedFiles.drain("run-7")).isEmpty();
    }

    @Test
    void given_no_run_context_or_failed_when_called_then_nothing_reported() {
        // 非 run 上下文（血统腿缺 runId）与全部失败（无落盘）都不报——事实面如实
        tool.callAsync(call(Map.of("prompt", "p"), null, null)).block();
        service.next = new ImageGenerationOutcome.Failed("全失败");
        tool.callAsync(call(Map.of("prompt", "p"), "run-8", "designer-1")).block();

        assertThat(landedFiles.drain("run-8")).isEmpty();
    }

    @Test
    void given_partial_failures_when_called_then_receipt_lists_failures_honestly() {
        service.next = new ImageGenerationOutcome.Generated("zhipu", "glm-image",
                List.of(new ImageGenerationOutcome.LandedImage("design/1-a.png", 10)),
                List.of("第 2 张出图失败（已重试 3 次）：HTTP 500"));

        ToolResultBlock result = tool.callAsync(
                call(Map.of("prompt", "p"), "run-1", "s")).block();

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).contains("第 2 张出图失败").contains("design/1-a.png");
    }

    @Test
    void given_kernel_failure_when_called_then_error_passed_through_without_throw() {
        service.next = new ImageGenerationOutcome.Failed("出图失败（HTTP 400（1301：拦截））");

        ToolResultBlock result = tool.callAsync(call(Map.of("prompt", "p"), null, null)).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).contains("1301");
    }

    @Test
    void given_platform_exception_when_called_then_honest_error_not_crash() {
        // 供应商未配置（PRJ_045）等平台异常如实回给模型
        RecordingService throwing = new RecordingService() {
            @Override
            public ImageGenerationOutcome generate(String workspaceId, String runId,
                    String sessionId, String prompt, int count, String size, String name) {
                throw new IllegalStateException("图片生成供应商未配置，暂时无法出图");
            }
        };
        GenerateImageTool tool = new GenerateImageTool("77", throwing, new LandedFileFacts(),
                new RecordingSpecService());

        ToolResultBlock result = tool.callAsync(call(Map.of("prompt", "p"), null, null)).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).contains("供应商未配置");
    }

    @Test
    void given_params_when_generated_then_materialized_with_landed_paths() {
        // #295 灵魂用例（平面类前半）：设计参数在场 → 出图成功即随每张落盘稿物化
        //（工作区＋落盘路径清单＋色板＋风格全要素）；字符串色板按分隔符宽容收口
        service.next = new ImageGenerationOutcome.Generated("zhipu", "glm-image",
                List.of(new ImageGenerationOutcome.LandedImage("design/123-a.png", 2048)),
                List.of());

        tool.callAsync(call(mapOf("prompt", "p", "palette", List.of("#166534", " #faf9f6 "),
                "style", "扁平暖调"), "run-9", "designer-1")).block();

        assertThat(specService.materializations).containsExactly(
                "77|[design/123-a.png]|[#166534, #faf9f6]|扁平暖调");

        specService.materializations.clear();
        service.next = new ImageGenerationOutcome.Generated("zhipu", "glm-image",
                List.of(new ImageGenerationOutcome.LandedImage("design/123-hero.png", 2048)),
                List.of());
        tool.callAsync(call(mapOf("prompt", "p", "palette", "#166534，#faf9f6"), null, null))
                .block();
        assertThat(specService.materializations).containsExactly(
                "77|[design/123-hero.png]|[#166534, #faf9f6]|null");
    }

    @Test
    void given_no_params_or_failed_when_called_then_no_materialization() {
        // 参数缺席＝无设计意图不物化空壳；出图失败无落盘、无物化
        tool.callAsync(call(Map.of("prompt", "p"), "run-10", "designer-1")).block();
        assertThat(specService.materializations).isEmpty();

        service.next = new ImageGenerationOutcome.Failed("全失败");
        tool.callAsync(call(mapOf("prompt", "p", "style", "极简"), "run-10", "designer-1"))
                .block();
        assertThat(specService.materializations).isEmpty();
    }

    // ---------- 替身与参数构造 ----------

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private ToolCallParam call(Map<String, Object> input, String runId, String sessionId) {
        RuntimeContext ctx = sessionId == null && runId == null ? null
                : RuntimeContext.builder().sessionId(sessionId).userId("u")
                        .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, runId)
                        .build();
        return ToolCallParam.builder().input(input).runtimeContext(ctx).build();
    }

    private static String textOf(ToolResultBlock result) {
        return ((io.agentscope.core.message.TextBlock) result.getOutput().get(0)).getText();
    }
}
