package com.aieducenter.aiplatform.business.project.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ImageTransfers;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMaterials;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.port.ActiveImageGenerationProvider;
import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationProvider;
import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationRequest;
import com.aieducenter.aiplatform.business.project.domain.port.ImageProviderResult;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 出图工具件内核（#288，ADR-0026/0027）：调图片生成供应商出图、按张计量、转存
 * 落盘产物目录——对调用方（出图工具件，发放接线归 #289 设计执行体票）收拢一件事：
 * 「按提示出 N 张位图稿、逐张落 {@code design/}、技术失败有限重试不产新候选」。
 *
 * <p><b>按张计量</b>：每次供应商调用成功报恰一条按张事件（张数进 images 列、token
 * 五档为零，与模型调用事件并列进成本观测；幂等键＝img-＋TSID，重试各次各报各的
 * ——ADR-0026「重试各次照计平台成本」：转存失败的尝试已向供应商付费，照实入账）。
 * dims 终态口径＝{@link UsageDims} 三键（agentKind=designer）。</p>
 *
 * <p><b>转存＝容器侧落盘</b>（ADR-0027）：URL 形在容器内 curl 下载直落产物目录
 * （字节不过平台 JVM，URL 只中转不入库不透出）；base64 形字节经 stdin 灌入
 * （回执与字节数核对）。两形同走 {@code .part} 暂存＋失败清理（
 * {@link ImageTransfers} 命令正本）：失败尝试不留半张稿，成功各张不覆盖——每次
 * 尝试各拿新落点（TSID 前缀），重试＝重新出图，不产新候选。</p>
 *
 * <p>无落库（事务注解取舍同读侧——工作区文件面不经数据库）；计量事件经
 * {@link UsageEventSink} 同步直报（端口幂等）。供应商未配置（无 active 适配器）
 * 如实 PRJ_045，不静默降级。</p>
 */
@Service
public class ImageGenerationAppService {

    /** 计量幂等键前缀（拼 TSID 尾段；与模型边界幂等键同「前缀+唯一段」形）。 */
    private static final String EVENT_PREFIX = "img";

    /** 落盘词干缺省（名字提示缺省时的回落，镜像物料面 material 缺省形）。 */
    private static final String DEFAULT_STEM = "image";

    private final ActiveImageGenerationProvider providers;
    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final UsageEventSink usageEventSink;
    private final int maxAttempts;

    public ImageGenerationAppService(
            ActiveImageGenerationProvider providers,
            ProjectRepository projectRepository,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            UsageEventSink usageEventSink,
            @Value("${app.image-generation.max-attempts:3}") int maxAttempts) {
        this.providers = providers;
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.usageEventSink = usageEventSink;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 出 N 张图（逐张串行：每张独立重试预算、独立落点，一张失败不牵连其余——部分
     * 失败如实带失败清单，全部失败才整体 Failed）。
     *
     * @param workspaceId 工作区标识（工具面锚定形；项目据此解析，计量 subject=projectId）
     * @param runId       run 标识（可空——非 run 级来源）
     * @param sessionId   会话标识（计量 dims 成分；空归空串）
     * @param prompt      画面正向描述（必填）
     * @param count       张数（1~5）
     * @param size        画幅（可空＝供应商缺省，原样透传由供应商校验）
     * @param name        落盘词干提示（可空；物料同规消毒，扩展名随生成图）
     * @return 落盘清单（路径＋字节）与部分失败清单；全部失败带如实理由
     * @throws ApplicationException PRJ_045 供应商未配置；PRJ_001 工作区名下无项目；
     *                              WSP_002 转存回执畸形（命令契约破坏，防御性暴露）
     */
    public ImageGenerationOutcome generate(String workspaceId, String runId, String sessionId,
            String prompt, int count, String size, String name) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空（画面正向描述）");
        }
        if (count < 1 || count > 5) {
            throw new IllegalArgumentException("张数须在 1~5 内: " + count);
        }
        ImageGenerationProvider provider = providers.active()
                .orElseThrow(() -> new ApplicationException(ProjectMessage.IMAGE_PROVIDER_UNCONFIGURED));
        Project project = projectRepository
                .findByWorkspaceId(Long.parseLong(workspaceId.strip()))
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        Long projectId = project.getId();
        String dimsSessionId = sessionId == null ? "" : sessionId;

        List<ImageGenerationOutcome.LandedImage> files = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        String model = null;
        for (int i = 0; i < count; i++) {
            String lastReason = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                ImageProviderResult result = provider.generate(new ImageGenerationRequest(prompt, size));
                if (result instanceof ImageProviderResult.Failed failed) {
                    lastReason = failed.reason();
                    continue;
                }
                ImageProviderResult.Generated generated = (ImageProviderResult.Generated) result;
                model = generated.model();
                // 供应商调用成功即计量（张数=1，单次调用单张口径）——转存失败也已付费，照实入账
                reportUsage(provider, generated, projectId, runId, dimsSessionId);
                TransferAttempt transfer = transfer(workspaceId, generated.image(), name);
                if (transfer.landed() != null) {
                    files.add(transfer.landed());
                    lastReason = null;
                    break;
                }
                lastReason = transfer.failure();
            }
            if (lastReason != null) {
                failures.add("第 " + (i + 1) + " 张出图失败（已尝试 " + maxAttempts + " 次，含首次）：" + lastReason);
            }
        }
        if (files.isEmpty()) {
            return new ImageGenerationOutcome.Failed(String.join("；", failures));
        }
        return new ImageGenerationOutcome.Generated(provider.providerKey(), model,
                List.copyOf(files), List.copyOf(failures));
    }

    /** 供应商调用成功即报恰一条按张事件（幂等键每次调用唯一；重试各次各报各的）。 */
    private void reportUsage(ImageGenerationProvider provider,
            ImageProviderResult.Generated generated, Long projectId,
            String runId, String sessionId) {
        usageEventSink.report(new UsageEvent(
                EVENT_PREFIX + "-" + EventsAppService.newRunId(),
                Instant.now(),
                projectId.toString(),
                runId,
                sessionId.isBlank() ? null : sessionId,
                provider.providerKey(),
                generated.model(),
                UsageDims.of(projectId, UsageDims.AGENT_KIND_DESIGNER, sessionId),
                TokenUsage.ZERO,
                1));
    }

    /**
     * 转存一张：URL 形容器内下载、base64 形 stdin 灌字节。落点＝
     * {@code design/{tsid}-{净化词干}.{扩展名}}（每次尝试新 TSID）。
     *
     * @return 成功带落盘件；失败带如实理由（退出码非 0，重试预算内再试）
     */
    private TransferAttempt transfer(String workspaceId, ImageProviderResult.Image image, String name) {
        String stem = name == null || name.isBlank()
                ? DEFAULT_STEM : ProjectMaterials.sanitizeName(name);
        String path = ImageTransfers.storedPath(EventsAppService.newRunId(), stem,
                ImageTransfers.extensionOf(image.extension()));
        ExecResultResponse result;
        long expectedBytes = -1;
        if (image.isUrlForm()) {
            result = workspaceLifecycleAppService.exec(workspaceId,
                    new WorkspaceExecCommand(ImageTransfers.downloadCommand(path, image.url())));
        }
        else {
            byte[] bytes = Base64.getDecoder().decode(image.base64());
            expectedBytes = bytes.length;
            result = workspaceLifecycleAppService.execWithStdin(workspaceId,
                    new WorkspaceExecCommand(ImageTransfers.writeCommand(path)), bytes);
        }
        if (result.exitCode() != 0) {
            return new TransferAttempt(null, "转存失败: " + result.stderr());
        }
        long sizeBytes;
        try {
            sizeBytes = Long.parseLong(result.stdout().trim());
        }
        catch (NumberFormatException e) {
            // exit 0 而 stdout 非字节回执：命令契约破坏，防御性如实暴露（不静默重试）
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "转存回执畸形: " + result.stdout());
        }
        if (expectedBytes >= 0 && sizeBytes != expectedBytes) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "转存回执与灌入字节数不符: " + result.stdout());
        }
        return new TransferAttempt(new ImageGenerationOutcome.LandedImage(path, sizeBytes), null);
    }

    /** 转存尝试的两态（落盘件或失败理由，恰一非空）。 */
    private record TransferAttempt(ImageGenerationOutcome.LandedImage landed, String failure) {
    }

    /** 出图业务面结果（与供数端口结果分立：业务面带落盘清单与部分失败）。 */
    public sealed interface ImageGenerationOutcome {

        /** 成功：供数方/模型标识＋落盘清单（至少一张）＋部分失败清单（可空集）。 */
        record Generated(String provider, String model, List<LandedImage> files,
                List<String> failures) implements ImageGenerationOutcome {
        }

        /** 全部失败：逐张如实理由（有限重试耗尽）。 */
        record Failed(String reason) implements ImageGenerationOutcome {
        }

        /** 一张落盘稿：工作区相对路径＋字节大小。 */
        record LandedImage(String path, long sizeBytes) {
        }
    }
}
