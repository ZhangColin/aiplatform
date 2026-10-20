package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aieducenter.aiplatform.business.project.application.VerificationFacts;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 验证结果登记工具（verify 子智能体资产，#352）：一轮验证结束时把本轮三面
 * （浏览器点流程／查库／看日志）逐项结果一次性登记为平台事实——收尾卡验证统计
 * 的事实源（平台从工具调用事实观测，不解析子智能体自由文本；对偶 finish_edit
 * 的判定登记面）。修复—复验环逐轮调用：末轮快照后写胜出、轮次累加。
 *
 * <p>效果仅平台侧事实登记（不动工作区），readOnly；无需用户确认（登记是验证
 * 协议的预期终点）。仅随项目 dev 工作区的 verify 子键视图注册（{@link
 * ProfileToolkitSupplier}），挂载治理归子智能体声明 allowlist。</p>
 */
public class ReportVerificationTool extends ToolBase {

    public static final String NAME = "report_verification";

    /** 三面封闭集（{@link VerificationFacts#FACES} 同义快捷——校验不解释语义）。 */
    static final Set<String> FACES = Set.copyOf(VerificationFacts.FACES);

    /** 三面键的枚举串（描述与报错文案同源引用——扩面只改 {@link VerificationFacts#FACES}）。 */
    private static final String FACE_ENUM_TEXT = String.join("/", VerificationFacts.FACES);

    private static final String CHECKS_KEY = "checks";
    private static final String NAME_KEY = "name";
    private static final String FACE_KEY = "face";
    private static final String OK_KEY = "ok";
    private static final String DETAIL_KEY = "detail";

    private final String workspaceId;
    private final VerificationFacts verificationFacts;

    public ReportVerificationTool(String workspaceId, VerificationFacts verificationFacts) {
        super(ToolBase.builder()
                .name(NAME)
                .description("验证结果登记（一轮验证结束时调用一次，checks 传本轮全量）：每项"
                        + " name=验证项名（用户语言，如「留言提交」）、face=三面之一（" + FACE_ENUM_TEXT
                        + "＝浏览器点流程／查库／看日志）、ok=过没过、"
                        + "detail=证据或失败现场（通过项可省）。未过项如实 ok=false，不粉饰。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(CHECKS_KEY, Map.of(
                                "type", "array",
                                "description", "本轮验证项全量清单",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                NAME_KEY, Map.of("type", "string",
                                                        "description", "验证项名（用户语言）"),
                                                FACE_KEY, Map.of("type", "string",
                                                        "description", "三面之一：" + FACE_ENUM_TEXT),
                                                OK_KEY, Map.of("type", "boolean",
                                                        "description", "过没过"),
                                                DETAIL_KEY, Map.of("type", "string",
                                                        "description", "证据或失败现场（可省）")),
                                        "required", List.of(NAME_KEY, FACE_KEY, OK_KEY)))),
                        "required", List.of(CHECKS_KEY)))
                .readOnly(true)
                .concurrencySafe(true));
        this.workspaceId = workspaceId;
        this.verificationFacts = verificationFacts;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 结果登记是验证协议的预期终点，工具点不放确认
        return Mono.just(PermissionDecision.allow("验证结果登记是协议的预期终点，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
        Object checks = input.get(CHECKS_KEY);
        if (!(checks instanceof List<?> list) || list.isEmpty()) {
            return Mono.just(ToolResultBlock.error(
                    "checks 必须是非空数组：一轮验证的全部项一次性登记"));
        }
        // 整轮原子：先全量校验再登记——任一项坏即整轮不登记（部分登记会让统计失真）
        List<VerificationFacts.Check> parsed = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> item)) {
                return Mono.just(ToolResultBlock.error("checks[" + i + "] 必须是对象"));
            }
            Object name = item.get(NAME_KEY);
            Object face = item.get(FACE_KEY);
            Object ok = item.get(OK_KEY);
            if (name == null || String.valueOf(name).isBlank()) {
                return Mono.just(ToolResultBlock.error("checks[" + i + "].name 不能为空"));
            }
            if (!(face instanceof String faceText) || !FACES.contains(faceText)) {
                return Mono.just(ToolResultBlock.error(
                        "checks[" + i + "].face 必须是三面之一：" + FACE_ENUM_TEXT));
            }
            if (!(ok instanceof Boolean okFlag)) {
                return Mono.just(ToolResultBlock.error(
                        "checks[" + i + "].ok 必须是布尔值：过传 true、未过传 false"));
            }
            Object detail = item.get(DETAIL_KEY);
            parsed.add(new VerificationFacts.Check(String.valueOf(name), faceText, okFlag,
                    detail == null || String.valueOf(detail).isBlank()
                            ? null : String.valueOf(detail)));
        }
        verificationFacts.record(workspaceId, parsed);
        long failed = parsed.stream().filter(check -> !check.ok()).count();
        return Mono.just(ToolResultBlock.text("验证结果已登记：本轮 " + parsed.size() + " 项"
                + (failed > 0 ? "，未过 " + failed + " 项（如实）" : "，全部通过")
                + "。把逐项 ✅/❌ 清单与失败项证据以简短文本回交 run 执行体。"));
    }
}
