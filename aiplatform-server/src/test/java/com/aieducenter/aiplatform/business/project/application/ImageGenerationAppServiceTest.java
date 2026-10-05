package com.aieducenter.aiplatform.business.project.application;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.business.project.application.ImageGenerationAppService.ImageGenerationOutcome;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.ImageTransfers;
import com.aieducenter.aiplatform.business.project.domain.port.ActiveImageGenerationProvider;
import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationProvider;
import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationRequest;
import com.aieducenter.aiplatform.business.project.domain.port.ImageProviderResult;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出图工具件内核集成测试（#288，ADR-0026/27）：真库（项目/工作区/计量事件走
 * aiplatform_test），供数口与容器面两假面——每次供应商调用报恰一条按张计量事件
 * （张数=1、token 五档为零、dims agentKind=designer、subject=projectId）、转存
 * 走命令正本（URL 形 curl 下载／base64 形 stdin 灌字节）、技术失败有限重试不产
 * 新候选（重试各次照计成本）、供应商未配置 PRJ_045、回执畸形 WSP_002 防御性暴露。
 */
@IntegrationTest
class ImageGenerationAppServiceTest {

    private static final byte[] IMAGE_BYTES = {1, 2, 3, 0, 4, 5}; // 含 NUL 的二进制
    private static final String B64 = java.util.Base64.getEncoder().encodeToString(IMAGE_BYTES);

    @Autowired
    private ImageGenerationAppService service;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 供数口假面：可编程结果序列（每次 generate 弹一个；耗尽重复末个）。 */
    @MockitoBean
    private ActiveImageGenerationProvider providers;

    /** docker 依赖收口（转存命令与字节由测试捕获断言）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    private final ScriptedProvider provider = new ScriptedProvider();

    private Long projectId;
    private String workspaceId;
    private long workspaceSeq = 930300L;

    /** 可编程供数器替身（非 Mockito——结果序列语义直白；耗尽重复末个）。 */
    private static final class ScriptedProvider implements ImageGenerationProvider {

        final Deque<ImageProviderResult> script = new ArrayDeque<>();
        final List<ImageGenerationRequest> calls = new ArrayList<>();
        private ImageProviderResult last;

        @Override
        public String providerKey() {
            return "testimg";
        }

        @Override
        public ImageProviderResult generate(ImageGenerationRequest request) {
            calls.add(request);
            if (!script.isEmpty()) {
                last = script.poll();
            }
            return last;
        }

        void enqueue(ImageProviderResult... results) {
            for (ImageProviderResult result : results) {
                last = result;
                script.add(result);
            }
        }
    }

    @BeforeEach
    void seed() {
        Workspace ready = Workspace.registerPending(
                WorkspaceId.of(Long.toString(workspaceSeq)), EnvKind.DEV);
        ready.complete(WorkspaceProvision.of(WorkspaceHandle.dev(
                WorkspaceId.of(Long.toString(workspaceSeq)),
                "ws-" + workspaceSeq, "previewnet")));
        workspaceRepository.save(ready);
        Project project = projectRepository.save(
                Project.create("出图测试", null, workspaceSeq, 1L));
        projectId = project.getId();
        workspaceId = Long.toString(workspaceSeq);
        when(providers.active()).thenReturn(Optional.of(provider));
    }

    @AfterEach
    void cleanup() {
        if (projectId != null) {
            jdbcTemplate.update("DELETE FROM met_usage_events WHERE subject = ?",
                    projectId.toString());
            projectRepository.findById(projectId).ifPresent(projectRepository::delete);
        }
        workspaceRepository.findById(workspaceSeq).ifPresent(workspaceRepository::delete);
    }

    private void givenUrlTransferSucceeds(String receipt) {
        when(environmentBackend.exec(any(WorkspaceHandle.class), anyString()))
                .thenAnswer(invocation -> new ExecResult(receipt, "", 0));
    }

    private void givenBase64TransferSucceeds() {
        when(environmentBackend.execWithStdin(any(WorkspaceHandle.class), anyString(), any()))
                .thenAnswer(invocation -> new ExecResult(
                        String.valueOf(IMAGE_BYTES.length), "", 0));
    }

    private int usageEventCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE subject = ?",
                Integer.class, projectId.toString());
    }

    private Map<String, Object> soleUsageEvent() {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM met_usage_events WHERE subject = ?", projectId.toString());
    }

    // ---------- 契约：出图→转存落盘→恰一条按张事件 ----------

    @Test
    void given_url_form_when_generate_then_container_download_and_one_image_event() {
        provider.enqueue(new ImageProviderResult.Generated("glm-image",
                new ImageProviderResult.Image(
                        "https://cdn.example/img/abc.png?Expires=1", null, "png")));
        givenUrlTransferSucceeds("12345");

        ImageGenerationOutcome outcome = service.generate(workspaceId, "run-7", "designer-1",
                "极简 logo", 1, null, "logo-方案A");

        assertThat(outcome).isInstanceOf(ImageGenerationOutcome.Generated.class);
        ImageGenerationOutcome.Generated generated = (ImageGenerationOutcome.Generated) outcome;
        assertThat(generated.provider()).isEqualTo("testimg");
        assertThat(generated.model()).isEqualTo("glm-image");
        assertThat(generated.failures()).isEmpty();
        assertThat(generated.files()).hasSize(1);
        assertThat(generated.files().get(0).path())
                .matches("design/\\d+-logo-方案A\\.png");
        assertThat(generated.files().get(0).sizeBytes()).isEqualTo(12345);

        // 转存命令正本＝ImageTransfers.downloadCommand（容器内 curl，URL 单引号转义）
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(environmentBackend).exec(any(WorkspaceHandle.class), command.capture());
        assertThat(command.getValue())
                .isEqualTo(ImageTransfers.downloadCommand(generated.files().get(0).path(),
                        "https://cdn.example/img/abc.png?Expires=1"));

        // 恰一条按张事件：张数=1、token 五档为零、dims agentKind=designer、幂等键 img- 前缀
        assertThat(usageEventCount()).isEqualTo(1);
        Map<String, Object> event = soleUsageEvent();
        assertThat(event.get("images")).isEqualTo(1L);
        assertThat(event.get("input")).isEqualTo(0L);
        assertThat(event.get("output")).isEqualTo(0L);
        assertThat(event.get("provider")).isEqualTo("testimg");
        assertThat(event.get("model")).isEqualTo("glm-image");
        assertThat(event.get("run_id")).isEqualTo("run-7");
        assertThat(event.get("session_id")).isEqualTo("designer-1");
        assertThat(String.valueOf(event.get("event_id"))).startsWith("img-");
        assertThat(String.valueOf(event.get("dims"))).contains("designer");
    }

    @Test
    void given_base64_form_when_generate_then_stdin_write_with_byte_fidelity() {
        provider.enqueue(new ImageProviderResult.Generated("gpt-image-2.5-sunburst",
                new ImageProviderResult.Image(null, B64, "png")));
        givenBase64TransferSucceeds();

        ImageGenerationOutcome outcome = service.generate(workspaceId, null, null,
                "mascot", 1, null, null);

        ImageGenerationOutcome.Generated generated = (ImageGenerationOutcome.Generated) outcome;
        assertThat(generated.files()).hasSize(1);
        assertThat(generated.files().get(0).path()).matches("design/\\d+-image\\.png");

        // base64 形＝stdin 灌 .part 暂存（ImageTransfers.writeCommand 正本），字节保真
        ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(environmentBackend).execWithStdin(any(WorkspaceHandle.class),
                command.capture(), stdin.capture());
        assertThat(stdin.getValue()).containsExactly(IMAGE_BYTES);
        assertThat(command.getValue())
                .isEqualTo(ImageTransfers.writeCommand(generated.files().get(0).path()));
        assertThat(usageEventCount()).isEqualTo(1);
    }

    @Test
    void given_count_three_when_generate_then_three_events_and_three_files() {
        for (int i = 0; i < 3; i++) {
            provider.enqueue(new ImageProviderResult.Generated("m",
                    new ImageProviderResult.Image("https://cdn.example/a.png", null, "png")));
        }
        givenUrlTransferSucceeds("10");

        ImageGenerationOutcome outcome = service.generate(workspaceId, "run-7", "s",
                "三稿海报", 3, "1024x1024", null);

        assertThat(((ImageGenerationOutcome.Generated) outcome).files()).hasSize(3);
        assertThat(provider.calls).hasSize(3);
        assertThat(provider.calls.get(0).size()).isEqualTo("1024x1024"); // size 原样透传
        assertThat(usageEventCount()).isEqualTo(3); // 每次调用恰一条
    }

    // ---------- 重试：技术失败有限重试、不产新候选、各次照计 ----------

    @Test
    void given_provider_fails_then_succeeds_when_generate_then_retried_once_and_one_event() {
        provider.enqueue(
                new ImageProviderResult.Failed("HTTP 429（限流）"),
                new ImageProviderResult.Generated("m",
                        new ImageProviderResult.Image("https://cdn.example/b.png", null, "png")));
        givenUrlTransferSucceeds("10");

        ImageGenerationOutcome outcome = service.generate(workspaceId, null, null,
                "p", 1, null, null);

        assertThat(((ImageGenerationOutcome.Generated) outcome).files()).hasSize(1);
        assertThat(provider.calls).hasSize(2);
        assertThat(usageEventCount()).isEqualTo(1); // 失败调用不计量，成功恰一条
    }

    @Test
    void given_provider_always_fails_when_generate_then_failed_outcome_and_zero_events() {
        provider.enqueue(new ImageProviderResult.Failed("HTTP 400（1301：内容拦截）"));
        givenUrlTransferSucceeds("10");

        ImageGenerationOutcome outcome = service.generate(workspaceId, null, null,
                "p", 1, null, null);

        assertThat(outcome).isInstanceOf(ImageGenerationOutcome.Failed.class);
        assertThat(((ImageGenerationOutcome.Failed) outcome).reason())
                .contains("1301").contains("3 次"); // 重试上界如实呈现
        assertThat(provider.calls).hasSize(3); // 有限重试（含首次 3 次）
        assertThat(usageEventCount()).isZero();
        verify(environmentBackend, never()).exec(any(), anyString()); // 判定失败零容器触达
    }

    @Test
    void given_transfer_fails_then_retries_when_generate_then_both_attempts_metered() {
        // 第 1 次供应商成功但转存失败（已付费照计）、第 2 次成功落盘——重试各次照计成本
        provider.enqueue(
                new ImageProviderResult.Generated("m",
                        new ImageProviderResult.Image("https://cdn.example/c.png", null, "png")),
                new ImageProviderResult.Generated("m",
                        new ImageProviderResult.Image("https://cdn.example/c2.png", null, "png")));
        when(environmentBackend.exec(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new ExecResult("", "curl: (28) timeout", 28))
                .thenReturn(new ExecResult("2048", "", 0));

        ImageGenerationOutcome outcome = service.generate(workspaceId, null, null,
                "p", 1, null, null);

        assertThat(((ImageGenerationOutcome.Generated) outcome).files()).hasSize(1);
        assertThat(provider.calls).hasSize(2);
        assertThat(usageEventCount()).isEqualTo(2); // 重试各次照计（ADR-0026）
    }

    @Test
    void given_partial_success_when_generate_then_files_with_honest_failures() {
        // 2 张：第 1 张成功、第 2 张重试耗尽——部分失败如实带清单，不静默缺稿
        provider.enqueue(
                new ImageProviderResult.Generated("m",
                        new ImageProviderResult.Image("https://cdn.example/d.png", null, "png")),
                new ImageProviderResult.Failed("HTTP 500（内部错误）"));
        givenUrlTransferSucceeds("10");

        ImageGenerationOutcome outcome = service.generate(workspaceId, null, null,
                "p", 2, null, null);

        ImageGenerationOutcome.Generated generated = (ImageGenerationOutcome.Generated) outcome;
        assertThat(generated.files()).hasSize(1);
        assertThat(generated.failures()).hasSize(1);
        assertThat(generated.failures().get(0)).contains("第 2 张");
        assertThat(usageEventCount()).isEqualTo(1);
    }

    // ---------- 守卫 ----------

    @Test
    void given_no_active_provider_when_generate_then_prj_045() {
        when(providers.active()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(workspaceId, null, null, "p", 1, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("图片生成供应商未配置");
    }

    @Test
    void given_unknown_workspace_when_generate_then_project_not_found() {
        assertThatThrownBy(() -> service.generate("999999", null, null, "p", 1, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("项目不存在");
    }

    @Test
    void given_malformed_receipt_when_generate_then_wsp_002_defensive() {
        provider.enqueue(new ImageProviderResult.Generated("m",
                new ImageProviderResult.Image("https://cdn.example/e.png", null, "png")));
        when(environmentBackend.exec(any(WorkspaceHandle.class), anyString()))
                .thenAnswer(invocation -> new ExecResult("not-a-number", "", 0));

        // exit 0 而 stdout 非字节回执：命令契约破坏，防御性如实暴露（不静默重试）
        assertThatThrownBy(() -> service.generate(workspaceId, null, null, "p", 1, null, null))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED);
    }

    @Test
    void given_invalid_params_when_generate_then_rejected_before_any_touch() {
        assertThatThrownBy(() -> service.generate(workspaceId, null, null, " ", 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generate(workspaceId, null, null, "p", 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generate(workspaceId, null, null, "p", 6, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.calls).isEmpty();
    }
}
