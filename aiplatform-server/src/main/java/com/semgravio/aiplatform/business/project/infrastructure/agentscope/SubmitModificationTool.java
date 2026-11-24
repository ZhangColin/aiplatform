package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;

import com.semgravio.aiplatform.business.project.application.ModificationHandoffFacts;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 交修改指令工具（#623，ADR-0056 修改交接）：对话面<b>唯一的修改出口</b>——用户
 * 想改系统且要改什么已说清楚时，模型交出「自足指令＋用户原话（＋附件定位说明）」，
 * 平台据此恰起一轮内核重跑。工具本身不起 run、不写工作区、不写 PRD：只把载荷
 * 登记到 {@link ModificationHandoffFacts}（工具执行事实），回合收口后由
 * {@code DialogAppService} 交 {@code IterationAppService} 按轨道口径派发（空闲即
 * 起、在途进待办并轻提示）。
 *
 * <p><b>说明不清先追问</b>：软确认是模型行为（对话面协议：拿不准先 ask_user），
 * 本工具不设平台确认门；工具只校验载荷非空（instruction/userWords 必填），
 * 缺字段回模型可见错误结果、不登记。</p>
 *
 * <p>只随对话面工具面注册（{@link ProfileToolkitSupplier} 的 {@code plane=dialog}
 * 装配分支）——首建访谈主智能体工具面没有本件（访谈期不交修改指令）。{@code readOnly}
 * 为真：工作区零写入，派发归平台应用层。</p>
 */
@Slf4j
public class SubmitModificationTool extends ToolBase {

    /** 注册名（对话面唯一修改出口）。 */
    public static final String NAME = "submit_modification";

    private static final String INSTRUCTION_KEY = "instruction";
    private static final String USER_WORDS_KEY = "userWords";
    private static final String LOCATION_KEY = "location";

    private final String workspaceId;
    private final ModificationHandoffFacts handoffFacts;

    public SubmitModificationTool(String workspaceId, ModificationHandoffFacts handoffFacts) {
        super(ToolBase.builder()
                .name(NAME)
                .description("提交一条已经说清楚了的系统修改指令（对话面唯一的修改出口）。"
                        + "只在用户想改系统、且要改什么已明确时调用（拿不准先用 ask_user 澄清）。"
                        + "instruction 传自足的修改指令：把「还是不行」「那个再调大一点」这类"
                        + "指代解析成完整句子，写清要改什么、改成什么样；userWords 传用户本轮的"
                        + "原始说法；location 传随话附件里的定位说明（圈注的选择器/元素文本/"
                        + "页面区域、图片物料的工作区路径等），没有就不填。调用后平台会据此发起"
                        + "一轮系统修改，你只需回用户一句简短确认，真正的修改过程由随后出现的"
                        + "工作消息承载。不要用它提问、闲聊或泛泛承诺。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                INSTRUCTION_KEY, Map.of(
                                        "type", "string",
                                        "description", "自足的修改指令：指代解析成完整句，写清要改什么、"
                                                + "改成什么样（含讨论中确定的要点与验收口径）"),
                                USER_WORDS_KEY, Map.of(
                                        "type", "string",
                                        "description", "用户本轮想改系统的原始说法（原话，不改写）"),
                                LOCATION_KEY, Map.of(
                                        "type", "string",
                                        "description", "随话附件里的定位说明（圈注选择器/元素文本/页面区域、"
                                                + "图片物料路径等），无则省略")),
                        "required", List.of(INSTRUCTION_KEY, USER_WORDS_KEY)))
                .readOnly(true)
                .concurrencySafe(true));
        this.workspaceId = workspaceId;
        this.handoffFacts = handoffFacts;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String instruction = textField(param, INSTRUCTION_KEY);
        if (instruction == null) {
            return Mono.just(ToolResultBlock.error(
                    "instruction 不能为空：传自足的修改指令（指代解析成完整句）后再次调用本工具"));
        }
        String userWords = textField(param, USER_WORDS_KEY);
        if (userWords == null) {
            return Mono.just(ToolResultBlock.error(
                    "userWords 不能为空：传用户本轮的原始说法后再次调用本工具"));
        }
        String location = textField(param, LOCATION_KEY);
        handoffFacts.record(workspaceId,
                new ModificationHandoffFacts.Instruction(instruction, userWords, location));
        log.info("[dialog] 工作区 {} 对话面交修改指令（{} 字，定位{}）", workspaceId,
                instruction.length(), location != null ? "有" : "无");
        return Mono.just(ToolResultBlock.text("已提交修改指令，平台将据此发起一轮系统修改。"));
    }

    /** 入参文本字段（null/空白 → null）。 */
    private static String textField(ToolCallParam param, String key) {
        Object value = param.getInput() != null ? param.getInput().get(key) : null;
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }
}
