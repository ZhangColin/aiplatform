package com.semgravio.aiplatform.base.agentscope;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import reactor.core.publisher.Mono;

/**
 * 生成内核工具四件（#594，ADR-0054 工具面）：{@code write_file}（覆盖全量写、
 * 自动建父目录）／{@code read_file}（带行号、可分段、有上限）／{@code edit_file}
 * （旧文本恰好出现一次才替换，没找到／不唯一／文件不存在三种错误如实报）／
 * {@code bash}（180 秒超时）——经 {@link DockerExecFilesystem.ExecCommand}
 * 缝落项目沙箱容器工作区根（{@link AgentWorkspace.ProjectDev#CONTAINER_ROOT}），
 * 与旧工具面（框架 FilesystemTool／ShellExecuteTool，{@link HarnessBuiltinTools}）
 * 并存，本票不拆旧（#594）。
 *
 * <p>规格出处照 {@code prototype/v0-firstrun2} 分支 {@code v0-harness4.py}
 * （#590 修正轮，用户已裁决）＋ DeepSeek Harness {@code packages/fs/tool-fs}
 * （master@4b399c3）：工具说明文字照 DSH 原文、只换工具名（{@code file_path}
 * 改名 {@code path}、去「resolved by the filesystem backend」——差别清单见该分支
 * README 出处表）；read 渲染（行号／分段 footer／行 2000 字符与总 51200 字节上限）
 * 照 {@code read.ts}／{@code read-render.ts}；edit 三种错误文案与成功文案照
 * {@code str-replace-editor}、非 UTF-8 拒因照脚本自写中文句原文；write 说明句
 * 自加「自动建父目录」（#588 决议施工项）——参数说明原型沿旧脚本未写，平台按
 * DSH {@code write.ts} 原文补（同一改名／去 backend 句差别）。bash 说明文字为
 * 原型自写句（DSH 的 bash 件是 persistent/background 形态，超出内核四件范围——
 * 出处表未列 bash 照抄条目）；bash 空 stderr 省略标记、双空回 {@code (no output)}
 * 为有意收紧（原型的无条件 {@code --stderr--} 标记令 {@code (no output)} 成为
 * 不可达死代码），非空输出形态与脚本一致。</p>
 *
 * <p>①族守卫（#440 同族，Windows 宿主限定）：bash 面智能体命令先过 argv 双引号
 * ／{@code $(} 守卫，拒命不触容器缝（防 sh 引号不配对从 stdin 无界挂死）——与
 * {@link DockerExecFilesystem#execute} 的平台性口径一致（os.name 条件化，
 * Linux execve 原样传 argv 不启用）。</p>
 */
public final class KernelTools {

    /** 内核四件注册名（#595 装配收口单点：构建后工具面恰四件的判定正本）。 */
    static final List<String> NAMES = List.of("write_file", "read_file", "edit_file", "bash");

    /** 真实装配：给定项目沙箱容器名构建四件（docker exec 落容器工作区根）。 */
    public static List<AgentTool> forContainer(String containerName) {
        return over(new DockerExecFilesystem.DockerExecCommand(containerName,
                AgentWorkspace.ProjectDev.CONTAINER_ROOT));
    }

    /** 执行缝注入（单测替身）。 */
    static List<AgentTool> over(DockerExecFilesystem.ExecCommand exec) {
        return over(exec, DockerExecFilesystem.isWindowsHostOs());
    }

    /** 执行缝＋宿主形态注入（单测两形态各测，OS 无关）。 */
    static List<AgentTool> over(DockerExecFilesystem.ExecCommand exec, boolean windowsHost) {
        return List.of(
                new WriteFileTool(exec),
                new ReadFileTool(exec),
                new EditFileTool(exec),
                new BashTool(exec, windowsHost));
    }

    private KernelTools() {
    }

    /**
     * 输入路径解析（三件共用）：空／空白回「path must be a non-empty string」；
     * 含 {@code ..} 逃逸回「路径非法」指引；合法回容器绝对路径。
     */
    private record ResolvedPath(String containerPath, String error) {
    }

    private static ResolvedPath resolvePath(Object path) {
        if (path == null || String.valueOf(path).isBlank()) {
            return new ResolvedPath(null, "path must be a non-empty string");
        }
        String containerPath = containerPathOrNull(String.valueOf(path));
        if (containerPath == null) {
            return new ResolvedPath(null, "路径非法: " + path
                    + "（路径须在工作区内、不得含 ..）——修正后重试");
        }
        return new ResolvedPath(containerPath, null);
    }

    /**
     * UTF-8 严格解码（read／edit 共用）：畸形输入如实回 null（调用方转
     * cannot-read-as-text 错误），不静默替换字符。
     */
    private static String decodeUtf8OrNull(byte[] bytes) {
        try {
            return Charset.forName("UTF-8")
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
        catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 输入路径 → 容器绝对路径；非法（空白／含 ..）返回 null。两种合法形态：工作区
     * 锚定形（{@code /docs/a.md}、{@code src/x.ts}——以 /workspace 为根）与已带容器根
     * 的绝对形（{@code /workspace/src/x.ts}——原样用，不二次锚定）。
     */
    private static String containerPathOrNull(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            AbstractFilesystem.validatePath(path);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        String root = AgentWorkspace.ProjectDev.CONTAINER_ROOT;
        // 已带容器根的绝对路径原样用（#598 真跑实证：提示词以 /workspace/… 叙述环境与
        // 任务，模型恒用该形态；再锚定即拼成 /workspace/workspace/…——read/edit 全失败、
        // write 落错位，模型被迫改走 bash 挪文件。实验脚本绝对路径本就直用）
        if (path.equals(root) || path.startsWith(root + "/")) {
            return path;
        }
        String rel = path.startsWith("/") ? path.substring(1) : path;
        return rel.isEmpty() || ".".equals(rel) ? root : root + "/" + rel;
    }

    /** write_file：覆盖全量写、自动建父目录（#588）。 */
    private static final class WriteFileTool extends ToolBase {

        private final DockerExecFilesystem.ExecCommand exec;

        WriteFileTool(DockerExecFilesystem.ExecCommand exec) {
            super(ToolBase.builder()
                    .name("write_file")
                    .description("Create or fully replace a UTF-8 text file "
                            + "(parent directories are created automatically).")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "path", Map.of("type", "string",
                                            "description", "Path to write. Provide `path` before "
                                                    + "`content` in the arguments."),
                                    "content", Map.of("type", "string",
                                            "description", "Full UTF-8 text content to write.")),
                            "required", List.of("path", "content")))
                    .readOnly(false)
                    .concurrencySafe(false));
            this.exec = exec;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return KernelToolCallLog.call("write_file", param, () -> invoke(param));
        }

        private ToolResultBlock invoke(ToolCallParam param) {
            Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
            ResolvedPath resolved = resolvePath(input.get("path"));
            if (resolved.error() != null) {
                return ToolResultBlock.error(resolved.error());
            }
            Object content = input.get("content");
            if (content == null) {
                return ToolResultBlock.error("content must be a non-empty string");
            }
            DockerExecFilesystem.ExecOutput out = exec.run(
                    DockerExecFilesystem.overwriteWriteCommand(resolved.containerPath()),
                    String.valueOf(content).getBytes(StandardCharsets.UTF_8));
            if (!out.ok()) {
                return ToolResultBlock.error("写入失败: " + out.stderr());
            }
            String echoed = new String(out.stdout(), StandardCharsets.UTF_8) + out.stderr();
            return ToolResultBlock.text(echoed.length() > 2000
                    ? echoed.substring(0, 2000)
                    : (echoed.isEmpty() ? "ok" : echoed));
        }
    }

    /** read_file：带行号、可分段（offset/limit）、有上限（照 DSH read.ts／read-render.ts 数值）。 */
    private static final class ReadFileTool extends ToolBase {

        /** 单次调用行数上限（默认与最大同值，DSH READ_LIMIT）。 */
        private static final int READ_LIMIT = 2000;
        /** 单行字符上限（DSH READ_MAX_LINE_LENGTH）。 */
        private static final int READ_MAX_LINE = 2000;
        /** 输出总字节上限（DSH READ_MAX_BYTES＝50*1024）。 */
        private static final int READ_MAX_BYTES = 50 * 1024;

        private final DockerExecFilesystem.ExecCommand exec;

        ReadFileTool(DockerExecFilesystem.ExecCommand exec) {
            super(ToolBase.builder()
                    .name("read_file")
                    .description("Read a UTF-8 text file and return line-numbered content.")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "path", Map.of("type", "string", "description", "Path to read."),
                                    "offset", Map.of("type", "integer",
                                            "description", "1-based first line to return. Defaults to 1."),
                                    "limit", Map.of("type", "integer",
                                            "description", "Maximum number of lines to return. "
                                                    + "Defaults to " + READ_LIMIT + ".")),
                            "required", List.of("path")))
                    .readOnly(true)
                    .concurrencySafe(true));
            this.exec = exec;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return KernelToolCallLog.call("read_file", param, () -> invoke(param));
        }

        private ToolResultBlock invoke(ToolCallParam param) {
            Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
            ResolvedPath resolved = resolvePath(input.get("path"));
            if (resolved.error() != null) {
                return ToolResultBlock.error(resolved.error());
            }
            Integer offset = positiveInt(input.get("offset"));
            if (input.containsKey("offset") && offset == null) {
                return ToolResultBlock.error("offset must be a positive integer");
            }
            Integer limit = positiveInt(input.get("limit"));
            if (input.containsKey("limit") && limit == null) {
                return ToolResultBlock.error("limit must be a positive integer");
            }
            int from = offset == null ? 1 : offset;
            int maxLines = limit == null ? READ_LIMIT : limit;
            if (maxLines > READ_LIMIT) {
                return ToolResultBlock.error(
                        "invalid arguments: limit must be less than or equal to " + READ_LIMIT);
            }
            String displayPath = String.valueOf(input.get("path"));
            DockerExecFilesystem.ExecOutput out = exec.run(
                    "cat -- " + DockerExecFilesystem.sh(resolved.containerPath()), null);
            if (!out.ok()) {
                return ToolResultBlock.error(
                        "cannot read \"" + displayPath + "\": not found");
            }
            String text = decodeUtf8OrNull(out.stdout());
            if (text == null) {
                return ToolResultBlock.error(
                        "cannot read \"" + displayPath + "\" as text: file is not UTF-8");
            }
            RenderOutcome rendered = renderRead(displayPath, text, from, maxLines);
            if (rendered.error() != null) {
                return ToolResultBlock.error(rendered.error());
            }
            return ToolResultBlock.text(rendered.text());
        }

        /** 渲染结果：error 非空即 offset 越界错误形（调用方转 error block），否则 text 为全文。 */
        private record RenderOutcome(String text, String error) {
        }

        /**
         * 行号渲染（照 DSH read-render.ts／原型 render_read）：1 起始行号 {@code "N: 行"}、
         * 行 2000 字符截断、总 51200 字节窗口、分段 footer 三种形（capped／continue／end）。
         * offset 越界回 cannot-read 错误形（空文件 offset=1 例外）。
         */
        static RenderOutcome renderRead(String displayPath, String text, int offset, int limit) {
            String[] raws = text.split("\n", -1);
            int last = raws.length - 1;
            if (last >= 0 && raws[last].isEmpty()) {
                last--; // 尾空行不算一行（split 的末段）
            }
            StringBuilder body = new StringBuilder();
            int outBytes = 0;
            boolean truncated = false;
            int total = 0;
            int end = Math.max(0, offset - 1);
            for (int i = 0; i <= last; i++) {
                total++;
                if (truncated || total < offset || body.length() > 0 && lineCount(body) >= limit) {
                    continue;
                }
                String line = raws[i].endsWith("\r") ? raws[i].substring(0, raws[i].length() - 1) : raws[i];
                if (line.length() > READ_MAX_LINE) {
                    line = line.substring(0, READ_MAX_LINE)
                            + "... (line truncated to " + READ_MAX_LINE + " chars)";
                }
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                // 行间各加一个换行字节（首行不加）——与原型 b = len + (1 if acc else 0) 同形
                int sepBytes = body.length() > 0 ? 1 : 0;
                if (outBytes + sepBytes + lineBytes > READ_MAX_BYTES) {
                    truncated = true;
                    continue;
                }
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(total).append(": ").append(line);
                outBytes += sepBytes + lineBytes;
                end = total;
            }
            if (!truncated && offset > total && !(total == 0 && offset == 1)) {
                return new RenderOutcome(null, "cannot read \"" + displayPath + "\": offset " + offset
                        + " is out of range (" + total + " lines)");
            }
            String footer;
            if (truncated) {
                footer = "(Output capped. Showing lines " + offset + "-" + end
                        + ". Use offset=" + (end + 1) + " to continue.)";
            }
            else if (end < total) {
                footer = "(Showing lines " + offset + "-" + end + " of " + total
                        + ". Use offset=" + (end + 1) + " to continue.)";
            }
            else {
                footer = "(End of file - total " + total + " lines)";
            }
            String content = body.length() > 0 ? body + "\n\n" + footer : footer;
            return new RenderOutcome("<path>" + displayPath + "</path>\n<type>file</type>\n<content>\n"
                    + content + "\n</content>", null);
        }

        /** 已入窗行数（渲染体按行拼接，数换行即得）。 */
        private static int lineCount(StringBuilder body) {
            int count = 1;
            for (int i = 0; i < body.length(); i++) {
                if (body.charAt(i) == '\n') {
                    count++;
                }
            }
            return count;
        }

        private static Integer positiveInt(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number n) {
                double d = n.doubleValue();
                if (d == Math.floor(d) && d >= 1) {
                    return (int) d;
                }
                return null;
            }
            try {
                int parsed = Integer.parseInt(String.valueOf(value));
                return parsed >= 1 ? parsed : null;
            }
            catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** edit_file：旧文本恰好出现一次才替换；没找到／不唯一／文件不存在三种错误如实报（DSH str-replace-editor 文案）。 */
    private static final class EditFileTool extends ToolBase {

        /** 错误文案里 old_string 展示截断（照原型：超 300 字符截断加 …）。 */
        private static final int SHOWN_OLD_MAX = 300;

        private final DockerExecFilesystem.ExecCommand exec;

        private EditFileTool(DockerExecFilesystem.ExecCommand exec) {
            super(ToolBase.builder()
                    .name("edit_file")
                    .description("Edit an existing UTF-8 text file by replacing literal text.")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "path", Map.of("type", "string",
                                            "description", "Path to edit. Provide `path` before "
                                                    + "`old_string` and `new_string` in the arguments."),
                                    "old_string", Map.of("type", "string",
                                            "description", "Literal text to replace."),
                                    "new_string", Map.of("type", "string",
                                            "description", "Literal replacement text. Use an empty "
                                                    + "string to delete the match."),
                                    "replace_all", Map.of("type", "boolean",
                                            "description", "Replace all matches. Defaults to false; "
                                                    + "when false, old_string must appear exactly once.")),
                            "required", List.of("path", "old_string", "new_string")))
                    .readOnly(false)
                    .concurrencySafe(false));
            this.exec = exec;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return KernelToolCallLog.call("edit_file", param, () -> invoke(param));
        }

        private ToolResultBlock invoke(ToolCallParam param) {
            Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
            ResolvedPath resolved = resolvePath(input.get("path"));
            if (resolved.error() != null) {
                return ToolResultBlock.error(resolved.error());
            }
            Object old = input.get("old_string");
            if (old == null || String.valueOf(old).isEmpty()) {
                return ToolResultBlock.error("old_string must be a non-empty string");
            }
            Object replacement = input.get("new_string");
            if (replacement == null) {
                return ToolResultBlock.error("new_string must be a string");
            }
            String displayPath = String.valueOf(input.get("path"));
            String oldString = String.valueOf(old);
            String newString = String.valueOf(replacement);
            boolean replaceAll = Boolean.TRUE.equals(input.get("replace_all"));
            DockerExecFilesystem.ExecOutput read = exec.run(
                    "cat -- " + DockerExecFilesystem.sh(resolved.containerPath()), null);
            if (!read.ok()) {
                return ToolResultBlock.error(
                        "cannot read \"" + displayPath + "\": not found");
            }
            String content = decodeUtf8OrNull(read.stdout());
            if (content == null) {
                // 非 UTF-8 拒因照裁决脚本原文（v0-harness4.py VARIANT=3 该分支为自写中文句）
                return ToolResultBlock.error(
                        "ERROR: " + displayPath + " 不是 UTF-8 文本文件。");
            }
            List<Integer> positions = occurrences(content, oldString);
            String shown = oldString.length() <= SHOWN_OLD_MAX
                    ? oldString
                    : oldString.substring(0, SHOWN_OLD_MAX) + "…";
            if (positions.isEmpty()) {
                return ToolResultBlock.error("No replacement was performed, old_string `"
                        + shown + "` did not appear verbatim in " + displayPath + ".");
            }
            if (positions.size() > 1 && !replaceAll) {
                StringBuilder lines = new StringBuilder();
                for (int pos : positions) {
                    if (lines.length() > 0) {
                        lines.append(", ");
                    }
                    lines.append(lineOf(content, pos));
                }
                return ToolResultBlock.error("No replacement was performed. Multiple "
                        + "occurrences of old_string `" + shown + "` in lines [" + lines
                        + "]. Please ensure it is unique");
            }
            String updated = replaceAll
                    ? content.replace(oldString, newString)
                    : DockerExecFilesystem.replaceFirst(content, oldString, newString);
            DockerExecFilesystem.ExecOutput written = exec.run(
                    DockerExecFilesystem.overwriteWriteCommand(resolved.containerPath()),
                    updated.getBytes(StandardCharsets.UTF_8));
            if (!written.ok()) {
                return ToolResultBlock.error(
                        "failed to write \"" + displayPath + "\": " + written.stderr());
            }
            return ToolResultBlock.text(replaceAll
                    ? "The file " + displayPath + " has been updated. All occurrences were "
                            + "successfully replaced."
                    : "The file " + displayPath + " has been updated successfully.");
        }

        /** 旧文本全部出现位置（字符偏移）——空目标按零处计（与原型 re.finditer 同形）。 */
        private static List<Integer> occurrences(String content, String target) {
            List<Integer> positions = new ArrayList<>();
            if (target.isEmpty()) {
                return positions;
            }
            for (int idx = content.indexOf(target); idx >= 0;
                    idx = content.indexOf(target, idx + target.length())) {
                positions.add(idx);
            }
            return positions;
        }

        /** 1 起始行号（偏移前换行数 + 1，照原型 s.count("\n", 0, pos) + 1）。 */
        private static int lineOf(String content, int position) {
            int line = 1;
            for (int i = 0; i < position; i++) {
                if (content.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }

    /** bash：容器内 sh -c 执行，180 秒超时（#594）；①族守卫同 {@link DockerExecFilesystem#execute} 口径。 */
    private static final class BashTool extends ToolBase {

        /** 命令超时（秒，#594 规格值）。 */
        private static final int TIMEOUT_SECONDS = 180;
        /**
         * 执行缝超时约定退出码 124（{@link DockerExecFilesystem.DockerExecCommand} 超时
         * 强杀语义；同 DockerExecFilesystem 既有口径——124 即超时，与文件面 30s 缺省共用）。
         */
        private static final int TIMEOUT_EXIT_CODE = 124;
        /** 输出上限（字符，照原型 v0-harness4.py 6000 截断）。 */
        private static final int OUTPUT_LIMIT = 6000;

        private final DockerExecFilesystem.ExecCommand exec;
        private final boolean windowsHost;

        private BashTool(DockerExecFilesystem.ExecCommand exec, boolean windowsHost) {
            super(ToolBase.builder()
                    .name("bash")
                    .description("在容器内 sh -c 执行命令（超时 180s）")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "command", Map.of("type", "string",
                                            "description", "在容器内执行的 shell 命令（sh -c 形态）")),
                            "required", List.of("command")))
                    .readOnly(false)
                    .concurrencySafe(false));
            this.exec = exec;
            this.windowsHost = windowsHost;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return KernelToolCallLog.call("bash", param, () -> invoke(param));
        }

        private ToolResultBlock invoke(ToolCallParam param) {
            Object command = param.getInput() != null ? param.getInput().get("command") : null;
            if (command == null || String.valueOf(command).isBlank()) {
                return ToolResultBlock.error("command must be a non-empty string");
            }
            String script = String.valueOf(command);
            if (windowsHost) {
                // ①族守卫复用 DockerExecFilesystem 单源定义（#594 放宽包级可见，行为零变化）
                String violation = DockerExecFilesystem.windowsArgvViolation(script);
                if (violation != null) {
                    return ToolResultBlock.error(
                            DockerExecFilesystem.guardRejectMessage(violation));
                }
            }
            DockerExecFilesystem.ExecOutput out = exec.run(script, null, TIMEOUT_SECONDS);
            if (out.exitCode() == TIMEOUT_EXIT_CODE) {
                return ToolResultBlock.error("TIMEOUT after " + TIMEOUT_SECONDS + "s");
            }
            String stdout = new String(out.stdout(), StandardCharsets.UTF_8);
            String output = out.stderr() == null || out.stderr().isBlank()
                    ? stdout
                    : stdout + "\n--stderr--\n" + out.stderr();
            if (output.length() > OUTPUT_LIMIT) {
                output = output.substring(0, OUTPUT_LIMIT);
            }
            if (output.isEmpty()) {
                output = "(no output)";
            }
            return out.ok()
                    ? ToolResultBlock.text(output)
                    : ToolResultBlock.error(output);
        }
    }
}
