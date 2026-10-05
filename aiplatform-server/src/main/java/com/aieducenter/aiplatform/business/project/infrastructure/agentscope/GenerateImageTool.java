package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.business.project.application.ImageGenerationAppService;
import com.aieducenter.aiplatform.business.project.application.ImageGenerationAppService.ImageGenerationOutcome;

/**
 * 出图工具件（#288 出图工具件内核，ADR-0026/0027；发放接线归 #289 设计执行体票
 * ——工具面＝写文件件＋本件、无 shell）：调图片生成模型出位图稿、平台即时转存
 * 落盘产物目录 {@code design/}——供应商 URL 只中转不透出（工具结果只回落盘路径
 * 与字节）。<b>档位表落提示词层</b>（ADR-0026：写实氛围/复杂光影/插画向用本件；
 * 文字排版为主的海报卡片写 HTML——语义判断归执行体，不做代码级硬路由）。
 * 技术失败（报错/拦截/超时）内核有限自动重试、不产新候选——工具面只见最终结果。
 *
 * <p>参数：{@code prompt}（画面正向描述，必传）；{@code count} 1~5 缺省 1（多稿
 * 候选数量归执行体协议约定）；{@code size} 可选（画幅，供应商词表原样透传）；
 * {@code name} 可选（落盘词干提示，平台消毒＋TSID 前缀防撞）。计量（按张事件）
 * 与转存归 {@link ImageGenerationAppService} 内核；run/会话标识经
 * {@link AgentscopeAgentClient#RUN_ID_CONTEXT_KEY} 上下文腿透传（#259 先例）。</p>
 */
public class GenerateImageTool extends ToolBase {

    /** 注册名（#289 装配面与 AgentTool 枚举登记锚——本票只立内核不发放）。 */
    public static final String NAME = "generate_image";

    private static final String PROMPT_KEY = "prompt";
    private static final String COUNT_KEY = "count";
    private static final String SIZE_KEY = "size";
    private static final String NAME_KEY = "name";

    private static final int DEFAULT_COUNT = 1;

    private final String workspaceId;
    private final ImageGenerationAppService generation;

    public GenerateImageTool(String workspaceId, ImageGenerationAppService generation) {
        super(ToolBase.builder()
                .name(NAME)
                .description("图片生成模型出位图设计稿，落盘到工作区 design/ 目录并返回文件路径。"
                        + "适用档位（重要）：写实照片质感、复杂光影、插画氛围向的画面用本工具；"
                        + "以文字排版为主的海报/卡片/横幅不要用本工具——写 HTML/CSS 再由平台渲 PNG"
                        + "（文字像素级可控，图片模型画长文案易错字漏字）。prompt 传完整的画面正向"
                        + "描述（主体、风格、配色、氛围；画面内文字尽量短）；size 可选（画幅，"
                        + "如 1024x1024）；count 出几张（1~5，探索多稿时用）；name 可选（文件名"
                        + "提示，如 logo-方案A）。失败会自动重试，仍失败会如实返回原因。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                PROMPT_KEY, Map.of(
                                        "type", "string",
                                        "description", "画面正向描述：主体、风格、配色、氛围，"
                                                + "画面内文字尽量缩短为标题短语"),
                                COUNT_KEY, Map.of(
                                        "type", "integer",
                                        "description", "出图张数（1~5，缺省 1；多稿探索时传）"),
                                SIZE_KEY, Map.of(
                                        "type", "string",
                                        "description", "画幅（可选，如 1024x1024；不传用供应商"
                                                + "缺省）"),
                                NAME_KEY, Map.of(
                                        "type", "string",
                                        "description", "文件名词干提示（可选，如 logo-方案A；"
                                                + "平台自动加唯一前缀）")),
                        "required", List.of(PROMPT_KEY)))
                .readOnly(false)
                .concurrencySafe(false));
        this.workspaceId = workspaceId;
        this.generation = generation;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            PermissionContextState context) {
        // 出稿是设计执行体的预期动作（无确认门概念），工具点不放确认
        return Mono.just(PermissionDecision.allow("出图落盘是设计过程的预期动作，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        Object prompt = input != null ? input.get(PROMPT_KEY) : null;
        if (prompt == null || String.valueOf(prompt).isBlank()) {
            return Mono.just(ToolResultBlock.error("prompt 不能为空：传完整的画面正向描述"));
        }
        Integer count = countOf(input == null ? null : input.get(COUNT_KEY));
        if (count == null) {
            return Mono.just(ToolResultBlock.error("count 须是 1~5 的整数（缺省 1）"));
        }
        RuntimeContext ctx = param.getRuntimeContext();
        String runId = ctx == null ? null : ctx.get(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY);
        String sessionId = ctx == null ? null : ctx.getSessionId();
        try {
            ImageGenerationOutcome outcome = generation.generate(
                    workspaceId, runId, sessionId,
                    String.valueOf(prompt).strip(), count,
                    textOf(input, SIZE_KEY), textOf(input, NAME_KEY));
            return Mono.just(switch (outcome) {
                case ImageGenerationOutcome.Generated generated -> ToolResultBlock.text(
                        format(generated));
                case ImageGenerationOutcome.Failed failed -> ToolResultBlock.error(
                        failed.reason());
            });
        }
        catch (RuntimeException e) {
            // 平台侧失败（供应商未配置/项目解析/回执畸形）如实回给模型，不炸会话
            return Mono.just(ToolResultBlock.error("出图失败: " + e.getMessage()));
        }
    }

    /** 成功结果 → 纯文本（落盘路径＋字节；部分失败如实附注——URL 永不透出）。 */
    private static String format(ImageGenerationOutcome.Generated generated) {
        StringBuilder out = new StringBuilder("已出图并落盘（").append(generated.provider())
                .append('/').append(generated.model()).append("）：");
        for (ImageGenerationOutcome.LandedImage file : generated.files()) {
            out.append("\n").append(file.path()).append("（").append(file.sizeBytes())
                    .append(" 字节）");
        }
        if (!generated.failures().isEmpty()) {
            out.append("\n部分失败（可对缺稿重出）：").append(String.join("；", generated.failures()));
        }
        return out.toString();
    }

    private static Integer countOf(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return DEFAULT_COUNT;
        }
        try {
            int count = Integer.parseInt(String.valueOf(raw).strip());
            return count >= 1 && count <= 5 ? count : null;
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textOf(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object raw = input.get(key);
        return raw == null || String.valueOf(raw).isBlank() ? null : String.valueOf(raw).strip();
    }
}
