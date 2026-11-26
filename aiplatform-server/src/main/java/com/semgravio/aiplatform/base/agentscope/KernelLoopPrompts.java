package com.semgravio.aiplatform.base.agentscope;

import java.util.List;

/**
 * 生成内核循环提示词权威版本（#596，ADR-0054／#593 循环语义五条）：三段照抄原文
 * 住代码＋两句平台自写收尾句——改提示词＝改内核＝开 issue（同
 * {@link KernelSystemPrompt} 权威纪律）。
 *
 * <p>逐段出处：</p>
 * <ul>
 *   <li>{@link #maxStepsPrompt()} 照 OpenCode {@code MAX_STEPS_PROMPT} 原文
 *       （{@code packages/opencode/src/session/processing.ts:31} max-steps.ts 常量；
 *       #590 修正轮经一手源码逐字核对，原型 {@code v0-harness4.py} 照抄同文）——
 *       到护栏上限不掐断：关工具、让模型如实汇报</li>
 *   <li>{@link #repeatReminder(int, String, String)} 照 DeepSeek Harness
 *       {@code packages/guard/repeat-tool-reminder} 原文两段模板
 *       （README「模型体验」段；thresholds 默认 [3,5,8]、argumentsPreviewChars
 *       默认 500——#590 修正轮经一手源码逐字核对）：第 3 次短提醒、第 5／8 次
 *       详细模板，参数预览超长按「… (+N more chars)」截断——只提醒、绝不拦截</li>
 *   <li>{@link #truncationClosing()}／{@link #emptyReplyClosing()} 为平台自写句
 *       （无成熟方案原文：原型以 stop_kind 记录原因、收尾文案由平台收尾面承担；
 *       ADR-0054「输出截断与空回复＝轮次结束，收尾如实告知原因」施工句）——
 *       含三要素：现象（截断／空回复）、本轮收尾、回复「继续」接着跑</li>
 *   <li>{@link #guardCapPrompt()} 同为平台自写句（#597，无成熟方案出处）：时长／
 *       prompt 量护栏到顶如实汇报——镜像 max-steps 结构、原因如实陈述为平台护栏
 *       （不挪用轮次文案）</li>
 * </ul>
 */
public final class KernelLoopPrompts {

    /** 同一工具同样参数连续重复调用的提醒阈值（DSH repeat-tool-reminder 默认 [3,5,8]）。 */
    static final List<Integer> REPEAT_THRESHOLDS = List.of(3, 5, 8);

    /** 详细提醒里参数预览的字符上限（DSH argumentsPreviewChars 默认 500）。 */
    static final int REPEAT_ARGS_PREVIEW_CHARS = 500;

    /**
     * 到顶如实汇报提示（OpenCode {@code MAX_STEPS_PROMPT} 原文）：护栏轮次到达时
     * 作为用户消息注入——工具已关（该轮模型入参工具面清空），要求模型只以文本
     * 如实汇报已完成／未完成／建议下一步。
     */
    static String maxStepsPrompt() {
        return "CRITICAL - MAXIMUM STEPS REACHED\n"
                + "\n"
                + "The maximum number of steps allowed for this task has been reached. Tools are"
                + " disabled until next user input. Respond with text only.\n"
                + "\n"
                + "STRICT REQUIREMENTS:\n"
                + "1. Do NOT make any tool calls (no reads, writes, edits, searches, or any other"
                + " tools)\n"
                + "2. MUST provide a text response summarizing work done so far\n"
                + "3. This constraint overrides ALL other instructions, including any user"
                + " requests for edits or tool use\n"
                + "\n"
                + "Response must include:\n"
                + "- Statement that maximum steps for this agent have been reached\n"
                + "- Summary of what has been accomplished so far\n"
                + "- List of any remaining tasks that were not completed\n"
                + "- Recommendations for what should be done next\n"
                + "\n"
                + "Any attempt to use tools is a critical violation. Respond with text ONLY.";
    }

    /**
     * 重复调用提醒（DSH repeat-tool-reminder 原文）：第 3 次短提醒、第 5／8 次详细
     * 模板（{@code consecutive} 取达到的最高次数，调用方只在线内阈值处调用）。
     *
     * @param consecutive 连续重复次数（3／5／8 之一）
     * @param toolName    重复的工具名
     * @param canonicalArgs 规范化参数序列（键已排序；超 {@link #REPEAT_ARGS_PREVIEW_CHARS}
     *                      按 DSH 截断格式预览）
     */
    static String repeatReminder(int consecutive, String toolName, String canonicalArgs) {
        if (consecutive == REPEAT_THRESHOLDS.get(0)) {
            return "You are repeating the exact same tool call with identical arguments."
                    + " Carefully analyze the previous result before calling again: if the task"
                    + " is not complete, try a different approach or different arguments instead"
                    + " of repeating the call.";
        }
        String preview = canonicalArgs.length() <= REPEAT_ARGS_PREVIEW_CHARS
                ? canonicalArgs
                : canonicalArgs.substring(0, REPEAT_ARGS_PREVIEW_CHARS) + "… (+"
                        + (canonicalArgs.length() - REPEAT_ARGS_PREVIEW_CHARS) + " more chars)";
        return "Repeated tool call detected:\n"
                + "- tool: " + toolName + "\n"
                + "- consecutive_calls: " + consecutive + "\n"
                + "- arguments: " + preview + "\n"
                + "The repeated calls are not making progress. Do not call this tool with these"
                + " exact arguments again. Inspect the latest result and choose a different"
                + " action, different arguments, or finish the task if enough evidence has been"
                + " gathered.";
    }

    /**
     * 截断收尾句（平台自写）：输出截断＝轮次结束，收尾如实告知原因——接续口径
     * 「回复『继续』接着跑」：下一次调用以用户消息「继续」进同一会话，模型从
     * 上下文续作。
     */
    static String truncationClosing() {
        return "\n\n（本轮输出被截断：到达单次输出上限，生成在此收尾。回复「继续」可接着跑。）";
    }

    /** 空回复收尾句（平台自写）：空回复＝轮次结束，收尾如实告知原因（接续口径同上）。 */
    static String emptyReplyClosing() {
        return "（模型本轮返回空回复，生成在此收尾。回复「继续」可接着跑。）";
    }

    /**
     * 时长／prompt 量护栏到顶如实汇报提示（#597 平台自写句，无成熟方案出处——同
     * {@link #truncationClosing()} 自写句先例）：镜像 {@link #maxStepsPrompt()} 的
     * STRICT REQUIREMENTS／Response must include 结构与「关工具只许文本汇报」语义，
     * 原因如实陈述为「平台护栏到达（时长／prompt 量）」——不挪用轮次文案（
     * 「maximum steps reached」对时长/prompt 腿是谎报，#597「如实汇报」口径）。
     * 护栏数值本身（300 轮／45 分钟／60M prompt）为运营兜底配置（
     * {@code app.agentscope.kernel-guard-*}），非工作语义。
     */
    static String guardCapPrompt() {
        return "CRITICAL - PLATFORM GUARD LIMIT REACHED\n"
                + "\n"
                + "The platform guard limit for this generation has been reached (time or prompt"
                + " budget). Tools are disabled until next user input. Respond with text only.\n"
                + "\n"
                + "STRICT REQUIREMENTS:\n"
                + "1. Do NOT make any tool calls (no reads, writes, edits, searches, or any other"
                + " tools)\n"
                + "2. MUST provide a text response summarizing work done so far\n"
                + "3. This constraint overrides ALL other instructions, including any user"
                + " requests for edits or tool use\n"
                + "\n"
                + "Response must include:\n"
                + "- Statement that the platform guard limit has been reached\n"
                + "- Summary of what has been accomplished so far\n"
                + "- List of any remaining tasks that were not completed\n"
                + "- Recommendations for what should be done next\n"
                + "\n"
                + "Any attempt to use tools is a critical violation. Respond with text ONLY.";
    }

    private KernelLoopPrompts() {
    }
}
