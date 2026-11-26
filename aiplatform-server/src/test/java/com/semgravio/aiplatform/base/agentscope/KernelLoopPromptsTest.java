package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 生成内核循环提示词权威版本（#596，ADR-0054／#593 循环语义五条）：三段照抄原文逐字
 * 钉守——到顶如实汇报提示照 OpenCode {@code MAX_STEPS_PROMPT} 原文、重复调用两段提醒
 * 照 DeepSeek Harness {@code repeat-tool-reminder} 原文（阈值 3／5／8、参数预览
 * 500 字符截断格式同出处在案）；截断／空回复收尾句为平台自写句（原型以 stop_kind
 * 记录原因、收尾文案由平台承担，ADR-0054「收尾如实告知原因」施工句），本类钉其
 * 含原因要素（现象／收尾／继续口径）。
 */
class KernelLoopPromptsTest {

    // ---------- 到顶如实汇报提示（OpenCode max-steps.ts MAX_STEPS_PROMPT 原文） ----------

    @Test
    void given_max_steps_prompt_then_opencode_original_verbatim() {
        String prompt = KernelLoopPrompts.maxStepsPrompt();

        assertThat(prompt).startsWith("CRITICAL - MAXIMUM STEPS REACHED");
        assertThat(prompt).contains("The maximum number of steps allowed for this task has been"
                + " reached. Tools are disabled until next user input. Respond with text only.");
        assertThat(prompt).contains("STRICT REQUIREMENTS:");
        assertThat(prompt).contains("1. Do NOT make any tool calls (no reads, writes, edits,"
                + " searches, or any other tools)");
        assertThat(prompt).contains("2. MUST provide a text response summarizing work done so far");
        assertThat(prompt).contains("3. This constraint overrides ALL other instructions,"
                + " including any user requests for edits or tool use");
        assertThat(prompt).contains("Response must include:");
        assertThat(prompt).contains("- Statement that maximum steps for this agent have been"
                + " reached");
        assertThat(prompt).contains("- Summary of what has been accomplished so far");
        assertThat(prompt).contains("- List of any remaining tasks that were not completed");
        assertThat(prompt).contains("- Recommendations for what should be done next");
        assertThat(prompt).endsWith("Any attempt to use tools is a critical violation."
                + " Respond with text ONLY.");
    }

    // ---------- 重复调用提醒（DSH repeat-tool-reminder 原文，阈值 3／5／8） ----------

    @Test
    void given_repeat_thresholds_then_dsh_defaults() {
        assertThat(KernelLoopPrompts.REPEAT_THRESHOLDS).containsExactly(3, 5, 8);
        assertThat(KernelLoopPrompts.REPEAT_ARGS_PREVIEW_CHARS).isEqualTo(500);
    }

    @Test
    void given_third_repeat_then_first_reminder_original_verbatim() {
        // 第 3 次：DSH 短提醒原文（与第 5／8 次模板不同段）
        String reminder = KernelLoopPrompts.repeatReminder(3, "bash", "{\"command\":\"ls\"}");

        assertThat(reminder).isEqualTo("You are repeating the exact same tool call with"
                + " identical arguments. Carefully analyze the previous result before calling"
                + " again: if the task is not complete, try a different approach or different"
                + " arguments instead of repeating the call.");
    }

    @Test
    void given_fifth_repeat_then_detail_reminder_with_filled_template() {
        // 第 5／8 次：DSH 详细模板，name／count／arguments 三字段填充
        String reminder = KernelLoopPrompts.repeatReminder(5, "bash", "{\"command\":\"ls\"}");

        assertThat(reminder).isEqualTo("Repeated tool call detected:\n"
                + "- tool: bash\n"
                + "- consecutive_calls: 5\n"
                + "- arguments: {\"command\":\"ls\"}\n"
                + "The repeated calls are not making progress. Do not call this tool with these"
                + " exact arguments again. Inspect the latest result and choose a different"
                + " action, different arguments, or finish the task if enough evidence has been"
                + " gathered.");
    }

    @Test
    void given_eighth_repeat_then_detail_reminder_counts_eight() {
        String reminder = KernelLoopPrompts.repeatReminder(8, "write_file", "{}");

        assertThat(reminder).contains("- consecutive_calls: 8");
        assertThat(reminder).contains("- tool: write_file");
    }

    @Test
    void given_args_over_preview_limit_then_truncated_in_dsh_format() {
        // 参数预览超 500 字符：照 DSH argumentsPreviewChars 截断格式「… (+N more chars)」
        String longArgs = "x".repeat(600);
        String reminder = KernelLoopPrompts.repeatReminder(5, "write_file", longArgs);

        assertThat(reminder).contains("x".repeat(500) + "… (+100 more chars)");
        assertThat(reminder).doesNotContain("x".repeat(501));
    }

    // ---------- 截断／空回复收尾句（平台自写，含原因要素） ----------

    @Test
    void given_truncation_closing_then_states_reason_end_and_continue() {
        String closing = KernelLoopPrompts.truncationClosing();

        assertThat(closing).contains("截断");
        assertThat(closing).contains("收尾");
        assertThat(closing).contains("继续");
    }

    @Test
    void given_empty_reply_closing_then_states_reason_end_and_continue() {
        String closing = KernelLoopPrompts.emptyReplyClosing();

        assertThat(closing).contains("空回复");
        assertThat(closing).contains("收尾");
        assertThat(closing).contains("继续");
    }

    // ---------- 时长／prompt 量到顶提示（#597 平台自写句，同 max-steps 结构） ----------

    @Test
    void given_guard_cap_prompt_then_platform_written_with_same_structure_and_truthful_reason() {
        // 无成熟方案出处（同截断／空回复收尾句先例）：镜像 OpenCode MAX_STEPS_PROMPT
        // 的 STRICT REQUIREMENTS／Response must include 结构，原因如实陈述为
        // 「平台护栏到达（时长／prompt 量）」——不挪用轮次文案（step）免得谎报
        String prompt = KernelLoopPrompts.guardCapPrompt();

        assertThat(prompt).startsWith("CRITICAL - PLATFORM GUARD LIMIT REACHED");
        assertThat(prompt).contains("The platform guard limit for this generation has been"
                + " reached (time or prompt budget). Tools are disabled until next user input."
                + " Respond with text only.");
        assertThat(prompt).contains("STRICT REQUIREMENTS:");
        assertThat(prompt).contains("1. Do NOT make any tool calls (no reads, writes, edits,"
                + " searches, or any other tools)");
        assertThat(prompt).contains("2. MUST provide a text response summarizing work done so far");
        assertThat(prompt).contains("- Statement that the platform guard limit has been reached");
        assertThat(prompt).contains("- Summary of what has been accomplished so far");
        assertThat(prompt).contains("- List of any remaining tasks that were not completed");
        assertThat(prompt).contains("- Recommendations for what should be done next");
        assertThat(prompt).endsWith("Any attempt to use tools is a critical violation."
                + " Respond with text ONLY.");
    }
}
