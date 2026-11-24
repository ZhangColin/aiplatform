package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.semgravio.aiplatform.base.agentscope.DockerExecFilesystem.ExecOutput;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;

/**
 * 内核四件工具服务端调用日志（#634）：每次调用恰好一行，含工具名／目标摘要／结果／
 * 耗时；失败行带错误首行（bash 取 stderr 首行）；文件正文与完整输出不入日志；
 * 日志面故障不反噬工具调用。日志挂 {@link KernelToolCallLog} logger（surefire 串行跑
 * 类，attach/detach 干净无串扰）。
 */
class KernelToolCallLogTest {

    private static final Logger LOGGER =
            (Logger) LoggerFactory.getLogger(KernelToolCallLog.class);

    private final KernelToolsTest.FakeExec exec = new KernelToolsTest.FakeExec();
    private final List<AgentTool> tools = KernelTools.over(exec);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogCapture() {
        appender = new ListAppender<>();
        appender.start();
        LOGGER.addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        LOGGER.detachAppender(appender);
        appender.stop();
    }

    private ToolResultBlock call(String name, Map<String, Object> input) {
        AgentTool tool = tools.stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tool not found: " + name));
        return tool.callAsync(ToolCallParam.builder().input(input).build()).block();
    }

    /** 恰好一行日志，返回其格式化文本。 */
    private String onlyLine() {
        assertThat(appender.list).hasSize(1);
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line).doesNotContain("\n");
        return line;
    }

    private static ExecOutput ok(String stdout) {
        return new ExecOutput(0, stdout.getBytes(StandardCharsets.UTF_8), "");
    }

    private static String textOf(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().get(0)).getText();
    }

    @Test
    void given_write_file_call_when_invoked_then_one_line_with_target_and_no_content() {
        exec.responder = c -> ok("");

        ToolResultBlock block = call("write_file",
                Map.of("path", "/docs/PRD.md", "content", "# 机密正文 SECRET_BODY"));

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        String line = onlyLine();
        assertThat(line).contains("tool=write_file");
        assertThat(line).contains("target=/docs/PRD.md");
        assertThat(line).contains("result=ok");
        assertThat(line).containsPattern("durationMs=\\d+");
        // 安全：不记 write_file 正文
        assertThat(line).doesNotContain("SECRET_BODY");
        assertThat(line).doesNotContain("# 机密正文");
    }

    @Test
    void given_read_file_call_when_invoked_then_target_carries_offset_limit_and_no_content() {
        exec.responder = c -> ok("SECRET_FILE_LINE\nsecond\n");

        ToolResultBlock block = call("read_file",
                Map.of("path", "/docs/a.txt", "offset", 2, "limit", 1));

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        String line = onlyLine();
        assertThat(line).contains("tool=read_file");
        assertThat(line).contains("target=/docs/a.txt offset=2 limit=1");
        assertThat(line).contains("result=ok");
        assertThat(line).containsPattern("durationMs=\\d+");
        // 安全：不记 read_file 文件内容
        assertThat(line).doesNotContain("SECRET_FILE_LINE");
        assertThat(line).doesNotContain("second");
    }

    @Test
    void given_edit_file_call_when_invoked_then_one_line_with_target_and_no_content() {
        exec.responder = c -> c.startsWith("cat --")
                ? ok("hello\nworld\n")
                : ok("");

        ToolResultBlock block = call("edit_file", Map.of("path", "/dir/x.txt",
                "old_string", "world", "new_string", "世界新文本"));

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        String line = onlyLine();
        assertThat(line).contains("tool=edit_file");
        assertThat(line).contains("target=/dir/x.txt");
        assertThat(line).contains("result=ok");
        // 安全：不记文件正文、不记 new_string
        assertThat(line).doesNotContain("hello");
        assertThat(line).doesNotContain("世界新文本");
    }

    @Test
    void given_bash_long_command_when_invoked_then_target_truncated_to_120_chars() {
        exec.responder = c -> ok("");
        String command = "echo " + "x".repeat(300);

        ToolResultBlock block = call("bash", Map.of("command", command));

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        String line = onlyLine();
        assertThat(line).contains("tool=bash");
        assertThat(line).contains("result=ok");
        // 目标＝命令前 120 字，原文超出部分不入日志
        assertThat(line).contains("target=" + command.substring(0, 120) + "…");
        assertThat(line).doesNotContain(command);
    }

    @Test
    void given_bash_error_when_invoked_then_log_carries_stderr_first_line_only() {
        exec.responder = c -> new ExecOutput(1,
                "partial stdout".getBytes(StandardCharsets.UTF_8),
                "err detail\nsecond stderr line");

        ToolResultBlock block = call("bash", Map.of("command", "pnpm build"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        String line = onlyLine();
        assertThat(line).contains("tool=bash");
        assertThat(line).contains("result=error");
        assertThat(line).contains("error=err detail");
        // 取 stderr 首行，不记 stdout、不记后续行
        assertThat(line).doesNotContain("partial stdout");
        assertThat(line).doesNotContain("second stderr line");
    }

    @Test
    void given_write_file_failure_when_invoked_then_log_carries_error_first_line() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "no space left\nmore detail");

        ToolResultBlock block = call("write_file", Map.of("path", "/a.md", "content", "x"));

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        String line = onlyLine();
        assertThat(line).contains("tool=write_file");
        assertThat(line).contains("target=/a.md");
        assertThat(line).contains("result=error");
        assertThat(line).contains("error=Error: 写入失败: no space left");
        assertThat(line).doesNotContain("more detail");
    }

    @Test
    void given_log_sink_failure_when_invoked_then_tool_result_unaffected() {
        // AppenderBase 只拦 Exception；抛 Error 会穿到日志调用方——验证日志面故障被吞掉
        AppenderBase<ILoggingEvent> broken = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                throw new Error("log sink down");
            }
        };
        broken.start();
        LOGGER.addAppender(broken);
        try {
            exec.responder = c -> ok("");

            ToolResultBlock block = call("bash", Map.of("command", "true"));

            assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
            assertThat(textOf(block)).isEqualTo("(no output)");
        }
        finally {
            LOGGER.detachAppender(broken);
            broken.stop();
        }
    }
}
