# -*- coding: utf-8 -*-
"""由 v0-harness3.py 生成 v0-harness4.py（#590 修正轮：三处保护照 DSH 改齐）。
用法：python make_v0_harness4.py。每处替换找不到就报错，防止原脚本改了而这里没跟上。"""
import io
import os
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
s = io.open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "v0-harness3.py"), encoding="utf-8").read()


def rep(old, new, what):
    global s
    assert old in s, "not found (%s): %r..." % (what, old[:70])
    assert s.count(old) == 1, "not unique (%s)" % what
    s = s.replace(old, new)


# 1) 文件头说明
rep('''"""内核对齐版实验脚本（#590 收尾验证）。

以 v0-harness.py（#576）为底，只改循环机制，逐项照成熟方案（出处见 README 对照表）：
  去掉：催续句；「连续两轮不调工具才算结束」；到上限直接掐断。
  补上：局部编辑工具；输出截断自动续写；空回复提醒；同一调用连续重复的检测；
        提示词里的完成纪律；到上限关工具、让模型如实汇报；服务由平台起（脚本外，见 setup）。
不变：模型、请求参数（max_tokens 8192、非流式）、三道题、护栏数值（300 轮／75 分钟，只改到顶后的行为）。
用法：python v0-harness3.py <container> <prd-file> [logfile]
      python v0-harness3.py --selftest <container>   （不调模型，用预置回复走一遍各分支）
"""''',
    '''"""内核对齐版实验脚本·二（#590 修正轮：三处保护照 DeepSeek Harness 改齐，用户裁决 2026-11-23）。

在 v0-harness3.py 的基础上改四处：
  去掉：输出截断自动续写（Cline）——照 DSH：截断即轮次结束，stop_kind=max_tokens；
        空回复提醒（Gemini CLI）——照 DSH：空回复落入「无工具调用」分支，轮次结束；
        同一调用连续重复第 5 次不执行并停（Cline）——照 DSH：3/5/8 只提醒、绝不拦截。
  调整：每次请求的输出上限 8192 → 32768（DSH 里这是每次请求的配置 agents[].maxTokens；
        接口实测可设到 131072，调大后截断基本不会发生）。
其余与 v0-harness3.py 完全相同（结束条件＝一轮不调工具即结束、工具与提示词、护栏数值）。
用法：python v0-harness4.py <container> <prd-file> [logfile]
      python v0-harness4.py --selftest <container> [scenario]   （不调模型，用预置回复走一遍各分支）
"""''', "文件头")

# 2) 默认日志名
rep('LOG = sys.argv[3] if len(sys.argv) > 3 else os.path.join(\n        os.path.dirname(os.path.abspath(__file__)), "v0r2-%s-log.jsonl" % CONTAINER)',
    'LOG = sys.argv[3] if len(sys.argv) > 3 else os.path.join(\n        os.path.dirname(os.path.abspath(__file__)), "v0r2d-%s-log.jsonl" % CONTAINER)', "日志名")

# 3) meta 记录里的脚本名
rep('"harness": "v0-harness3 (aligned)"', '"harness": "v0-harness4 (aligned, DSH-exact)"', "meta harness 名")

# 4) 输出上限
rep('body = {"model": MODEL, "messages": messages, "max_tokens": 8192, "stream": False}',
    'body = {"model": MODEL, "messages": messages, "max_tokens": MODEL_MAX_TOKENS, "stream": False}', "max_tokens 引用")
rep('MODEL = "deepseek-flash"',
    'MODEL = "deepseek-flash"\n# 每次请求的输出上限（DSH：这是 agents[].maxTokens 的配置项；原 8192，2026-11-23 调大，接口实测可设 131072）\nMODEL_MAX_TOKENS = 32768', "MODEL_MAX_TOKENS 定义")

# 5) 常量区：去掉 Cline 截断／Gemini 空回复／Cline 阈值，换成 DSH 重复提醒
rep('''# ============ 照抄的原文与数值（出处见 README 对照表）============
# Cline  agent-runtime.ts:75 / MAX_TOKENS_RECOVERY_LIMIT = 3
CLINE_TRUNCATION_PROMPT = (
    "Your previous response was cut off because it reached the model's output-token limit before "
    "finishing. Keep responses concise: take one small step at a time, avoid long explanations, and "
    "write large files or command output in smaller chunks across multiple tool calls.")
TRUNCATION_LIMIT = 3
# Gemini CLI  useGeminiStream.ts / MAX_AUTO_NUDGE_ATTEMPTS = 2
GEMINI_EMPTY_PROMPT = (
    "[System: You successfully executed a tool but returned an empty response. Please analyze the "
    "tool output and explain your progress or final answer.]")
EMPTY_LIMIT = 2
# Cline  loop-detection.ts  DEFAULT_CONFIG
SOFT_THRESHOLD, HARD_THRESHOLD = 3, 5
# OpenCode  max-steps.ts  MAX_STEPS_PROMPT（原文）''',
    '''# ============ 照抄的原文与数值（出处见 README 对照表）============
# DeepSeek Harness  packages/guard/repeat-tool-reminder（README「模型体验」两段模板原文、thresholds 默认 [3, 5, 8]）
REPEAT_THRESHOLDS = (3, 5, 8)
REPEAT_ARGS_PREVIEW = 500          # argumentsPreviewChars 默认值
DSH_REPEAT_FIRST = (
    "You are repeating the exact same tool call with identical arguments. Carefully analyze the previous "
    "result before calling again: if the task is not complete, try a different approach or different "
    "arguments instead of repeating the call.")
DSH_REPEAT_DETAIL = (
    "Repeated tool call detected:\\n- tool: {name}\\n- consecutive_calls: {count}\\n- arguments: {args}\\n"
    "The repeated calls are not making progress. Do not call this tool with these exact arguments again. "
    "Inspect the latest result and choose a different action, different arguments, or finish the task if "
    "enough evidence has been gathered.")
# OpenCode  max-steps.ts  MAX_STEPS_PROMPT（原文）''', "常量区")

# 6) soft/hard 提示函数 → DSH 提醒函数
rep('''def soft_msg(n, name):
    return "Detected %d consecutive identical calls to `%s`; consider trying a different approach." % (n, name)


def hard_msg(n, name):
    return "Detected %d consecutive identical calls to `%s`; stopping to avoid a loop." % (n, name)''',
    '''def repeat_msg(n, name, canonical):
    """照 DSH：第 3 次短提醒、第 5 与 8 次详细模板；参数预览超长按它的截断格式「… (+N more chars)」。"""
    if n == 3:
        return DSH_REPEAT_FIRST
    preview = canonical if len(canonical) <= REPEAT_ARGS_PREVIEW else (
        canonical[:REPEAT_ARGS_PREVIEW] + "… (+%d more chars)" % (len(canonical) - REPEAT_ARGS_PREVIEW))
    return DSH_REPEAT_DETAIL.format(name=name, count=n, args=preview)''', "提醒函数")

# 7) 主循环：截断／空回复／重复调用的分支
rep('''        # —— 输出被截断：让它接着写，连续最多 3 次 ——
        if finish_reason == "length":
            trunc += 1
            log({"round": rnd, "event": "truncated", "consecutive": trunc, "elapsed": round(time.time() - t1, 1),
                 "tool_calls_dropped": len(calls), "usage": usage})
            if trunc >= TRUNCATION_LIMIT:
                finish("output_limit", "Model reached the maximum output token limit before completing the turn (%d consecutive cut-offs)." % trunc,
                       {"truncated_total": trunc})
                return
            if (msg.get("content") or "").strip():
                messages.append({"role": "assistant", "content": msg["content"]})
            messages.append({"role": "user", "content": CLINE_TRUNCATION_PROMPT})
            continue

        # —— 没有工具调用 ——
        if not calls:
            text = (msg.get("content") or "").strip()
            if not text:
                empty += 1
                log({"round": rnd, "event": "empty_reply", "consecutive": empty, "elapsed": round(time.time() - t1, 1), "usage": usage})
                if empty >= EMPTY_LIMIT:
                    finish("empty_limit", "", {"empty_total": empty})
                    return
                messages.append({"role": "user", "content": GEMINI_EMPTY_PROMPT})
                continue
            # 一轮不调工具即结束（成熟方案默认）
            log({"round": rnd, "elapsed": round(time.time() - t1, 1), "text": text[:300], "usage": usage})
            finish("natural", text)
            return

        # —— 有工具调用 ——
        trunc = 0
        empty = 0
        messages.append(msg)''',
    '''        # —— 输出被截断：照 DSH，轮次结束（它的客户端会给用户一张提示卡，这里如实记 stop_kind）——
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
        messages.append(msg)''', "主循环前半")

rep('''            # 同一工具同样参数连续重复：第 3 次提醒，第 5 次不执行并停
            sig = json.dumps(sort_keys(args), ensure_ascii=False, sort_keys=True)
            if name == last["name"] and sig == last["sig"]:
                last["n"] += 1
            else:
                last.update({"name": name, "sig": sig, "n": 1})
            a0 = args.get("path") or args.get("command") or ""
            line = "%s:%s" % (name, str(a0)[:100])
            if last["n"] >= HARD_THRESHOLD:
                log({"round": rnd, "event": "loop_hard", "tool": name, "consecutive": last["n"], "calls_so_far": summary})
                finish("loop_stop", hard_msg(last["n"], name), {"loop": {"tool": name, "consecutive": last["n"]}})
                return
            result, ok, head = run_tool(name, args)
            if SELFTEST:
                print("   -> %s" % result[:420].replace("\\n", "\\n      "))
            messages.append({"role": "tool", "tool_call_id": c["id"], "content": result})
            summary.append(line + " [%s]" % head)
            if last["n"] == SOFT_THRESHOLD:
                messages.append({"role": "user", "content": soft_msg(last["n"], name)})
                summary[-1] += " {soft}"''',
    '''            # 同一工具同样参数连续重复：照 DSH 只在 3/5/8 次提醒、绝不拦截（换成别的调用就重新计数）
            canonical = json.dumps(sort_keys(args), ensure_ascii=False, sort_keys=True)
            if name == last["name"] and canonical == last["sig"]:
                last["n"] += 1
            else:
                last.update({"name": name, "sig": canonical, "n": 1})
            a0 = args.get("path") or args.get("command") or ""
            line = "%s:%s" % (name, str(a0)[:100])
            result, ok, head = run_tool(name, args)
            if SELFTEST:
                print("   -> %s" % result[:420].replace("\\n", "\\n      "))
            messages.append({"role": "tool", "tool_call_id": c["id"], "content": result})
            summary.append(line + " [%s]" % head)
            if last["n"] in REPEAT_THRESHOLDS:
                reminded = last["n"]
        if reminded:
            # 照 DSH：提醒出现在重复调用的结果之后，作为一条注入的用户消息（本轮只发一条，取达到的最高次数）
            messages.append({"role": "user", "content": repeat_msg(reminded, last["name"], last["sig"])})
            summary[-1] += " {repeat-reminder x%d}" % reminded
            log({"round": rnd, "event": "repeat_reminder", "tool": last["name"], "consecutive": reminded})''', "主循环重复调用段")

# 8) 去掉截断／空回复计数器
rep('''    trunc = 0            # 连续截断次数（一次正常回复即清零）
    empty = 0            # 连续空回复次数
    last = {"name": "", "sig": "", "n": 0}   # 同一调用连续重复计数''',
    '''    last = {"name": "", "sig": "", "n": 0}   # 同一调用连续重复计数（照 DSH repeat-tool-reminder 的链）''', "计数器初始化")

# 9) 自测脚本：截断×1 即停、空回复×1 即停、重复×8 只提醒
rep('''    scripts = {
        # 截断×2 → 写新目录文件 → 编辑成功／没找到／不唯一 → 空回复×1 → 同一调用×5（第 3 次提醒、第 5 次停）
        "tools": [
            {"finish_reason": "length", "tool_calls": [tc("c1", "write_file", {"path": "/tmp/selftest/a.txt", "content": "abc"})]},
            {"finish_reason": "length", "content": "", "tool_calls": []},
            tool("write_file", {"path": D + "x.txt", "content": "hello\\nworld\\n"}),
            tool("edit_file", {"path": D + "x.txt", "old_string": "world", "new_string": "世界"}),
            tool("edit_file", {"path": D + "x.txt", "old_string": "nope", "new_string": "x"}),
            tool("write_file", {"path": D + "y.txt", "content": "a\\na\\n"}),
            tool("edit_file", {"path": D + "y.txt", "old_string": "a", "new_string": "b"}),
            tool("edit_file", {"path": D + "nope.txt", "old_string": "a", "new_string": "b"}),
            {"finish_reason": "stop", "content": "", "tool_calls": []},
            same, same, same, same, same,
            {"finish_reason": "stop", "content": "不该到这里。", "tool_calls": []},
        ],
        "natural": [tool("write_file", {"path": D + "n.txt", "content": "ok"}), {"finish_reason": "stop", "content": "做完了。", "tool_calls": []}],
        "output_limit": [{"finish_reason": "length", "content": "", "tool_calls": []}] * 3,
        "empty_limit": [{"finish_reason": "stop", "content": "", "tool_calls": []}] * 3,
        "cap": [same] * 50,''',
    '''    scripts = {
        # 写新目录文件 → 编辑成功／没找到／不唯一 → 自然结束
        "tools": [
            tool("write_file", {"path": D + "x.txt", "content": "hello\\nworld\\n"}),
            tool("edit_file", {"path": D + "x.txt", "old_string": "world", "new_string": "世界"}),
            tool("edit_file", {"path": D + "x.txt", "old_string": "nope", "new_string": "x"}),
            tool("write_file", {"path": D + "y.txt", "content": "a\\na\\n"}),
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
        "cap": [same] * 50,''', "自测脚本")

io.open("v0-harness4.py", "w", encoding="utf-8", newline="\n").write(s)
print("written v0-harness4.py (%d chars)" % len(s))
