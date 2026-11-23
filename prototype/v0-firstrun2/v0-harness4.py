# -*- coding: utf-8 -*-
"""内核对齐版实验脚本·二（#590 修正轮：三处保护照 DeepSeek Harness 改齐，用户裁决 2026-11-23）。

在 v0-harness3.py 的基础上改四处：
  去掉：输出截断自动续写（Cline）——照 DSH：截断即轮次结束，stop_kind=max_tokens；
        空回复提醒（Gemini CLI）——照 DSH：空回复落入「无工具调用」分支，轮次结束；
        同一调用连续重复第 5 次不执行并停（Cline）——照 DSH：3/5/8 只提醒、绝不拦截。
  调整：每次请求的输出上限 8192 → 32768（DSH 里这是每次请求的配置 agents[].maxTokens；
        接口实测可设到 131072，调大后截断基本不会发生）。
其余与 v0-harness3.py 完全相同（结束条件＝一轮不调工具即结束、工具与提示词、护栏数值）。
用法：python v0-harness4.py <container> <prd-file> [logfile]
      python v0-harness4.py --selftest <container> [scenario]   （不调模型，用预置回复走一遍各分支）
"""
import json, subprocess, time, sys, io, os, re
import urllib.request

SELFTEST = len(sys.argv) > 1 and sys.argv[1] == "--selftest"
if SELFTEST:
    CONTAINER = sys.argv[2]
    PRD_FILE = "-"
    SCENARIO = sys.argv[3] if len(sys.argv) > 3 else "tools"
    LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "selftest-%s-log.jsonl" % SCENARIO)
    if os.path.exists(LOG):
        os.remove(LOG)
else:
    CONTAINER = sys.argv[1]
    PRD_FILE = sys.argv[2]
    LOG = sys.argv[3] if len(sys.argv) > 3 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "v0r2d-%s-log.jsonl" % CONTAINER)

API = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-flash"
# 每次请求的输出上限（DSH：这是 agents[].maxTokens 的配置项；原 8192，2026-11-23 调大，接口实测可设 131072）
MODEL_MAX_TOKENS = 32768
# 实验保护线（运营护栏，非内核语义；数值沿用 #576 脚本，只改到顶后的行为）
GUARD_ROUNDS = 300
GUARD_WALL_SEC = 75 * 60


def api_key():
    with io.open(r"c:\Users\zhang\workspace\aiplatform\aiplatform-server\.env.local", encoding="utf-8") as f:
        for line in f:
            if line.startswith("DEEPSEEK_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"')
    raise SystemExit("no key")


KEY = "" if SELFTEST else api_key()

# ============ 提示词：原七行，只动两处（第二、三遍按实测补齐，见 VARIANT）============
# ① 「把应用起在…常驻（如 pnpm start -- -p 8081 后台运行）」→ 平台已起服务（第 9 条）
# ② 末尾加完成纪律三句（bolt.diy 原文，第 7 条）
# VARIANT=2（第一遍实测后补）：工具选用两句＋服务日志位置。
#   第二句是 bolt.diy 原句只换工具名（prompt-workstyle.ts 第 14 行）；
#   第一句不是原文——照 Codex「Use the `apply_patch` tool to edit files」的意思自己写的；日志位置一句也是自己写的。
#   （本注释在三次运行结束后按出处更正过措辞，脚本行为未动。）
# VARIANT=3（第二遍实测后补）：改成 DeepSeek Harness 默认给模型的三句工具使用说明（原文，只换工具名），
#            读取／编辑工具的参数、说明、输出格式也照它（packages/fs/tool-fs）；
#            与原文的差别：file_path 改名 path；path 的说明去掉「resolved by the filesystem backend」；
#            offset 用 integer（原文 number）；write_file 只换说明一句、参数说明沿旧脚本（没有）
VARIANT = os.environ.get("VARIANT", "1")
SERVER_NOTE = ("开发服务器已由平台启动并常驻在 0.0.0.0:8081，改动会自动生效，不要停掉或重启它"
               + ("（日志在 /tmp/app.log）" if VARIANT in ("2", "3") else "") + "。")
if VARIANT == "2":
    TOOL_STEER = ("Use the `edit_file` tool to edit existing files. Use `write_file` for new files and when "
                  "the requested change needs a full-file replacement. ")
elif VARIANT == "3":
    TOOL_STEER = (
        "Use the read_file tool — not shell commands like cat, sed, head, or tail — to inspect text files. "
        "Use offset and limit to read a line range or continue a large file. "
        "Read an existing file before overwriting it with write_file and prefer edit_file for targeted changes. "
        "Read a file before editing it unless you just created or edited it in this session. ")
else:
    TOOL_STEER = ""
SYSTEM = (
    "你是全栈工程师。环境：Linux 容器，/workspace 是配置好的 Next.js(App Router)+React 19"
    "+Tailwind 4+shadcn 工程，pnpm 可用、node_modules 已装；容器内 PostgreSQL 与 Redis 已在"
    "运行，连接串在 /workspace/.env。任务：把 /workspace/docs/PRD.md 描述的完整系统实现出来"
    "（建表、后端 API、全部页面、演示数据）。用工具读写文件、运行命令，可随时跑命令验证与"
    "调试。注意：单个文件太长时拆分或分块写（write_file 覆盖写全量、bash 可追加）。"
    + SERVER_NOTE +
    "完成后用 pnpm build 验证可构建，用 curl http://localhost:8081 确认有响应才算完成。"
    + TOOL_STEER +
    "Treat tool output as the source of truth. Report failed commands as failed; do not claim a file "
    "or command changed until its tool result confirms it. Do not claim a task or check is complete "
    "without tool evidence. 全程中文。"
)

if VARIANT == "3":
    # 说明文字照抄 DeepSeek Harness tool-fs（read.ts / write.ts / edit.ts），只把 file_path 换成 path、
    # 去掉 path 说明里的「resolved by the filesystem backend」（差别清单见文件头 VARIANT=3 注释）
    EDIT_TOOL = {"type": "function", "function": {"name": "edit_file",
        "description": "Edit an existing UTF-8 text file by replacing literal text.",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string",
                     "description": "Path to edit. Provide `path` before `old_string` and `new_string` in the arguments."},
            "old_string": {"type": "string", "description": "Literal text to replace."},
            "new_string": {"type": "string",
                           "description": "Literal replacement text. Use an empty string to delete the match."},
            "replace_all": {"type": "boolean",
                            "description": "Replace all matches. Defaults to false; when false, old_string must appear exactly once."}},
            "required": ["path", "old_string", "new_string"]}}}
    READ_TOOL = {"type": "function", "function": {"name": "read_file",
        "description": "Read a UTF-8 text file and return line-numbered content.",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string", "description": "Path to read."},
            "offset": {"type": "integer", "description": "1-based first line to return. Defaults to 1."},
            "limit": {"type": "integer", "description": "Maximum number of lines to return. Defaults to 2000."}},
            "required": ["path"]}}}
    WRITE_DESC = "Create or fully replace a UTF-8 text file (parent directories are created automatically)."
else:
    EDIT_TOOL = {"type": "function", "function": {"name": "edit_file",
        "description": "对已有文件做局部替换：old_string 必须在文件中恰好出现一次，替换为 new_string",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}, "old_string": {"type": "string"}, "new_string": {"type": "string"}},
            "required": ["path", "old_string", "new_string"]}}}
    READ_TOOL = {"type": "function", "function": {"name": "read_file",
        "description": "读文件内容",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}}, "required": ["path"]}}}
    WRITE_DESC = "覆盖写文件（全量内容，自动创建父目录）"

TOOLS = [
    {"type": "function", "function": {"name": "write_file",
        "description": WRITE_DESC,
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"]}}},
    EDIT_TOOL,
    READ_TOOL,
    {"type": "function", "function": {"name": "bash",
        "description": "在容器内 sh -c 执行命令（超时 180s）",
        "parameters": {"type": "object", "properties": {
            "command": {"type": "string"}}, "required": ["command"]}}},
]

# 读取工具的上限：DeepSeek Harness tool-fs 的默认值（README「配置」表）
READ_LIMIT, READ_MAX_LINE, READ_MAX_BYTES = 2000, 2000, 51200


def render_read(display_path, text, offset, limit):
    """照 dsh-tool-fs read-render.ts：带行号、分页 footer、字节上限。"""
    raw_lines = text.split("\n")
    if raw_lines and raw_lines[-1] == "":
        raw_lines.pop()
    acc, out_bytes, truncated, total = [], 0, False, 0
    for raw in raw_lines:
        total += 1
        if truncated or total < offset or len(acc) >= limit:
            continue
        line = raw[:-1] if raw.endswith("\r") else raw
        if len(line) > READ_MAX_LINE:
            line = line[:READ_MAX_LINE] + "... (line truncated to %d chars)" % READ_MAX_LINE
        b = len(line.encode("utf-8")) + (1 if acc else 0)
        if out_bytes + b > READ_MAX_BYTES:
            truncated = True
            continue
        out_bytes += b
        acc.append((total, line))
    if (not truncated) and offset > total and not (total == 0 and offset == 1):
        return 'cannot read "%s": offset %d is out of range (%d lines)' % (display_path, offset, total)
    end = acc[-1][0] if acc else max(0, offset - 1)
    if truncated:
        footer = "(Output capped. Showing lines %d-%d. Use offset=%d to continue.)" % (offset, end, end + 1)
    elif end < total:
        footer = "(Showing lines %d-%d of %d. Use offset=%d to continue.)" % (offset, end, total, end + 1)
    else:
        footer = "(End of file - total %d lines)" % total
    body = ("\n".join("%d: %s" % (n, t) for n, t in acc) + "\n\n" + footer) if acc else footer
    return "<path>%s</path>\n<type>file</type>\n<content>\n%s\n</content>" % (display_path, body)

# ============ 照抄的原文与数值（出处见 README 对照表）============
# DeepSeek Harness  packages/guard/repeat-tool-reminder（README「模型体验」两段模板原文、thresholds 默认 [3, 5, 8]）
REPEAT_THRESHOLDS = (3, 5, 8)
REPEAT_ARGS_PREVIEW = 500          # argumentsPreviewChars 默认值
DSH_REPEAT_FIRST = (
    "You are repeating the exact same tool call with identical arguments. Carefully analyze the previous "
    "result before calling again: if the task is not complete, try a different approach or different "
    "arguments instead of repeating the call.")
DSH_REPEAT_DETAIL = (
    "Repeated tool call detected:\n- tool: {name}\n- consecutive_calls: {count}\n- arguments: {args}\n"
    "The repeated calls are not making progress. Do not call this tool with these exact arguments again. "
    "Inspect the latest result and choose a different action, different arguments, or finish the task if "
    "enough evidence has been gathered.")
# OpenCode  max-steps.ts  MAX_STEPS_PROMPT（原文）
MAX_STEPS_PROMPT = """CRITICAL - MAXIMUM STEPS REACHED

The maximum number of steps allowed for this task has been reached. Tools are disabled until next user input. Respond with text only.

STRICT REQUIREMENTS:
1. Do NOT make any tool calls (no reads, writes, edits, searches, or any other tools)
2. MUST provide a text response summarizing work done so far
3. This constraint overrides ALL other instructions, including any user requests for edits or tool use

Response must include:
- Statement that maximum steps for this agent have been reached
- Summary of what has been accomplished so far
- List of any remaining tasks that were not completed
- Recommendations for what should be done next

Any attempt to use tools is a critical violation. Respond with text ONLY."""


def repeat_msg(n, name, canonical):
    """照 DSH：第 3 次短提醒、第 5 与 8 次详细模板；参数预览超长按它的截断格式「… (+N more chars)」。"""
    if n == 3:
        return DSH_REPEAT_FIRST
    preview = canonical if len(canonical) <= REPEAT_ARGS_PREVIEW else (
        canonical[:REPEAT_ARGS_PREVIEW] + "… (+%d more chars)" % (len(canonical) - REPEAT_ARGS_PREVIEW))
    return DSH_REPEAT_DETAIL.format(name=name, count=n, args=preview)


def call_api(messages, with_tools=True):
    body = {"model": MODEL, "messages": messages, "max_tokens": MODEL_MAX_TOKENS, "stream": False}
    if with_tools:
        body["tools"] = TOOLS
    req = urllib.request.Request(API, data=json.dumps(body).encode("utf-8"), headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read().decode("utf-8"))


def sh_quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def read_in_container(path):
    return subprocess.run(["docker", "exec", CONTAINER, "cat", "--", path], capture_output=True, timeout=60)


def write_in_container(path, data):
    p = subprocess.run(["docker", "exec", "-i", CONTAINER, "sh", "-c",
                        "mkdir -p \"$(dirname %s)\" && cat > %s" % (sh_quote(path), sh_quote(path))],
                       input=data, capture_output=True, timeout=60)
    return p


def run_tool(name, args):
    """返回 (给模型看的结果文本, 是否成功, 结果头用于日志)"""
    try:
        if name == "write_file":
            p = write_in_container(args["path"], args["content"].encode("utf-8"))
            out = (p.stdout + p.stderr).decode("utf-8", "replace")[:2000] or "ok"
            return out, p.returncode == 0, out[:160]
        if name == "edit_file":
            path = args.get("path", "")
            old, new = args.get("old_string", ""), args.get("new_string", "")
            if not isinstance(old, str) or not isinstance(new, str) or not old:
                return "ERROR: edit_file 需要 path、old_string（非空）、new_string 三个字符串参数。", False, "bad args"
            p = read_in_container(path)
            if p.returncode != 0:
                if VARIANT == "3":
                    return 'cannot read "%s": not found' % path, False, "no file"
                return "ERROR: 读不到文件 %s（edit_file 只改已有文件；新文件请用 write_file）。" % path, False, "no file"
            try:
                s = p.stdout.decode("utf-8")
            except UnicodeDecodeError:
                return "ERROR: %s 不是 UTF-8 文本文件。" % path, False, "not utf8"
            positions = [m.start() for m in re.finditer(re.escape(old), s)]
            n = len(positions)
            if VARIANT == "3":
                # 语义与提示文字照 DeepSeek Harness 的编辑工具（tool-fs/src/edit.ts、str-replace-editor/src/index.ts）
                replace_all = bool(args.get("replace_all"))
                shown = old if len(old) <= 300 else old[:300] + "…"
                if n == 0:
                    return ("No replacement was performed, old_string `%s` did not appear verbatim in %s." % (shown, path),
                            False, "not found")
                if n > 1 and not replace_all:
                    lines = ", ".join(str(s.count("\n", 0, pos) + 1) for pos in positions)
                    return ("No replacement was performed. Multiple occurrences of old_string `%s` in lines [%s]. "
                            "Please ensure it is unique" % (shown, lines), False, "ambiguous x%d" % n)
                s2 = s.replace(old, new) if replace_all else s.replace(old, new, 1)
                p2 = write_in_container(path, s2.encode("utf-8"))
                if p2.returncode != 0:
                    return "ERROR: 写入失败：" + (p2.stdout + p2.stderr).decode("utf-8", "replace")[:500], False, "write failed"
                if replace_all:
                    return ("The file %s has been updated. All occurrences were successfully replaced." % path, True, "ok all x%d" % n)
                return "The file %s has been updated successfully." % path, True, "ok"
            if n == 0:
                return ("ERROR: old_string 在文件里没找到（要求逐字一致，含空格与缩进）。先用 read_file 看文件现状。" , False, "not found")
            if n > 1:
                return "ERROR: old_string 在文件里出现了 %d 处，请多带几行上下文让它唯一。" % n, False, "ambiguous x%d" % n
            p2 = write_in_container(path, s.replace(old, new, 1).encode("utf-8"))
            if p2.returncode != 0:
                return "ERROR: 写入失败：" + (p2.stdout + p2.stderr).decode("utf-8", "replace")[:500], False, "write failed"
            return "ok：已替换 1 处。", True, "ok"
        if name == "read_file":
            if VARIANT == "3":
                path = args.get("path", "")
                p = read_in_container(path)
                if p.returncode != 0:
                    return 'cannot read "%s": not found' % path, False, "no file"
                try:
                    text = p.stdout.decode("utf-8")
                except UnicodeDecodeError:
                    return 'cannot read "%s" as text: file is not UTF-8' % path, False, "not utf8"
                off, lim = args.get("offset"), args.get("limit")
                offset = off if isinstance(off, int) and off > 0 else 1
                limit = lim if isinstance(lim, int) and lim > 0 else READ_LIMIT
                if limit > READ_LIMIT:
                    return "invalid arguments: limit must be less than or equal to %d" % READ_LIMIT, False, "bad limit"
                out = render_read(path, text, offset, limit)
                head = "bad offset" if out.startswith("cannot read") else ("paged" if "Use offset=" in out else "ok")
                return out, not out.startswith("cannot read"), head
            p = read_in_container(args["path"])
            return p.stdout.decode("utf-8", "replace")[:30000], p.returncode == 0, "rc=%d" % p.returncode
        if name == "bash":
            p = subprocess.run(["docker", "exec", CONTAINER, "sh", "-c", args["command"]],
                               capture_output=True, timeout=180)
            out = ((p.stdout + b"\n--stderr--\n" + p.stderr).decode("utf-8", "replace"))
            return out[:6000] or "(no output)", p.returncode == 0, "rc=%d" % p.returncode
        return "unknown tool", False, "unknown tool"
    except subprocess.TimeoutExpired:
        return "TIMEOUT after 180s", False, "timeout"
    except Exception as e:
        return "ERROR: %s" % e, False, "error"


def log(obj):
    with io.open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")


def sort_keys(v):
    if v is None or not isinstance(v, dict):
        return v
    return {k: sort_keys(v[k]) for k in sorted(v)}


# ============ 预置回复（仅 --selftest 用）============


def fake_api_factory(scenario):
    def tc(cid, name, args):
        return {"id": cid, "type": "function", "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}
    def tool(name, args):
        return {"finish_reason": "stop", "tool_calls": [tc("c%s" % name, name, args)]}
    D = "/tmp/selftest/dir1/dir2/"
    same = tool("bash", {"command": "cat %sx.txt" % D})
    scripts = {
        # 写新目录文件 → 编辑成功／没找到／不唯一 → 自然结束
        "tools": [
            tool("write_file", {"path": D + "x.txt", "content": "hello\nworld\n"}),
            tool("edit_file", {"path": D + "x.txt", "old_string": "world", "new_string": "世界"}),
            tool("edit_file", {"path": D + "x.txt", "old_string": "nope", "new_string": "x"}),
            tool("write_file", {"path": D + "y.txt", "content": "a\na\n"}),
            tool("edit_file", {"path": D + "y.txt", "old_string": "a", "new_string": "b"}),
            tool("edit_file", {"path": D + "nope.txt", "old_string": "a", "new_string": "b"}),
            {"finish_reason": "stop", "content": "做完了。", "tool_calls": []},
        ],
        "natural": [tool("write_file", {"path": D + "n.txt", "content": "ok"}), {"finish_reason": "stop", "content": "做完了。", "tool_calls": []}],
        # 截断×1 即轮次结束（照 DSH）
        "max_tokens": [{"finish_reason": "length", "content": "写了一半", "tool_calls": [tc("c1", "write_file", {"path": "/tmp/selftest/a.txt", "content": "abc"})]}],
        # 空回复×1 即轮次结束（照 DSH）
        "empty": [{"finish_reason": "stop", "content": "", "tool_calls": []}],
        # 同一调用×8：第 3、5、8 次后各注入一条提醒，不停；换一个调用重新计数；最后自然结束
        "repeat": [same] * 8 + [tool("write_file", {"path": D + "r.txt", "content": "ok"}), {"finish_reason": "stop", "content": "做完了。", "tool_calls": []}],
        "cap": [same] * 50,
        # 第三遍：读取工具带行号与分页、编辑工具的全部替换／删除／各种失败
        "tools3": [
            tool("write_file", {"path": D + "z.txt", "content": "alpha\nbeta\nalpha\ngamma\nalpha\n"}),
            tool("read_file", {"path": D + "z.txt"}),
            tool("read_file", {"path": D + "z.txt", "offset": 2, "limit": 2}),
            tool("read_file", {"path": D + "z.txt", "offset": 99}),
            tool("edit_file", {"path": D + "z.txt", "old_string": "alpha", "new_string": "A"}),
            tool("edit_file", {"path": D + "z.txt", "old_string": "alpha", "new_string": "A", "replace_all": True}),
            tool("edit_file", {"path": D + "z.txt", "old_string": "beta\n", "new_string": ""}),
            tool("edit_file", {"path": D + "z.txt", "old_string": "nope", "new_string": "x"}),
            tool("read_file", {"path": D + "nope.txt"}),
            tool("read_file", {"path": D + "z.txt"}),
            {"finish_reason": "stop", "content": "做完了。", "tool_calls": []},
        ],
    }
    script = scripts[scenario]
    state = {"i": 0}

    def fake(messages, with_tools=True):
        i = state["i"]
        state["i"] += 1
        r = script[i] if i < len(script) else {"finish_reason": "stop", "content": "脚本用尽。", "tool_calls": []}
        msg = {"role": "assistant", "content": r.get("content") or ""}
        if r.get("tool_calls"):
            msg["tool_calls"] = r["tool_calls"]
        # 到上限的收尾调用不带工具
        if not with_tools:
            msg = {"role": "assistant", "content": "【收尾汇报】已做一部分，剩余未完成。"}
        return {"choices": [{"message": msg, "finish_reason": r["finish_reason"]}], "usage": {"prompt_tokens": 100, "completion_tokens": 10}}
    return fake


if SELFTEST and SCENARIO == "cap":
    GUARD_ROUNDS = 4  # 只为走到「到上限」分支
call = fake_api_factory(SCENARIO) if SELFTEST else call_api


def main():
    t0 = time.time()
    log({"meta": {"container": CONTAINER, "prd": PRD_FILE, "model": MODEL, "harness": "v0-harness4 (aligned, DSH-exact)",
                  "variant": VARIANT,
                  "guard": {"rounds": GUARD_ROUNDS, "wall_sec": GUARD_WALL_SEC}, "t0": t0,
                  "system": SYSTEM}})
    messages = [{"role": "system", "content": SYSTEM}]
    rnd = 0
    u_prompt = u_completion = 0
    last = {"name": "", "sig": "", "n": 0}   # 同一调用连续重复计数（照 DSH repeat-tool-reminder 的链）

    def finish(kind, final_text, extra=None):
        rec = {"final_text": (final_text or "")[:4000], "total_sec": round(time.time() - t0), "rounds": rnd,
               "usage_total": {"prompt": u_prompt, "completion": u_completion}, "stop_kind": kind}
        if extra:
            rec.update(extra)
        log(rec)
        print("END kind=%s r%d %.0fs" % (kind, rnd, time.time() - t0))
        return

    while True:
        rnd += 1
        over_rounds = rnd > GUARD_ROUNDS
        over_wall = (time.time() - t0) > GUARD_WALL_SEC
        if over_rounds or over_wall:
            # 到上限：关掉工具，让模型如实汇报（OpenCode 原文）
            log({"event": "cap", "round": rnd, "over_rounds": over_rounds, "over_wall": over_wall})
            messages.append({"role": "user", "content": MAX_STEPS_PROMPT})
            resp = call(messages, with_tools=False)
            msg = resp["choices"][0]["message"]
            usage = resp.get("usage", {})
            u_prompt += usage.get("prompt_tokens", 0)
            u_completion += usage.get("completion_tokens", 0)
            finish("cap_summary", msg.get("content"), {"cap": {"over_rounds": over_rounds, "over_wall": over_wall}})
            return
        t1 = time.time()
        try:
            resp = call(messages)
        except Exception as e:
            log({"round": rnd, "api_error": str(e)[:300]})
            time.sleep(5)
            rnd -= 1  # 失败的请求不计轮
            continue
        choice = resp["choices"][0]
        msg = choice["message"]
        finish_reason = choice.get("finish_reason")
        usage = resp.get("usage", {})
        u_prompt += usage.get("prompt_tokens", 0)
        u_completion += usage.get("completion_tokens", 0)
        calls = msg.get("tool_calls") or []

        # —— 输出被截断：照 DSH，轮次结束（它的客户端会给用户一张提示卡，这里如实记 stop_kind）——
        if finish_reason == "length":
            log({"round": rnd, "event": "max_tokens", "elapsed": round(time.time() - t1, 1),
                 "tool_calls_dropped": len(calls), "usage": usage})
            finish("max_tokens", (msg.get("content") or "").strip())
            return

        # —— 没有工具调用：一轮不调工具即结束；空回复同样结束（照 DSH）——
        if not calls:
            text = (msg.get("content") or "").strip()
            if not text:
                log({"round": rnd, "event": "empty_reply", "elapsed": round(time.time() - t1, 1), "usage": usage})
                finish("empty_reply", "")
                return
            log({"round": rnd, "elapsed": round(time.time() - t1, 1), "text": text[:300], "usage": usage})
            finish("natural", text)
            return

        # —— 有工具调用 ——
        reminded = 0
        messages.append(msg)
        summary = []
        for c in calls:
            fn = c["function"]
            name = fn["name"]
            try:
                args = json.loads(fn["arguments"] or "{}")
            except Exception:
                args = {"_raw": (fn["arguments"] or "")[:200]}
            # 同一工具同样参数连续重复：照 DSH 只在 3/5/8 次提醒、绝不拦截（换成别的调用就重新计数）
            canonical = json.dumps(sort_keys(args), ensure_ascii=False, sort_keys=True)
            if name == last["name"] and canonical == last["sig"]:
                last["n"] += 1
            else:
                last.update({"name": name, "sig": canonical, "n": 1})
            a0 = args.get("path") or args.get("command") or ""
            line = "%s:%s" % (name, str(a0)[:100])
            result, ok, head = run_tool(name, args)
            if SELFTEST:
                print("   -> %s" % result[:420].replace("\n", "\n      "))
            messages.append({"role": "tool", "tool_call_id": c["id"], "content": result})
            summary.append(line + " [%s]" % head)
            if last["n"] in REPEAT_THRESHOLDS:
                reminded = last["n"]
        if reminded:
            # 照 DSH：提醒出现在重复调用的结果之后，作为一条注入的用户消息（本轮只发一条，取达到的最高次数）
            messages.append({"role": "user", "content": repeat_msg(reminded, last["name"], last["sig"])})
            summary[-1] += " {repeat-reminder x%d}" % reminded
            log({"round": rnd, "event": "repeat_reminder", "tool": last["name"], "consecutive": reminded})
        rec = {"round": rnd, "elapsed": round(time.time() - t1, 1), "calls": summary, "usage": usage}
        log(rec)
        sys.stdout.write("r%d %.0fs %s\n" % (rnd, time.time() - t1, "; ".join(summary)[:220]))
        sys.stdout.flush()


if __name__ == "__main__":
    main()
