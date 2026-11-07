package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

import com.semgravio.aiplatform.business.project.application.VerificationFacts;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 验证结果登记工具（verify 子智能体资产，#352）：一轮验证结束时把本轮逐项结果
 * 一次性登记为平台事实——收尾卡验证统计的事实源（平台从工具调用事实观测，不解析
 * 子智能体自由文本；对偶 finish_edit 的判定登记面）。修复—复验环逐轮调用：末轮
 * 快照后写胜出、轮次累加。
 *
 * <p><b>三面口径（#424 载荷重构）</b>：browser＝浏览器主径（验证主轴，含刷新一次
 * 验真落库）、db＝按需下钻（主径断言需要查落库结果时才用）、log＝失败定位（主径
 * 失败时定位原因，不再独立扫日志）——浏览器＋查库＋看日志三面全跑的旧口径退役；
 * 面键封闭集不变（{@link VerificationFacts#FACES}）。</p>
 *
 * <p><b>单场轮次预算（#424 防无限复验）</b>：登记后本场轮次达预算且仍有未过项时，
 * 回执带「预算已尽停止修复—复验」指引（执行体据此停止循环、把未过项如实带入收口
 * 说明——fail 不静默、预算内修、超预算如实呈现）；事实照常登记（平台观测面不因
 * 预算截断）。预算值经 {@code roundBudget} 供数动态读（智能体运营配置面 executor
 * 行，改完下一轮登记即生效——进行中 run 不定格）。</p>
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
    private static final String EXISTING_KEY = "existing";

    private final String workspaceId;
    private final VerificationFacts verificationFacts;

    /** 单场验证轮次预算供数（运营配置动态读——#424 常开硬门的防烧护栏）。 */
    private final IntSupplier roundBudget;

    public ReportVerificationTool(String workspaceId, VerificationFacts verificationFacts,
            IntSupplier roundBudget) {
        super(ToolBase.builder()
                .name(NAME)
                .description("验证结果登记（一轮验证结束时调用一次，checks 传本轮全量）：每项"
                        + " name=验证项名（用户语言，如「留言提交」）、face=三面之一（" + FACE_ENUM_TEXT
                        + "＝浏览器主径〔验证主轴，含刷新一次验真落库〕／查库〔主径断言需要时"
                        + "才下钻〕／看日志〔失败定位用〕）、ok=过没过、"
                        + "detail=证据或失败现场（通过项可省）、existing=能力已存在确认"
                        + "（#420：确认本能力已被前片实现、无需新做——此时 ok 必须 true）。"
                        + "未过项如实 ok=false，不粉饰。")
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
                                                        "description", "证据或失败现场（可省）"),
                                                EXISTING_KEY, Map.of("type", "boolean",
                                                        "description", "能力已存在确认（可省，缺省"
                                                                + " false）：确认本能力已被前片"
                                                                + "实现、无需新做——须 ok=true")),
                                        "required", List.of(NAME_KEY, FACE_KEY, OK_KEY)))),
                        "required", List.of(CHECKS_KEY)))
                .readOnly(true)
                .concurrencySafe(true));
        this.workspaceId = workspaceId;
        this.verificationFacts = verificationFacts;
        this.roundBudget = roundBudget;
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
            Object existing = item.get(EXISTING_KEY);
            boolean existingFlag = Boolean.TRUE.equals(existing);
            if (existingFlag && !okFlag) {
                return Mono.just(ToolResultBlock.error(
                        "checks[" + i + "]：existing=true（能力已存在确认）须 ok=true——"
                                + "「已存在但未过」是矛盾态，未过就如实 ok=false 且不带 existing"));
            }
            Object detail = item.get(DETAIL_KEY);
            parsed.add(new VerificationFacts.Check(String.valueOf(name), faceText, okFlag,
                    detail == null || String.valueOf(detail).isBlank()
                            ? null : String.valueOf(detail),
                    existingFlag));
        }
        verificationFacts.record(workspaceId, parsed);
        long failed = parsed.stream().filter(check -> !check.ok()).count();
        StringBuilder receipt = new StringBuilder("验证结果已登记：本轮 " + parsed.size() + " 项"
                + (failed > 0 ? "，未过 " + failed + " 项（如实）" : "，全部通过"));
        // 单场轮次预算（#424，谓词单点 Facts#budgetExhausted）：达预算仍有未过项＝
        // 停止修复—复验（超预算如实呈现）
        VerificationFacts.Facts current = verificationFacts.peek(workspaceId);
        if (current != null && current.budgetExhausted(roundBudget.getAsInt())) {
            receipt.append("。已达单场验证轮次预算上限（").append(roundBudget.getAsInt())
                    .append(" 轮）：停止修复—复验循环，未过项不再重验——把未过项与证据"
                            + "如实回交 run 执行体带入收口说明（不粉饰、不省略）");
        }
        receipt.append("。把逐项 ✅/❌ 清单与失败项证据以简短文本回交 run 执行体。");
        return Mono.just(ToolResultBlock.text(receipt.toString()));
    }
}
