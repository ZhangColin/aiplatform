package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 对话面交指令事实登记（#623，ADR-0056 修改交接）：对话面模型在修改意图明确后调用
 * 交指令工具（{@code submit_modification}），工具执行体把<b>自足修改指令</b>、用户
 * 原话与可选定位说明登记于此；对话面回合收口（{@link DialogAppService}）取走即清，
 * 交 {@link IterationAppService#handOffModification} 派发。
 *
 * <p><b>生命期</b>（与 {@code PrdRevisionFacts} 同口径的进程内事实，run 无表口径）：
 * 对话面回合起跑先清残留 → 轮内工具调用登记（一轮多次调用按序累积）→ 回合正常
 * 收口即消费（取走即清）；回合失败也清（指令不越过一次失败的对话轮自动下发——
 * 对话轮失败语义＝错误呈现＋用户重说）。重启即清，用户重说即恢复。</p>
 *
 * <p><b>为什么不是派发工具</b>：工具只登记事实、不直接起 run——派发归平台应用层，
 * 在对话轮收口后按「空闲即起、在途排队」的轨道口径落地（ADR-0056 两平面互不穿透）。</p>
 */
@Component
public class ModificationHandoffFacts {

    /**
     * 一条交指令载荷（#623）：{@code instruction}＝平台蒸馏出的自足修改指令（指代
     * 已解析成完整句，含讨论要点）；{@code userWords}＝用户本轮原始说法（进迭代
     * 任务提示与版本「用户那句话」）；{@code location}＝随话附件里的定位说明
     * （圈注选择器/文本/页面区域、图片物料路径等，无则 null）。
     */
    public record Instruction(String instruction, String userWords, String location) {
    }

    /** 工作区（一项目一会话）→ 本回合待交接的指令序列。 */
    private final Map<String, List<Instruction>> instructions = new ConcurrentHashMap<>();

    /**
     * 工具执行侧登记（对话面交指令工具调用即事实；一轮多次调用按序累积）。登记与
     * 收口/清残留在同一监视器内串行——对话轮的 REST 起跑（清残留）与该轮异步
     * 执行体的工具登记/消费可能交叠，list 本身不并发安全。
     */
    public synchronized void record(String workspaceId, Instruction instruction) {
        if (instruction == null) {
            return;
        }
        instructions.computeIfAbsent(workspaceId, key -> new ArrayList<>())
                .add(instruction);
    }

    /** 回合收口消费（取走即清）：空列表 = 本回合未交指令。 */
    public synchronized List<Instruction> consume(String workspaceId) {
        List<Instruction> taken = instructions.remove(workspaceId);
        return taken != null ? List.copyOf(taken) : List.of();
    }

    /** 回合起跑/失败清残留：指令不跨回合泄漏。 */
    public synchronized void clear(String workspaceId) {
        instructions.remove(workspaceId);
    }
}
